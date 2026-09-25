package com.nuvio.app.features.player.seekpreview

import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamItem
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
    /** Keyframes being fetched at once. */
    val workers: Int = 0,
    /** Playback stalls that made fill slow down. */
    val stalls: Int = 0,
    /** Playback is buffering right now; fill runs one worker meanwhile. */
    val buffering: Boolean = false,
    /** Network or parse errors fill recovered from, and the latest one's message. */
    val readErrors: Int = 0,
    val lastError: String? = null,
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
internal class LocalPreviewStreamCandidate(
    /** Direct URL when the addon gave one; debrid streams get theirs from [resolveUrl]. */
    val url: String?,
    val requestHeaders: Map<String, String>,
    val sizeBytes: Long,
    /** Asks the debrid service for a playable link; only for streams it reports cached. */
    val resolveUrl: (suspend () -> String?)? = null,
)

/**
 * Streams the addons offered for the title playing, smallest first. Keyframes of a small encode
 * cost a fraction of a remux's, so background fill prefers one whose runtime matches.
 */
internal object LocalSeekPreviewStreams {
    val candidates = MutableStateFlow<List<LocalPreviewStreamCandidate>>(emptyList())
    /** Why streams were left out, for the debug readout, e.g. "12 streams, 3 no size". */
    val summary = MutableStateFlow<String?>(null)
}

/** Usable streams with a known size, smallest first, plus a note on what was left out. */
internal fun StreamsUiState.localPreviewCandidates(
    season: Int?,
    episode: Int?,
): Pair<List<LocalPreviewStreamCandidate>, String> {
    val all = groups.flatMap { it.streams }
    var noLink = 0
    var noSize = 0
    var uncached = 0
    var other = 0
    val usable = all.mapNotNull { stream ->
        if (stream.debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED || stream.looksUncached()) {
            // Opening an uncached debrid link would make the service start downloading it.
            uncached++
            return@mapNotNull null
        }
        val size = stream.clientResolve?.stream?.raw?.size
            ?: stream.behaviorHints.videoSize
            ?: stream.debridCacheStatus?.cachedSize
            ?: stream.sizeFromText()
        if (size == null) {
            noSize++
            return@mapNotNull null
        }
        // Samples and trailers are tiny but useless.
        if (size < MIN_CANDIDATE_BYTES) {
            other++
            return@mapNotNull null
        }
        val headers = stream.behaviorHints.proxyHeaders?.request.orEmpty()
        val url = stream.playableDirectUrl
        when {
            url != null -> {
                if (!url.isUsableHttpStream()) {
                    other++
                    null
                } else {
                    LocalPreviewStreamCandidate(url, headers, size)
                }
            }
            stream.isAddonDebridCandidate && (stream.isDirectDebridStream || stream.isCachedDebridTorrentStream) ->
                LocalPreviewStreamCandidate(null, headers, size, resolveUrl = {
                    (DirectDebridPlaybackResolver.resolveToPlayableStream(stream, season, episode) as? DirectDebridPlayableResult.Success)
                        ?.stream?.playableDirectUrl
                        ?.takeIf { it.isUsableHttpStream() }
                })
            else -> {
                noLink++
                null
            }
        }
    }
        .distinctBy { it.url ?: it.hashCode().toString() }
        .sortedBy { it.sizeBytes }
        .take(MAX_CANDIDATES)
    val summary = buildString {
        append(all.size).append(" streams")
        if (noSize > 0) append(", ").append(noSize).append(" no size")
        if (noLink > 0) append(", ").append(noLink).append(" no link")
        if (uncached > 0) append(", ").append(uncached).append(" uncached")
        if (other > 0) append(", ").append(other).append(" other")
    }
    return usable to summary
}

private fun String.isUsableHttpStream(): Boolean {
    val lower = lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    val host = lower.substringAfter("://").substringBefore('/').substringBefore(':')
    if (host == "localhost" || host.startsWith("127.")) return false
    val path = lower.substringBefore('?')
    return !path.endsWith(".m3u8") && !path.endsWith(".mpd")
}

private val UncachedMarker = Regex("""\[[^\]]*download[^\]]*]|uncached|⏳""", RegexOption.IGNORE_CASE)
private val SizeInText = Regex("""(\d+(?:[.,]\d+)?)\s*(TB|GB|GiB|MB|MiB)\b""", RegexOption.IGNORE_CASE)

/** Addon markers like "[RD download]" for torrents the debrid service has not cached. */
private fun StreamItem.looksUncached(): Boolean =
    listOfNotNull(name, title, description).any { UncachedMarker.containsMatchIn(it) }

/** Many addons only print the size, e.g. "💾 2.3 GB"; take the first one mentioned. */
private fun StreamItem.sizeFromText(): Long? {
    val match = listOfNotNull(title, description, name)
        .firstNotNullOfOrNull { SizeInText.find(it) } ?: return null
    val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    val multiplier = when (match.groupValues[2].uppercase()) {
        "TB" -> 1e12
        "GB" -> 1e9
        "GIB" -> 1_073_741_824.0
        "MIB" -> 1_048_576.0
        else -> 1e6
    }
    return (value * multiplier).toLong()
}

private const val MIN_CANDIDATE_BYTES = 150L * 1_000_000L
private const val MAX_CANDIDATES = 8
