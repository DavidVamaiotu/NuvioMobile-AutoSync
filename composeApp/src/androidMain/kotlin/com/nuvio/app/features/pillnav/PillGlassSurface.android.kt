package com.nuvio.app.features.pillnav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.glass.GlassBarSurface
import dev.chrisbanes.haze.HazeState

// Reuses Nuvio's own refracting glass (edge refraction, chromatic rim, specular highlight on Android 13+).
@Composable
internal actual fun PillGlassSurface(hazeState: HazeState?, modifier: Modifier) {
    GlassBarSurface(hazeState = hazeState, modifier = modifier, glowStrength = 1f)
}
