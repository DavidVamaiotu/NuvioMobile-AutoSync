@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.audiosync

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.CuesWithTiming
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.AudioSyncSettings
import com.nuvio.app.features.player.SidecarSubtitleController
import com.nuvio.app.features.player.parseSidecarTimedCuesRobust
import com.nuvio.app.features.player.audiosync.asr.AsrModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_audio_sync_adjusted
import nuvio.composeapp.generated.resources.player_audio_sync_estimated
import nuvio.composeapp.generated.resources.player_audio_sync_listening
import nuvio.composeapp.generated.resources.player_audio_sync_live_only
import nuvio.composeapp.generated.resources.player_audio_sync_model_downloading
import nuvio.composeapp.generated.resources.player_audio_sync_model_needs_wifi
import nuvio.composeapp.generated.resources.player_audio_sync_synced
import nuvio.composeapp.generated.resources.player_audio_sync_synced_rate
import nuvio.composeapp.generated.resources.player_audio_sync_withdrawn
import org.jetbrains.compose.resources.getString
import java.util.concurrent.atomic.AtomicReference

/**
 * Audio subtitle sync as AutoSync's fallback, for one stream of one player.
 *
 * AutoSync decides first. While it analyses a subtitle the audio is already being listened to
 * ([arm]); when it keeps the subtitle's original timing (no embedded subtitles, no usable
 * reference, or a weak match) the audio takes over ([takeOver]); otherwise nothing more is done
 * ([disarm]). The mapping found is applied by retiming the subtitle AutoSync's sidecar shows, the
 * same way AutoSync applies its own result, so Nuvio's subtitle rendering is untouched.
 */
