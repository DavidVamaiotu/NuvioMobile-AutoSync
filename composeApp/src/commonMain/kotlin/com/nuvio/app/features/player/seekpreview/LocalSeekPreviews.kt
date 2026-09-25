package com.nuvio.app.features.player.seekpreview

import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamsUiState
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
    /** Recent average time to fetch one keyframe over the network. */
    val avgFetchMs: Long = 0,
    /** Recent average time to decode one keyframe into a thumbnail. */
    val avgDecodeMs: Long = 0,
    /** "hw" or "sw" decoder, or null before the first frame. */
    val decoder: String? = null,
    /** Which stream background fill reads, e.g. "1.4 GB" or "playing". */
    val fillSource: String? = null,
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

/** Another stream of the same title that background fill may read keyframes from. */
internal data class LocalPreviewStreamCandidate(
    val url: String,
    val requestHeaders: Map<String, String>,
    val sizeBytes: Long,
)

/**
 * Streams the addons offered for the title playing, smallest first. Keyframes of a small encode
 * cost a fraction of a remux's, so background fill prefers one whose runtime matches.
 */
internal object LocalSeekPreviewStreams {
    val candidates = MutableStateFlow<List<LocalPreviewStreamCandidate>>(emptyList())
}

/** Direct HTTP streams with a known size, smallest first. */
internal fun StreamsUiState.localPreviewCandidates(): List<LocalPreviewStreamCandidate> =
    groups.asSequence()
        .flatMap { it.streams.asSequence() }
        .mapNotNull { stream ->
            val url = stream.playableDirectUrl ?: return@mapNotNull null
            val lowerUrl = url.lowercase()
            if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) return@mapNotNull null
            val host = lowerUrl.substringAfter("://").substringBefore('/').substringBefore(':')
            if (host == "localhost" || host.startsWith("127.")) return@mapNotNull null
            val path = lowerUrl.substringBefore('?')
            if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return@mapNotNull null
            // Opening an uncached debrid link would make the service start downloading it.
            if (stream.debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED) return@mapNotNull null
            val text = listOfNotNull(stream.name, stream.title, stream.description).joinToString(" ").lowercase()
            if ("download" in text) return@mapNotNull null
            val size = stream.clientResolve?.stream?.raw?.size
                ?: stream.behaviorHints.videoSize
                ?: stream.debridCacheStatus?.cachedSize
                ?: return@mapNotNull null
            // Samples and trailers are tiny but useless.
            if (size < MIN_CANDIDATE_BYTES) return@mapNotNull null
            LocalPreviewStreamCandidate(url, stream.behaviorHints.proxyHeaders?.request.orEmpty(), size)
        }
        .distinctBy { it.url }
        .sortedBy { it.sizeBytes }
        .take(MAX_CANDIDATES)
        .toList()

private const val MIN_CANDIDATE_BYTES = 150L * 1_000_000L
private const val MAX_CANDIDATES = 6
