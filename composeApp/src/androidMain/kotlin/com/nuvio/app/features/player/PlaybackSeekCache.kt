@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Disk read-ahead for ExoPlayer, sized by [PlaybackBufferSettings].
 *
 * ExoPlayer's own buffer lives on the Java heap, so it is capped well below the setting. Here one
 * connection reads the stream ahead of playback into a ring file of the chosen size and the
 * player reads from that file, so seeks anywhere inside it need no network. Parts behind
 * playback are overwritten: only what is coming up is kept.
 *
 * It stands in for the player's own connection rather than adding one: a seek outside the ring
 * moves the read-ahead there, so the stream never has more than one connection. The ring is a
 * temporary file, deleted when playback ends and at every launch.
 */
internal object PlaybackSeekCache {
    private const val TAG = "PlaybackSeekCache"
    private const val DIR = "seek_cache"
    private const val MB = 1024L * 1024L
    private const val MIN_CAPACITY = 32L * MB

    @Volatile private var session: ReadAheadSession? = null

    private fun cacheDir(context: Context) = File(context.applicationContext.cacheDir, DIR)

    /** Called at launch: nothing from an earlier run is kept. */
    fun cleanUp(context: Context) {
        val dir = cacheDir(context)
        if (!dir.exists()) return
        Thread({ runCatching { dir.deleteRecursively() } }, "NuvioSeekCacheCleanup").apply {
            isDaemon = true
        }.start()
    }

    /**
     * [upstream] with the read-ahead in front of it for [sourceUrl], or [upstream] itself when
     * the setting is on Nuvio's default or [cacheable] is false (sources already on the device).
     */
    @Synchronized
    fun wrap(context: Context, sourceUrl: String, cacheable: Boolean, upstream: DataSource.Factory): DataSource.Factory {
        val chosen = PlaybackBufferSettings.bufferMb.value.coerceAtLeast(0) * MB
        if (!cacheable || chosen <= 0) return upstream
        val active = session?.takeIf { it.key == sourceUrl && !it.isClosed } ?: run {
            session?.close()
            session = null
            val dir = cacheDir(context).apply { mkdirs() }
            val free = runCatching { dir.usableSpace }.getOrDefault(0L)
            val capacity = if (free > 0) minOf(chosen, free / 2) else chosen
            if (capacity < MIN_CAPACITY) return upstream
            runCatching { ReadAheadSession(sourceUrl, File(dir, "read_ahead.bin"), capacity) }
                .onFailure { Log.w(TAG, "read-ahead unavailable", it) }
                .getOrNull() ?: return upstream
        }
        active.upstreamFactory = upstream
        session = active
        return ReadAheadDataSourceFactory(active, upstream)
    }

    /** The factory without the read-ahead, for readers other than the player (they seek elsewhere). */
    fun unwrap(factory: DataSource.Factory): DataSource.Factory =
        (factory as? ReadAheadDataSourceFactory)?.upstream ?: factory

    /**
     * [exoBufferedMs] extended by what the read-ahead holds beyond the player's buffer, converted
     * to time with the file's average bitrate, so the seek bar shows it. An estimate: variable
     * bitrate files can be a little off either way.
     */
    fun bufferedPositionMs(exoBufferedMs: Long, durationMs: Long): Long {
        if (durationMs <= 0) return exoBufferedMs
        val (aheadBytes, totalBytes) = session?.aheadOfPlayer() ?: return exoBufferedMs
        if (aheadBytes <= 0 || totalBytes <= 0) return exoBufferedMs
        val aheadMs = (aheadBytes.toDouble() / totalBytes * durationMs).toLong()
        return minOf(exoBufferedMs + aheadMs, durationMs)
    }

    /**
     * Whether playback of [sourceUrl] is downloading right now, for the connection speed
     * sampler: with the read-ahead on, that is its connection, not ExoPlayer's disk reads.
     * Null when no read-ahead serves [sourceUrl], so ExoPlayer's own loading state applies.
     */
    fun isDownloading(sourceUrl: String): Boolean? =
        session?.takeIf { it.key == sourceUrl && !it.isClosed }?.isDownloading

    /** Called when the player for [sourceUrl] is released: stops reading and deletes the file. */
    @Synchronized
    fun release(sourceUrl: String) {
        val current = session?.takeIf { it.key == sourceUrl } ?: return
        current.close()
        session = null
    }
}

private class ReadAheadDataSourceFactory(
    private val session: ReadAheadSession,
    val upstream: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = ReadAheadDataSource(session, upstream.createDataSource())
}

/**
 * The ring file and the one connection filling it. Valid bytes are the stream range
 * [windowStart, windowEnd); stream position p lives at file offset p % capacity.
 */
