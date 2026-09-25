package com.nuvio.app.features.player.seekpreview

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Seek-preview state for one player: the loaded Seekr track, the cue the preview is showing,
 * and the session-scoped manual sync correction. Plays the role the TV fork splits across its
 * view model and runtime controller events.
 */
@Stable
internal class SeekPreviewSession {
    var track by mutableStateOf<SeekPreviewTrack?>(null)
        private set

    /** The cue window (playback timebase) of the frame the preview is centred on. */
    var previewCue by mutableStateOf<SeekPreviewCue?>(null)
        private set

    /**
     * Manual correction pushed onto the track before every lookup. Session-scoped on purpose:
     * it describes the gap between this release and the preview source, so it is dropped
     * whenever a new track loads.
     */
    var offsetMs by mutableStateOf(0)
        private set

    /**
     * The duration gap between the playing release and the preview source, offered as a
     * starting point for the manual sync. Deliberately not applied automatically: the backend
     * anchors cues at the start, and most gaps are a different credits length around an
     * identical body, for which shifting by the gap would make every thumbnail wrong.
     */
    var suggestedOffsetMs by mutableStateOf(0L)
        private set

    var showSyncPanel by mutableStateOf(false)

    /** Position being previewed by the horizontal swipe-to-seek gesture while it is in progress. */
    var gesturePositionMs by mutableStateOf<Long?>(null)

    /** Spacing between preview cues, or 0 before any preview has resolved. */
    val cueIntervalMs: Long get() = previewCue?.durationMs ?: 0L

    private var generation = 0

    /** Loads the track for the given title; call from an effect keyed on these inputs. */
    suspend fun load(
        apiKey: String,
        contentId: String?,
        contentType: String?,
        season: Int?,
        episode: Int?,
        durationMs: Long,
    ) {
        val loadGeneration = ++generation
        // A new track describes a different release, so any sync dialled in for the previous
        // one is meaningless.
        track?.close()
        track = null
        previewCue = null
        offsetMs = 0
        suggestedOffsetMs = 0L
        showSyncPanel = false
        if (durationMs <= 0L) return
        // Thumbnails made from the playing stream always line up, so they win over Seekr.
        if (LocalSeekPreviewSettings.enabled.value) {
            val cacheKey = localSeekPreviewCacheKey(contentId, season, episode, durationMs)
            val local = openLocalSeekPreviewTrack(cacheKey, durationMs)
            if (local != null) {
                if (loadGeneration != generation) {
                    local.close()
                    return
                }
                track = local
                local.prefetch()
                return
            }
        }
        if (apiKey.isBlank()) return
        val content = seekrContentFor(contentId, contentType, season, episode) ?: return
        val loaded = SeekrClient(apiKey).loadTrack(content, durationMs) ?: return
        if (loadGeneration != generation) return
        track = loaded
        suggestedOffsetMs = if (loaded.sourceDurationMs > 0L) loaded.sourceDurationMs - durationMs else 0L
        loaded.prefetch()
    }

    fun clear() {
        generation++
        track?.close()
        track = null
        previewCue = null
        offsetMs = 0
        suggestedOffsetMs = 0L
        showSyncPanel = false
    }

    /** Sets the manual correction; the cached cue was converted with the old offset, so it is dropped. */
    fun setOffset(targetMs: Int) {
        val clamped = clampSeekPreviewOffset(targetMs.toLong())
        if (offsetMs == clamped) return
        offsetMs = clamped
        previewCue = null
    }

    fun adjustOffset(deltaMs: Int) = setOffset(offsetMs + deltaMs)

    fun onPreviewCueResolved(cue: SeekPreviewCue?) {
        previewCue = cue
    }

    /**
     * Grid-locked scrubbing for touch: the position to show and seek to for a scrub at
     * [positionMs] — the start of the cue whose frame the preview is showing, so the preview
     * and the frame playback lands on always agree. Unchanged when no track is loaded or no
     * cue describes the position yet (see [SeekPreviewCueStepper.alignedTargetMs]).
     */
    fun alignedPosition(positionMs: Long, durationMs: Long): Long {
        if (track == null) return positionMs
        return SeekPreviewCueStepper.alignedTargetMs(
            cue = previewCue,
            pendingMs = positionMs,
            durationMs = if (durationMs > 0L) durationMs else Long.MAX_VALUE,
        ) ?: positionMs
    }
}

internal val LocalSeekPreviewSession = staticCompositionLocalOf<SeekPreviewSession?> { null }
