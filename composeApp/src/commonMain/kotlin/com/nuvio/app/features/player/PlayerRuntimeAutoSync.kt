package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository
import kotlinx.coroutines.flow.collect

private fun List<AddonSubtitle>.toAutoSyncCandidates(): List<AutoSyncSubtitleCandidate> =
    map { subtitle ->
        AutoSyncSubtitleCandidate(
            url = subtitle.url,
            language = subtitle.language,
            name = subtitle.display,
        )
    }

private fun PlayerScreenRuntime.currentAutoSyncCandidates(): List<AutoSyncSubtitleCandidate> =
    addonSubtitles.toAutoSyncCandidates()

internal fun PlayerScreenRuntime.configureAutoSyncController(
    controller: PlayerEngineController,
) {
    controller.setAutoSyncSubtitleCandidates(currentAutoSyncCandidates())
    controller.setAutoSyncAppliedListener { subtitleUrl, delayMs ->
        val appliedSubtitle = addonSubtitles.firstOrNull { it.url == subtitleUrl }
        selectedAddonSubtitleId = appliedSubtitle?.selectionKey ?: subtitleUrl
        selectedSubtitleIndex = -1
        useCustomSubtitles = true
        preferredSubtitleSelectionApplied = true
        if (appliedSubtitle != null) {
            persistAddonSubtitlePreference(appliedSubtitle)
        }

        val appliedDelayMs = delayMs.coerceIn(
            SUBTITLE_DELAY_MIN_MS,
            SUBTITLE_DELAY_MAX_MS,
        )
        subtitleDelayMs = appliedDelayMs
        PlayerTrackPreferenceStorage.saveSubtitleDelayMs(
            playbackSession.videoId,
            appliedDelayMs,
        )
    }
}

@Composable
internal fun PlayerScreenRuntime.BindAutoSyncRuntimeEffects() {
    LaunchedEffect(playerController, externalSubtitles) {
        val controller = playerController ?: return@LaunchedEffect
        SubtitleRepository.addonSubtitles.collect { repositorySubtitles ->
            controller.setAutoSyncSubtitleCandidates(
                mergeStreamAndAddonSubtitles(
                    repositorySubtitles,
                    externalSubtitles,
                ).toAutoSyncCandidates(),
            )
        }
    }
}


internal fun PlayerScreenRuntime.maybeAutoSyncRestoredSubtitleAtStart(url: String): Boolean {
    val controller = playerController ?: return false
    val videoKey = activeVideoId?.takeIf { it.isNotBlank() } ?: activeSourceUrl
    if (!AutoSyncPreferencesRepository.claimStartupRun(hashCode(), videoKey)) return false
    controller.setSubtitleUriWithAutoSync(url)
    return true
}

internal fun PlayerScreenRuntime.maybeAutoSyncPreferredSubtitleAtStart(
    subtitle: AddonSubtitle,
): Boolean {
    if (isUserExplicitSubtitleSelection) return false
    val preferredLanguage =
        normalizeLanguageCode(playerSettingsUiState.preferredSubtitleLanguage) ?: return false
    if (
        preferredLanguage.isBlank() ||
        preferredLanguage == SubtitleLanguageOption.NONE ||
        preferredLanguage == SubtitleLanguageOption.FORCED
    ) {
        return false
    }

    val controller = playerController ?: return false
    val videoKey = activeVideoId?.takeIf { it.isNotBlank() } ?: activeSourceUrl
    if (!AutoSyncPreferencesRepository.claimStartupRun(hashCode(), videoKey)) return false
    controller.setSubtitleUriWithAutoSync(subtitle.url)
    return true
}
