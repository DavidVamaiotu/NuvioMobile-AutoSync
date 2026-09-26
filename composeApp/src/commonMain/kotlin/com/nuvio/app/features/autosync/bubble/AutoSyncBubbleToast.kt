package com.nuvio.app.features.autosync.bubble

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val SyncedGreen = Color(0xFF30D158)
private val FailedRed = Color(0xFFFF453A)
private val GlassBody = Color(0xFF1C1C1E)
private val BubbleCorner = 23.dp
private val OrbSize = 30.dp
private const val TWO_PI = (2.0 * PI).toFloat()

/** How long the working bubble keeps its words before settling to just the droplet. */
private const val WORKING_LABEL_MS = 7_000L
/** A run that never reports back fades away after this. */
private const val WORKING_TIMEOUT_MS = 240_000L
private const val SUCCESS_HOLD_MS = 1_600L
private const val FAILURE_HOLD_MS = 7_000L

/**
 * AutoSync's glass bubble, at the bottom centre of the player. Draws nothing, and runs no
 * animation, unless the setting is on and AutoSync has something to say. It only takes touches
 * while its failure card is open, so it never gets in the way of the player's own gestures.
 */
@Composable
internal fun BoxScope.AutoSyncBubbleToastHost(controlsVisible: Boolean) {
    DisposableEffect(Unit) {
        AutoSyncBubbleToasts.attachHost()
        onDispose { AutoSyncBubbleToasts.detachHost() }
    }
    val enabled by AutoSyncBubbleToasts.enabled.collectAsState()
    val message by AutoSyncBubbleToasts.current.collectAsState()
    val current = message
    if (!enabled || current == null) return
    // Rise above the player's seek bar and buttons while the controls are showing.
    val lift = animateDpAsState(
        targetValue = if (controlsVisible) 128.dp else 20.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
    )
    key(current.session) {
        AutoSyncBubble(
            message = current,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, -lift.value.roundToPx()) },
        )
    }
}

