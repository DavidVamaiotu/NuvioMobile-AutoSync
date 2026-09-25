package com.nuvio.app.features.player.seekpreview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_seek_preview_frame_at
import org.jetbrains.compose.resources.stringResource

private val CenterWidth = 176.dp
private val CenterHeight = 99.dp
private val NeighborWidth = 104.dp
private val NeighborHeight = 59.dp
private val FrameGap = 6.dp
private val StripWidth = CenterWidth + (NeighborWidth + FrameGap) * 2
private const val LingerAfterScrubMs = 1500L

/** Height the strip occupies above whatever it is anchored to. */
internal val SeekPreviewStripHeight = CenterHeight + 46.dp

/**
 * Distance beyond which the frame on screen is admitted to describe a different moment than
 * the scrub position. Grid-locked scrubbing normally keeps the two identical.
 */
private const val FrameLabelToleranceMs = 1_000L

/**
 * The frames rendered by [SeekPreviewThumbnailStrip]: the cue covering the scrub position plus
 * its two neighbours. [neighborsOwnerCueStartMs] pins the neighbours to the centre cue they
 * were resolved for, so a freshly published centre never shows the previous centre's strip.
 */
private data class SeekPreviewFrames(
    val center: SeekrThumbnail? = null,
    val centerCueStartMs: Long? = null,
    val previous: SeekrThumbnail? = null,
    val previousCueStartMs: Long? = null,
    val next: SeekrThumbnail? = null,
    val nextCueStartMs: Long? = null,
    val neighborsOwnerCueStartMs: Long? = null,
) {
    val neighborsMatchCenter: Boolean
        get() = centerCueStartMs != null && neighborsOwnerCueStartMs == centerCueStartMs
}

/**
 * The scrub-time preview: a three-frame strip centred on the cue the playhead sits in.
 *
 * Sprite sheets hold one frame per ~10 second cue, so a single thumbnail cannot say whether a
 * cut happens just out of shot. Showing the neighbouring cues makes the granularity visible and
 * turns scrubbing into reading a sequence. Grid-locked scrubbing (see [SeekPreviewCueStepper])
 * parks the playhead on the centre frame's own timestamp, so the label stays one honest number.
 *
 * @param positionMs the scrub position being previewed.
 * @param active true while the user is scrubbing; the strip lingers briefly after it ends.
 * @param followPosition place the strip above the scrub position along the available width
 *   (for a seek bar) rather than centring it.
 */
