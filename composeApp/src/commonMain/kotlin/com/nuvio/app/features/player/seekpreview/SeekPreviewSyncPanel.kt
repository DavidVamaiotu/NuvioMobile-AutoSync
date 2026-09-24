package com.nuvio.app.features.player.seekpreview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_done
import nuvio.composeapp.generated.resources.player_seek_preview_sync
import nuvio.composeapp.generated.resources.player_seek_preview_sync_hint
import nuvio.composeapp.generated.resources.player_seek_preview_sync_reset
import nuvio.composeapp.generated.resources.player_seek_preview_sync_suggested
import nuvio.composeapp.generated.resources.player_seek_preview_sync_use_suggested
import org.jetbrains.compose.resources.stringResource

private val SyncThumbnailWidth = 208.dp
private val SyncThumbnailHeight = 117.dp

/**
 * Manual seek-preview sync control.
 *
 * Sprite sheets are generated once per release, but the user may be playing a different
 * release. The backend anchors cue times at the start and never shifts them, so when the gap
 * *is* at the head the only thing that can resolve it is the user looking at the picture:
 * nudge until the thumbnail matches the frame playing behind this panel.
 */
@Composable
internal fun SeekPreviewSyncPanel(
    session: SeekPreviewSession,
    positionMs: Long,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offsetMs = session.offsetMs
    val suggestedOffsetMs = session.suggestedOffsetMs
    val fraction = ((offsetMs - SEEK_PREVIEW_OFFSET_MIN_MS).toFloat() /
        (SEEK_PREVIEW_OFFSET_MAX_MS - SEEK_PREVIEW_OFFSET_MIN_MS).toFloat()).coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .widthIn(max = 620.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xCC0F0F0F))
            // Swallow taps so they don't reach the player surface behind the panel.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.player_seek_preview_sync),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = formatSeekPreviewOffset(offsetMs),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.95f),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SyncPreviewImage(session = session, positionMs = positionMs, offsetMs = offsetMs)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(Res.string.player_seek_preview_sync_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                )
                if (suggestedOffsetMs != 0L) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = stringResource(
                                Res.string.player_seek_preview_sync_suggested,
                                formatSeekPreviewOffset(clampSeekPreviewOffset(suggestedOffsetMs)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF9BE2AF),
                        )
                    }
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp),
        ) {
            val thumbWidth = 22.dp
            val thumbOffset = (maxWidth - thumbWidth) * fraction
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .align(Alignment.CenterStart)
                    .background(Color.White.copy(alpha = 0.15f)),
            )
            Box(
                modifier = Modifier
                    .padding(start = thumbOffset)
                    .size(width = thumbWidth, height = 12.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .align(Alignment.CenterStart)
                    .background(Color(0xFF4AA3FF)),
            )
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NudgeButton("−2s") { session.adjustOffset(-SEEK_PREVIEW_OFFSET_COARSE_STEP_MS) }
                NudgeButton("−0.25s") { session.adjustOffset(-SEEK_PREVIEW_OFFSET_STEP_MS) }
                NudgeButton(stringResource(Res.string.player_seek_preview_sync_reset)) { session.setOffset(0) }
                NudgeButton("+0.25s") { session.adjustOffset(SEEK_PREVIEW_OFFSET_STEP_MS) }
                NudgeButton("+2s") { session.adjustOffset(SEEK_PREVIEW_OFFSET_COARSE_STEP_MS) }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (suggestedOffsetMs != 0L) {
                TextButton(onClick = { session.setOffset(clampSeekPreviewOffset(suggestedOffsetMs)) }) {
                    Text(stringResource(Res.string.player_seek_preview_sync_use_suggested), color = Color(0xFF9BE2AF))
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_done), color = Color.White)
            }
        }
    }
}

@Composable
private fun NudgeButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

@Composable
private fun SyncPreviewImage(
    session: SeekPreviewSession,
    positionMs: Long,
    offsetMs: Int,
) {
    val track = session.track
    var thumbnail by remember(track) { mutableStateOf<SeekrThumbnail?>(null) }
    val requestFlow = remember(track) { MutableStateFlow(positionMs to offsetMs) }
    LaunchedEffect(track, positionMs, offsetMs) {
        requestFlow.value = positionMs to offsetMs
    }
    LaunchedEffect(track) {
        requestFlow.collectLatest { (position, offset) ->
            val active = track ?: return@collectLatest
            active.offsetMs = offset.toLong()
            active.thumbnailFor(position)?.let { thumbnail = it }
        }
    }

    Box(
        modifier = Modifier
            .size(SyncThumbnailWidth, SyncThumbnailHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(8.dp)),
    ) {
        SeekPreviewFrameImage(thumbnail, Modifier.size(SyncThumbnailWidth, SyncThumbnailHeight))
    }
}