@Composable
private fun AutoSyncBubble(message: AutoSyncBubbleMessage, modifier: Modifier) {
    val kind = message.kind
    val kindState = rememberUpdatedState(kind)
    val scope = rememberCoroutineScope()

    val appear = remember { Animatable(0f) }
    val settle = remember { Animatable(0f) } // 0 = flowing liquid, 1 = still glass
    val mark = remember { Animatable(0f) } // the check or the X draws itself
    val shake = remember { Animatable(0f) } // one small wobble on failure
    val melt = remember { Animatable(0f) } // success: the bubble draws itself in and fades
    val leave = remember { Animatable(0f) } // failure or timeout: sinks away
    val clock = remember { mutableFloatStateOf(0f) }
    val bubblePath = remember { Path() }
    val dropPath = remember { Path() }
    var labelVisible by remember { mutableStateOf(true) }
    var cardOpen by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val tint = animateColorAsState(targetValue = kind.color(), animationSpec = tween(480))
    val tinted = animateFloatAsState(
        targetValue = if (kind == AutoSyncBubbleKind.Working) 0f else 1f,
        animationSpec = tween(480),
    )

    fun dismiss() {
        if (leaving) return
        leaving = true
        scope.launch {
            cardOpen = false
            labelVisible = false
            delay(240)
            leave.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
            AutoSyncBubbleToasts.finished(message.id)
        }
    }

    LaunchedEffect(Unit) {
        appear.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 300f))
    }
    // The liquid clock: only ticks while the bubble is flowing, then stops for good.
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (kindState.value == AutoSyncBubbleKind.Working || settle.value < 1f) {
            withFrameNanos { clock.floatValue = (it - start) / 1_000_000_000f }
        }
    }
    LaunchedEffect(message.id) {
        if (kind != AutoSyncBubbleKind.Working) return@LaunchedEffect
        labelVisible = true
        delay(WORKING_LABEL_MS)
        labelVisible = false
        delay(WORKING_TIMEOUT_MS - WORKING_LABEL_MS)
        dismiss()
    }
    LaunchedEffect(kind) {
        when (kind) {
            AutoSyncBubbleKind.Working -> Unit
            AutoSyncBubbleKind.Success -> {
                labelVisible = true
                settle.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 180f))
                mark.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
                delay(SUCCESS_HOLD_MS)
                labelVisible = false
                delay(380)
                leaving = true
                melt.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
                AutoSyncBubbleToasts.finished(message.id)
            }
            AutoSyncBubbleKind.Failure -> {
                labelVisible = true
                settle.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 180f))
                launch { shake.animateTo(1f, tween(560)) }
                mark.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
                delay(160)
                cardOpen = true
                delay(FAILURE_HOLD_MS)
                dismiss()
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .graphicsLayer {
                val a = appear.value
                val l = leave.value
                val m = melt.value
                val liquid = 1f - settle.value
                val t = clock.floatValue
                // Melting away: a touch wider and shorter as it draws in, like a drop settling.
                val squash = 0.10f * sin(m * PI.toFloat())
                val scale = (0.72f + 0.28f * a) * (1f - 0.25f * l) * (1f - 0.5f * m)
                scaleX = scale * (1f + squash)
                scaleY = scale * (1f - squash)
                alpha = a.coerceIn(0f, 1f) * (1f - l) * (1f - m)
                // While it works, the whole bubble drifts a little, as if floating.
                val wobble = sin(shake.value * PI.toFloat() * 4f) * (1f - shake.value) * 4.dp.toPx()
                translationX = sin(t * 1.1f) * 2.dp.toPx() * liquid + wobble
                translationY = (1f - a) * 20.dp.toPx() + (l + m * 0.5f) * 10.dp.toPx() +
                    sin(t * 1.6f + 0.8f) * 1.5.dp.toPx() * liquid
                transformOrigin = TransformOrigin(0.5f, 0.6f)
            }
            .then(
                if (cardOpen) {
                    Modifier.pointerInput(Unit) { detectTapGestures { dismiss() } }
                } else {
                    Modifier
                },
            )
            .drawBehind {
                drawLiquidGlass(
                    path = bubblePath,
                    time = clock.floatValue,
                    liquid = 1f - settle.value,
                    tint = tint.value,
                    tinted = tinted.value,
                )
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Canvas(Modifier.size(OrbSize)) {
            drawDroplet(
                path = dropPath,
                kind = kindState.value,
                time = clock.floatValue,
                liquid = 1f - settle.value,
                tint = tint.value,
                tinted = tinted.value,
                mark = mark.value,
            )
        }
        AnimatedVisibility(
            visible = labelVisible,
            enter = fadeIn(tween(240, delayMillis = 80)) +
                expandHorizontally(spring(dampingRatio = 0.8f, stiffness = 360f), expandFrom = Alignment.Start),
            exit = fadeOut(tween(140)) +
                shrinkHorizontally(spring(dampingRatio = 0.9f, stiffness = 420f), shrinkTowards = Alignment.Start),
        ) {
            BubbleLabel(message = message, cardOpen = cardOpen)
        }
    }
}

@Composable
private fun BubbleLabel(message: AutoSyncBubbleMessage, cardOpen: Boolean) {
    Row {
        Spacer(Modifier.width(10.dp))
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .heightIn(min = OrbSize)
                .widthIn(max = if (cardOpen) 272.dp else 236.dp)
                .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 300f))
                .padding(end = 8.dp),
        ) {
            Text(
                text = "AutoSync",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp,
            )
            AnimatedContent(
                targetState = message.headline,
                transitionSpec = {
                    (fadeIn(tween(240)) + slideInVertically(tween(280)) { it / 3 }) togetherWith
                        (fadeOut(tween(160)) + slideOutVertically(tween(220)) { -it / 3 }) using
                        SizeTransform(clip = false)
                },
            ) { headline ->
                Text(
                    text = headline,
                    color = Color.White,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (cardOpen) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val detail = message.detail
            if (cardOpen && detail != null) {
                Text(
                    text = detail,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 3.dp, bottom = 2.dp),
                )
            }
        }
    }
}

private fun AutoSyncBubbleKind.color(): Color = when (this) {
    AutoSyncBubbleKind.Working -> Color.White
    AutoSyncBubbleKind.Success -> SyncedGreen
    AutoSyncBubbleKind.Failure -> FailedRed
}

/**
 * Liquid glass without a blur (a blur can't see the video surface anyway): a clear, lightly
 * frosted body, light caught on the upper-left and lower-right edges, a faint inner refraction
 * line and a soft shadow. While [liquid] is above zero the outline flows: slow waves travel around
 * its edge and the body gently swells and narrows, then it settles into a clean capsule.
 */
