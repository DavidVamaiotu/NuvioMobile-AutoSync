@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.seekpreview.local

import android.graphics.BitmapFactory
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.MediaExtractorCompat
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import com.nuvio.app.features.player.seekpreview.LocalSeekPreviewSettings
import com.nuvio.app.features.player.seekpreview.LocalSeekPreviewStats
import com.nuvio.app.features.player.seekpreview.SeekPreviewTrack
import com.nuvio.app.features.player.seekpreview.SeekrThumbnail
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Seek-preview thumbnails generated on the device from the stream that is playing, one per
 * [SLOT_MS] slot. Two sources fill the slots:
 *
 *  - **Buffer tap** ([onKeyframe]): keyframes playback downloads anyway. Costs no data.
 *  - **Background fill** ([workerLoop]): reads single keyframes far from the playhead over
 *    its own connections, coarse to fine (every 5 min, 1 min, 30 s, then 10 s), with slots
 *    around the scrub position jumping the queue. The container's own index (MP4 sample table,
 *    MKV cues) says where every keyframe is, so a slot whose nearest keyframe was already
 *    thumbnailed reuses it without downloading anything.
 *
 * Until a slot is filled, lookups return the nearest filled one marked approximate (shown
 * blurred). Thumbnails are small JPEGs kept in memory and in a disk cache per title/release.
 */
