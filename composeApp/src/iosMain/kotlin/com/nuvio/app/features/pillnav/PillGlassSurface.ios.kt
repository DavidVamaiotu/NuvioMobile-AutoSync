package com.nuvio.app.features.pillnav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

@Composable
internal actual fun PillGlassSurface(hazeState: HazeState?, modifier: Modifier) {
    Box(
        modifier
            .then(if (hazeState != null) Modifier.hazeEffect(state = hazeState) { blurRadius = 24.dp } else Modifier)
            .background(Color(0xFF1C1C1E).copy(alpha = if (hazeState != null) 0.5f else 0.82f))
            .border(
                0.75.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.3f), Color.White.copy(alpha = 0.04f))),
                RoundedCornerShape(50),
            ),
    )
}
