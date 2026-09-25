package com.nuvio.app.features.pillnav

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.profiles.ActiveProfileMiniAvatar
import com.nuvio.app.features.profiles.AvatarRepository
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.AppScreenTab
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileSwitcherTab
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_nav_home
import nuvio.composeapp.generated.resources.compose_nav_library
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.compose_settings_page_root
import org.jetbrains.compose.resources.stringResource

private object PillNavTokens {
    val barHeight = 48.dp
    val barTopGap = 8.dp
    val barSideMargin = 12.dp
    val barMaxWidth = 880.dp
    val innerPadding = 5.dp
    val iconItemSize = 38.dp
    val iconSize = 22.dp
    const val avatarSize = 30
    val labelSize = 15.sp
    const val unselectedAlpha = 0.84f
    const val hideScrollThreshold = 48f
}

/** Top padding tab content needs so it starts below the pill (the home tab draws under it). */
internal val pillNavContentTopPadding: Dp =
    PillNavTokens.barTopGap + PillNavTokens.barHeight + PillNavTokens.barTopGap

/** Hides the pill while home scrolls down and brings it back on the way up. Never consumes scroll. */
@Stable
internal class PillNavState {
    var hiddenByScroll by mutableStateOf(false)
        private set

    private var accumulated = 0f

    fun show() {
        hiddenByScroll = false
        accumulated = 0f
    }

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            if (dy == 0f) return Offset.Zero
            if ((dy < 0f) != (accumulated < 0f)) accumulated = 0f
            accumulated += dy
            if (accumulated < -PillNavTokens.hideScrollThreshold && !hiddenByScroll) {
                hiddenByScroll = true
                accumulated = 0f
            } else if (accumulated > PillNavTokens.hideScrollThreshold && hiddenByScroll) {
                hiddenByScroll = false
                accumulated = 0f
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Overscrolling at the top always reveals the pill.
            if (available.y > 0f) show()
            return Offset.Zero
        }
    }
}

@Composable
internal fun rememberPillNavState(): PillNavState = remember { PillNavState() }

/** Content modifier: scroll tracking plus room for the pill on every tab except home. */
internal fun Modifier.pillNavContent(state: PillNavState, selectedTab: AppScreenTab): Modifier =
    nestedScroll(state.nestedScrollConnection)
        .then(if (selectedTab == AppScreenTab.Home) Modifier else Modifier.padding(top = pillNavContentTopPadding))

private class PillTab(val tab: AppScreenTab, val label: String)

/**
 * Floating glass pill with text tabs on the left and a settings icon and the profile picture on the right.
 * A liquid glass lens slides between items; all motion is read in the draw or placement phase.
 */
@Composable
internal fun PillNavigationBar(
    selectedTab: AppScreenTab,
    onTabSelected: (AppScreenTab) -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
    onSwitchProfile: () -> Unit,
    state: PillNavState,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(selectedTab) { state.show() }
    val visible = selectedTab != AppScreenTab.Home || !state.hiddenByScroll
    val hideFraction = animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 320f),
        label = "pill_nav_hide",
    )
    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + PillNavTokens.barTopGap
    val hideDistancePx = with(density) { (topInset + PillNavTokens.barHeight).toPx() }

    val tabs = listOf(
        PillTab(AppScreenTab.Home, stringResource(Res.string.compose_nav_home)),
        PillTab(AppScreenTab.Search, stringResource(Res.string.compose_nav_search)),
        PillTab(AppScreenTab.Library, stringResource(Res.string.compose_nav_library)),
    )
    // Indices 0..2 are the text tabs, 3 is settings; the profile picture is never highlighted.
    val settingsIndex = tabs.size
    val selectedIndex = when (selectedTab) {
        AppScreenTab.Settings -> settingsIndex
        else -> tabs.indexOfFirst { it.tab == selectedTab }
    }

    val itemBounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val indicator = remember { LiquidIndicator() }
    val target = itemBounds[selectedIndex]
    LaunchedEffect(selectedIndex, target) {
        val (x, width) = target ?: return@LaunchedEffect
        indicator.moveTo(x, x + width)
    }

    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()
    val avatars by AvatarRepository.avatars.collectAsStateWithLifecycle()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(PaddingValues(top = topInset, start = PillNavTokens.barSideMargin, end = PillNavTokens.barSideMargin))
            .offset { IntOffset(0, -(hideFraction.value * hideDistancePx).roundToInt()) }
            .graphicsLayer {
                val hidden = hideFraction.value
                alpha = 1f - hidden
                scaleX = 1f - 0.04f * hidden
                scaleY = 1f - 0.04f * hidden
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        val itemPadding = if (maxWidth < 400.dp) 12.dp else 18.dp
        Box(
            modifier = Modifier
                .widthIn(max = PillNavTokens.barMaxWidth)
                .fillMaxWidth()
                .height(PillNavTokens.barHeight),
        ) {
            PillGlassSurface(hazeState, Modifier.matchParentSize().clip(RoundedCornerShape(50)))
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .padding(PillNavTokens.innerPadding)
                    .drawBehind { if (selectedIndex >= 0) drawLiquidIndicator(indicator) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    PillTextItem(
                        label = tab.label,
                        selected = index == selectedIndex,
                        enabled = visible,
                        horizontalPadding = itemPadding,
                        onClick = { onTabSelected(tab.tab) },
                        modifier = Modifier.onPlaced { itemBounds[index] = it.positionInParent().x to it.size.width.toFloat() },
                    )
                }
                Spacer(Modifier.weight(1f))
                PillIconItem(
                    icon = Icons.Rounded.Settings,
                    contentDescription = stringResource(Res.string.compose_settings_page_root),
                    selected = selectedIndex == settingsIndex,
                    enabled = visible,
                    onClick = { onTabSelected(AppScreenTab.Settings) },
                    modifier = Modifier.onPlaced { itemBounds[settingsIndex] = it.positionInParent().x to it.size.width.toFloat() },
                )
                ProfileSwitcherTab(
                    selected = false,
                    onClick = { if (visible) onSwitchProfile() },
                    onProfileSelected = onProfileSelected,
                    onAddProfileRequested = onSwitchProfile,
                    hazeState = hazeState,
                    popupBelowAnchor = true,
                    modifier = Modifier.size(PillNavTokens.iconItemSize).clip(CircleShape),
                    triggerContent = {
                        ActiveProfileMiniAvatar(
                            profile = profileState.activeProfile,
                            avatars = avatars,
                            selected = false,
                            size = PillNavTokens.avatarSize,
                        )
                    },
                )
            }
        }
    }
}

