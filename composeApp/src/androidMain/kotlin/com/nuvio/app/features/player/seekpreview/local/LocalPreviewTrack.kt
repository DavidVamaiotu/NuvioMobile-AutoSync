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
import com.nuvio.app.features.player.PlatformPlaybackDataSourceFactory
import com.nuvio.app.features.player.seekpreview.LocalPreviewStreamCandidate
import com.nuvio.app.features.player.seekpreview.LocalSeekPreviewSettings
import com.nuvio.app.features.player.seekpreview.LocalSeekPreviewStreams
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
import kotlinx.coroutines.runBlocking
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
    private val decodeQueue = ArrayBlockingQueue<DecodeJob>(DECODE_QUEUE)
    private val fetchTimes = RollingAverage()
    private val decodeTimes = RollingAverage()
    private val resolveLock = Any()
    private val concurrencyLock = Any()
    @Volatile private var allowedWorkers = START_WORKERS
    private var fetchesSinceAdjust = 0
    private var baselineFetchMs = 0L
    private var handledBufferingEpisode = 0
    /** Stalls fill backed off for, for the debug readout. */
    @Volatile private var stalls = 0
    private val readErrors = AtomicInteger()
    @Volatile private var lastError: String? = null
    @Volatile private var fillSource: FillSource? = null
    private var fillChosen = false
    /** Fill-stream keyframe time (µs) → slot holding it; its timeline may differ from playback's. */
    private val fillKeyframeSlots = HashMap<Long, Int>()
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
            Thread({ decodeLoop() }, "NuvioPreviewDecode").apply { isDaemon = true }.start()
            repeat(MAX_WORKERS) { index ->
                val worker = Thread({ workerLoop(index) }, "NuvioPreviewFill$index").apply { isDaemon = true }
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

    private fun workerLoop(index: Int) {
        // Slightly below normal: THREAD_PRIORITY_BACKGROUND puts threads in the background
        // cgroup, which on many phones caps them at a sliver of one core and made fill crawl.
        Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE * 2)
        // Let playback start alone; the first seconds of buffering matter most.
        while (!closed && !source.released) {
            val readyAt = source.readyAtMs
            if (readyAt != 0L && SystemClock.uptimeMillis() - readyAt >= START_GRACE_MS) break
            setPaused("waiting for playback")
            Thread.sleep(250L)
        }
        if (closed || source.released) return
        // Extra workers join only once the controller sees the connection can take them.
        while (!closed && !source.released && index >= allowedWorkers) Thread.sleep(500L)
        if (closed || source.released) return
        var failures = 0
        while (!closed && !source.released) {
            val fill = chooseFillSource()
            if (fill == null) {
                setPaused(source.backgroundBlockedReason ?: "no stream to read")
                return
            }
            when (val outcome = runFill(index, fill)) {
                FillOutcome.Finished -> return
                FillOutcome.Switched -> failures = 0
                is FillOutcome.Stopped -> {
                    setPaused(outcome.reason)
                    return
                }
                is FillOutcome.Failed -> {
                    // Debrid CDNs drop connections and throttle now and then; one error must not
                    // end fill. Retry with growing waits and, if a smaller stream keeps failing,
                    // go back to reading the playing one.
                    failures = if (outcome.madeProgress) 1 else failures + 1
                    readErrors.incrementAndGet()
                    lastError = outcome.message
                    Log.w(TAG, "fill worker $index error ($failures): ${outcome.message}")
                    if (failures >= SWITCH_AFTER_FAILURES) abandonFillSource(fill)
                    val waitMs = (RETRY_BASE_MS shl (failures - 1).coerceAtMost(4)).coerceAtMost(RETRY_MAX_MS)
                    val until = SystemClock.uptimeMillis() + waitMs
                    while (!closed && SystemClock.uptimeMillis() < until) Thread.sleep(200L)
                }
            }
        }
    }

    private sealed class FillOutcome {
        object Finished : FillOutcome()
        object Switched : FillOutcome()
        class Stopped(val reason: String) : FillOutcome()
        class Failed(val message: String, val madeProgress: Boolean) : FillOutcome()
    }

    /** Reads keyframes from [fill] until done or an error; a fresh extractor per attempt. */
    private fun runFill(index: Int, fill: FillSource): FillOutcome {
        val seekMaps = SeekMapCapturingExtractorsFactory(DefaultExtractorsFactory())
        val extractor = MediaExtractorCompat(seekMaps, BoundedRangeDataSourceFactory(fill.factory))
        var slot = -1
        var madeProgress = false
        try {
            extractor.setDataSource(fill.uri, 0L)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { track ->
                extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            } ?: return FillOutcome.Stopped("no video track")
            extractor.selectTrack(videoTrack)
            val format = extractor.getTrackFormat(videoTrack)
            val seekMap = seekMaps.seekMap
            if (seekMap == null || !seekMap.isSeekable) {
                // Without an index every seek would read from the start of the file.
                return FillOutcome.Stopped("no keyframe index: buffer only")
            }
            var buffer = ByteBuffer.allocate(1 shl 20)
            while (!closed && !source.released) {
                if (fillSource !== fill) return FillOutcome.Switched
                val reason = pauseReason()
                if (reason != null) {
                    setPaused(reason)
                    if (reason == REASON_BUDGET) return FillOutcome.Stopped(reason)
                    Thread.sleep(500L)
                    continue
                }
                // While playback buffers, one worker keeps going so fill never stops outright.
                val limit = if (source.buffering) 1 else allowedWorkers
                if (index >= limit) {
                    Thread.sleep(200L)
                    continue
                }
                setPaused(null)
                slot = nextSlot() ?: break
                buffer = fillSlot(extractor, seekMap, format, slot, buffer, fill.scale)
                slot = -1
                madeProgress = true
            }
            if (!closed && isComplete()) setPaused(null)
            return FillOutcome.Finished
        } catch (_: InterruptedException) {
            return FillOutcome.Finished
        } catch (error: Exception) {
            // Hand the slot back so another attempt fills it.
            if (slot >= 0) release(slot)
            return FillOutcome.Failed(error.message ?: error.javaClass.simpleName, madeProgress)
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Stops using a smaller stream that keeps failing; workers fall back to the playing one. */
    private fun abandonFillSource(failed: FillSource) {
        synchronized(resolveLock) {
            if (fillSource !== failed || failed.isPlaying) return
            val uri = source.uri
            val upstream = source.dataSourceFactory
            if (uri == null || upstream == null) return
            val factory = counting(upstream)
            fillSource = FillSource(uri, factory, 1.0, "playing (${failed.label.substringBefore(" (")} kept failing)", isPlaying = true)
            notifyChanged()
        }
    }

    /** A stream background fill reads keyframes from, and how its timeline maps to playback's. */
    private class FillSource(
        val uri: Uri,
        val factory: DataSource.Factory,
        /** Fill-stream time = playback time × scale (1.0, or a PAL speed-up ratio). */
        val scale: Double,
        val label: String,
        val isPlaying: Boolean = false,
    )

    private fun counting(upstream: DataSource.Factory) = DataSource.Factory {
        upstream.createDataSource().apply { addTransferListener(ByteCounter(downloaded)) }
    }

    /**
     * Picks the stream to read once for all workers: the smallest stream the addons offered
     * whose runtime matches playback (its keyframes are several times smaller than a remux's),
     * else the playing stream itself.
     */
    private fun chooseFillSource(): FillSource? {
        synchronized(resolveLock) {
            if (fillChosen) return fillSource
            // The stream list may still be loading; give it a moment.
            val waitUntil = SystemClock.uptimeMillis() + CANDIDATE_WAIT_MS
            while (!closed && LocalSeekPreviewStreams.candidates.value.isEmpty() && SystemClock.uptimeMillis() < waitUntil) {
                setPaused("finding smallest stream")
                Thread.sleep(250L)
            }
            val candidates = LocalSeekPreviewStreams.candidates.value
            val playingSize = candidates.firstOrNull { it.url == source.sourceKey }?.sizeBytes
            var chosen: FillSource? = null
            val rejected = mutableListOf<String>()
            for (candidate in candidates.take(MAX_PROBES)) {
                if (closed) break
                // Nothing smaller than the playing stream is left to try.
                if (candidate.url != null && candidate.url == source.sourceKey) break
                if (playingSize != null && candidate.sizeBytes >= playingSize) break
                setPaused("checking ${formatSize(candidate.sizeBytes)} stream")
                val result = probe(candidate)
                if (result is ProbeResult.Accepted) {
                    chosen = result.source
                    break
                }
                rejected += "${formatSize(candidate.sizeBytes)}: ${(result as ProbeResult.Rejected).reason}"
            }
            // Say what happened to the smaller streams, so a test run shows what to fix next.
            val listNote = LocalSeekPreviewStreams.summary.value ?: "no stream list"
            val skipNote = if (rejected.isEmpty()) "" else " · skipped " + rejected.joinToString(", ")
            chosen = chosen?.let { FillSource(it.uri, it.factory, it.scale, "${it.label} ($listNote$skipNote)") }
            val why = when {
                candidates.isEmpty() -> listNote
                rejected.isEmpty() -> "already smallest; $listNote"
                else -> "$listNote$skipNote"
            }
            if (chosen == null && !closed) {
                val uri = source.uri
                val upstream = source.dataSourceFactory
                if (uri != null && upstream != null) {
                    val factory = counting(upstream)
                    chosen = FillSource(resolveRedirect(uri, factory), factory, 1.0, "playing ($why)", isPlaying = true)
                }
            }
            fillSource = chosen
            fillChosen = true
            notifyChanged()
            return chosen
        }
    }

    private sealed class ProbeResult {
        class Accepted(val source: FillSource) : ProbeResult()
        class Rejected(val reason: String) : ProbeResult()
    }

    /** Opens [candidate] and accepts it only when its runtime matches what is playing. */
    private fun probe(candidate: LocalPreviewStreamCandidate): ProbeResult {
        val url = candidate.url
            ?: runCatching { runBlocking { candidate.resolveUrl?.invoke() } }.getOrNull()
            ?: return ProbeResult.Rejected("no link")
        val factory = counting(
            PlatformPlaybackDataSourceFactory.create(
                context = source.context,
                defaultRequestHeaders = candidate.requestHeaders,
                defaultResponseHeaders = emptyMap(),
                useYoutubeChunkedPlayback = false,
            ),
        )
        val uri = resolveRedirect(Uri.parse(url), factory)
        val seekMaps = SeekMapCapturingExtractorsFactory(DefaultExtractorsFactory())
        val extractor = MediaExtractorCompat(seekMaps, BoundedRangeDataSourceFactory(factory))
        return try {
            extractor.setDataSource(uri, 0L)
            val hasVideo = (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            }
            val seekMap = seekMaps.seekMap
            val candidateMs = seekMap?.durationUs?.takeIf { it != C.TIME_UNSET && it > 0 }?.div(1_000L)
            if (!hasVideo || seekMap == null || !seekMap.isSeekable || candidateMs == null) {
                return ProbeResult.Rejected("no index")
            }
            val scale = matchingScale(candidateMs)
                ?: return ProbeResult.Rejected("runtime ${signedSeconds(candidateMs - durationMs)}")
            Log.i(TAG, "reading keyframes from ${formatSize(candidate.sizeBytes)} stream, scale $scale")
            ProbeResult.Accepted(FillSource(uri, factory, scale, formatSize(candidate.sizeBytes)))
        } catch (error: Exception) {
            Log.i(TAG, "smaller stream rejected: ${error.message}")
            ProbeResult.Rejected("unreadable")
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun signedSeconds(ms: Long): String = (if (ms >= 0) "+" else "") + (ms / 1_000L) + "s"

    /**
     * How another release's timeline maps onto playback's, or null when it may be a different
     * cut: same runtime (within a few seconds), or the 25 fps / 23.976 fps PAL speed change.
     */
    private fun matchingScale(candidateMs: Long): Double? {
        if (abs(candidateMs - durationMs) <= SAME_RUNTIME_TOLERANCE_MS) return 1.0
        val ratio = candidateMs.toDouble() / durationMs.toDouble()
        for (pal in doubleArrayOf(PAL_RATIO, 1.0 / PAL_RATIO)) {
            if (abs(ratio - pal) < PAL_TOLERANCE) return pal
        }
        return null
    }

    /**
     * The stream's final URL. Debrid links often redirect to a CDN; resolving that once saves a
     * round trip on every one of the hundreds of range requests that follow.
     */
    private fun resolveRedirect(original: Uri, factory: DataSource.Factory): Uri {
        val result = runCatching {
            val dataSource = factory.createDataSource()
            try {
                dataSource.open(DataSpec.Builder().setUri(original).setPosition(0).setLength(1).build())
                dataSource.read(ByteArray(1), 0, 1)
                dataSource.uri
            } finally {
                dataSource.close()
            }
        }.getOrNull()?.takeIf { it.scheme == "http" || it.scheme == "https" } ?: original
        if (result != original) Log.i(TAG, "resolved stream redirect to ${result.host}")
        return result
    }

    /** Fetches the keyframe nearest [slot]'s start and hands it to the decode thread. */
    private fun fillSlot(
        extractor: MediaExtractorCompat,
        seekMap: SeekMap,
        format: MediaFormat,
        slot: Int,
        initialBuffer: ByteBuffer,
        scale: Double,
    ): ByteBuffer {
        var buffer = initialBuffer
        // Target and keyframe times are on the fill stream's timeline.
        val targetUs = (slot * SLOT_MS * 1_000L * scale).toLong()
        // The index names the keyframes around the target without downloading anything.
        val points = seekMap.getSeekPoints(targetUs)
        val keyUs = listOf(points.first.timeUs, points.second.timeUs).minByOrNull { abs(it - targetUs) } ?: targetUs
        val knownSlot = synchronized(lock) { fillKeyframeSlots[keyUs] }
        if (knownSlot != null) {
            // Long-GOP encodes: this slot's nearest keyframe is already a thumbnail.
            val bytes = synchronized(lock) { jpegs[knownSlot] }
            if (bytes != null) {
                store(slot, bytes, (keyUs / scale / 1_000.0).toLong())
                return buffer
            }
        }
        val fetchStart = SystemClock.elapsedRealtime()
        extractor.seekTo(keyUs, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
        val timeUs = extractor.sampleTime
        // Landing far from the target means the seek did not work; never show a wrong frame.
        if (timeUs < 0 || abs(timeUs - targetUs) > MAX_KEYFRAME_DISTANCE_US) {
            markFailed(slot)
            return buffer
        }
        val size = extractor.sampleSize.toInt()
        if (size > buffer.capacity()) buffer = ByteBuffer.allocate(size + (size shr 2))
        buffer.clear()
        val read = extractor.readSampleData(buffer, 0)
        if (read <= 0 || closed) {
            markFailed(slot)
            return buffer
        }
        fetchTimes.add(SystemClock.elapsedRealtime() - fetchStart)
        adjustConcurrency()
        val sample = buffer.array().copyOfRange(buffer.arrayOffset(), buffer.arrayOffset() + read)
        // Blocks when the decoder is behind, so fetching never runs far ahead of it.
        synchronized(lock) { fillKeyframeSlots.putIfAbsent(timeUs, slot) }
        val job = DecodeJob(slot, format, sample, (timeUs / scale).toLong())
        while (!closed && !decodeQueue.offer(job, 500L, TimeUnit.MILLISECONDS)) Unit
        if (closed) markFailed(slot)
        return buffer
    }

    private class DecodeJob(val slot: Int, val format: MediaFormat, val sample: ByteArray, val timeUs: Long)

    private fun decodeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE)
        try {
            while (!closed) {
                val job = decodeQueue.poll(500L, TimeUnit.MILLISECONDS) ?: continue
                val started = SystemClock.elapsedRealtime()
                val frame = runCatching { decoder.decode(job.format, job.sample, 0, job.sample.size, job.timeUs) }.getOrNull()
                decodeTimes.add(SystemClock.elapsedRealtime() - started)
                if (frame != null) store(job.slot, frame.jpeg, job.timeUs / 1_000L) else markFailed(job.slot)
            }
        } catch (_: InterruptedException) {
            // Closing.
        }
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

    /**
     * Additive increase, multiplicative decrease, like TCP: each worker is one more keyframe in
     * flight, and per-request latency (not bandwidth) is what limits fill. Add a worker while
     * fetch times hold steady; halve them as soon as playback starts buffering.
     */
    private fun adjustConcurrency() {
        synchronized(concurrencyLock) {
            if (++fetchesSinceAdjust < ADJUST_EVERY) return
            fetchesSinceAdjust = 0
            val average = fetchTimes.average()
            // The baseline follows the lowest recent average but may drift up slowly, so one
            // lucky early burst does not make every later fetch look congested.
            baselineFetchMs = when {
                baselineFetchMs == 0L || average < baselineFetchMs -> average
                else -> minOf(average, baselineFetchMs + baselineFetchMs / 8)
            }
            if (average <= baselineFetchMs * 3 / 2 && allowedWorkers < MAX_WORKERS) allowedWorkers++
            else if (average > baselineFetchMs * 2 && allowedWorkers > MIN_WORKERS) allowedWorkers--
        }
    }

    private fun pauseReason(): String? {
        if (downloaded.get() >= BYTE_BUDGET) return REASON_BUDGET
        if (source.buffering) {
            // Buffering right after a seek is playback refilling, not fill starving it: keep the
            // worker count. A stall during normal playback halves it, once per stall.
            val episode = source.bufferingEpisode
            val afterSeek = SystemClock.uptimeMillis() - source.lastSeekAtMs < SEEK_GRACE_MS
            synchronized(concurrencyLock) {
                if (episode != handledBufferingEpisode) {
                    handledBufferingEpisode = episode
                    if (!afterSeek) {
                        stalls++
                        allowedWorkers = maxOf(MIN_WORKERS, allowedWorkers / 2)
                        fetchesSinceAdjust = 0
                    }
                }
            }
        }
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
        avgFetchMs = fetchTimes.average(),
        avgDecodeMs = decodeTimes.average(),
        decoder = decoder.activeDecoderLabel,
        fillSource = fillSource?.label,
        workers = if (source.buffering) 1 else allowedWorkers,
        stalls = stalls,
        readErrors = readErrors.get(),
        lastError = lastError,
        buffering = source.buffering,
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
        private const val MIN_WORKERS = 2
        private const val START_WORKERS = 3
        private const val MAX_WORKERS = 8
        private const val ADJUST_EVERY = 4
        private const val SEEK_GRACE_MS = 5_000L
        private const val RETRY_BASE_MS = 1_000L
        private const val RETRY_MAX_MS = 15_000L
        private const val SWITCH_AFTER_FAILURES = 3
        private const val DECODE_QUEUE = 6
        private const val CANDIDATE_WAIT_MS = 8_000L
        private const val MAX_PROBES = 5
        private const val SAME_RUNTIME_TOLERANCE_MS = 5_000L
        private const val PAL_RATIO = 25.0 / (24_000.0 / 1_001.0)
        private const val PAL_TOLERANCE = 0.002

        private fun formatSize(bytes: Long): String =
            if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000L} MB"
        private const val MAX_KEYFRAME_DISTANCE_US = 2 * SLOT_MS * 1_000L
        private const val PRIORITY_RADIUS = 6
        private const val START_GRACE_MS = 2_000L
        private const val BYTE_BUDGET = 400L * 1_000_000L
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

/** Average of the last few durations, for the debug readout. */
private class RollingAverage(private val size: Int = 20) {
    private val values = LongArray(size)
    private var count = 0
    private var next = 0

    @Synchronized
    fun add(value: Long) {
        values[next] = value
        next = (next + 1) % size
        if (count < size) count++
    }

    @Synchronized
    fun average(): Long = if (count == 0) 0L else values.take(count).sum() / count
}
