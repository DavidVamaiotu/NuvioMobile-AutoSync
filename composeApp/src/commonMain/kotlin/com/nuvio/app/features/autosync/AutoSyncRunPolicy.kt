package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.AudioLanguageOption
import com.nuvio.app.features.player.SubtitleLanguageMatching
import com.nuvio.app.features.player.SubtitleLanguageOption
import com.nuvio.app.features.player.normalizeLanguageCode

internal enum class AutoSyncCandidateScope {
    STARTUP_SEARCH,
    SELECTED_ONLY,
}

internal fun decideAutoSyncStart(enabled: Boolean): AutoSyncStartAction =
    if (enabled) AutoSyncStartAction.RUN else AutoSyncStartAction.ATTACH_ORIGINAL

internal enum class AutoSyncStartAction {
    RUN,
    ATTACH_ORIGINAL,
}

internal fun AutoSyncCandidateScope.alternativeCandidates(
    candidates: List<AutoSyncSubtitleCandidate>,
): List<AutoSyncSubtitleCandidate> =
    if (this == AutoSyncCandidateScope.STARTUP_SEARCH) candidates else emptyList()

internal val AutoSyncCandidateScope.usesAlternativeProvider: Boolean
    get() = this == AutoSyncCandidateScope.STARTUP_SEARCH

internal fun shouldRestoreOriginalSubtitle(
    activeSidecarSubtitleKey: String?,
): Boolean = activeSidecarSubtitleKey == null

/**
 * The subtitle that seeds a second search in the user's secondary subtitle language, after the
 * search in [searchedLanguage] found no match. Null when no secondary language is set, it is the
 * language already searched, or no subtitle is offered in it.
 */
internal fun secondaryLanguageSearchSeed(
    candidates: List<AutoSyncSubtitleCandidate>,
    selectedUrl: String,
    searchedLanguage: String?,
    secondaryLanguage: String?,
): AutoSyncSubtitleCandidate? {
    val secondary = normalizeLanguageCode(secondaryLanguage)?.takeUnless {
        it == SubtitleLanguageOption.NONE ||
            it == SubtitleLanguageOption.DEVICE ||
            it == SubtitleLanguageOption.FORCED ||
            it == AudioLanguageOption.DEFAULT
    } ?: return null
    if (SubtitleLanguageMatching.matchesLanguageCode(searchedLanguage, secondary)) return null
    return candidates.firstOrNull { candidate ->
        candidate.url.isNotBlank() &&
            candidate.url != selectedUrl &&
            SubtitleLanguageMatching.matchesLanguageCode(candidate.language, secondary)
    }
}