internal class AudioSyncFallback(
    context: Context,
    private val scope: CoroutineScope,
    private val player: ExoPlayer,
    private val sidecar: SidecarSubtitleController,
    private val sourceUrl: String,
    sourceAudioUrl: String?,
    dataSourceFactory: DataSource.Factory?,
    private val getSubtitleHeaders: (String) -> Map<String, String>,
    /** The fallback now shows [url] instead of the subtitle it started with (it fits the audio). */
    private val onSubtitleReplaced: (url: String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val controller = AudioSubtitleSyncController(
        context = appContext,
        // Subtitles are retimed in place, so the user's delay simply adds on top.
        manualDelayMs = { 0 },
        onStatus = ::toast,
        requestSwitch = ::replaceSubtitle,
    )

    /** The subtitle being synced, with its original cues and the sidecar attachment it lives in. */
    private class Target(val url: String, val cues: List<CuesWithTiming>, val generation: Long)

    private val target = AtomicReference<Target?>(null)

    // Handed to the engine only once AutoSync starts on a subtitle, so a stream AutoSync syncs on
    // its own never loads the speech model or fetches references for the fallback.
    private var candidates: List<AudioSubtitleSyncController.ReferenceCandidate> = emptyList()
    private var content: Pair<String, String>? = null
    private var armed = false
    private var appliedModel: SubtitleSyncModel? = null
    private var ticker: Job? = null
    private var takeOverJob: Job? = null

    private val trackListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            controller.onAudioTrackSelected(tracks.selectedAudioFormat())
        }
    }

    init {
        controller.listensBeforeSession = false
        controller.enabled = AudioSyncSettings.fallbackEnabled.value
        controller.samplingOnMobileData = AudioSyncSettings.samplingOnMobileData.value
        controller.onSourceChanged(sourceUrl)
        val audioUrl = sourceAudioUrl?.takeIf { it.isNotBlank() } ?: sourceUrl
        if (dataSourceFactory != null) {
            controller.setSpotSource(
                forSourceKey = sourceUrl,
                url = audioUrl,
                dataSourceFactory = dataSourceFactory,
                extractorsFactory = DefaultExtractorsFactory(),
                localEngine = isLoopback(audioUrl),
            )
        }
        player.addListener(trackListener)
        controller.onAudioTrackSelected(player.currentTracks.selectedAudioFormat())
        AudioSyncTaps.attach(controller)
    }

    /** Subtitles offered for this title: same-language alternatives and English references. */
    fun setCandidates(candidates: List<Triple<String, String, String?>>) {
        this.candidates = candidates.map { (url, language, name) ->
            AudioSubtitleSyncController.ReferenceCandidate(
                url = url,
                language = language,
                headers = getSubtitleHeaders(url),
                label = name.orEmpty(),
            )
        }
        if (armed) controller.setReferenceSubtitles(this.candidates)
    }

    /** The title being played, to look up English references beyond the addons' own. */
    fun setContent(type: String, videoId: String) {
        content = type to videoId
        if (armed) controller.setContent(type, videoId)
    }

    /** AutoSync started analysing a subtitle: listen meanwhile, so a takeover starts with audio. */
    fun arm() {
        stop()
        if (!AudioSyncSettings.fallbackEnabled.value) return
        controller.enabled = true
        controller.samplingOnMobileData = AudioSyncSettings.samplingOnMobileData.value
        controller.listensBeforeSession = true
        if (!armed) {
            armed = true
            content?.let { (type, videoId) -> controller.setContent(type, videoId) }
            controller.setReferenceSubtitles(candidates)
        }
        startTicker()
    }

    /** AutoSync applied its own result: nothing is left for the audio to do. */
    fun disarm() {
        stop()
    }

    /**
     * AutoSync kept [url]'s original timing, shown by the sidecar: sync it to the audio instead.
     * Waits briefly for the sidecar's cues, as AutoSync does before applying its own result.
     */
    fun takeOver(url: String) {
        if (!AudioSyncSettings.fallbackEnabled.value || !controller.enabled) return disarm()
        takeOverJob?.cancel()
        takeOverJob = scope.launch {
            var waitedMs = 0L
            while (sidecar.activeSidecarSubtitleKey == url && sidecar.sidecarTimedCues.isEmpty() && waitedMs < CUES_WAIT_MS) {
                delay(CUES_POLL_MS)
                waitedMs += CUES_POLL_MS
            }
            val generation = sidecar.currentGenerationFor(url)
            val cues = sidecar.sidecarTimedCues
            if (generation == null || cues.isEmpty()) return@launch disarm()
            target.set(Target(url, cues, generation))
            appliedModel = null
            startTicker()
            SyncLog.i("AutoSync kept the original timing of $url; syncing it to the audio")
            controller.startSession(url, cues)
        }
    }

    /** The user moved to another subtitle or track, or AutoSync starts over. */
    fun stop() {
        takeOverJob?.cancel()
        takeOverJob = null
        ticker?.cancel()
        ticker = null
        target.set(null)
        appliedModel = null
        controller.listensBeforeSession = false
        controller.stopSession()
    }

    fun release() {
        stop()
        player.removeListener(trackListener)
        AudioSyncTaps.detach(controller)
        controller.release()
    }

    /** Feeds the playhead and applies each new mapping to the sidecar, on the main thread. */
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                controller.onPlaybackPosition(player.currentPosition, durationMs)
                val current = target.get()
                val model = controller.currentModel()
                if (current != null && model !== appliedModel) {
                    appliedModel = model
                    apply(current, model)
                }
                delay(TICK_MS)
            }
        }
    }

    private suspend fun apply(current: Target, model: SubtitleSyncModel?) {
        val retimed = withContext(Dispatchers.Default) { retime(current.cues, model) }
        if (target.get() !== current) return
        val committed = sidecar.commitPreparedSidecarSubtitle(
            expectedCurrentUrl = current.url,
            newUrl = current.url,
            cues = retimed,
            expectedGeneration = current.generation,
        )
        // The sidecar moved on (another subtitle, or it stopped): so does the fallback.
        if (!committed) stop()
    }

    /** Automatic switch to another subtitle that fits the audio, as AutoSync replaces a subtitle. */
    private fun replaceSubtitle(url: String) {
        scope.launch {
            val current = target.get() ?: return@launch
            val cues = withContext(Dispatchers.IO) {
                runCatching {
                    parseSidecarTimedCuesRobust(httpGetTextWithHeaders(url = url, headers = getSubtitleHeaders(url)), url).cues
                }.getOrDefault(emptyList())
            }
            if (cues.isEmpty() || target.get() !== current) return@launch
            val committed = sidecar.commitPreparedSidecarSubtitle(
                expectedCurrentUrl = current.url,
                newUrl = url,
                cues = cues,
                expectedGeneration = current.generation,
            )
            val generation = sidecar.currentGenerationFor(url)
            if (!committed || generation == null) return@launch
            target.set(Target(url, cues, generation))
            appliedModel = null
            onSubtitleReplaced(url)
            // The new session adopts the mapping the switch was decided with.
            controller.startSession(url, cues)
        }
    }

    private fun toast(status: AudioSyncStatus) {
        scope.launch {
            val message = status.message() ?: return@launch
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TICK_MS = 250L
        private const val CUES_WAIT_MS = 5_000L
        private const val CUES_POLL_MS = 50L

        /** Registers settings persistence, the speech model and the log. Call once at app start. */
        fun initialize(context: Context) {
            val preferences = context.getSharedPreferences("nuvio_audio_sync_settings", Context.MODE_PRIVATE)
            AudioSyncSettings.installPersistence(
                load = { key -> if (preferences.contains(key)) preferences.getBoolean(key, false) else null },
                save = { key, value -> preferences.edit().putBoolean(key, value).apply() },
            )
            AsrModel.initialize(context)
            SyncLog.initialize(context)
        }

        private fun isLoopback(url: String): Boolean = runCatching {
            val host = Uri.parse(url).host.orEmpty().lowercase()
            host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
        }.getOrDefault(false)

        /**
         * [cues] placed on the media timeline by [model] (subtitle time -> media time); the
         * original cues when there is no mapping. Lines of a scene the release cuts are dropped,
         * as a subtitle delay would never show them either.
         */
        internal fun retime(cues: List<CuesWithTiming>, model: SubtitleSyncModel?): List<CuesWithTiming> {
            if (model == null) return cues
            return cues.mapNotNull { entry ->
                if (entry.startTimeUs == C.TIME_UNSET) return@mapNotNull entry
                val startUs = model.mediaTimeUs(entry.startTimeUs) ?: return@mapNotNull null
                val durationUs = when {
                    entry.durationUs != C.TIME_UNSET -> entry.durationUs
                    entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs - entry.startTimeUs
                    else -> C.TIME_UNSET
                }
                val scaledUs = if (durationUs == C.TIME_UNSET) durationUs else (durationUs * model.segmentAt(startUs / 1_000L).scale).toLong()
                CuesWithTiming(entry.cues, startUs, scaledUs)
            }.sortedBy { it.startTimeUs }
        }
    }
}

