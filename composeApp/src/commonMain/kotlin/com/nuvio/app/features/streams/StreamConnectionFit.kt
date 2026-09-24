package com.nuvio.app.features.streams

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.parseRuntimeMinutes

/**
 * Moves streams the current connection can't sustain to the end of their list.
 *
 * Demote-only and stable: nothing is promoted on a guess, streams whose bitrate can't be known
 * (no size or runtime) keep their place, and addon or user sort order is otherwise untouched.
 * Built once per stream load from a snapshot, so the order never shifts while a list is open.
 */
internal class StreamConnectionFit(
    private val runtimeMinutes: Int,
    private val connectionMbps: Double,
) {
    fun apply(group: AddonStreamGroup): AddonStreamGroup {
        val streams = apply(group.streams)
        return if (streams === group.streams) group else group.copy(streams = streams)
    }

    fun apply(streams: List<StreamItem>): List<StreamItem> {
        if (streams.size < 2) return streams
        val (fitting, exceeding) = streams.partition { !exceedsConnection(it) }
        return if (exceeding.isEmpty() || fitting.isEmpty()) streams else fitting + exceeding
    }

    internal fun exceedsConnection(stream: StreamItem): Boolean {
        val bitrateMbps = stream.averageBitrateMbps(runtimeMinutes) ?: return false
        return bitrateMbps * BITRATE_HEADROOM > connectionMbps
    }

    companion object {
        /** Bitrate peaks run well above a file's average; the player buffer only absorbs part of that. */
        private const val BITRATE_HEADROOM = 1.5

        /** Returns null, leaving order untouched, when disabled or when speed or runtime is unknown. */
        fun capture(
            type: String,
            videoId: String,
            parentMetaId: String?,
            season: Int?,
            episode: Int?,
        ): StreamConnectionFit? {
            if (!StreamBadgeSettingsRepository.preferConnectionFitSnapshot()) return null
            val connectionMbps = ConnectionSpeedEstimator.estimateMbps() ?: return null
            val metas = listOfNotNull(
                parentMetaId?.let { MetaDetailsRepository.peek(type, it) },
                MetaDetailsRepository.peek(type, videoId),
                MetaDetailsRepository.uiState.value.meta,
            )
            val runtimeMinutes = metas.firstNotNullOfOrNull { meta ->
                meta.runtimeMinutesFor(videoId, season, episode)
            } ?: return null
            return StreamConnectionFit(runtimeMinutes, connectionMbps)
        }
    }
}

private const val MIN_RUNTIME_MINUTES = 10
private const val MAX_RUNTIME_MINUTES = 600
private const val MIN_SIZE_BYTES = 50L * 1024 * 1024
private const val MIN_PLAUSIBLE_MBPS = 0.2
private const val MAX_PLAUSIBLE_MBPS = 200.0

/**
 * Average bitrate from file size and runtime, or null when either is missing or the result is
 * implausible (for example a season pack's size reported for a single episode).
 */
internal fun StreamItem.averageBitrateMbps(runtimeMinutes: Int): Double? {
    if (runtimeMinutes !in MIN_RUNTIME_MINUTES..MAX_RUNTIME_MINUTES) return null
    val sizeBytes = clientResolve?.stream?.raw?.size ?: behaviorHints.videoSize ?: return null
    if (sizeBytes < MIN_SIZE_BYTES) return null
    val mbps = sizeBytes * 8.0 / (runtimeMinutes * 60.0) / 1_000_000.0
    return mbps.takeIf { it in MIN_PLAUSIBLE_MBPS..MAX_PLAUSIBLE_MBPS }
}

/** Runtime of [videoId], only when this meta is verifiably the same title. */
internal fun MetaDetails.runtimeMinutesFor(videoId: String, season: Int?, episode: Int?): Int? {
    val belongsToTitle = videoId == id || videoId.startsWith("$id:")
    val video = videos.firstOrNull { it.id == videoId }
        ?: videos.takeIf { belongsToTitle && season != null && episode != null }
            ?.firstOrNull { it.season == season && it.episode == episode }
    video?.runtime?.takeIf { it > 0 }?.let { return it }
    return if (video != null || belongsToTitle) parseRuntimeMinutes(runtime) else null
}
