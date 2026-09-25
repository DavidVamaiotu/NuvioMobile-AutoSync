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

/** Progress of on-device generation for one title. */
internal data class LocalSeekPreviewStats(
    val filled: Int = 0,
    val total: Int = 0,
    /** Bytes the background sampler downloaded (the buffer tap costs nothing). */
    val downloadedBytes: Long = 0,
    /** Thumbnails taken from what playback already downloaded. */
    val fromBuffer: Int = 0,
    /** Thumbnails restored from the disk cache. */
    val fromCache: Int = 0,
    /** Why background fill is not running, or null while it runs or is done. */
    val pausedReason: String? = null,
)

/** User settings for on-device seek previews. */
internal object LocalSeekPreviewSettings {
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _mobileData = MutableStateFlow(false)
    /** Background fill on metered networks; the buffer tap runs regardless. */
    val mobileData: StateFlow<Boolean> = _mobileData.asStateFlow()

    private var persist: (enabled: Boolean, mobileData: Boolean) -> Unit = { _, _ -> }

    fun installPersistence(
        load: () -> Pair<Boolean, Boolean>?,
        save: (enabled: Boolean, mobileData: Boolean) -> Unit,
    ) {
        load()?.let { (enabled, mobileData) ->
            _enabled.value = enabled
            _mobileData.value = mobileData
        }
        persist = save
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        persist(value, _mobileData.value)
    }

    fun setMobileData(value: Boolean) {
        _mobileData.value = value
        persist(_enabled.value, value)
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
