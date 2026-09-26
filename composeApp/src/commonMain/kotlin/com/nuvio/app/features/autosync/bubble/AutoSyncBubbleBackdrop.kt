package com.nuvio.app.features.autosync.bubble

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap

/** A small, soft copy of the video behind the bubble, and where it was taken from (window px). */
internal class BubbleBackdropFrame(val image: ImageBitmap, val windowRect: Rect)

/** Where the bubble sits in the window, in px, before its animations move it. */
internal class BubbleWindowBounds {
    @Volatile
    var rect: Rect? = null

    /** How often to copy the video: often while words show, rarely once only the droplet is left. */
    @Volatile
    var sampleIntervalMs: Long = 60L
}

/**
 * The platform's way to see the video behind the bubble, so the glass can blur and bend it.
 * Installed on Android; without it the bubble draws its plain frosted glass.
 */
internal object AutoSyncBubbleBackdrop {
    var sampler: (@Composable (bounds: BubbleWindowBounds, marginPx: Float) -> State<BubbleBackdropFrame?>)? = null
}