private fun DrawScope.drawLiquidGlass(path: Path, time: Float, liquid: Float, tint: Color, tinted: Float) {
    val corner = BubbleCorner.toPx().coerceAtMost(size.minDimension / 2f)
    val flowing = liquid > 0.002f
    if (flowing) {
        buildLiquidOutline(
            path = path,
            width = size.width,
            height = size.height,
            corner = corner,
            amplitude = 1.8.dp.toPx() * liquid,
            swell = 0.03f * sin(time * 2.1f) * liquid,
            time = time,
        )
    }

    fun shape(brush: Brush? = null, color: Color = Color.Unspecified, style: Stroke? = null) {
        if (flowing) {
            if (brush != null) {
                if (style != null) drawPath(path, brush, style = style) else drawPath(path, brush)
            } else {
                if (style != null) drawPath(path, color, style = style) else drawPath(path, color)
            }
        } else {
            val radius = CornerRadius(corner)
            if (brush != null) {
                if (style != null) drawRoundRect(brush, cornerRadius = radius, style = style)
                else drawRoundRect(brush, cornerRadius = radius)
            } else {
                if (style != null) drawRoundRect(color, cornerRadius = radius, style = style)
                else drawRoundRect(color, cornerRadius = radius)
            }
        }
    }

    // Soft shadow: the same outline, nudged down, in three faint steps.
    for (step in 1..3) {
        translate(top = step * 1.2.dp.toPx()) {
            shape(color = Color.Black.copy(alpha = 0.06f))
        }
    }
    shape(color = GlassBody.copy(alpha = 0.40f))
    if (tinted > 0f) {
        shape(
            brush = Brush.horizontalGradient(
                0f to tint.copy(alpha = 0.22f * tinted),
                1f to Color.Transparent,
                endX = size.height * 3f,
            ),
        )
    }
    // Frost, brighter at the top where the light comes from.
    shape(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.16f),
            0.5f to Color.White.copy(alpha = 0.05f),
            1f to Color.White.copy(alpha = 0.08f),
        ),
    )
    // Rim: bright on the upper left, a softer second highlight on the lower right.
    shape(
        brush = Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.75f),
            0.3f to Color.White.copy(alpha = 0.12f),
            0.7f to Color.White.copy(alpha = 0.06f),
            1f to Color.White.copy(alpha = 0.40f),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        style = Stroke(width = 1.dp.toPx()),
    )
    // A faint refraction line just inside the rim.
    val inset = 2.5.dp.toPx()
    if (!flowing && size.width > inset * 2f && size.height > inset * 2f) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.07f),
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2f, size.height - inset * 2f),
            cornerRadius = CornerRadius((corner - inset).coerceAtLeast(0f)),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

/**
 * The rounded outline of [width] x [height], each point pushed along its normal by two slow waves
 * travelling in opposite directions, and the whole swollen sideways by [swell] (narrower when
 * negative), so the bubble reads as liquid rather than a shape that shakes.
 */
