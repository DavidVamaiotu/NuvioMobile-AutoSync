@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.autosync

import android.content.Context
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.text.CuesWithTiming
import com.nuvio.app.features.autosync.audio.AudioSubtitleSync
import com.nuvio.app.features.autosync.audio.AudioSyncStatus
import com.nuvio.app.features.autosync.audio.SubtitleSyncModel
import com.nuvio.app.features.autosync.audio.retimeCues
import com.nuvio.app.features.player.SidecarSubtitleController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

private const val SIDECAR_CUES_WAIT_MS = 15_000L
private const val SIDECAR_CUES_POLL_MS = 25L
private const val PLAYBACK_TICK_MS = 250L

/**
 * AutoSync's fallback for streams without an embedded subtitle to sync against: syncs the
 * attached sidecar subtitle to the stream's speech ([AudioSubtitleSync]) and commits each result
 * as retimed cues, the same way AutoSync applies its own matches. Runs until [stop], or until the
 * sidecar moves to another subtitle.
 */
internal class AutoSyncAudioFallback(
    private val context: Context,
    private val scope: CoroutineScope,
    private val player: ExoPlayer,
    private val sidecar: SidecarSubtitleController,
    private val sourceUrl: String,
    private val sourceHeaders: Map<String, String>,
    /** The first result was applied to [subtitleUrl]; its timing now comes from the audio. */
    private val onApplied: (subtitleUrl: String) -> Unit,
) {
    private var job: Job? = null

    /**
     * Starts syncing [url] to the audio when AutoSync found no embedded subtitle to use as a
     * reference. Returns false (and does nothing) otherwise.
     */
    fun startIfNoEmbeddedReference(outcome: AutoSyncAnalysisOutcome?, url: String): Boolean {
        if (
            outcome != AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS &&
            outcome != AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE
        ) {
            return false
        }
        val generation = sidecar.currentGenerationFor(url) ?: return false
        stop()
        job = scope.launch { run(url, generation) }
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run(url: String, generation: Long) {
        fun stillAttached() = sidecar.currentGenerationFor(url) == generation
        val original = withTimeoutOrNull(SIDECAR_CUES_WAIT_MS) {
            while (stillAttached() && sidecar.sidecarTimedCues.isEmpty()) delay(SIDECAR_CUES_POLL_MS)
            sidecar.sidecarTimedCues.takeIf { stillAttached() && it.isNotEmpty() }
        } ?: return

        val owner = currentCoroutineContext().job
        val models = MutableStateFlow<SubtitleSyncModel?>(null)
        val sync = AudioSubtitleSync(
            context = context,
            sourceKey = sourceUrl,
            sourceHeaders = sourceHeaders,
            onModel = { models.value = it },
            onStatus = { status -> scope.launch { if (owner.isActive) showToast(status) } },
        )
        var applied = false
        val commits = scope.launch {
            models.drop(1).collect { model ->
                val cues = retimed(original, model)
                if (!sidecar.commitPreparedSidecarSubtitle(url, url, cues, generation)) return@collect
                if (model != null && !applied) {
                    applied = true
                    onApplied(url)
                }
            }
        }
        try {
            sync.start(url, original)
            while (stillAttached()) {
                sync.onPlayback(
                    positionMs = player.currentPosition,
                    durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
                    tracks = player.currentTracks,
                )
                delay(PLAYBACK_TICK_MS)
            }
        } finally {
            commits.cancel()
            sync.release()
        }
    }

    private suspend fun retimed(
        original: List<CuesWithTiming>,
        model: SubtitleSyncModel?,
    ): List<CuesWithTiming> =
        if (model == null) original else withContext(Dispatchers.Default) { retimeCues(original, model) }

    private fun showToast(status: AudioSyncStatus) {
        val message = when (status) {
            AudioSyncStatus.Listening -> "Auto Sync • No embedded subtitles • listening to the audio…"
            AudioSyncStatus.Unavailable -> "Auto Sync • Could not sync to the audio • original timing kept"
            AudioSyncStatus.Withdrawn -> "Auto Sync • Audio estimate withdrawn • original timing kept"
            is AudioSyncStatus.Estimated -> "Auto Sync • Audio estimate ${formatOffset(status.offsetMs)}"
            is AudioSyncStatus.Synced ->
                "Auto Sync • Synced to audio ${formatOffset(status.offsetMs)}" +
                    if (status.rateCorrected) " • drift corrected" else ""
            is AudioSyncStatus.Adjusted -> "Auto Sync • Audio sync adjusted ${formatOffset(status.offsetMs)}"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun formatOffset(offsetMs: Long): String {
        val sign = if (offsetMs < 0) "-" else "+"
        val tenths = (abs(offsetMs) + 50) / 100
        return "$sign${tenths / 10}.${tenths % 10} s"
    }
}
