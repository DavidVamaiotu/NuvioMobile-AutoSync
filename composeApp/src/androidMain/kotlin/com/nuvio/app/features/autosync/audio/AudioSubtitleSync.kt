@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.autosync.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.nuvio.app.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToLong

/**
 * AutoSync's audio fallback: syncs one external subtitle to the speech of the playing stream when
 * there is no embedded subtitle to sync against.
 *
 * Compressed audio is copied as the player demuxes it (see [AudioSyncTaps]) and, on unmetered
 * networks, sampled at a few dialogue spots across the film ahead of playback. It is decoded on a
 * background thread, turned into a speech timeline by Silero VAD, and aligned against the subtitle
 * by [AudioSyncTracker]. Every change of the resulting mapping is reported through [onModel]
 * (null = back to the file's own timing). One instance per synced subtitle; [release] it after.
 */
internal class AudioSubtitleSync(
    context: Context,
    private val sourceKey: String,
    private val sourceHeaders: Map<String, String>,
    /** Called from background threads. */
    private val onModel: (SubtitleSyncModel?) -> Unit,
    /** Called from background threads. */
    private val onStatus: (AudioSyncStatus) -> Unit,
) : AudioSampleSink {
    private val appContext = context.applicationContext
    private val timeline = SpeechTimeline()
    private val aligner: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "NuvioAudioSyncAlign").apply { isDaemon = true }
    }
    private val alignRunning = AtomicBoolean(false)
    private val sessionRequest = AtomicInteger(0)
    private val preferences by lazy { appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }

    private class Session(val key: String, val track: SubtitleSpeechTrack) {
        val tracker = AudioSyncTracker(track)
    }

    @Volatile private var session: Session? = null
    @Volatile private var decoder: AudioSyncDecoder? = null
    @Volatile private var decoderUnavailable = false
    @Volatile private var model: SubtitleSyncModel? = null
    @Volatile private var estimated = false
    @Volatile private var lockedAtElapsedMs = 0L
    @Volatile private var released = false
    @Volatile private var playbackPositionMs = 0L
    @Volatile private var mediaDurationMs = 0L
    @Volatile private var selectedAudioFormat: Format? = null
    @Volatile private var provisionalAudioFormat: Format? = null
    private val spotSamplingStarted = AtomicBoolean(false)
    private var lastAlignedSession: Session? = null
    private var lastAlignVersion = -1L
    private var lastAlignAtMs = 0L

    /** Starts syncing the subtitle [key] with its parsed [cues]; reports progress via callbacks. */
    fun start(key: String, cues: List<CuesWithTiming>) {
        val request = sessionRequest.incrementAndGet()
        session = null
        publish(null, estimated = false)
        lockedAtElapsedMs = 0L
        try {
            aligner.execute {
                val track = SubtitleSpeechTrack.fromCues(dialogueOf(cues))
                if (sessionRequest.get() != request || released) return@execute
                if (track.size < MIN_TRACK_CUES) {
                    SyncLog.i("subtitle has only ${track.size} dialogue cues; audio sync skipped")
                    notify(AudioSyncStatus.Unavailable)
                    return@execute
                }
                if (createDecoder() == null || sessionRequest.get() != request) return@execute
                val started = Session(key, track)
                session = started
                AudioSyncTaps.attach(sourceKey, this)
                SyncLog.i("sync session started with ${track.size} dialogue cues")
                if (!applyRemembered(started)) notify(AudioSyncStatus.Listening)
            }
        } catch (_: Exception) {
            // Executor already shut down: released meanwhile.
        }
    }

    /** Called on the main thread about four times a second while the session runs. */
    fun onPlayback(positionMs: Long, durationMs: Long, tracks: Tracks) {
        playbackPositionMs = positionMs.coerceAtLeast(0L)
        if (durationMs > 0) mediaDurationMs = durationMs
        tracks.selectedAudioFormat()?.let { format ->
            if (selectedAudioFormat?.let { matches(it, format) } != true) selectedAudioFormat = format
        }
        scheduleAlignment()
        maybeSampleSpots()
    }

    fun release() {
        released = true
        sessionRequest.incrementAndGet()
        session = null
        AudioSyncTaps.detach(sourceKey, this)
        decoder?.release()
        aligner.shutdownNow()
    }

    // Audio demuxed by the player (see AudioSyncTaps).

    override fun wantsSamples(format: Format): Boolean {
        if (released || decoderUnavailable || session == null) return false
        val selected = selectedAudioFormat
        if (selected != null) return matches(format, selected)
        val provisional = provisionalAudioFormat ?: format.also { provisionalAudioFormat = it }
        return matches(format, provisional)
    }

    override fun onSample(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
        if (!inDutyWindow(timeUs)) return
        val decoder = decoder ?: createDecoder() ?: return
        decoder.offer(format, timeUs, data, offset, size)
    }

    override fun onDiscontinuity() {
        decoder?.discontinuity()
    }

    /**
     * Once a lock has been refined over a long stretch, analyse one minute in three: enough to
     * follow later jumps while saving most of the CPU.
     */
    private fun inDutyWindow(timeUs: Long): Boolean {
        if (session?.tracker?.model == null || lockedAtElapsedMs == 0L) return true
        if (SystemClock.elapsedRealtime() - lockedAtElapsedMs < FULL_ANALYSIS_AFTER_LOCK_MS) return true
        return (timeUs / DUTY_WINDOW_US) % DUTY_CYCLE == 0L
    }

    @Synchronized
    private fun createDecoder(): AudioSyncDecoder? {
        decoder?.let { return it }
        if (decoderUnavailable || released) return null
        val weights = runCatching { loadWeights(appContext) }.onFailure {
            SyncLog.w("could not load VAD weights: ${it.message}")
        }.getOrNull()
        if (weights == null) {
            decoderUnavailable = true
            return null
        }
        val analyzer = SpeechAnalyzer(SileroVad(weights), timeline)
        // The second analyzer serves playback-output audio, which this fallback does not tap.
        return AudioSyncDecoder(analyzer, analyzer) { mime ->
            SyncLog.i("no decoder usable for $mime; audio sync unavailable")
            notify(AudioSyncStatus.Unavailable)
        }.also { decoder = it }
    }

    // Alignment.

    private fun scheduleAlignment() {
        val current = session ?: return
        if (released) return
        val now = SystemClock.elapsedRealtime()
        val version = timeline.version
        // Check often while searching so an early estimate lands quickly; relax once confirmed.
        val interval = if (current.tracker.model == null) SEARCH_INTERVAL_MS else ALIGN_INTERVAL_MS
        if (current === lastAlignedSession && (version == lastAlignVersion || now - lastAlignAtMs < interval)) {
            return
        }
        if (!alignRunning.compareAndSet(false, true)) return
        lastAlignedSession = current
        lastAlignVersion = version
        lastAlignAtMs = now
        val position = playbackPositionMs
        try {
            aligner.execute {
                try {
                    align(current, position)
                } catch (error: Throwable) {
                    SyncLog.w("alignment failed: ${error.message}")
                } finally {
                    alignRunning.set(false)
                }
            }
        } catch (_: Exception) {
            alignRunning.set(false)
        }
    }

    private fun align(current: Session, positionMs: Long) {
        val outcome = current.tracker.update(timeline, positionMs)
        if (session !== current) return
        fun offsetMs(synced: SubtitleSyncModel) = synced.delayUsAt(positionMs * 1_000L) / 1_000L
        when (outcome) {
            is AudioSyncTracker.Outcome.Provisional -> {
                val first = model == null
                publish(outcome.model, estimated = true)
                SyncLog.i("provisional ${outcome.model}")
                if (first) notify(AudioSyncStatus.Estimated(offsetMs(outcome.model)))
            }
            is AudioSyncTracker.Outcome.Retracted -> {
                publish(null, estimated = false)
                SyncLog.i("early estimate withdrawn")
                notify(AudioSyncStatus.Withdrawn)
            }
            is AudioSyncTracker.Outcome.Locked -> {
                lockedAtElapsedMs = SystemClock.elapsedRealtime()
                publish(outcome.model, estimated = false)
                remember(current.key, outcome.model)
                SyncLog.i("LOCKED ${outcome.model}")
                notify(AudioSyncStatus.Synced(offsetMs(outcome.model), outcome.estimate.scale != 1.0))
            }
            is AudioSyncTracker.Outcome.Refined -> {
                publish(outcome.model, estimated = false)
                remember(current.key, outcome.model)
                SyncLog.i("refined ${outcome.model}")
            }
            is AudioSyncTracker.Outcome.Jumped -> {
                publish(outcome.model, estimated = false)
                remember(current.key, outcome.model)
                SyncLog.i("jump detected ${outcome.model}")
                notify(AudioSyncStatus.Adjusted(offsetMs(outcome.model)))
            }
            else -> Unit
        }
    }

    private fun publish(synced: SubtitleSyncModel?, estimated: Boolean) {
        this.estimated = estimated
        if (model === synced) return
        model = synced
        try {
            onModel(synced)
        } catch (_: Throwable) {
        }
    }

    private fun notify(status: AudioSyncStatus) {
        try {
            onStatus(status)
        } catch (_: Throwable) {
        }
    }

    // Sampling dialogue spots across the film, ahead of playback.

    /**
     * Once playback runs with some buffer, samples a few dialogue spots across the film while the
     * subtitle is still unsynced. Once per session, on unmetered networks and plain HTTP files only.
     */
    private fun maybeSampleSpots() {
        val current = session ?: return
        if (released || spotSamplingStarted.get() || (model != null && !estimated)) return
        if (mediaDurationMs < MIN_SAMPLED_FILM_MS) return
        if (timeline.knownFrameCount() < SAMPLE_AFTER_FRAMES) return
        val uri = spotSourceUri() ?: return
        if (!spotSamplingStarted.compareAndSet(false, true)) return
        if (isMetered()) {
            SyncLog.i("not sampling audio across the film on a metered network")
            return
        }
        Thread({ sampleSpots(uri, current) }, "NuvioAudioSyncSpots").apply {
            isDaemon = true
            start()
        }
    }

    private fun spotSourceUri(): Uri? {
        val uri = runCatching { Uri.parse(sourceKey) }.getOrNull() ?: return null
        val host = uri.host.orEmpty()
        val path = uri.path.orEmpty().lowercase()
        return uri.takeIf {
            (uri.scheme == "http" || uri.scheme == "https") &&
                host != "localhost" && !host.startsWith("127.") &&
                !path.endsWith(".m3u8") && !path.endsWith(".mpd")
        }
    }

    private fun sampleSpots(uri: Uri, current: Session) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val weights = runCatching { loadWeights(appContext) }.getOrNull() ?: return
        // One decoder per worker: each reads a different place, so their audio must not mix.
        val decoders = List(SPOT_WORKERS) {
            val analyzer = SpeechAnalyzer(SileroVad(weights), timeline)
            AudioSyncDecoder(analyzer, analyzer, allowVendorDecoders = false)
        }
        try {
            // A fresh start covers the opening itself; after a resume any part of the film helps.
            val coveredMs = timeline.segments(fromFrame = SpeechTimeline.frameForTimeUs(playbackPositionMs * 1_000L))
                .firstOrNull()?.let { (it.toFrame * SpeechTimeline.FRAME_DURATION_MS).toLong() } ?: 0L
            val notBeforeMs = if (playbackPositionMs < RESUME_THRESHOLD_MS) coveredMs + SPOT_MS else 0L
            val spots = DialogueSpotPlanner.plan(listOf(current.track), mediaDurationMs, SPOT_COUNT, SPOT_MS, notBeforeMs)
            if (spots.isEmpty()) return
            SyncLog.i("sampling audio at ${spots.map { it / 1_000 }}s")
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(sourceHeaders)
                .setAllowCrossProtocolRedirects(true)
            val sampler = AudioSpotSampler(uri, dataSourceFactory, MAX_SPOT_BYTES)
            val result = sampler.run(
                spotsMs = spots,
                spotMs = SPOT_MS,
                workers = decoders.map { AudioSyncExtractorsFactory(spotExtractorsFactory(), SpotSink(it)) },
                isCancelled = { released || session !== current || (model != null && !estimated) },
            ) { _, _ -> }
            SyncLog.i("sampled ${result.sampled} spots, ${result.bytes / 1_000_000} MB, failure=${result.failure}")
            decoders.forEach { it.awaitDrained(DRAIN_TIMEOUT_MS) }
        } catch (error: Throwable) {
            SyncLog.w("audio sampling failed: ${error.message}")
        } finally {
            decoders.forEach(AudioSyncDecoder::release)
        }
    }

    /** Feeds sampled audio of the playing track to [decoder], waiting when it is busy. */
    private inner class SpotSink(private val decoder: AudioSyncDecoder) : AudioSampleSink {
        override fun wantsSamples(format: Format): Boolean {
            if (released) return false
            val playing = selectedAudioFormat ?: provisionalAudioFormat ?: return false
            return matches(format, playing)
        }

        override fun onSample(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
            while (!decoder.offer(format, timeUs, data, offset, size)) {
                if (!decoder.accepts(format)) return
                Thread.sleep(SPOT_BACKOFF_MS)
            }
        }

        override fun onDiscontinuity() {
            decoder.discontinuity()
        }
    }

    private fun isMetered(): Boolean =
        runCatching {
            appContext.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
        }.getOrDefault(true)

    // A lock found earlier for this subtitle on this stream is applied at once.

    /** Versioned: stored as "fromMs,scale,shiftMs" per segment, separated by "|". */
    private fun rememberKey(subtitleKey: String): String = "v2:${sourceKey.hashCode()}:${subtitleKey.hashCode()}"

    private fun remember(subtitleKey: String, synced: SubtitleSyncModel) {
        val stored = synced.segments.joinToString("|") { "${it.fromMediaMs},${it.scale},${it.shiftMs}" }
        runCatching { preferences.edit().putString(rememberKey(subtitleKey), stored).apply() }
    }

    private fun applyRemembered(current: Session): Boolean {
        val stored = runCatching { preferences.getString(rememberKey(current.key), null) }.getOrNull() ?: return false
        val segments = stored.split('|').map { entry ->
            val parts = entry.split(',')
            SubtitleSyncSegment(
                fromMediaMs = parts.getOrNull(0)?.toLongOrNull() ?: return false,
                scale = parts.getOrNull(1)?.toDoubleOrNull() ?: return false,
                shiftMs = parts.getOrNull(2)?.toDoubleOrNull() ?: return false,
            )
        }
        val restored = segments.takeIf { it.isNotEmpty() }?.let(::SubtitleSyncModel) ?: return false
        current.tracker.adopt(restored)
        publish(restored, estimated = false)
        SyncLog.i("restored remembered sync $restored")
        notify(
            AudioSyncStatus.Synced(
                offsetMs = restored.delayUsAt(playbackPositionMs * 1_000L) / 1_000L,
                rateCorrected = restored.segments.first().scale != 1.0,
            ),
        )
        return true
    }

    private fun dialogueOf(cues: List<CuesWithTiming>): List<Triple<Long, Long, String>> =
        cues.mapNotNull { entry ->
            val startUs = entry.startTimeUs
            if (startUs == C.TIME_UNSET) return@mapNotNull null
            val endUs = when {
                entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs
                entry.durationUs != C.TIME_UNSET -> startUs + entry.durationUs
                else -> return@mapNotNull null
            }
            val text = entry.cues.joinToString("\n") { it.text?.toString().orEmpty() }
            Triple(startUs / 1_000L, endUs / 1_000L, text)
        }

    companion object {
        private const val MIN_TRACK_CUES = 20
        private const val ALIGN_INTERVAL_MS = 8_000L
        private const val SEARCH_INTERVAL_MS = 3_000L
        private const val PREFERENCES = "nuvio_audio_sync"

        private const val SPOT_COUNT = 4
        /** One connection per spot, so every spot arrives in the same round. */
        private const val SPOT_WORKERS = SPOT_COUNT
        private const val SPOT_MS = 30_000L
        private const val MAX_SPOT_BYTES = 150L * 1_000_000L
        private const val MIN_SAMPLED_FILM_MS = 20 * 60_000L
        private const val RESUME_THRESHOLD_MS = 10 * 60_000L
        private const val SPOT_BACKOFF_MS = 5L
        private const val DRAIN_TIMEOUT_MS = 10_000L

        /** Playback has started and buffered this much before sampling competes for bandwidth. */
        private val SAMPLE_AFTER_FRAMES = (5_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()
        private const val FULL_ANALYSIS_AFTER_LOCK_MS = 10 * 60_000L
        private const val DUTY_WINDOW_US = 60_000_000L
        private const val DUTY_CYCLE = 3L

        @Volatile
        private var cachedWeights: SileroVadWeights? = null

        private fun loadWeights(context: Context): SileroVadWeights {
            cachedWeights?.let { return it }
            return synchronized(this) {
                cachedWeights ?: context.resources.openRawResource(R.raw.silero_vad_v5_16k).use {
                    SileroVadWeights.read(it)
                }.also { cachedWeights = it }
            }
        }

        /** Same extractors the player uses for plain files. */
        private fun spotExtractorsFactory(): ExtractorsFactory =
            DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)

        private val periodPrefix = Regex("^(\\d+:)+")

        /**
         * Same audio track, tolerating the Format differences between the extractor output and the
         * player's track groups (MergingMediaSource prefixes ids with the child index, e.g. "0:2").
         */
        private fun matches(a: Format, b: Format): Boolean {
            if (a == b) return true
            if (a.sampleMimeType != b.sampleMimeType) return false
            val aId = a.id?.replace(periodPrefix, "")
            val bId = b.id?.replace(periodPrefix, "")
            if (!aId.isNullOrEmpty() && !bId.isNullOrEmpty()) return aId == bId
            return a.language == b.language && a.channelCount == b.channelCount && a.sampleRate == b.sampleRate
        }
    }
}