/**
 * The selection highlight as two independently sprung edges: the leading edge races ahead and the
 * trailing edge follows, so the lens stretches and thins in flight and settles back into a pill.
 */
@Stable
private class LiquidIndicator {
    val left = Animatable(0f)
    val right = Animatable(0f)
    var placed by mutableStateOf(false)
        private set
    private var restWidth = 1f

    suspend fun moveTo(targetLeft: Float, targetRight: Float) {
        restWidth = (targetRight - targetLeft).coerceAtLeast(1f)
        if (!placed) {
            left.snapTo(targetLeft)
            right.snapTo(targetRight)
            placed = true
            return
        }
        val movingRight = targetLeft > left.value
        val lead = spring<Float>(dampingRatio = 0.72f, stiffness = 520f)
        val trail = spring<Float>(dampingRatio = 0.86f, stiffness = 210f)
        coroutineScope {
            launch { left.animateTo(targetLeft, if (movingRight) trail else lead) }
            launch { right.animateTo(targetRight, if (movingRight) lead else trail) }
        }
    }

    /** 0 at rest, towards 1 while stretched in flight. */
    fun stretch(): Float = ((right.value - left.value) / restWidth - 1f).coerceIn(0f, 1.5f) / 1.5f
}

private fun DrawScope.drawLiquidIndicator(indicator: LiquidIndicator) {
    if (!indicator.placed) return
    val stretch = indicator.stretch()
    val squash = 1f - 0.16f * stretch
    val height = size.height * squash
    val top = (size.height - height) / 2f
    val width = indicator.right.value - indicator.left.value
    val topLeft = Offset(indicator.left.value, top)
    val lensSize = Size(width, height)
    val radius = CornerRadius(height / 2f)
    // Glass lens: a bright top falling to a soft base, a specular rim, and a faint inner glow.
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.30f),
            0.55f to Color.White.copy(alpha = 0.16f),
            1f to Color.White.copy(alpha = 0.22f),
            startY = top,
            endY = top + height,
        ),
        topLeft = topLeft,
        size = lensSize,
        cornerRadius = radius,
    )
    val rim = 1.dp.toPx()
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.62f),
            0.5f to Color.White.copy(alpha = 0.08f),
            1f to Color.White.copy(alpha = 0.28f),
            startY = top,
            endY = top + height,
        ),
        topLeft = Offset(topLeft.x + rim / 2f, top + rim / 2f),
        size = Size(width - rim, height - rim),
        cornerRadius = CornerRadius((height - rim) / 2f),
        style = Stroke(rim),
    )
}

@Composable
private fun PillTextItem(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    horizontalPadding: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val contentAlpha = animateFloatAsState(
        targetValue = if (selected) 1f else PillNavTokens.unselectedAlpha,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pill_nav_label_alpha",
    )
    val pressScale = pressScale(interactionSource)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(CircleShape)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .graphicsLayer {
                alpha = contentAlpha.value
                scaleX = pressScale.value
                scaleY = pressScale.value
            }
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = PillNavTokens.labelSize,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.sp,
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun PillIconItem(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val contentAlpha = animateFloatAsState(
        targetValue = if (selected) 1f else PillNavTokens.unselectedAlpha,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pill_nav_icon_alpha",
    )
    val pressScale = pressScale(interactionSource)
    Box(
        modifier = modifier
            .size(PillNavTokens.iconItemSize)
            .clip(CircleShape)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .graphicsLayer {
                alpha = contentAlpha.value
                scaleX = pressScale.value
                scaleY = pressScale.value
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(PillNavTokens.iconSize),
        )
    }
}

@Composable
private fun pressScale(interactionSource: MutableInteractionSource): State<Float> {
    val pressed by interactionSource.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "pill_nav_press",
    )
}