internal class LocalPreviewTrack(
    private val source: LocalPreviewSource,
    private val cacheKey: String,
    private val durationMs: Long,
) : SeekPreviewTrack {
    override val isLocal: Boolean get() = true

    @Volatile
    override var offsetMs: Long = 0L

    private val slotCount = ((durationMs + SLOT_MS - 1) / SLOT_MS).toInt().coerceAtLeast(1)
    private val lock = Any()
    private val jpegs = arrayOfNulls<ByteArray>(slotCount)
    private val frameMs = LongArray(slotCount) { -1L }
    private val slotState = ByteArray(slotCount)
    private var filledCount = 0
    /** Keyframe time (ms) → slot holding its thumbnail, so a keyframe is never fetched twice. */
    private val keyframeSlots = HashMap<Long, Int>()
    private val decoded = object : LinkedHashMap<Int, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ImageBitmap>?) = size > MAX_DECODED
    }
    private val fillOrder = coarseToFineOrder(slotCount)
    private var fillCursor = 0

    @Volatile private var prioritySlot = -1
    @Volatile private var closed = false
    @Volatile private var pausedReason: String? = null

    private val downloaded = AtomicLong()
    private val fromBuffer = AtomicInteger()
    private val fromCache = AtomicInteger()
    private val sinceSave = AtomicInteger()

    private val decoder = KeyframeThumbnailDecoder()
    private val tapExecutor = ThreadPoolExecutor(
        1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(TAP_QUEUE),
        { runnable -> Thread(runnable, "NuvioPreviewTap").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val workers = mutableListOf<Thread>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val changes = Channel<Unit>(Channel.CONFLATED)

    private val _revision = MutableStateFlow(0)
    override val revision: StateFlow<Int> = _revision.asStateFlow()
    private val _stats = MutableStateFlow(LocalSeekPreviewStats(total = slotCount))
    override val localStats: StateFlow<LocalSeekPreviewStats> = _stats.asStateFlow()

    private val cacheFile: File = File(File(source.context.cacheDir, CACHE_DIR), sha1(cacheKey) + ".bin")

    fun start() {
        // Coalesce UI updates: at most a few revisions per second however fast frames land.
        scope.launch {
            for (unit in changes) {
                _revision.value = _revision.value + 1
                _stats.value = snapshotStats()
                delay(UI_UPDATE_INTERVAL_MS)
            }
        }
        scope.launch(Dispatchers.IO) {
            loadCache()
            notifyChanged()
            if (closed) return@launch
            val blocked = source.backgroundBlockedReason
            if (blocked != null) {
                pausedReason = blocked
                notifyChanged()
                return@launch
            }
            repeat(WORKERS) { index ->
                val worker = Thread({ workerLoop() }, "NuvioPreviewFill$index").apply { isDaemon = true }
                synchronized(workers) { workers += worker }
                worker.start()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        tapExecutor.shutdownNow()
        Thread({
            synchronized(workers) { workers.toList() }.forEach { runCatching { it.join(3_000L) } }
            decoder.release()
            saveCache()
            scope.cancel()
        }, "NuvioPreviewClose").apply { isDaemon = true }.start()
    }

    // ---- Lookups -------------------------------------------------------------------------

    override suspend fun thumbnailFor(positionMs: Long): SeekrThumbnail? {
        val corrected = (positionMs + offsetMs).coerceIn(0L, (durationMs - 1).coerceAtLeast(0L))
        val slot = (corrected / SLOT_MS).toInt().coerceIn(0, slotCount - 1)
        prioritySlot = slot
        val found = synchronized(lock) { nearestFilled(slot) } ?: return null
        val bitmap = bitmapFor(found) ?: return null
        val cueStart = slot * SLOT_MS
        val cueEnd = minOf(cueStart + SLOT_MS, durationMs).coerceAtLeast(cueStart + 1)
        return SeekrThumbnail(
            sheet = bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            cueStartMs = cueStart,
            cueEndMs = cueEnd,
            approximate = found != slot,
        )
    }

    private fun nearestFilled(slot: Int): Int? {
        if (jpegs[slot] != null) return slot
        for (distance in 1 until slotCount) {
            val before = slot - distance
            val after = slot + distance
            if (before < 0 && after >= slotCount) break
            if (before >= 0 && jpegs[before] != null) return before
            if (after < slotCount && jpegs[after] != null) return after
        }
        return null
    }

    private suspend fun bitmapFor(slot: Int): ImageBitmap? {
        synchronized(lock) { decoded[slot] }?.let { return it }
        val bytes = synchronized(lock) { jpegs[slot] } ?: return null
        val bitmap = withContext(Dispatchers.Default) {
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        } ?: return null
        synchronized(lock) { decoded[slot] = bitmap }
        return bitmap
    }

    // ---- Buffer tap ----------------------------------------------------------------------

    fun wantsKeyframes(): Boolean = !closed && synchronized(lock) { filledCount < slotCount }

    fun wantsKeyframe(timeUs: Long): Boolean {
        if (closed) return false
        val keyMs = timeUs / 1_000L
        val slot = slotFor(keyMs) ?: return false
        return synchronized(lock) { slotState[slot] == EMPTY && keyMs !in keyframeSlots }
    }

    fun onKeyframe(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
        val keyMs = timeUs / 1_000L
        val slot = slotFor(keyMs) ?: return
        // Decoding runs behind playback's loader thread; when it falls behind, skip frames.
        if (tapExecutor.queue.remainingCapacity() == 0) return
        if (!claim(slot)) return
        val copy = data.copyOfRange(offset, offset + size)
        val submitted = runCatching {
            tapExecutor.execute {
                val frame = runCatching {
                    decoder.decode(MediaFormatUtil.createMediaFormatFromFormat(format), copy, 0, copy.size, timeUs)
                }.getOrNull()
                // A dark tap frame is kept anyway: background fill may still replace it later.
                if (frame != null) {
                    store(slot, frame.jpeg, keyMs)
                    fromBuffer.incrementAndGet()
                } else {
                    release(slot)
                }
            }
        }.isSuccess
        if (!submitted) release(slot)
    }

    /** Nearest slot to a keyframe, or null when the keyframe is closer to no slot start. */
    private fun slotFor(keyMs: Long): Int? {
        if (keyMs < 0 || keyMs > durationMs + SLOT_MS) return null
        return ((keyMs + SLOT_MS / 2) / SLOT_MS).toInt().coerceIn(0, slotCount - 1)
    }

    // ---- Background fill -----------------------------------------------------------------

    private fun workerLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val uri: Uri = source.uri ?: return
        val upstream = source.dataSourceFactory ?: return
        val counting = DataSource.Factory {
            upstream.createDataSource().apply { addTransferListener(ByteCounter(downloaded)) }
        }
        // Let playback start alone; the first seconds of buffering matter most.
        while (!closed && !source.released) {
            val readyAt = source.readyAtMs
            if (readyAt != 0L && SystemClock.uptimeMillis() - readyAt >= START_GRACE_MS) break
            setPaused("waiting for playback")
            Thread.sleep(250L)
        }
        if (closed || source.released) return
        val seekMaps = SeekMapCapturingExtractorsFactory(DefaultExtractorsFactory())
        val extractor = MediaExtractorCompat(seekMaps, BoundedRangeDataSourceFactory(counting))
        try {
            extractor.setDataSource(uri, 0L)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            }
            if (videoTrack == null) {
                setPaused("no video track")
                return
            }
            extractor.selectTrack(videoTrack)
            val format = extractor.getTrackFormat(videoTrack)
            var buffer = ByteBuffer.allocate(1 shl 20)
            while (!closed && !source.released) {
                val reason = pauseReason()
                if (reason != null) {
                    setPaused(reason)
                    if (reason == REASON_BUDGET) return
                    Thread.sleep(500L)
                    continue
                }
                setPaused(null)
                val slot = nextSlot() ?: break
                buffer = fillSlot(extractor, seekMaps.seekMap, format, slot, buffer)
            }
            if (!closed) setPaused(if (isComplete()) null else pausedReason)
        } catch (_: InterruptedException) {
            // Closing.
        } catch (error: Exception) {
            Log.w(TAG, "background fill stopped: ${error.message}")
            setPaused("cannot read stream")
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Fills [slot] with the keyframe nearest its start; returns the (possibly grown) buffer. */
    private fun fillSlot(
        extractor: MediaExtractorCompat,
        seekMap: SeekMap?,
        format: MediaFormat,
        slot: Int,
        initialBuffer: ByteBuffer,
    ): ByteBuffer {
        var buffer = initialBuffer
        val targetUs = slot * SLOT_MS * 1_000L
        // The index names the keyframes around the target without downloading anything.
        val keyUs = seekMap?.takeIf { it.isSeekable }?.let { map ->
            val points = map.getSeekPoints(targetUs)
            listOf(points.first.timeUs, points.second.timeUs).minByOrNull { abs(it - targetUs) }
        } ?: targetUs
        val knownSlot = synchronized(lock) { keyframeSlots[keyUs / 1_000L] }
        if (knownSlot != null) {
            // Long-GOP encodes: this slot's nearest keyframe is already a thumbnail.
            val bytes = synchronized(lock) { jpegs[knownSlot] }
            if (bytes != null) {
                store(slot, bytes, keyUs / 1_000L)
                return buffer
            }
        }
        var seekUs = keyUs
        var darkFrame: Pair<ByteArray, Long>? = null
        for (attempt in 0 until 2) {
            if (closed) break
            extractor.seekTo(seekUs, if (attempt == 0) MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC else MediaExtractorCompat.SEEK_TO_NEXT_SYNC)
            val timeUs = extractor.sampleTime
            // The next keyframe after a dark one may be too far away to stand for this slot.
            if (timeUs < 0 || (attempt > 0 && timeUs - targetUs > SLOT_MS * 1_000L)) break
            val size = extractor.sampleSize.toInt()
            if (size > buffer.capacity()) buffer = ByteBuffer.allocate(size + (size shr 2))
            buffer.clear()
            val read = extractor.readSampleData(buffer, 0)
            if (read <= 0 || closed) break
            val frame = decoder.decode(format, buffer.array(), buffer.arrayOffset(), read, timeUs) ?: break
            if (frame.dark && attempt == 0) {
                // A fade to black says nothing about the scene; try the next keyframe once.
                darkFrame = frame.jpeg to timeUs / 1_000L
                seekUs = timeUs + DARK_SKIP_US
                continue
            }
            store(slot, frame.jpeg, timeUs / 1_000L)
            return buffer
        }
        val fallback = darkFrame
        if (fallback != null) store(slot, fallback.first, fallback.second) else markFailed(slot)
        return buffer
    }

    private fun nextSlot(): Int? = synchronized(lock) {
        val focus = prioritySlot
        if (focus >= 0) {
            for (distance in 0..PRIORITY_RADIUS) {
                // Ahead first: people scrub forward more often than back.
                for (candidate in intArrayOf(focus + distance, focus - distance)) {
                    if (candidate in 0 until slotCount && slotState[candidate] == EMPTY) {
                        slotState[candidate] = CLAIMED
                        return@synchronized candidate
                    }
                }
            }
        }
        while (fillCursor < fillOrder.size) {
            val candidate = fillOrder[fillCursor++]
            if (slotState[candidate] == EMPTY) {
                slotState[candidate] = CLAIMED
                return@synchronized candidate
            }
        }
        // Slots skipped while claimed by the tap and later released.
        val leftover = (0 until slotCount).firstOrNull { slotState[it] == EMPTY } ?: return@synchronized null
        slotState[leftover] = CLAIMED
        leftover
    }

    private fun pauseReason(): String? {
        if (downloaded.get() >= BYTE_BUDGET) return REASON_BUDGET
        if (source.buffering) return "playback buffering"
        if (!LocalSeekPreviewSettings.mobileData.value && isMetered()) return "mobile data: buffer only"
        return null
    }

    private fun isMetered(): Boolean {
        val manager = source.context.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching { manager.isActiveNetworkMetered }.getOrDefault(false)
    }

    // ---- Slot bookkeeping ----------------------------------------------------------------

    private fun claim(slot: Int): Boolean = synchronized(lock) {
        if (slotState[slot] != EMPTY) return@synchronized false
        slotState[slot] = CLAIMED
        true
    }

    private fun release(slot: Int) {
        synchronized(lock) {
            if (slotState[slot] == CLAIMED) slotState[slot] = EMPTY
        }
    }

    private fun markFailed(slot: Int) {
        synchronized(lock) {
            if (slotState[slot] != FILLED) slotState[slot] = FAILED
        }
    }

    private fun store(slot: Int, jpeg: ByteArray, keyMs: Long) {
        synchronized(lock) {
            if (slotState[slot] != FILLED) filledCount++
            slotState[slot] = FILLED
            jpegs[slot] = jpeg
            frameMs[slot] = keyMs
            keyframeSlots.putIfAbsent(keyMs, slot)
            decoded.remove(slot)
        }
        if (sinceSave.incrementAndGet() >= SAVE_EVERY) {
            sinceSave.set(0)
            scope.launch(Dispatchers.IO) { saveCache() }
        }
        notifyChanged()
    }

    private fun isComplete(): Boolean = synchronized(lock) { slotState.none { it == EMPTY || it == CLAIMED } }

    private fun setPaused(reason: String?) {
        if (pausedReason == reason) return
        pausedReason = reason
        notifyChanged()
    }

    private fun notifyChanged() {
        changes.trySend(Unit)
    }

    private fun snapshotStats(): LocalSeekPreviewStats = LocalSeekPreviewStats(
        filled = synchronized(lock) { filledCount },
        total = slotCount,
        downloadedBytes = downloaded.get(),
        fromBuffer = fromBuffer.get(),
        fromCache = fromCache.get(),
        pausedReason = pausedReason,
    )

    // ---- Disk cache ----------------------------------------------------------------------

    private fun loadCache() {
        if (!cacheFile.exists()) return
        runCatching {
            DataInputStream(cacheFile.inputStream().buffered()).use { input ->
                if (input.readInt() != CACHE_MAGIC || input.readInt().toLong() != SLOT_MS) return
                val count = input.readInt()
                repeat(count) {
                    val slot = input.readInt()
                    val keyMs = input.readLong()
                    val bytes = ByteArray(input.readInt())
                    input.readFully(bytes)
                    if (slot in 0 until slotCount && claim(slot)) {
                        synchronized(lock) {
                            filledCount++
                            slotState[slot] = FILLED
                            jpegs[slot] = bytes
                            frameMs[slot] = keyMs
                            keyframeSlots.putIfAbsent(keyMs, slot)
                        }
                        fromCache.incrementAndGet()
                    }
                }
            }
            cacheFile.setLastModified(System.currentTimeMillis())
        }.onFailure { Log.w(TAG, "cache unreadable: ${it.message}") }
    }

    @Synchronized
    private fun saveCache() {
        val entries = synchronized(lock) {
            (0 until slotCount).mapNotNull { slot ->
                val bytes = jpegs[slot]
                if (slotState[slot] == FILLED && bytes != null) Triple(slot, frameMs[slot], bytes) else null
            }
        }
        if (entries.isEmpty()) return
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val temp = File(cacheFile.path + ".tmp")
            DataOutputStream(temp.outputStream().buffered()).use { output ->
                output.writeInt(CACHE_MAGIC)
                output.writeInt(SLOT_MS.toInt())
                output.writeInt(entries.size)
                for ((slot, keyMs, bytes) in entries) {
                    output.writeInt(slot)
                    output.writeLong(keyMs)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                }
            }
            temp.renameTo(cacheFile)
            pruneCache(cacheFile.parentFile)
        }.onFailure { Log.w(TAG, "cache not saved: ${it.message}") }
    }

    private fun pruneCache(dir: File?) {
        val files = dir?.listFiles { file -> file.name.endsWith(".bin") }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (file in files) {
            total += file.length()
            if (total > CACHE_LIMIT_BYTES && file != cacheFile) file.delete()
        }
    }

    private class ByteCounter(private val bytes: AtomicLong) : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork) bytes.addAndGet(bytesTransferred.toLong())
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    companion object {
        private const val TAG = "NuvioLocalPreviews"
        const val SLOT_MS = 10_000L
        private const val WORKERS = 2
        private const val PRIORITY_RADIUS = 6
        private const val START_GRACE_MS = 2_000L
        private const val BYTE_BUDGET = 400L * 1_000_000L
        private const val DARK_SKIP_US = 1_000_000L
        private const val MAX_DECODED = 48
        private const val TAP_QUEUE = 3
        private const val UI_UPDATE_INTERVAL_MS = 250L
        private const val SAVE_EVERY = 60
        private const val CACHE_DIR = "seek_previews"
        private const val CACHE_MAGIC = 0x4E535031 // "NSP1"
        private const val CACHE_LIMIT_BYTES = 200L * 1_000_000L
        private const val REASON_BUDGET = "data cap reached"

        private const val EMPTY: Byte = 0
        private const val CLAIMED: Byte = 1
        private const val FILLED: Byte = 2
        private const val FAILED: Byte = 3

        /** Every 5 min, then every 1 min, 30 s and 10 s: the whole bar gets something fast. */
        internal fun coarseToFineOrder(slotCount: Int): IntArray {
            val seen = BooleanArray(slotCount)
            val order = ArrayList<Int>(slotCount)
            for (stride in intArrayOf(30, 6, 3, 1)) {
                var slot = 0
                while (slot < slotCount) {
                    if (!seen[slot]) {
                        seen[slot] = true
                        order += slot
                    }
                    slot += stride
                }
            }
            return order.toIntArray()
        }

        private fun sha1(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Remembers the container's seek map (its keyframe index) as the extractor reports it. */
private class SeekMapCapturingExtractorsFactory(private val delegate: ExtractorsFactory) : ExtractorsFactory {
    @Volatile var seekMap: SeekMap? = null

    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(delegate.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> = Array(extractors.size) { index ->
        val inner = extractors[index]
        object : Extractor {
            override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)
            override fun getSniffFailureDetails(): List<SniffFailure> = inner.sniffFailureDetails
            override fun init(output: ExtractorOutput) = inner.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = output.track(id, type)
                override fun endTracks() = output.endTracks()
                override fun seekMap(seekMap: SeekMap) {
                    this@SeekMapCapturingExtractorsFactory.seekMap = seekMap
                    output.seekMap(seekMap)
                }
            })
            override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)
            override fun seek(position: Long, timeUs: Long) = inner.seek(position, timeUs)
            override fun release() = inner.release()
            override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
        }
    }
}
