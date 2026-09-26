package com.nuvio.app.features.autosync.bubble

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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

private val Aqua = Color(0xFF7FD8FF)
private val Lilac = Color(0xFFB9A2FF)
private val Mint = Color(0xFF5BE49B)
private val Coral = Color(0xFFFF6B7A)
private val GlassBody = Color(0xFF12151D)
private val BubbleCorner = 23.dp
private val OrbSize = 34.dp

/** How long the working bubble keeps its words before tucking in to just the orb. */
private const val WORKING_LABEL_MS = 7_000L
/** A run that never reports back floats away after this. */
private const val WORKING_TIMEOUT_MS = 240_000L
private const val SUCCESS_HOLD_MS = 1_600L
private const val FAILURE_HOLD_MS = 7_000L

/**
 * AutoSync's glass bubble, at the bottom centre of the player. Draws nothing, and runs no animation, unless
 * the setting is on and AutoSync has something to say. It only takes touches while its failure
 * card is open, so it never gets in the way of the player's own gestures.
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
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
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
    val resolve = remember { Animatable(0f) } // the little bubbles gather into one
    val mark = remember { Animatable(0f) } // the check or the X draws itself
    val bump = remember { Animatable(0f) } // happy bounce
    val shake = remember { Animatable(0f) } // unhappy wobble
    val pop = remember { Animatable(0f) } // success: the orb pops into droplets
    val leave = remember { Animatable(0f) } // failure or timeout: shrinks away
    val clock = remember { mutableFloatStateOf(0f) }
    var labelVisible by remember { mutableStateOf(true) }
    var cardOpen by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val tint = animateColorAsState(
        targetValue = kind.color(),
        animationSpec = tween(420),
    )

    fun dismiss() {
        if (leaving) return
        leaving = true
        scope.launch {
            cardOpen = false
            labelVisible = false
            delay(220)
            leave.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
            AutoSyncBubbleToasts.finished(message.id)
        }
    }

    LaunchedEffect(Unit) {
        appear.animateTo(1f, spring(dampingRatio = 0.48f, stiffness = 380f))
    }
    // The bubbling clock: only ticks while there is bubbling to draw, then stops for good.
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (kindState.value == AutoSyncBubbleKind.Working || resolve.value < 1f) {
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
                resolve.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
                launch { bump.animateTo(1f, tween(460, easing = LinearOutSlowInEasing)) }
                mark.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
                delay(SUCCESS_HOLD_MS)
                labelVisible = false
                delay(360)
                leaving = true
                pop.animateTo(1f, tween(460, easing = LinearOutSlowInEasing))
                AutoSyncBubbleToasts.finished(message.id)
            }
            AutoSyncBubbleKind.Failure -> {
                labelVisible = true
                resolve.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
                launch { shake.animateTo(1f, tween(620)) }
                mark.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
                delay(160)
                cardOpen = true
                delay(FAILURE_HOLD_MS)
                dismiss()
            }
        }
    }

    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .graphicsLayer {
                val a = appear.value
                val l = leave.value
                val scale = (0.55f + 0.45f * a) * (1f - 0.35f * l)
                scaleX = scale
                scaleY = scale
                alpha = a.coerceIn(0f, 1f) * (1f - l)
                translationY = (1f - a) * 28.dp.toPx() + l * 12.dp.toPx()
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .then(
                if (cardOpen) {
                    Modifier.pointerInput(Unit) { detectTapGestures { dismiss() } }
                } else {
                    Modifier
                },
            )
            .drawBehind { drawGlass(tint.value, glassAlpha = (1f - pop.value * 2.2f).coerceIn(0f, 1f)) }
            .padding(6.dp),
    ) {
        Canvas(Modifier.size(OrbSize)) {
            drawOrb(
                kind = kindState.value,
                time = clock.floatValue,
                resolve = resolve.value,
                mark = mark.value,
                bump = bump.value,
                shake = shake.value,
                pop = pop.value,
            )
        }
        AnimatedVisibility(
            visible = labelVisible,
            enter = fadeIn(tween(220, delayMillis = 60)) +
                expandHorizontally(spring(dampingRatio = 0.72f, stiffness = 420f), expandFrom = Alignment.Start),
            exit = fadeOut(tween(140)) +
                shrinkHorizontally(spring(dampingRatio = 0.85f, stiffness = 520f), shrinkTowards = Alignment.Start),
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
                .animateContentSize(spring(dampingRatio = 0.62f, stiffness = 320f))
                .padding(end = 10.dp, top = 1.dp, bottom = 1.dp),
        ) {
            Text(
                text = "AutoSync",
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.4.sp,
            )
            AnimatedContent(
                targetState = message.headline,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 2 }) togetherWith
                        (fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it / 2 }) using
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
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 3.dp, bottom = 3.dp),
                )
            }
        }
    }
}

private fun AutoSyncBubbleKind.color(): Color = when (this) {
    AutoSyncBubbleKind.Working -> Aqua
    AutoSyncBubbleKind.Success -> Mint
    AutoSyncBubbleKind.Failure -> Coral
}

/**
 * Liquid glass without a blur (a blur can't see the video surface anyway): a dark see-through
 * body, a white sheen that fades downwards, a tinted glow on the orb's side, a rim that catches
 * the light at the top, and a soft three-step shadow.
 */
