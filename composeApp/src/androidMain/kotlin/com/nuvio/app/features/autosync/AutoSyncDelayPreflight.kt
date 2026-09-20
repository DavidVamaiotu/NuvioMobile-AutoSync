package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue

/**
 * Scheduling-only fixed-delay preflight for AutoSync V2.
 *
 * There is deliberately no independent scoring model here. Every score, margin and offset comes
 * from AutoSyncTimelineRetimer's real V2 delay-only validator. This object only ranks completed
 * downloads and carries the winning V2 alignment forward into the authoritative full matcher.
 */
internal object AutoSyncDelayPreflight {
    // Scheduling threshold only. It never authorizes a subtitle by itself; authoritative V2
    // still has to pass its existing strong/exceptional gates before an early return.
    private const val REALLY_GOOD_SCORE = 0.90
    private const val REALLY_GOOD_MARGIN = 0.04

    internal data class Match(
        val referenceKey: String,
        val alignment: AutoSyncDelayOnlyAlignment,
    ) {
        val offsetMs: Double get() = alignment.offsetMs
        val score: Double get() = alignment.score
        val margin: Double get() = alignment.margin
        val segmentsPassed: Int get() = alignment.segmentsPassed
    }

    internal fun isReallyGood(match: Match): Boolean =
        match.score >= REALLY_GOOD_SCORE &&
            match.margin >= REALLY_GOOD_MARGIN &&
            match.segmentsPassed >= 3

    internal fun bestMatch(
        referenceTracks: List<ReferenceTrack>,
        target: List<SubtitleSyncCue>,
        referenceActivityCache: MutableMap<String, AutoSyncTimelineRetimer.PreparedActivity?>,
    ): Match? {
        val targetActivity =
            AutoSyncTimelineRetimer.prepareUnitActivity(target) ?: return null

        var best: Match? = null

        for (track in referenceTracks) {
            val preparedReference = synchronized(referenceActivityCache) {
                if (referenceActivityCache.containsKey(track.key)) {
                    referenceActivityCache[track.key]
                } else {
                    val prepared =
                        AutoSyncTimelineRetimer.prepareUnitActivity(track.cues)
                    referenceActivityCache[track.key] = prepared
                    prepared
                }
            } ?: continue

            // Match the exact delay-margin relaxation used by authoritative V2.
            val overSegmentedReference =
                track.cues.size.toLong() * 2L >= target.size.toLong() * 3L
            val relaxDelayMargin =
                AutomaticSubtitleSync.isSdhReferenceTrack(track) ||
                    overSegmentedReference

            val alignment =
                AutoSyncTimelineRetimer.findDelayOnlyAlignmentPrepared(
                    referenceActivity = preparedReference,
                    targetActivity = targetActivity,
                    targetSize = target.size,
                    allowAmbiguousMargin = relaxDelayMargin,
                ) ?: continue

            val candidate = Match(
                referenceKey = track.key,
                alignment = alignment,
            )

            val currentBest = best
            if (
                currentBest == null ||
                candidate.score > currentBest.score ||
                (
                    candidate.score == currentBest.score &&
                        candidate.margin > currentBest.margin
                    ) ||
                (
                    candidate.score == currentBest.score &&
                        candidate.margin == currentBest.margin &&
                        candidate.segmentsPassed > currentBest.segmentsPassed
                    )
            ) {
                best = candidate
            }
        }

        return best
    }
}