private class ReadAheadSession(val key: String, private val file: File, private val capacity: Long) {
    @Volatile var upstreamFactory: DataSource.Factory? = null

    private val channel: FileChannel = RandomAccessFile(file, "rw").channel
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    // Guarded by lock.
    private var windowStart = 0L
    private var windowEnd = 0L
    private var generation = 0
    private var started = false
    private var ended = false
    private var error: IOException? = null
    private var contentLength = C.LENGTH_UNSET.toLong()
    private var closed = false
    private var filler: Thread? = null
    private var playerPosition = -1L
    private var retryNow = false

    /** True while the connection is receiving data, false while it waits (full, ended, retry). */
    @Volatile var isDownloading = false
        private set
    var uri: Uri? = null
        private set
    var responseHeaders: Map<String, List<String>> = emptyMap()
        private set

    val isClosed: Boolean get() = lock.withLock { closed }

    /**
     * Prepares the ring for a player read at [position]: inside (or just past) it, the read waits
     * for the connection; anywhere else the read-ahead moves there. A connection error the
     * player is retrying after is retried at once. False once closed.
     */
    fun serve(position: Long): Boolean {
        lock.withLock {
            if (closed) return false
            if (started && position >= windowStart && position <= windowEnd + NEAR_BYTES) {
                if (error != null) {
                    error = null
                    retryNow = true
                    changed.signalAll()
                }
                return true
            }
        }
        relocate(position)
        return !isClosed
    }

    /** Bytes read ahead past where the player last read, and the stream length; null if unknown. */
    fun aheadOfPlayer(): Pair<Long, Long>? = lock.withLock {
        if (closed || !started || contentLength == C.LENGTH_UNSET.toLong()) return null
        if (playerPosition < windowStart || playerPosition > windowEnd) return null
        (windowEnd - playerPosition) to contentLength
    }

    /** Stream length, when the server told us. */
    fun length(): Long = lock.withLock { contentLength }

    /** Moves the read-ahead to [position]; what was read ahead elsewhere is dropped. */
    fun relocate(position: Long) = lock.withLock {
        if (closed) return@withLock
        generation++
        windowStart = position
        windowEnd = position
        ended = contentLength != C.LENGTH_UNSET.toLong() && position >= contentLength
        error = null
        started = true
        playerPosition = position
        changed.signalAll()
        if (filler == null) {
            filler = Thread(::fillLoop, "NuvioReadAhead").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * Reads from the ring at [position], waiting for the connection when it is not there yet.
     * Returns [C.RESULT_END_OF_INPUT] at the end of the stream.
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val available = lock.withLock {
            while (!closed && position >= windowStart && position >= windowEnd && !ended && error == null) {
                try {
                    changed.await()
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException()
                }
            }
            if (closed) throw IOException("read-ahead closed")
            if (position < windowStart) throw IOException("read-ahead moved")
            if (position >= windowEnd) {
                if (ended) return C.RESULT_END_OF_INPUT
                throw error ?: IOException("read-ahead failed")
            }
            minOf(length.toLong(), windowEnd - position).toInt()
        }
        readFile(position, buffer, offset, available)
        lock.withLock {
            // Keep a little behind the player for re-reads; the rest of the ring is free again.
            playerPosition = position + available
            val keepFrom = position + available - BACK_KEEP_BYTES
            if (keepFrom > windowStart) {
                windowStart = minOf(keepFrom, windowEnd)
                changed.signalAll()
            }
        }
        return available
    }

    fun close() {
        val hadFiller = lock.withLock {
            if (closed) return
            closed = true
            changed.signalAll()
            filler != null
        }
        // The filler deletes the file itself once its connection is closed.
        if (!hadFiller) disposeFile()
    }

    private fun fillLoop() {
        val buffer = ByteArray(CHUNK_BYTES)
        var source: DataSource? = null
        var myGeneration = -1
        var position = 0L
        var failures = 0
        while (true) {
            val space = lock.withLock {
                while (!closed && myGeneration == generation && (ended || windowEnd - windowStart >= capacity)) {
                    isDownloading = false
                    changed.await()
                }
                if (closed) return@withLock null
                if (myGeneration != generation) {
                    myGeneration = generation
                    position = windowEnd
                    failures = 0
                    source?.closeQuietly()
                    source = null
                }
                minOf(CHUNK_BYTES.toLong(), capacity - (windowEnd - windowStart)).toInt()
            } ?: break
            isDownloading = true
            try {
                val open = source ?: openAt(position, myGeneration)?.also { source = it } ?: continue
                val read = open.read(buffer, 0, space)
                if (read == C.RESULT_END_OF_INPUT) {
                    open.closeQuietly()
                    source = null
                    lock.withLock {
                        if (myGeneration == generation) {
                            contentLength = windowEnd
                            ended = true
                            changed.signalAll()
                        }
                    }
                    continue
                }
                writeFile(position, buffer, read)
                position += read
                failures = 0
                lock.withLock {
                    if (myGeneration == generation) {
                        windowEnd = position
                        changed.signalAll()
                    }
                }
            } catch (failure: IOException) {
                source?.closeQuietly()
                source = null
                isDownloading = false
                failures++
                lock.withLock {
                    // Brief drops are retried quietly, like a slow network; repeated failures
                    // reach the player so its own error handling (and error screen) applies.
                    if (myGeneration == generation && failures >= SURFACE_AFTER_FAILURES) {
                        error = failure
                        changed.signalAll()
                    }
                    val waitMs = minOf(RETRY_BASE_MS shl minOf(failures - 1, 3), RETRY_MAX_MS)
                    var leftNs = TimeUnit.MILLISECONDS.toNanos(waitMs)
                    while (!closed && myGeneration == generation && !retryNow && leftNs > 0) {
                        leftNs = changed.awaitNanos(leftNs)
                    }
                    retryNow = false
                }
            }
        }
        isDownloading = false
        source?.closeQuietly()
        disposeFile()
    }

    /** Opens the connection at [position]; null when the read-ahead moved meanwhile. */
    private fun openAt(position: Long, forGeneration: Int): DataSource? {
        val factory = upstreamFactory ?: throw IOException("no upstream")
        val source = factory.createDataSource()
        val opened = source.open(DataSpec.Builder().setUri(key).setPosition(position).build())
        lock.withLock {
            if (forGeneration != generation || closed) {
                source.closeQuietly()
                return null
            }
            if (opened != C.LENGTH_UNSET.toLong()) contentLength = position + opened
            uri = source.uri
            responseHeaders = source.responseHeaders
            error = null
        }
        return source
    }

    private fun writeFile(position: Long, buffer: ByteArray, length: Int) {
        var done = 0
        while (done < length) {
            var at = (position + done) % capacity
            val part = minOf((length - done).toLong(), capacity - at).toInt()
            val bytes = ByteBuffer.wrap(buffer, done, part)
            while (bytes.hasRemaining()) at += channel.write(bytes, at)
            done += part
        }
    }

    private fun readFile(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            var at = (position + done) % capacity
            val part = minOf((length - done).toLong(), capacity - at).toInt()
            val bytes = ByteBuffer.wrap(buffer, offset + done, part)
            while (bytes.hasRemaining()) {
                val read = channel.read(bytes, at)
                if (read < 0) throw IOException("read-ahead file truncated")
                at += read
            }
            done += part
        }
    }

