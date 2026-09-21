package com.nuvio.app.features.autosync

internal enum class AutoSyncCandidateScope {
    STARTUP_SEARCH,
    SELECTED_ONLY,
}

internal fun decideAutoSyncStart(
    enabled: Boolean,
    attachSubtitleOnReject: Boolean,
): AutoSyncStartAction =
    when {
        enabled -> AutoSyncStartAction.RUN
        attachSubtitleOnReject -> AutoSyncStartAction.ATTACH_ORIGINAL
        else -> AutoSyncStartAction.NO_OP
    }

internal enum class AutoSyncStartAction {
    RUN,
    ATTACH_ORIGINAL,
    NO_OP,
}

internal fun AutoSyncCandidateScope.alternativeCandidates(
    candidates: List<AutoSyncSubtitleCandidate>,
): List<AutoSyncSubtitleCandidate> =
    if (this == AutoSyncCandidateScope.STARTUP_SEARCH) candidates else emptyList()

internal val AutoSyncCandidateScope.usesAlternativeProvider: Boolean
    get() = this == AutoSyncCandidateScope.STARTUP_SEARCH

internal fun shouldRestoreOriginalSubtitle(
    attachSubtitleOnReject: Boolean,
    activeSidecarSubtitleKey: String?,
): Boolean =
    attachSubtitleOnReject && activeSidecarSubtitleKey == null