/** Progress worth showing to the user. */
internal sealed interface AudioSyncStatus {
    /** The audio is being analysed. */
    data object Listening : AudioSyncStatus

    /** This subtitle or this audio format cannot be synced to the audio. */
    data object Unavailable : AudioSyncStatus

    /** The early estimate could not be confirmed; subtitles are back on the file's own timing. */
    data object Withdrawn : AudioSyncStatus

    /** An early, unconfirmed estimate was applied; it keeps adjusting until confirmed. */
    data class Estimated(val offsetMs: Long) : AudioSyncStatus

    /** Subtitles now follow the audio; [offsetMs] is the applied delay at the playhead. */
    data class Synced(val offsetMs: Long, val rateCorrected: Boolean) : AudioSyncStatus

    /** The subtitle timing changed partway through (a different cut). */
    data class Adjusted(val offsetMs: Long) : AudioSyncStatus
}

/**
 * Where the player's demuxed audio goes. AutoSync's extractor wrapper sends every source's audio
 * to [sinkFor]; it reaches an [AudioSubtitleSync] only while one is attached for that source, and
 * is otherwise dropped after a map lookup.
 */
internal object AudioSyncTaps {
    private val attached = ConcurrentHashMap<String, AudioSampleSink>()

    fun sinkFor(sourceKey: String): AudioSampleSink = object : AudioSampleSink {
        override fun wantsSamples(format: Format): Boolean =
            attached[sourceKey]?.wantsSamples(format) == true

        override fun onSample(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
            attached[sourceKey]?.onSample(format, timeUs, data, offset, size)
        }

        override fun onDiscontinuity() {
            attached[sourceKey]?.onDiscontinuity()
        }
    }