private fun DrawScope.drawGlass(tint: Color, glassAlpha: Float) {
    if (glassAlpha <= 0f) return
    val corner = BubbleCorner.toPx().coerceAtMost(size.minDimension / 2f)
    val radius = CornerRadius(corner)
    for (step in 1..3) {
        val spread = step * 2.dp.toPx()
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.10f * glassAlpha),
            topLeft = Offset(-spread / 2f, spread / 2f + 1.dp.toPx()),
            size = Size(size.width + spread, size.height + spread),
            cornerRadius = CornerRadius(corner + spread / 2f),
        )
    }
    drawRoundRect(color = GlassBody.copy(alpha = 0.56f * glassAlpha), cornerRadius = radius)
    drawRoundRect(
        brush = Brush.horizontalGradient(
            0f to tint.copy(alpha = 0.32f * glassAlpha),
            1f to Color.Transparent,
            endX = size.height * 3f,
        ),
        cornerRadius = radius,
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.20f * glassAlpha),
            0.55f to Color.White.copy(alpha = 0.05f * glassAlpha),
            1f to Color.White.copy(alpha = 0.02f * glassAlpha),
        ),
        cornerRadius = radius,
    )
    val sheenInset = corner * 0.9f
    if (size.width > sheenInset * 2f) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.16f * glassAlpha),
            topLeft = Offset(sheenInset, 2.dp.toPx()),
            size = Size(size.width - sheenInset * 2f, 3.dp.toPx()),
            cornerRadius = CornerRadius(1.5.dp.toPx()),
        )
    }
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.55f * glassAlpha),
            0.5f to Color.White.copy(alpha = 0.08f * glassAlpha),
            1f to tint.copy(alpha = 0.40f * glassAlpha),
        ),
        cornerRadius = radius,
        style = Stroke(width = 1.dp.toPx()),
    )
}

/**
 * The orb. While working, three soft bubbles drift around inside it like a lava lamp and tiny
 * bubbles rise off the top, while its colour drifts between aqua and lilac. When the result
 * arrives the bubbles gather into one, the orb takes the result's colour and the mark draws in.
 */
