package com.nuvio.app.features.autosync.bubble

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/** Protected (DRM) video can't be copied; stop trying after this many failures in a row. */
private const val MAX_FAILURES = 8

/**
 * Copies the patch of video behind the bubble from the player's SurfaceView (ExoPlayer and mpv
 * both draw into one) with PixelCopy, shrunk so it is already soft. Only runs while the bubble is
 * on screen, and needs nothing from the player: it finds the video surface in the view tree.
 */
@Composable
internal fun rememberVideoBackdrop(bounds: BubbleWindowBounds, marginPx: Float): State<BubbleBackdropFrame?> {
    val view = LocalView.current
    val frame = remember { mutableStateOf<BubbleBackdropFrame?>(null) }
    LaunchedEffect(view) {
        val handler = Handler(Looper.getMainLooper())
        // Android 12+ blurs the copy on the GPU, so it can be sharper; older versions rely on
        // the shrink alone for the softness.
        val shrink = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 3 else 5
        val buffers = arrayOfNulls<Bitmap>(2)
        var next = 0
        var failures = 0
        var surface: SurfaceView? = null
        var lookups = 0
        val location = IntArray(2)
        while (isActive && failures < MAX_FAILURES) {
            delay(bounds.sampleIntervalMs)
            if (surface == null || !surface.isAttachedToWindow || lookups++ % 32 == 0) {
                surface = findVideoSurface(view.rootView)
            }
            val target = surface ?: continue
            val holderSurface = target.holder.surface
            if (holderSurface == null || !holderSurface.isValid || target.width <= 0) continue
            val rect = bounds.rect ?: continue
            target.getLocationInWindow(location)
            val left = max(0, (rect.left - marginPx).roundToInt() - location[0])
            val top = max(0, (rect.top - marginPx).roundToInt() - location[1])
            val right = minOf(target.width, (rect.right + marginPx).roundToInt() - location[0])
            val bottom = minOf(target.height, (rect.bottom + marginPx).roundToInt() - location[1])
            if (right - left < shrink || bottom - top < shrink) continue
            val width = (right - left) / shrink
            val height = (bottom - top) / shrink
            // Two buffers: one on screen while the next copy lands in the other.
            val buffer = buffers[next]?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { buffers[next] = it }
            val result = suspendCancellableCoroutine { continuation ->
                try {
                    PixelCopy.request(
                        target,
                        android.graphics.Rect(left, top, right, bottom),
                        buffer,
                        { code -> if (continuation.isActive) continuation.resume(code) },
                        handler,
                    )
                } catch (error: IllegalArgumentException) {
                    continuation.resume(PixelCopy.ERROR_SOURCE_INVALID)
                }
            }
            if (result == PixelCopy.SUCCESS) {
                failures = 0
                frame.value = BubbleBackdropFrame(
                    image = buffer.asImageBitmap(),
                    windowRect = Rect(
                        left = (left + location[0]).toFloat(),
                        top = (top + location[1]).toFloat(),
                        right = (right + location[0]).toFloat(),
                        bottom = (bottom + location[1]).toFloat(),
                    ),
                )
                next = 1 - next
            } else {
                failures++
            }
        }
        frame.value = null
    }
    return frame
}

/** The largest visible SurfaceView in the window: the video. */
private fun findVideoSurface(root: View): SurfaceView? {
    var best: SurfaceView? = null
    var bestArea = 0L
    fun visit(view: View) {
        if (view.visibility != View.VISIBLE) return
        if (view is SurfaceView) {
            val area = view.width.toLong() * view.height
            if (area > bestArea && view.isShown) {
                best = view
                bestArea = area
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
    }
    visit(root)
    return best
}
