package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.autosync.bubble.AutoSyncBubbleToasts
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.rememberSubtitleFontPicker
import com.nuvio.app.features.streams.ConnectionSpeedEstimator
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.isIos
import kotlin.math.roundToInt
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_streams
import nuvio.composeapp.generated.resources.settings_autosync_bubble_toast
import nuvio.composeapp.generated.resources.settings_autosync_bubble_toast_description
import nuvio.composeapp.generated.resources.settings_nuvio_reshaped
import nuvio.composeapp.generated.resources.settings_nuvio_reshaped_autosync_section
import nuvio.composeapp.generated.resources.settings_nuvio_reshaped_description
import nuvio.composeapp.generated.resources.settings_nuvio_reshaped_subtitle_section
import nuvio.composeapp.generated.resources.settings_playback_subtitle_font
import nuvio.composeapp.generated.resources.settings_playback_subtitle_font_default
import nuvio.composeapp.generated.resources.settings_playback_subtitle_font_importing
import nuvio.composeapp.generated.resources.settings_playback_subtitle_font_reset
import nuvio.composeapp.generated.resources.settings_stream_connection_fit_learning
import nuvio.composeapp.generated.resources.settings_stream_connection_fit_measured
import nuvio.composeapp.generated.resources.settings_stream_connection_fit_title
import org.jetbrains.compose.resources.stringResource

/** Settings root entry for the Nuvio Reshaped page. */
internal fun LazyListScope.nuvioReshapedRootSection(isTablet: Boolean, onClick: () -> Unit) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_nuvio_reshaped),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_nuvio_reshaped),
                    description = stringResource(Res.string.settings_nuvio_reshaped_description),
                    icon = Icons.Rounded.AutoAwesome,
                    isTablet = isTablet,
                    onClick = onClick,
                )
            }
        }
    }
}

/** Everything Nuvio Reshaped adds on top of Nuvio, in one place. */
internal fun LazyListScope.nuvioReshapedSettingsContent(isTablet: Boolean) {
    if (!isIos) {
        item {
            val playerSettings by remember {
                PlayerSettingsRepository.ensureLoaded()
                PlayerSettingsRepository.uiState
            }.collectAsStateWithLifecycle()
            SettingsSection(
                title = stringResource(Res.string.settings_nuvio_reshaped_autosync_section),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    AutoSyncPlaybackSettingsRows(
                        isTablet = isTablet,
                        enabled = !playerSettings.externalPlayerEnabled,
                        preferredSubtitleLanguage = playerSettings.preferredSubtitleLanguage,
                    )
                    AutoSyncBubbleToastSettingsRow(isTablet = isTablet, enabled = !playerSettings.externalPlayerEnabled)
                }
            }
        }
    }
    item {
        SubtitleFontSettingsSection(isTablet = isTablet)
    }
    item {
        PlaybackBufferSettingsSection(isTablet = isTablet)
    }
    item {
        SeekPreviewSettingsSection(isTablet = isTablet)
    }
    item {
        PillNavSettingsSection(isTablet = isTablet)
    }
    item {
        val streamSettings by remember {
            StreamBadgeSettingsRepository.ensureLoaded()
            StreamBadgeSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        SettingsSection(
            title = stringResource(Res.string.compose_settings_page_streams),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_stream_connection_fit_title),
                    description = connectionFitStatus(),
                    checked = streamSettings.preferConnectionFit,
                    isTablet = isTablet,
                    onCheckedChange = StreamBadgeSettingsRepository::setPreferConnectionFit,
                )
            }
        }
    }
}

@Composable
private fun AutoSyncBubbleToastSettingsRow(isTablet: Boolean, enabled: Boolean) {
    if (!AutoSyncBubbleToasts.isAvailable) return
    val bubbleEnabled by AutoSyncBubbleToasts.enabled.collectAsStateWithLifecycle()
    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_autosync_bubble_toast),
        description = stringResource(Res.string.settings_autosync_bubble_toast_description),
        checked = bubbleEnabled,
        enabled = enabled,
        isTablet = isTablet,
        onCheckedChange = AutoSyncBubbleToasts::setEnabled,
    )
}

@Composable
private fun SubtitleFontSettingsSection(isTablet: Boolean) {
    val picker = rememberSubtitleFontPicker() ?: return
    val playerSettings by remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val enabled = !playerSettings.externalPlayerEnabled
    SettingsSection(
        title = stringResource(Res.string.settings_nuvio_reshaped_subtitle_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(Res.string.settings_playback_subtitle_font),
                description = when {
                    picker.isImporting -> stringResource(Res.string.settings_playback_subtitle_font_importing)
                    picker.fontName != null -> picker.fontName
                    else -> stringResource(Res.string.settings_playback_subtitle_font_default)
                },
                enabled = enabled && !picker.isImporting,
                isTablet = isTablet,
                trailingContent = if (picker.fontName != null) {
                    {
                        TextButton(onClick = picker.reset, enabled = enabled) {
                            Text(stringResource(Res.string.settings_playback_subtitle_font_reset))
                        }
                    }
                } else {
                    null
                },
                onClick = picker.pick,
            )
        }
    }
}

@Composable
private fun connectionFitStatus(): String {
    val connectionMbps = remember { ConnectionSpeedEstimator.estimateMbps() }
    return if (connectionMbps == null) {
        stringResource(Res.string.settings_stream_connection_fit_learning)
    } else {
        stringResource(Res.string.settings_stream_connection_fit_measured, connectionMbps.roundToInt())
    }
}
