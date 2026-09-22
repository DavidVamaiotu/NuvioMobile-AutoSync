package com.nuvio.app.features.autosync

import kotlinx.coroutines.flow.StateFlow

internal data class AutoSyncSubtitleCandidate(
    val url: String,
    val language: String,
    val name: String? = null,
)

internal enum class AutoSyncRetryStatus {
    IDLE,
    TRYING,
    UPDATED,
    EXHAUSTED,
    FAILED,
}

internal data class AutoSyncRetryUiState(
    val available: Boolean = false,
    val busy: Boolean = false,
    val exhausted: Boolean = false,
    val status: AutoSyncRetryStatus = AutoSyncRetryStatus.IDLE,
)

/**
 * Optional Android AutoSync capability layered beside PlayerEngineController.
 * Other platforms do not need to implement it.
 */
internal interface AutoSyncPlayerController {
    val autoSyncRetryState: StateFlow<AutoSyncRetryUiState>
    fun retryWithAnotherReference()
    fun setAutoSyncSubtitleCandidates(candidates: List<AutoSyncSubtitleCandidate>)
    fun setSubtitleUriWithAutoSync(url: String)
    fun setSubtitleUriWithSelectedAutoSync(url: String)
    fun setAutoSyncAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    )
}
