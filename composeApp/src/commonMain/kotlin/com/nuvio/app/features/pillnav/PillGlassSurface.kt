package com.nuvio.app.features.pillnav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeState

/** The pill's glass background: refracting liquid glass where the platform supports it, frosted blur elsewhere. */
@Composable
internal expect fun PillGlassSurface(hazeState: HazeState?, modifier: Modifier = Modifier)