    private fun disposeFile() {
        runCatching { channel.close() }
        runCatching { file.delete() }
    }

    private companion object {
        const val CHUNK_BYTES = 256 * 1024
        const val NEAR_BYTES = 4L * 1024 * 1024
        const val BACK_KEEP_BYTES = 8L * 1024 * 1024
        const val SURFACE_AFTER_FAILURES = 3
        const val RETRY_BASE_MS = 1_000L
        const val RETRY_MAX_MS = 8_000L
    }
}

/**
 * The player's data source. Every read of the playing stream comes from the ring: an open
 * outside it (a seek, or a container index at the end of the file) moves the read-ahead there
 * first, so the stream only ever has the read-ahead's one connection. A second connection next
 * to it was refused by some debrid hosts. Other URLs (subtitles, a separate audio track) read
 * directly.
 */
private class ReadAheadDataSource(
    private val session: ReadAheadSession,
    private val direct: DataSource,
) : DataSource {
    private var fromRing = false
    private var directOpen = false
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun addTransferListener(transferListener: TransferListener) {
        direct.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        position = dataSpec.position
        remaining = dataSpec.length
        if (dataSpec.uri.toString() == session.key && session.serve(position)) {
            fromRing = true
            val length = session.length()
            return when {
                remaining != C.LENGTH_UNSET.toLong() -> remaining
                length != C.LENGTH_UNSET.toLong() -> (length - position).coerceAtLeast(0L)
                else -> C.LENGTH_UNSET.toLong()
            }
        }
        fromRing = false
        directOpen = true
        return direct.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val wanted = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val read = if (fromRing) {
            session.read(position, buffer, offset, wanted)
        } else {
            direct.read(buffer, offset, wanted)
        }
        if (read == C.RESULT_END_OF_INPUT) return read
        position += read
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
        return read
    }

    override fun getUri(): Uri? = if (fromRing) session.uri ?: Uri.parse(session.key) else direct.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (fromRing) session.responseHeaders else direct.responseHeaders

    override fun close() {
        fromRing = false
        if (directOpen) {
            directOpen = false
            direct.close()
        }
    }
}

private fun DataSource.closeQuietly() {
    runCatching { close() }
}