/**
 * Routes audio the player demuxes or plays to the fallback of the stream being played. The
 * player's extractors and audio output are created before the fallback, so they look it up here.
 */
internal object AudioSyncTaps {
    @Volatile
    private var active: AudioSubtitleSyncController? = null

    fun attach(controller: AudioSubtitleSyncController) {
        active = controller
    }

    fun detach(controller: AudioSubtitleSyncController) {
        if (active === controller) active = null
    }

    /** Copies the audio of [sourceKey]'s stream while it is demuxed (look-ahead). */
    fun wrapExtractors(factory: ExtractorsFactory, sourceKey: String): ExtractorsFactory =
        AudioSyncExtractorsFactory(factory, object : AudioSampleSink {
            private fun current() = active?.takeIf { it.currentSourceKey == sourceKey }

            override fun wantsSamples(format: Format): Boolean = current()?.wantsSamples(format) == true

            override fun onSample(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
                current()?.onSample(format, timeUs, data, offset, size)
            }

            override fun onDiscontinuity() {
                current()?.onDiscontinuity()
            }
        })

    /** Hears the player's own decoded audio where the look-ahead copy can't be decoded. */
    fun wrapAudioSink(sink: AudioSink): AudioSink = PlaybackAudioTap(sink, object : PlaybackPcmListener {
        override fun wantsPlaybackPcm(mediaTimeUs: Long, durationUs: Long): Boolean =
            active?.wantsPlaybackPcm(mediaTimeUs, durationUs) == true

        override fun onPlaybackPcm(mono: FloatArray, frames: Int, sampleRate: Int, mediaTimeUs: Long) {
            active?.onPlaybackPcm(mono, frames, sampleRate, mediaTimeUs)
        }
    })
}

private suspend fun AudioSyncStatus.message(): String? = when (this) {
    AudioSyncStatus.Listening -> getString(Res.string.player_audio_sync_listening)
    AudioSyncStatus.Withdrawn -> getString(Res.string.player_audio_sync_withdrawn)
    is AudioSyncStatus.ModelDownloading -> getString(Res.string.player_audio_sync_model_downloading, megabytes)
    is AudioSyncStatus.ModelNeedsWifi -> getString(Res.string.player_audio_sync_model_needs_wifi, megabytes)
    is AudioSyncStatus.LiveOnly -> getString(
        Res.string.player_audio_sync_live_only,
        mimeType.substringAfter('/').uppercase(),
    )
    is AudioSyncStatus.Estimated -> getString(Res.string.player_audio_sync_estimated, formatOffset(offsetMs))
    is AudioSyncStatus.Synced -> getString(
        if (rateCorrected) Res.string.player_audio_sync_synced_rate else Res.string.player_audio_sync_synced,
        formatOffset(offsetMs),
    )
    is AudioSyncStatus.Adjusted -> getString(Res.string.player_audio_sync_adjusted, formatOffset(offsetMs))
}

private fun formatOffset(offsetMs: Long): String {
    val sign = if (offsetMs < 0) "-" else "+"
    val tenths = (kotlin.math.abs(offsetMs) + 50) / 100
    return "$sign${tenths / 10}.${tenths % 10}s"
}
