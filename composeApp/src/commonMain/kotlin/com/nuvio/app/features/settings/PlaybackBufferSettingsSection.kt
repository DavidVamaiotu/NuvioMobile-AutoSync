package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.player.PlaybackBufferSettings
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_buffer_default
import nuvio.composeapp.generated.resources.settings_playback_buffer_description
import nuvio.composeapp.generated.resources.settings_playback_buffer_section
import nuvio.composeapp.generated.resources.settings_playback_buffer_title
import nuvio.composeapp.generated.resources.settings_playback_buffer_value_gb
import nuvio.composeapp.generated.resources.settings_playback_buffer_value_mb
import org.jetbrains.compose.resources.stringResource

/** Seek buffer size for instant seeks (Android players only). */
@Composable
internal fun PlaybackBufferSettingsSection(isTablet: Boolean) {
    if (isIos) return
    val bufferMb by PlaybackBufferSettings.bufferMb.collectAsStateWithLifecycle()

    SettingsSection(
        title = stringResource(Res.string.settings_playback_buffer_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(
                    Res.string.settings_playback_buffer_title,
                    when {
                        bufferMb == PlaybackBufferSettings.NUVIO_DEFAULT_MB ->
                            stringResource(Res.string.settings_playback_buffer_default)
                        bufferMb % 1024 == 0 ->
                            stringResource(Res.string.settings_playback_buffer_value_gb, bufferMb / 1024)
                        else -> stringResource(Res.string.settings_playback_buffer_value_mb, bufferMb)
                    },
                ),
                description = stringResource(Res.string.settings_playback_buffer_description),
                isTablet = isTablet,
                onClick = PlaybackBufferSettings::cycle,
            )
        }
    }
}
