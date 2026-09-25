package com.nuvio.app.features.player.seekpreview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Opens an on-device preview track for the stream the player is showing, or null when the
 * platform or stream cannot provide one (iOS, libmpv engine, no stream registered yet).
 *
 * [cacheKey] names the title and release, so thumbnails saved for it can be reused.
 */
internal expect fun openLocalSeekPreviewTrack(cacheKey: String, durationMs: Long): SeekPreviewTrack?

/** User setting for on-device seek previews. */
internal object LocalSeekPreviewSettings {
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var persist: (enabled: Boolean) -> Unit = {}

    fun installPersistence(load: () -> Boolean?, save: (enabled: Boolean) -> Unit) {
        load()?.let { _enabled.value = it }
        persist = save
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        persist(value)
    }
}

/** Names a title and release for the thumbnail cache; the duration tells releases apart. */
internal fun localSeekPreviewCacheKey(
    contentId: String?,
    season: Int?,
    episode: Int?,
    durationMs: Long,
): String = listOf(
    contentId.orEmpty(),
    season?.toString().orEmpty(),
    episode?.toString().orEmpty(),
    (durationMs / 1000L).toString(),
).joinToString("|")
