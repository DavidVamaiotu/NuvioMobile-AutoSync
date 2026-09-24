package com.nuvio.app.features.player.seekpreview

import kotlin.math.abs

/**
 * Bounds for the manual seek-preview sync correction.
 *
 * The backend only serves a sprite version whose duration is within 240s of the playing
 * release, so the true preview/playback gap can never exceed that.
 */
internal const val SEEK_PREVIEW_OFFSET_MIN_MS = -240_000
internal const val SEEK_PREVIEW_OFFSET_MAX_MS = 240_000

/** Fine step: one tap of the small nudge buttons. */
internal const val SEEK_PREVIEW_OFFSET_STEP_MS = 250

/** Coarse step, so ±4 min stays reachable without hundreds of taps. */
internal const val SEEK_PREVIEW_OFFSET_COARSE_STEP_MS = 2_000

internal fun clampSeekPreviewOffset(offsetMs: Long): Int =
    offsetMs.coerceIn(SEEK_PREVIEW_OFFSET_MIN_MS.toLong(), SEEK_PREVIEW_OFFSET_MAX_MS.toLong()).toInt()

internal fun formatSeekPreviewOffset(offsetMs: Int): String {
    val sign = if (offsetMs >= 0) "+" else "-"
    val absMs = abs(offsetMs)
    return "$sign${absMs / 1000}.${(absMs % 1000).toString().padStart(3, '0')}s"
}
