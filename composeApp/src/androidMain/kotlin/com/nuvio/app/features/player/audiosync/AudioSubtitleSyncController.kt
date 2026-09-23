@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.audiosync

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.CuesWithTiming
import com.nuvio.app.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Keeps external subtitles in sync with the audio of the playing stream.
 *
 * Compressed audio is copied as ExoPlayer demuxes it (see [AudioSyncExtractorsFactory]), decoded on
 * a background thread, turned into a speech timeline by Silero VAD, and aligned against the
 * subtitle cues. The resulting mapping is exposed as an extra subtitle delay through
 * [autoDelayMs], on top of the user's manual delay. One instance lives per player surface.
 */
internal class AudioSubtitleSyncController(
    context: Context,
    /** The user's current manual subtitle delay. */
    private val manualDelayMs: () -> Int,
    /** User-visible progress, called from background threads. */
    private val onStatus: (AudioSyncStatus) -> Unit = {},
) : AudioSampleSink, PlaybackPcmListener {
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

    @Volatile
    private var decoder: AudioSyncDecoder? = null

    @Volatile
    private var decoderUnavailable = false

    private class Session(val key: String, val track: SubtitleSpeechTrack) {
        val tracker = AudioSyncTracker(track)
    }

    @Volatile
    private var session: Session? = null

    @Volatile
    private var model: SubtitleSyncModel? = null

    @Volatile
    private var manualDelayAtLockMs = 0

    @Volatile
    private var lockedAtElapsedMs = 0L

    @Volatile
    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) model = null
        }

    @Volatile
    private var playbackPositionMs = 0L

    @Volatile
    private var selectedAudioFormat: Format? = null

    @Volatile
    private var provisionalAudioFormat: Format? = null

    @Volatile
    private var released = false

    private var lastAlignedSession: Session? = null
    private var lastAlignVersion = -1L
    private var lastAlignAtMs = 0L

    /** Wraps [factory] so audio is tapped while it is demuxed. */
    fun wrap(factory: ExtractorsFactory): ExtractorsFactory = AudioSyncExtractorsFactory(factory, this)

    /** Wraps the player's audio output so its decoded audio can be used when demux-time capture can't. */
    fun wrapAudioSink(sink: AudioSink): AudioSink = PlaybackAudioTap(sink, this)

    /** Extra delay to add to the user's subtitle delay at the current playback position. */
    fun autoDelayMs(): Int {
        val current = model ?: return 0
        if (!enabled || session == null) return 0
        val totalMs = current.delayUsAt(playbackPositionMs * 1_000L) / 1_000.0
        return (totalMs - manualDelayAtLockMs).roundToInt()
    }

    /** Called on the main thread from the player's periodic snapshot. */
    fun onPlaybackPosition(positionMs: Long) {
        playbackPositionMs = positionMs.coerceAtLeast(0L)
        scheduleAlignment()
    }

    fun onSourceChanged() {
        timeline.clear()
        decoder?.discontinuity()
        selectedAudioFormat = null
        provisionalAudioFormat = null
        session?.let { current -> session = Session(current.key, current.track) }
        model = null
        lockedAtElapsedMs = 0L
        lastAlignVersion = -1L
    }

    fun onAudioTrackSelected(format: Format?) {
        if (format == null || selectedAudioFormat?.let { matches(it, format) } == true) return
        selectedAudioFormat = format
        Log.d(TAG, "audio track selected: ${describe(format)}")
    }

    /**
     * Starts syncing the subtitle identified by [key] with the given parsed cues. The dialogue track
     * is built and the model loaded on the background thread; the session becomes active afterwards
     * unless another subtitle was chosen meanwhile.
     */
    fun startSession(key: String, cues: List<CuesWithTiming>) {
        val request = sessionRequest.incrementAndGet()
        session = null
        model = null
        lockedAtElapsedMs = 0L
        try {
            aligner.execute {
                val track = buildTrack(cues)
                if (sessionRequest.get() != request || released) return@execute
                if (track.size < MIN_TRACK_CUES) {
                    Log.i(TAG, "subtitle $key has only ${track.size} dialogue cues; audio sync skipped")
                    return@execute
                }
                createDecoder()
                if (sessionRequest.get() != request) return@execute
                session = Session(key, track)
                Log.i(TAG, "sync session started for $key with ${track.size} dialogue cues")
                if (enabled) notify(AudioSyncStatus.Listening)
            }
        } catch (_: Exception) {
            // Executor already shut down: the surface is being released.
        }
    }

    private fun buildTrack(cues: List<CuesWithTiming>): SubtitleSpeechTrack {
        val dialogue = cues.mapNotNull { entry ->
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
        return SubtitleSpeechTrack.fromCues(dialogue)
    }

    fun stopSession() {
        sessionRequest.incrementAndGet()
        lockedAtElapsedMs = 0L
        if (session != null) Log.i(TAG, "sync session stopped")
        session = null
        model = null
    }

    fun activeSessionKey(): String? = session?.key

    fun release() {
        released = true
        session = null
        model = null
        decoder?.release()
        aligner.shutdownNow()
    }

    override fun wantsSamples(format: Format): Boolean {
        if (!enabled || released || session == null || decoderUnavailable) return false
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
     * Playback audio is only analysed where the look-ahead capture left the timeline unknown: codecs
     * without a usable second decoder, HLS/DASH streams, or gaps. Otherwise it is skipped cheaply.
     */
    override fun wantsPlaybackPcm(mediaTimeUs: Long, durationUs: Long): Boolean {
        if (!enabled || released || session == null || decoder == null) return false
        if (mediaTimeUs < 0 || !inDutyWindow(mediaTimeUs)) return false
        val from = SpeechTimeline.frameForTimeUs(mediaTimeUs)
        val to = SpeechTimeline.frameForTimeUs(mediaTimeUs + durationUs) + 1
        return timeline.knownFramesIn(from, to) < to - from - 1
    }

    override fun onPlaybackPcm(mono: FloatArray, frames: Int, sampleRate: Int, mediaTimeUs: Long) {
        decoder?.offerPlaybackPcm(mono, frames, sampleRate, mediaTimeUs)
    }

    /**
     * Once a lock has been refined over a long stretch, analyse one minute in three: enough to follow
     * later jumps while saving most of the CPU.
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
            Log.w(TAG, "could not load VAD weights: ${it.message}")
        }.getOrNull()
        if (weights == null) {
            decoderUnavailable = true
            return null
        }
        val analyzer = SpeechAnalyzer(SileroVad(weights), timeline)
        val liveAnalyzer = SpeechAnalyzer(SileroVad(weights), timeline)
        return AudioSyncDecoder(analyzer, liveAnalyzer) { mime ->
            Log.i(TAG, "look-ahead capture unavailable for $mime; syncing from playback audio instead")
            if (session != null) notify(AudioSyncStatus.LiveOnly(mime))
        }.also { decoder = it }
    }

    private fun scheduleAlignment() {
        val current = session ?: return
        if (!enabled || released) return
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
                    Log.w(TAG, "alignment failed: ${error.message}")
                } finally {
                    alignRunning.set(false)
                }
            }
        } catch (_: Exception) {
            alignRunning.set(false)
        }
    }

    private fun align(current: Session, positionMs: Long) {
        val startedAt = SystemClock.elapsedRealtime()
        val outcome = current.tracker.update(timeline, positionMs)
        if (session !== current) return
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        when (outcome) {
            is AudioSyncTracker.Outcome.Provisional -> {
                val first = model == null
                if (first) manualDelayAtLockMs = manualDelayMs()
                model = outcome.model
                Log.i(TAG, "provisional ${outcome.model} ${describe(outcome.estimate)} in ${elapsed}ms")
                if (first) {
                    notify(
                        AudioSyncStatus.Estimated(
                            offsetMs = outcome.model.delayUsAt(positionMs * 1_000L) / 1_000L,
                        ),
                    )
                }
            }
            is AudioSyncTracker.Outcome.Retracted -> {
                model = null
                Log.i(TAG, "early estimate withdrawn ${describe(outcome.estimate)}")
                notify(AudioSyncStatus.Withdrawn)
            }
            is AudioSyncTracker.Outcome.Locked -> {
                if (model == null) manualDelayAtLockMs = manualDelayMs()
                lockedAtElapsedMs = SystemClock.elapsedRealtime()
                model = outcome.model
                Log.i(TAG, "LOCKED ${outcome.model} ${describe(outcome.estimate)} in ${elapsed}ms")
                notify(
                    AudioSyncStatus.Synced(
                        offsetMs = outcome.model.delayUsAt(positionMs * 1_000L) / 1_000L,
                        rateCorrected = outcome.estimate.scale != 1.0,
                    ),
                )
            }
            is AudioSyncTracker.Outcome.Refined -> {
                model = outcome.model
                Log.i(TAG, "refined ${outcome.model} ${describe(outcome.estimate)}")
            }
            is AudioSyncTracker.Outcome.Jumped -> {
                model = outcome.model
                Log.i(TAG, "jump detected ${outcome.model} ${describe(outcome.estimate)}")
                notify(AudioSyncStatus.Adjusted(offsetMs = outcome.model.delayUsAt(positionMs * 1_000L) / 1_000L))
            }
            is AudioSyncTracker.Outcome.Searching -> Log.d(
                TAG,
                "searching ${outcome.estimate?.let(::describe) ?: "-"} known=${timeline.knownFrameCount()} in ${elapsed}ms",
            )
            else -> Unit
        }
    }

    private fun notify(status: AudioSyncStatus) {
        try {
            onStatus(status)
        } catch (_: Throwable) {
        }
    }

    private fun describe(estimate: SubtitleAudioAligner.Estimate): String =
        "scale=${"%.5f".format(estimate.scale)} shift=${estimate.shiftMs.roundToInt()}ms " +
            "peak=${"%.3f".format(estimate.peak)} prominence=${"%.3f".format(estimate.prominence)} " +
            "cues=${estimate.cueCount} speech=${estimate.speechSeconds.roundToInt()}s"

    private fun describe(format: Format): String =
        "${format.sampleMimeType} ${format.channelCount}ch ${format.sampleRate}Hz lang=${format.language} id=${format.id}"

    companion object {
        private const val TAG = "NuvioAudioSync"
        private const val MIN_TRACK_CUES = 20
        private const val ALIGN_INTERVAL_MS = 8_000L
        private const val SEARCH_INTERVAL_MS = 3_000L
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
    /** A subtitle was picked and the audio is being analysed. */
    data object Listening : AudioSyncStatus

    /** No second decoder for this codec: syncing uses the playing audio only, without look-ahead. */
    data class LiveOnly(val mimeType: String) : AudioSyncStatus

    /** The early estimate could not be confirmed; subtitles are back on the file's own timing. */
    data object Withdrawn : AudioSyncStatus

    /** An early, unconfirmed estimate was applied; it keeps adjusting until confirmed. */
    data class Estimated(val offsetMs: Long) : AudioSyncStatus

    /** Subtitles now follow the audio; [offsetMs] is the applied delay at the playhead. */
    data class Synced(val offsetMs: Long, val rateCorrected: Boolean) : AudioSyncStatus

    /** The subtitle timing changed partway through (a different cut). */
    data class Adjusted(val offsetMs: Long) : AudioSyncStatus
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
