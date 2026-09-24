package com.nuvio.app.features.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.seekpreview.LocalSeekPreviewSession
import com.nuvio.app.features.player.seekpreview.SeekPreviewSyncPanel
import com.nuvio.app.features.player.seekpreview.SeekPreviewThumbnailStrip
import com.nuvio.app.features.player.seekpreview.SeekrConfig
import com.nuvio.app.isIos

/*
 * Seek-preview (Seekr) integration points for the player. Everything the player needs lives
 * here and in the seekpreview package, so upstream player files only carry one-line calls in.
 */

/** Minimum spacing between drawn preview-cue ticks; denser than this they read as a solid bar. */
private val MinCueTickSpacing = 5.dp

/** Upper bound on tick count, so a long title cannot turn the scrubber into a solid block. */
private const val MaxCueTicks = 400L

/**
 * Wraps the player UI with seek previews: loads the Seekr track for what is playing, provides
 * it to the seek bars and controls, and draws the swipe-seek strip and Preview Sync panel.
 * Android only for now.
 */
@Composable
internal fun PlayerScreenRuntime.WithSeekPreview(content: @Composable () -> Unit) {
    if (isIos || SeekrConfig.API_KEY.isBlank()) {
        content()
        return
    }
    BindSeekPreviewEffects()
    CompositionLocalProvider(LocalSeekPreviewSession provides seekPreview) {
        Box(Modifier.fillMaxSize()) {
            content()
            RenderSeekPreviewOverlays()
        }
    }
}

/**
 * The position to show and seek to for a scrub at [positionMs]: the start of the cue whose
 * frame the preview shows, so preview and playback agree. Unchanged without a preview track.
 */
internal fun PlayerScreenRuntime.seekPreviewAligned(positionMs: Long): Long =
    seekPreview.alignedPosition(positionMs, playbackSnapshot.durationMs)

@Composable
private fun PlayerScreenRuntime.BindSeekPreviewEffects() {
    val durationSeconds = playbackSnapshot.durationMs / 1000L
    LaunchedEffect(
        parentMetaId,
        contentType,
        parentMetaType,
        activeSeasonNumber,
        activeEpisodeNumber,
        durationSeconds,
        activePlaybackIdentity,
    ) {
        seekPreview.load(
            apiKey = SeekrConfig.API_KEY,
            contentId = parentMetaId,
            contentType = contentType ?: parentMetaType,
            season = activeSeasonNumber,
            episode = activeEpisodeNumber,
            durationMs = playbackSnapshot.durationMs,
        )
    }
    // Once the preview resolves a new cue, park an in-progress scrub on the frame it shows.
    LaunchedEffect(seekPreview.previewCue) {
        val pendingMs = scrubbingPositionMs
        if (isScrubbingTimeline && pendingMs != null) {
            scrubbingPositionMs = seekPreviewAligned(pendingMs)
        }
    }
}

@Composable
private fun PlayerScreenRuntime.RenderSeekPreviewOverlays() {
    val session = seekPreview
    if (session.track == null) return
    Box(Modifier.fillMaxSize()) {
        val gesturePositionMs = session.gesturePositionMs
        SeekPreviewThumbnailStrip(
            session = session,
            positionMs = gesturePositionMs ?: playbackSnapshot.positionMs,
            durationMs = playbackSnapshot.durationMs,
            active = gesturePositionMs != null,
            followPosition = false,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = horizontalSafePadding),
        )
        if (session.showSyncPanel) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { session.showSyncPanel = false },
            ) {
                SeekPreviewSyncPanel(
                    session = session,
                    positionMs = playbackSnapshot.positionMs,
                    onDismiss = { session.showSyncPanel = false },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = horizontalSafePadding + 16.dp)
                        .padding(bottom = 24.dp),
                )
            }
        }
    }
}

/**
 * Seek-preview thumbnails above a seek bar while it is being scrubbed. Takes no height in the
 * layout — it is drawn above whatever precedes the bar — so the controls never shift.
 */
@Composable
internal fun SeekPreviewAboveTimeline(positionMs: Long, durationMs: Long, active: Boolean) {
    val session = LocalSeekPreviewSession.current ?: return
    if (session.track == null) return
    Box(
        Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                layout(placeable.width, 0) {
                    placeable.placeRelative(0, -placeable.height)
                }
            },
    ) {
        SeekPreviewThumbnailStrip(
            session = session,
            positionMs = positionMs,
            durationMs = durationMs,
            active = active,
        )
    }
}

/** Spacing between preview cues for the current title, or 0 when there are no previews. */
@Composable
internal fun seekPreviewCueIntervalMs(): Long = LocalSeekPreviewSession.current?.cueIntervalMs ?: 0L

/**
 * Draws preview-cue ticks across a seek bar track — where grid-locked scrubbing can stop —
 * when they are far enough apart to read.
 */
internal fun DrawScope.drawSeekPreviewCueTicks(cueIntervalMs: Long, durationMs: Long, top: Float, height: Float) {
    if (cueIntervalMs <= 0L || durationMs <= 0L) return
    val tickCount = durationMs / cueIntervalMs
    val stepPx = size.width * (cueIntervalMs.toFloat() / durationMs.toFloat())
    if (tickCount !in 2..MaxCueTicks || stepPx < MinCueTickSpacing.toPx()) return
    var x = stepPx
    while (x < size.width) {
        drawLine(
            color = Color.White.copy(alpha = 0.28f),
            start = Offset(x, top),
            end = Offset(x, top + height),
            strokeWidth = 1.dp.toPx(),
        )
        x += stepPx
    }
}

/** Opens Preview Sync, or null when no preview track loaded for this title. */
@Composable
internal fun seekPreviewSyncAction(): (() -> Unit)? {
    val session = LocalSeekPreviewSession.current?.takeIf { it.track != null } ?: return null
    return { session.showSyncPanel = true }
}