@Composable
internal fun SeekPreviewThumbnailStrip(
    session: SeekPreviewSession,
    positionMs: Long,
    durationMs: Long,
    active: Boolean,
    modifier: Modifier = Modifier,
    followPosition: Boolean = true,
) {
    val activeTrack = session.track

    // Prevent flicker between repeated scrub inputs.
    var lingerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            lingerVisible = true
        } else {
            delay(LingerAfterScrubMs)
            lingerVisible = false
        }
    }

    val duration = durationMs.coerceAtLeast(1L)
    val fraction = (positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    val offsetMs = session.offsetMs.toLong()
    // On-device tracks fill in while playing; a new revision means frames may have sharpened.
    val revision by (activeTrack?.revision ?: NoRevision).collectAsState()
    val localStats = (activeTrack?.localStats ?: NoLocalStats).collectAsState().value
        .takeIf { activeTrack?.localStats != null }
    var frames by remember(activeTrack) { mutableStateOf(SeekPreviewFrames()) }
    // Conflate rapid scrub/nudge changes so only the latest request triggers a lookup.
    val requestFlow = remember(activeTrack) { MutableStateFlow(Triple(positionMs, offsetMs, revision)) }
    LaunchedEffect(activeTrack, positionMs, offsetMs, revision) {
        requestFlow.value = Triple(positionMs, offsetMs, revision)
    }
    LaunchedEffect(activeTrack, lingerVisible) {
        // Only the visible strip drives the preview cue, so a hidden one can't overwrite it.
        if (activeTrack == null || !lingerVisible) return@LaunchedEffect
        // Caching the inputs to the re-centring decision below — the covering cue and which
        // side of its midpoint the position falls — replays that decision exactly, so the cache
        // expires precisely when the centre frame is due to hand over to its successor.
        var cachedCovering: SeekPreviewCue? = null
        var cachedPrefersSuccessor = false
        var cachedOffsetMs: Long? = null
        var cachedRevision: Int? = null
        requestFlow.collectLatest { (position, offset, rev) ->
            val covering = cachedCovering
            if (offset == cachedOffsetMs &&
                rev == cachedRevision &&
                covering != null &&
                covering.contains(position) &&
                covering.prefersSuccessorFor(position) == cachedPrefersSuccessor
            ) {
                return@collectLatest
            }
            // Single writer for the track's offset, pushed right before the lookup so a nudge
            // is reflected on the very next frame.
            activeTrack.offsetMs = offset
            // Only overwrite on success — keeps the last good frame visible during a fetch.
            val coveringThumbnail = activeTrack.thumbnailFor(position) ?: return@collectLatest
            // Cue times arrive on the preview timeline; undo the offset to compare with playback.
            val coveringCue = SeekPreviewCue(
                startMs = coveringThumbnail.cueStartMs - offset,
                endMs = coveringThumbnail.cueEndMs - offset,
            )

            // A cue's frame is captured at its start, so past the halfway mark the next cue's
            // frame is the closer one; centring on it keeps the strip symmetric around the playhead.
            val prefersSuccessor = coveringCue.prefersSuccessorFor(position)
            val successor = if (prefersSuccessor) {
                activeTrack.thumbnailFor(coveringCue.endMs)
                    ?.takeIf { it.cueStartMs != coveringThumbnail.cueStartMs }
            } else {
                null
            }
            cachedCovering = coveringCue
            cachedPrefersSuccessor = prefersSuccessor
            cachedOffsetMs = offset
            cachedRevision = rev

            val center = successor ?: coveringThumbnail
            val centerStartMs = center.cueStartMs - offset
            val centerEndMs = center.cueEndMs - offset
            frames = frames.copy(center = center, centerCueStartMs = centerStartMs)
            session.onPreviewCueResolved(SeekPreviewCue(centerStartMs, centerEndMs))

            // A lookup that clamps at either end resolves back to the centre cue; dropping
            // those keeps the strip from showing the same frame twice.
            val previous = if (successor != null) {
                coveringThumbnail
            } else {
                activeTrack.thumbnailFor(centerStartMs - 1)
                    ?.takeIf { it.cueStartMs != center.cueStartMs }
            }
            val next = activeTrack.thumbnailFor(centerEndMs)
                ?.takeIf { it.cueStartMs != center.cueStartMs }
            frames = frames.copy(
                previous = previous,
                previousCueStartMs = previous?.let { it.cueStartMs - offset },
                next = next,
                nextCueStartMs = next?.let { it.cueStartMs - offset },
                neighborsOwnerCueStartMs = centerStartMs,
            )
        }
    }

    AnimatedVisibility(
        visible = lingerVisible && activeTrack != null,
        enter = fadeIn(animationSpec = tween(120)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(SeekPreviewStripHeight),
        ) {
            // Below this width the strip would be clipped, so fall back to the single frame.
            val showNeighbors = maxWidth >= StripWidth
            val stripWidth = if (showNeighbors) StripWidth else CenterWidth
            val left = if (followPosition) {
                previewOffset(maxWidth, stripWidth, fraction)
            } else {
                ((maxWidth - stripWidth) / 2).coerceAtLeast(0.dp)
            }
            val frameTs = frames.centerCueStartMs
            val showFrameLabel = frameTs != null && abs(frameTs - positionMs) > FrameLabelToleranceMs

            Column(
                modifier = Modifier
                    .offset(x = left)
                    .width(stripWidth)
                    .align(Alignment.TopStart),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(FrameGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showNeighbors) {
                        NeighborFrame(
                            thumbnail = frames.previous.takeIf { frames.neighborsMatchCenter },
                            timeMs = frames.previousCueStartMs.takeIf { frames.neighborsMatchCenter },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(CenterWidth, CenterHeight)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black)
                            .border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(6.dp)),
                    ) {
                        SeekPreviewFrameImage(frames.center, Modifier.fillMaxSize())
                    }
                    if (showNeighbors) {
                        NeighborFrame(
                            thumbnail = frames.next.takeIf { frames.neighborsMatchCenter },
                            timeMs = frames.nextCueStartMs.takeIf { frames.neighborsMatchCenter },
                        )
                    }
                }
                Text(
                    text = formatScrubTime(positionMs),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = Color.White.copy(alpha = 0.95f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (localStats != null) {
                    Text(
                        text = localStats.debugLine(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                if (showFrameLabel && frameTs != null) {
                    Text(
                        text = stringResource(Res.string.player_seek_preview_frame_at, formatScrubTime(frameTs)),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

/** A dimmed context frame either side of the centre, labelled with the moment it holds. */
@Composable
private fun NeighborFrame(thumbnail: SeekrThumbnail?, timeMs: Long?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.width(NeighborWidth),
    ) {
        Box(
            modifier = Modifier
                .size(NeighborWidth, NeighborHeight)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.6f)),
        ) {
            SeekPreviewFrameImage(thumbnail, Modifier.fillMaxSize(), alpha = 0.45f)
        }
        Text(
            text = timeMs?.let(::formatScrubTime).orEmpty(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = Color.White.copy(alpha = 0.55f),
        )
    }
}

/**
 * Draws one sprite tile straight from its sheet, cropped to fill the box (ContentScale.Crop),
 * so no per-frame bitmap is ever copied out of the sheet.
 */
@Composable
internal fun SeekPreviewFrameImage(
    thumbnail: SeekrThumbnail?,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
) {
    if (thumbnail == null) return
    // A stand-in from a nearby moment is blurred so it reads as "loading", not as the frame.
    Canvas(modifier = if (thumbnail.approximate) modifier.blur(6.dp) else modifier) {
        val dstW = size.width
        val dstH = size.height
        if (dstW <= 0f || dstH <= 0f) return@Canvas
        val srcW = thumbnail.srcSize.width
        val srcH = thumbnail.srcSize.height
        val dstAspect = dstW / dstH
        val srcAspect = srcW.toFloat() / srcH.toFloat()
        var cropX = thumbnail.srcOffset.x
        var cropY = thumbnail.srcOffset.y
        var cropW = srcW
        var cropH = srcH
        if (srcAspect > dstAspect) {
            cropW = (srcH * dstAspect).roundToInt().coerceIn(1, srcW)
            cropX += (srcW - cropW) / 2
        } else if (srcAspect < dstAspect) {
            cropH = (srcW / dstAspect).roundToInt().coerceIn(1, srcH)
            cropY += (srcH - cropH) / 2
        }
        drawImage(
            image = thumbnail.sheet,
            srcOffset = IntOffset(cropX, cropY),
            srcSize = IntSize(cropW, cropH),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(dstW.roundToInt(), dstH.roundToInt()),
            alpha = alpha,
        )
    }
}

private fun previewOffset(trackWidth: Dp, thumbWidth: Dp, fraction: Float): Dp {
    val centerX = trackWidth * fraction
    val leftUnclamped = centerX - thumbWidth / 2
    val maxLeft = (trackWidth - thumbWidth).coerceAtLeast(0.dp)
    return leftUnclamped.coerceIn(0.dp, maxLeft)
}

internal fun formatScrubTime(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds / 60) % 60
    val seconds = totalSeconds % 60
    val mm = minutes.toString().padStart(2, '0')
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:$mm:$ss" else "$minutes:$ss"
}

private val NoRevision = MutableStateFlow(0)
private val NoLocalStats = MutableStateFlow(LocalSeekPreviewStats())

/** Debug readout for on-device previews: progress, data used and where frames came from. */
private fun LocalSeekPreviewStats.debugLine(): String = buildString {
    append("On device ").append(filled).append('/').append(total)
    append(" · ").append(downloadedBytes / 1_000_000L).append(" MB")
    if (fromBuffer > 0) append(" · ").append(fromBuffer).append(" buffer")
    if (fromCache > 0) append(" · ").append(fromCache).append(" cached")
    if (avgFetchMs > 0) append(" · fetch ").append(avgFetchMs).append("ms")
    if (avgDecodeMs > 0) append(" · ").append(decoder ?: "?").append(' ').append(avgDecodeMs).append("ms")
    pausedReason?.let { append(" · ").append(it) }
}
