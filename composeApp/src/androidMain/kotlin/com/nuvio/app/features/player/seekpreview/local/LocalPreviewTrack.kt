@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.seekpreview.local

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.Format
import androidx.media3.common.util.MediaFormatUtil
import com.nuvio.app.features.player.seekpreview.SeekPreviewTrack
import com.nuvio.app.features.player.seekpreview.SeekrThumbnail
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
 * [SLOT_MS] slot, filled only from the buffer tap ([onKeyframe]): keyframes playback downloads
 * anyway, so it costs no extra requests. Reading keyframes over separate connections was tried
 * and dropped: debrid CDNs rate limit the burst of range requests it makes.
 *
 * Lookups return the nearest filled slot marked approximate (shown blurred, or replaced by a
 * Seekr frame when one is loaded). Thumbnails are small JPEGs kept in memory and in a disk cache
 * per title/release, so a rewatch starts with every part watched before.
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
    @Volatile private var closed = false

    private val sinceSave = AtomicInteger()

    private val decoder = KeyframeThumbnailDecoder()
    private val tapExecutor = ThreadPoolExecutor(
        1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(TAP_QUEUE),
        { runnable -> Thread(runnable, "NuvioPreviewTap").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val changes = Channel<Unit>(Channel.CONFLATED)

    private val _revision = MutableStateFlow(0)
    override val revision: StateFlow<Int> = _revision.asStateFlow()

    private val cacheFile: File = File(File(source.context.cacheDir, CACHE_DIR), sha1(cacheKey) + ".bin")

    fun start() {
        // Coalesce UI updates: at most a few revisions per second however fast frames land.
        scope.launch {
            for (unit in changes) {
                _revision.value = _revision.value + 1
                delay(UI_UPDATE_INTERVAL_MS)
            }
        }
        scope.launch(Dispatchers.IO) {
            loadCache()
            notifyChanged()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        tapExecutor.shutdownNow()
        Thread({
            runCatching { tapExecutor.awaitTermination(3, TimeUnit.SECONDS) }
            decoder.release()
            saveCache()
            scope.cancel()
        }, "NuvioPreviewClose").apply { isDaemon = true }.start()
    }

    // ---- Lookups -------------------------------------------------------------------------

    override suspend fun thumbnailFor(positionMs: Long): SeekrThumbnail? {
        val corrected = (positionMs + offsetMs).coerceIn(0L, (durationMs - 1).coerceAtLeast(0L))
        val slot = (corrected / SLOT_MS).toInt().coerceIn(0, slotCount - 1)
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
                if (frame != null) {
                    store(slot, frame.jpeg, keyMs)
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

    private fun notifyChanged() {
        changes.trySend(Unit)
    }

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

    companion object {
        private const val TAG = "NuvioLocalPreviews"
        const val SLOT_MS = 10_000L
        private const val MAX_DECODED = 48
        private const val TAP_QUEUE = 3
        private const val UI_UPDATE_INTERVAL_MS = 250L
        private const val SAVE_EVERY = 60
        private const val CACHE_DIR = "seek_previews"
        private const val CACHE_MAGIC = 0x4E535031 // "NSP1"
        private const val CACHE_LIMIT_BYTES = 200L * 1_000_000L

        private const val EMPTY: Byte = 0
        private const val CLAIMED: Byte = 1
        private const val FILLED: Byte = 2

        private fun sha1(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
