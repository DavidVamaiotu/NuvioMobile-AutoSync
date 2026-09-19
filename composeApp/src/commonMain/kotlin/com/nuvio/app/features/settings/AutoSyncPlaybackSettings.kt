package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository
import com.nuvio.app.features.player.SubtitleLanguageOption
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_debug_logs
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_debug_logs_description
import nuvio.composeapp.generated.resources.settings_playback_subtitle_auto_sync
import nuvio.composeapp.generated.resources.settings_playback_subtitle_auto_sync_description
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AutoSyncPlaybackSettingsRows(
    isTablet: Boolean,
    enabled: Boolean,
    preferredSubtitleLanguage: String,
) {
    val preferredSubtitleAutoSyncOnStart by remember {
        AutoSyncPreferencesRepository.ensureLoaded()
        AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart
    }.collectAsStateWithLifecycle()
    val debugLogsEnabled by remember {
        AutoSyncPreferencesRepository.ensureLoaded()
        AutoSyncPreferencesRepository.debugLogsEnabled
    }.collectAsStateWithLifecycle()

    if (isIos) return

    val preferredLanguageAvailable =
        preferredSubtitleLanguage.isNotBlank() &&
            preferredSubtitleLanguage != SubtitleLanguageOption.NONE &&
            preferredSubtitleLanguage != SubtitleLanguageOption.FORCED

    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_playback_subtitle_auto_sync),
        description = stringResource(
            Res.string.settings_playback_subtitle_auto_sync_description,
        ),
        checked = preferredSubtitleAutoSyncOnStart,
        enabled = enabled && preferredLanguageAvailable,
        isTablet = isTablet,
        onCheckedChange = AutoSyncPreferencesRepository::setPreferredSubtitleAutoSyncOnStart,
    )
    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_playback_auto_sync_debug_logs),
        description = stringResource(
            Res.string.settings_playback_auto_sync_debug_logs_description,
        ),
        checked = debugLogsEnabled,
        enabled = enabled,
        isTablet = isTablet,
        onCheckedChange = AutoSyncPreferencesRepository::setDebugLogsEnabled,
    )
}
