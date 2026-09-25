package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository
import com.nuvio.app.features.player.AudioSyncSettings
import com.nuvio.app.features.player.SubtitleLanguageOption
import com.nuvio.app.features.player.SubtitleSyncStatus
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_audio_sync_fallback
import nuvio.composeapp.generated.resources.settings_playback_audio_sync_fallback_description
import nuvio.composeapp.generated.resources.settings_playback_audio_sync_mobile_data
import nuvio.composeapp.generated.resources.settings_playback_audio_sync_mobile_data_description
import nuvio.composeapp.generated.resources.settings_playback_audio_sync_share_log
import nuvio.composeapp.generated.resources.settings_playback_audio_sync_share_log_description
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_debug_logs
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_debug_logs_description
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_mode_aggressive
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_mode_aggressive_description
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_mode_passive
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_mode_passive_description
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_tolerance
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_tolerance_description
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_tolerance_off
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_tolerance_value
import nuvio.composeapp.generated.resources.settings_playback_speech_model
import nuvio.composeapp.generated.resources.settings_playback_speech_model_downloading
import nuvio.composeapp.generated.resources.settings_playback_speech_model_failed
import nuvio.composeapp.generated.resources.settings_playback_speech_model_missing
import nuvio.composeapp.generated.resources.settings_playback_speech_model_ready
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
    val aggressiveMode by remember {
        AutoSyncPreferencesRepository.ensureLoaded()
        AutoSyncPreferencesRepository.aggressiveMode
    }.collectAsStateWithLifecycle()
    val debugLogsEnabled by remember {
        AutoSyncPreferencesRepository.ensureLoaded()
        AutoSyncPreferencesRepository.debugLogsEnabled
    }.collectAsStateWithLifecycle()
    val syncToleranceMs by remember {
        AutoSyncPreferencesRepository.ensureLoaded()
        AutoSyncPreferencesRepository.syncToleranceMs
    }.collectAsStateWithLifecycle()
    val speechModel by SubtitleSyncStatus.speechModel.collectAsStateWithLifecycle()
    var offerSpeechModel by remember { mutableStateOf(false) }

    if (isIos) return

    val preferredLanguageAvailable =
        preferredSubtitleLanguage.isNotBlank() &&
            preferredSubtitleLanguage != SubtitleLanguageOption.NONE &&
            preferredSubtitleLanguage != SubtitleLanguageOption.FORCED

    SettingsSwitchRow(
        title = stringResource(Res.string.settings_playback_subtitle_auto_sync),
        description = stringResource(
            Res.string.settings_playback_subtitle_auto_sync_description,
        ),
        checked = preferredSubtitleAutoSyncOnStart,
        enabled = enabled && preferredLanguageAvailable,
        isTablet = isTablet,
        onCheckedChange = { on ->
            AutoSyncPreferencesRepository.setPreferredSubtitleAutoSyncOnStart(on)
            // The audio fallback that comes with it works best with the speech model: offer it.
            offerSpeechModel = on && AudioSyncSettings.fallbackEnabled.value && speechModel.supported &&
                !speechModel.downloaded && !speechModel.downloading
        },
    )
    if (offerSpeechModel) {
        SpeechModelOfferDialog(
            sizeMb = speechModel.sizeMb,
            onDownload = { SubtitleSyncStatus.modelActions?.download() },
            onDismiss = { offerSpeechModel = false },
        )
    }
    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(
            if (aggressiveMode) {
                Res.string.settings_playback_auto_sync_mode_aggressive
            } else {
                Res.string.settings_playback_auto_sync_mode_passive
            },
        ),
        description = stringResource(
            if (aggressiveMode) {
                Res.string.settings_playback_auto_sync_mode_aggressive_description
            } else {
                Res.string.settings_playback_auto_sync_mode_passive_description
            },
        ),
        checked = aggressiveMode,
        enabled = enabled,
        isTablet = isTablet,
        onCheckedChange = AutoSyncPreferencesRepository::setAggressiveMode,
    )
    SettingsGroupDivider(isTablet = isTablet)
    SettingsNavigationRow(
        title = stringResource(
            Res.string.settings_playback_auto_sync_tolerance,
            if (syncToleranceMs > 0) {
                stringResource(Res.string.settings_playback_auto_sync_tolerance_value, syncToleranceMs)
            } else {
                stringResource(Res.string.settings_playback_auto_sync_tolerance_off)
            },
        ),
        description = stringResource(
            Res.string.settings_playback_auto_sync_tolerance_description,
        ),
        enabled = enabled,
        isTablet = isTablet,
        onClick = {
            val options = AutoSyncPreferencesRepository.syncToleranceOptionsMs
            val next = options[(options.indexOf(syncToleranceMs) + 1) % options.size]
            AutoSyncPreferencesRepository.setSyncToleranceMs(next)
        },
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
    AudioSyncFallbackSettingsRows(isTablet = isTablet, enabled = enabled && preferredSubtitleAutoSyncOnStart)
}

/** The audio sync fallback: it runs only after AutoSync, so it follows AutoSync's switch. */
@Composable
private fun AudioSyncFallbackSettingsRows(isTablet: Boolean, enabled: Boolean) {
    val fallbackEnabled by AudioSyncSettings.fallbackEnabled.collectAsStateWithLifecycle()
    val samplingOnMobileData by AudioSyncSettings.samplingOnMobileData.collectAsStateWithLifecycle()
    val speechModel by SubtitleSyncStatus.speechModel.collectAsStateWithLifecycle()

    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_playback_audio_sync_fallback),
        description = stringResource(Res.string.settings_playback_audio_sync_fallback_description),
        checked = fallbackEnabled,
        enabled = enabled,
        isTablet = isTablet,
        onCheckedChange = AudioSyncSettings::setFallbackEnabled,
    )
    val active = enabled && fallbackEnabled
    if (speechModel.supported) {
        SettingsGroupDivider(isTablet = isTablet)
        SettingsNavigationRow(
            title = stringResource(Res.string.settings_playback_speech_model, speechModel.sizeMb),
            description = when {
                speechModel.downloading -> stringResource(
                    Res.string.settings_playback_speech_model_downloading,
                    (speechModel.progress * 100).toInt(),
                )
                speechModel.downloaded -> stringResource(Res.string.settings_playback_speech_model_ready)
                speechModel.error != null -> stringResource(
                    Res.string.settings_playback_speech_model_failed,
                    speechModel.error.orEmpty(),
                )
                else -> stringResource(Res.string.settings_playback_speech_model_missing)
            },
            enabled = active && !speechModel.downloading,
            isTablet = isTablet,
            onClick = {
                val actions = SubtitleSyncStatus.modelActions
                if (speechModel.downloaded) actions?.delete() else actions?.download()
            },
        )
    }
    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_playback_audio_sync_mobile_data),
        description = stringResource(Res.string.settings_playback_audio_sync_mobile_data_description),
        checked = samplingOnMobileData,
        enabled = active,
        isTablet = isTablet,
        onCheckedChange = AudioSyncSettings::setSamplingOnMobileData,
    )
    SubtitleSyncStatus.logActions?.let { logActions ->
        SettingsGroupDivider(isTablet = isTablet)
        SettingsNavigationRow(
            title = stringResource(Res.string.settings_playback_audio_sync_share_log),
            description = stringResource(Res.string.settings_playback_audio_sync_share_log_description),
            isTablet = isTablet,
            onClick = { logActions.share() },
        )
    }
}