    fun attach(sourceKey: String, sink: AudioSampleSink) {
        attached[sourceKey] = sink
    }

    fun detach(sourceKey: String, sink: AudioSampleSink) {
        attached.remove(sourceKey, sink)
    }
}

/** Format of the audio track the player currently has selected, if any. */
internal fun Tracks.selectedAudioFormat(): Format? {
    for (group in groups) {
        if (group.type != C.TRACK_TYPE_AUDIO || !group.isSelected) continue
        for (index in 0 until group.length) {
            if (group.isTrackSelected(index)) return group.getTrackFormat(index)
        }
    }
    return null
}

/**
 * [cues] placed on the media timeline by [model] (media time = subtitle time * scale + shift of
 * the piece the cue lands in), sorted by start as the sidecar renderer expects.
 */
internal fun retimeCues(cues: List<CuesWithTiming>, model: SubtitleSyncModel): List<CuesWithTiming> {
    fun mediaUs(subtitleUs: Long): Long {
        var segment = model.segmentAt(subtitleUs / 1_000L)
        repeat(2) {
            val mediaMs = (subtitleUs / 1_000.0) * segment.scale + segment.shiftMs
            segment = model.segmentAt(mediaMs.toLong())
        }
        val mediaMs = (subtitleUs / 1_000.0) * segment.scale + segment.shiftMs
        return (mediaMs * 1_000.0).roundToLong().coerceAtLeast(0L)
    }
    return cues.mapNotNull { entry ->
        val startUs = entry.startTimeUs.takeIf { it != C.TIME_UNSET } ?: return@mapNotNull null
        val endUs = when {
            entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs
            entry.durationUs != C.TIME_UNSET -> startUs + entry.durationUs
            else -> return@mapNotNull null
        }
        val mediaStartUs = mediaUs(startUs)
        val mediaEndUs = mediaUs(endUs).coerceAtLeast(mediaStartUs + 1_000L)
        CuesWithTiming(entry.cues, mediaStartUs, mediaEndUs - mediaStartUs)
    }.sortedBy { it.startTimeUs }
}