private fun buildLiquidOutline(
    path: Path,
    width: Float,
    height: Float,
    corner: Float,
    amplitude: Float,
    swell: Float,
    time: Float,
) {
    path.reset()
    val straightX = (width - 2f * corner).coerceAtLeast(0f)
    val straightY = (height - 2f * corner).coerceAtLeast(0f)
    val arc = corner * PI.toFloat() / 2f
    val perimeter = 2f * straightX + 2f * straightY + 4f * arc
    if (perimeter <= 0f) return
    val cx = width / 2f
    val cy = height / 2f
    val steps = 72
    for (i in 0..steps) {
        val u = (i % steps).toFloat() / steps
        var s = u * perimeter
        var x: Float
        var y: Float
        var nx: Float
        var ny: Float
        // Walk the outline clockwise from the top-left end of the top edge.
        if (s < straightX) {
            x = corner + s; y = 0f; nx = 0f; ny = -1f
        } else if (run { s -= straightX; s < arc }) {
            val a = -PI.toFloat() / 2f + s / corner
            nx = cos(a); ny = sin(a); x = width - corner + nx * corner; y = corner + ny * corner
        } else if (run { s -= arc; s < straightY }) {
            x = width; y = corner + s; nx = 1f; ny = 0f
        } else if (run { s -= straightY; s < arc }) {
            val a = s / corner
            nx = cos(a); ny = sin(a); x = width - corner + nx * corner; y = height - corner + ny * corner
        } else if (run { s -= arc; s < straightX }) {
            x = width - corner - s; y = height; nx = 0f; ny = 1f
        } else if (run { s -= straightX; s < arc }) {
            val a = PI.toFloat() / 2f + s / corner
            nx = cos(a); ny = sin(a); x = corner + nx * corner; y = height - corner + ny * corner
        } else if (run { s -= arc; s < straightY }) {
            x = 0f; y = height - corner - s; nx = -1f; ny = 0f
        } else {
            s -= straightY
            val a = PI.toFloat() + s / corner
            nx = cos(a); ny = sin(a); x = corner + nx * corner; y = corner + ny * corner
        }
        val wave = amplitude * (
            0.6f * sin(TWO_PI * 2f * u + time * 2.3f) +
                0.4f * sin(TWO_PI * 3f * u - time * 1.7f + 1.1f)
            )
        x = cx + (x + nx * wave - cx) * (1f + swell)
        y = cy + (y + ny * wave - cy) * (1f - swell)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

/**
 * The droplet beside the words: a small glass lens. While working its outline flows and a
 * highlight glides around its rim; the result settles it into a clean circle filled with green
 * or red, and the check or X draws in.
 */
private fun DrawScope.drawDroplet(
    path: Path,
    kind: AutoSyncBubbleKind,
    time: Float,
    liquid: Float,
    tint: Color,
    tinted: Float,
    mark: Float,
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = size.minDimension / 2f
    path.reset()
    val steps = 48
    for (i in 0..steps) {
        val theta = (i % steps).toFloat() / steps * TWO_PI
        val r = radius * (
            1f + liquid * (
                0.07f * sin(3f * theta + time * 2.1f) +
                    0.05f * sin(2f * theta - time * 1.5f + 0.7f)
                )
            ) * (1f - 0.06f * liquid)
        val x = center.x + cos(theta) * r
        val y = center.y + sin(theta) * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()

    drawPath(
        path,
        Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.30f),
            1f to Color.White.copy(alpha = 0.06f),
            center = center + Offset(-radius * 0.35f, -radius * 0.45f),
            radius = radius * 1.6f,
        ),
    )
    if (tinted > 0f) {
        drawPath(
            path,
            Brush.radialGradient(
                0f to tint.copy(alpha = 0.95f * tinted),
                1f to tint.copy(alpha = 0.80f * tinted),
                center = center + Offset(-radius * 0.3f, -radius * 0.4f),
                radius = radius * 1.5f,
            ),
        )
    }
    drawPath(
        path,
        Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.8f),
            0.45f to Color.White.copy(alpha = 0.1f),
            1f to Color.White.copy(alpha = 0.45f),
            start = center - Offset(radius, radius),
            end = center + Offset(radius, radius),
        ),
        style = Stroke(width = 1.dp.toPx()),
    )
    // The gliding highlight: the loading cue, calm and continuous.
    if (liquid > 0f) {
        val inner = radius * 0.62f
        drawArc(
            color = Color.White.copy(alpha = 0.85f * liquid),
            startAngle = time * 150f,
            sweepAngle = 80f,
            useCenter = false,
            topLeft = center - Offset(inner, inner),
            size = Size(inner * 2f, inner * 2f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }

    if (mark > 0f) {
        val stroke = 2.3.dp.toPx()
        val m = mark.coerceIn(0f, 1f)
        if (kind == AutoSyncBubbleKind.Failure) {
            val d = radius * 0.30f
            drawTrimmed(listOf(center + Offset(-d, -d), center + Offset(d, d)), (m * 2f).coerceAtMost(1f), Color.White, stroke)
            if (m > 0.5f) {
                drawTrimmed(listOf(center + Offset(d, -d), center + Offset(-d, d)), (m - 0.5f) * 2f, Color.White, stroke)
            }
        } else {
            drawTrimmed(
                listOf(
                    center + Offset(-radius * 0.38f, radius * 0.02f),
                    center + Offset(-radius * 0.11f, radius * 0.29f),
                    center + Offset(radius * 0.40f, -radius * 0.27f),
                ),
                m,
                Color.White,
                stroke,
            )
        }
    }
}

/** Draws the first [progress] of the polyline through [points], as a pen would. */
private fun DrawScope.drawTrimmed(points: List<Offset>, progress: Float, color: Color, stroke: Float) {
    if (progress <= 0f) return
    var total = 0f
    for (i in 1 until points.size) total += (points[i] - points[i - 1]).getDistance()
    var remaining = total * progress
    for (i in 1 until points.size) {
        val from = points[i - 1]
        val to = points[i]
        val length = (to - from).getDistance()
        if (remaining <= 0f || length <= 0f) break
        val end = if (remaining >= length) to else from + (to - from) * (remaining / length)
        drawLine(color = color, start = from, end = end, strokeWidth = stroke, cap = StrokeCap.Round)
        remaining -= length
    }
}
