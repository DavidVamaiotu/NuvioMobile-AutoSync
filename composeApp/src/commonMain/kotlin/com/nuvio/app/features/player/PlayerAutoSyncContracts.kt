package com.nuvio.app.features.player

internal data class AutoSyncSubtitleCandidate(
    val url: String,
    val language: String,
    val name: String? = null,
)

/**
 * Optional Android AutoSync capability layered beside PlayerEngineController.
 * Other platforms do not need to implement it.
 */
internal interface AutoSyncPlayerController {
    fun setAutoSyncSubtitleCandidates(candidates: List<AutoSyncSubtitleCandidate>)
    fun setSubtitleUriWithAutoSync(url: String)
    fun setSubtitleUriWithSelectedAutoSync(url: String)
    fun runSubtitleAutoSync(url: String)
    fun setAutoSyncAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    )
}

internal fun PlayerEngineController.setAutoSyncSubtitleCandidates(
    candidates: List<AutoSyncSubtitleCandidate>,
) {
    (this as? AutoSyncPlayerController)?.setAutoSyncSubtitleCandidates(candidates)
}

internal fun PlayerEngineController.setSubtitleUriWithAutoSync(url: String) {
    val autoSync = this as? AutoSyncPlayerController
    if (autoSync != null) {
        autoSync.setSubtitleUriWithAutoSync(url)
    } else {
        setSubtitleUri(url)
    }
}

internal fun PlayerEngineController.setSubtitleUriWithSelectedAutoSync(url: String) {
    val autoSync = this as? AutoSyncPlayerController
    if (autoSync != null) {
        autoSync.setSubtitleUriWithSelectedAutoSync(url)
    } else {
        setSubtitleUri(url)
    }
}

internal fun PlayerEngineController.runSubtitleAutoSync(url: String) {
    (this as? AutoSyncPlayerController)?.runSubtitleAutoSync(url)
}

internal fun PlayerEngineController.setAutoSyncAppliedListener(
    listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
) {
    (this as? AutoSyncPlayerController)?.setAutoSyncAppliedListener(listener)
}