private fun DrawScope.drawOrb(
    kind: AutoSyncBubbleKind,
    time: Float,
    resolve: Float,
    mark: Float,
    bump: Float,
    shake: Float,
    pop: Float,
) {
    val working = 1f - resolve
    val drift = 0.5f + 0.5f * sin(time * 0.9f)
    val bubbling = lerp(Aqua, Lilac, drift)
    val base = if (kind == AutoSyncBubbleKind.Working) bubbling else lerp(bubbling, kind.color(), resolve)
    val shakeX = sin(shake * PI.toFloat() * 6f) * (1f - shake) * 3.5.dp.toPx()
    val center = Offset(size.width / 2f + shakeX, size.height / 2f)
    val breath = 1f + 0.045f * sin(time * 2.6f) * working
    val bounce = 1f + 0.16f * sin(bump * PI.toFloat())
    val radius = size.minDimension / 2f * breath * bounce * (1f + 0.4f * pop)
    val alpha = 1f - pop

    if (pop > 0f) drawDroplets(center, size.minDimension / 2f, base, pop)
    if (alpha <= 0f) return

    drawCircle(
        brush = Brush.radialGradient(
            0f to lerp(base, Color.White, 0.45f),
            0.55f to base,
            1f to lerp(base, Color.Black, 0.28f),
            center = center + Offset(-radius * 0.35f, -radius * 0.4f),
            radius = radius * 1.6f,
        ),
        radius = radius,
        center = center,
        alpha = 0.92f * alpha,
    )

    if (working > 0f) {
        for (i in 0 until 3) {
            val angle = time * (1.1f + 0.5f * i) + i * 2.1f
            val orbit = radius * 0.40f * working * (0.8f + 0.2f * sin(time * 1.7f + i))
            val blobCenter = center + Offset(cos(angle) * orbit, sin(angle * 1.2f) * orbit)
            val blobRadius = radius * (0.34f + 0.07f * sin(time * 2.2f + i * 1.3f))
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.62f * working),
                    1f to Color.White.copy(alpha = 0f),
                    center = blobCenter,
                    radius = blobRadius,
                ),
                radius = blobRadius,
                center = blobCenter,
            )
        }
        // Tiny bubbles escaping off the top of the orb.
        for (i in 0 until 3) {
            val life = ((time * 0.62f + i / 3f) % 1f)
            val rise = life * 16.dp.toPx()
            val wiggle = sin(time * 3f + i * 2f) * 3.dp.toPx()
            val tiny = (1.4f + i * 0.5f).dp.toPx() * (0.6f + 0.4f * life)
            drawCircle(
                color = Color.White.copy(alpha = 0.55f * (1f - life) * working),
                radius = tiny,
                center = Offset(center.x + wiggle + (i - 1) * radius * 0.35f, center.y - radius * 0.75f - rise),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }

    // Glass sphere: a highlight up top and a thin rim.
    drawOval(
        color = Color.White.copy(alpha = 0.5f * alpha),
        topLeft = center + Offset(-radius * 0.55f, -radius * 0.78f),
        size = Size(radius * 0.66f, radius * 0.36f),
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.38f * alpha),
        radius = radius,
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )

    if (mark > 0f) {
        val stroke = 2.6.dp.toPx()
        val m = mark.coerceIn(0f, 1f)
        val color = Color.White.copy(alpha = alpha)
        if (kind == AutoSyncBubbleKind.Failure) {
            val d = radius * 0.30f
            drawTrimmed(listOf(center + Offset(-d, -d), center + Offset(d, d)), (m * 2f).coerceAtMost(1f), color, stroke)
            if (m > 0.5f) {
                drawTrimmed(listOf(center + Offset(d, -d), center + Offset(-d, d)), (m - 0.5f) * 2f, color, stroke)
            }
        } else {
            drawTrimmed(
                listOf(
                    center + Offset(-radius * 0.40f, radius * 0.02f),
                    center + Offset(-radius * 0.12f, radius * 0.30f),
                    center + Offset(radius * 0.42f, -radius * 0.28f),
                ),
                m,
                color,
                stroke,
            )
        }
    }
}

/** Droplets flung out as the orb pops. */
private fun DrawScope.drawDroplets(center: Offset, radius: Float, color: Color, pop: Float) {
    val count = 8
    for (i in 0 until count) {
        val angle = i * (2f * PI.toFloat() / count) + 0.35f
        val distance = radius * (0.9f + 1.25f * pop) * (if (i % 2 == 0) 1f else 0.8f)
        val dropRadius = (if (i % 2 == 0) 2.8f else 2f).dp.toPx() * (1f - pop)
        if (dropRadius <= 0f) continue
        drawCircle(
            color = lerp(color, Color.White, 0.3f).copy(alpha = (1f - pop) * 0.9f),
            radius = dropRadius,
            center = center + Offset(cos(angle) * distance, sin(angle) * distance),
        )
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
