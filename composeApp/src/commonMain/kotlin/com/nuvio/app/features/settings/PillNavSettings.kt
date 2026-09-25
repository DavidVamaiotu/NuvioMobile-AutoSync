package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.pillnav.PillNavRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_pill_nav_description
import nuvio.composeapp.generated.resources.settings_pill_nav_section
import nuvio.composeapp.generated.resources.settings_pill_nav_title
import org.jetbrains.compose.resources.stringResource

/** Pill menu: opt-in top navigation that replaces Nuvio's bar. Hidden where it can't be saved. */
@Composable
internal fun PillNavSettingsSection(isTablet: Boolean) {
    if (!PillNavRepository.isAvailable) return
    val enabled by PillNavRepository.enabled.collectAsStateWithLifecycle()
    SettingsSection(
        title = stringResource(Res.string.settings_pill_nav_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_pill_nav_title),
                description = stringResource(Res.string.settings_pill_nav_description),
                checked = enabled,
                isTablet = isTablet,
                onCheckedChange = PillNavRepository::setEnabled,
            )
        }
    }
}
