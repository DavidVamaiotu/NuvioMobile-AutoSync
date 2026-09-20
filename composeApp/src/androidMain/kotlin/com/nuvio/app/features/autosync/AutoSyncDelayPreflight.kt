package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToLong

/**
 * Cheap fixed-delay candidate scheduler for AutoSync V2.
 *
 * It never decides final synchronization. Its job is only to:
 *  1) find obvious same-cut constant-offset candidates quickly;
 *  2) rank candidates/references for the authoritative V2 matcher.
 */
internal object AutoSyncDelayPreflight {
    private const val SAMPLE_CUES = 36
    private const val OFFSET_BIN_MS = 500L
    private const val MAX_OFFSET_MS = 180_000L
    private const val MAX_OFFSET_CANDIDATES = 12
    private const val MATCH_TOLERANCE_MS = 1_800L
    private const val DISTINCT_OFFSET_MS = 3_000L

    // Deliberately strict: a "really good" preflight result still receives one authoritative
    // V2 delay-only validation before AutoSync returns it.
    private const val REALLY_GOOD_SCORE = 0.95
    private const val REALLY_GOOD_PARTICIPATION = 0.92
    private const val REALLY_GOOD_MAX_RESIDUAL_MS = 180.0
    private const val REALLY_GOOD_MARGIN = 0.08

    internal data class Match(
        val referenceKey: String,
        val offsetMs: Long,
        val score: Double,
        val margin: Double,
        val participation: Double,
        val meanResidualMs: Double,
    )

    internal fun isReallyGood(match: Match): Boolean =
        match.score >= REALLY_GOOD_SCORE &&
            match.participation >= REALLY_GOOD_PARTICIPATION &&
            match.meanResidualMs <= REALLY_GOOD_MAX_RESIDUAL_MS &&
            match.margin >= REALLY_GOOD_MARGIN

    internal fun bestMatch(
        referenceTracks: List<ReferenceTrack>,
        target: List<SubtitleSyncCue>,
    ): Match? {
        var best: Match? = null
        for (track in referenceTracks) {
            val candidate = matchReference(track, target) ?: continue
            val current = best
            if (
                current == null ||
                candidate.score > current.score ||
                (
                    candidate.score == current.score &&
                        candidate.margin > current.margin
                    ) ||
                (
                    candidate.score == current.score &&
                        candidate.margin == current.margin &&
                        candidate.participation > current.participation
                    ) ||
                (
                    candidate.score == current.score &&
                        candidate.margin == current.margin &&
                        candidate.participation == current.participation &&
                        candidate.meanResidualMs < current.meanResidualMs
                    )
            ) {
                best = candidate
            }
        }
        return best
    }

    private fun matchReference(
        track: ReferenceTrack,
        target: List<SubtitleSyncCue>,
    ): Match? {
        val reference = track.cues
        if (reference.size < 4 || target.size < 4) return null

        val referenceSample = evenlySample(reference, SAMPLE_CUES)
        val targetSample = evenlySample(target, SAMPLE_CUES)
        if (referenceSample.isEmpty() || targetSample.isEmpty()) return null

        val votes = HashMap<Long, Int>()
        for (targetCue in targetSample) {
            for (referenceCue in referenceSample) {
                val rawOffset = referenceCue.startTimeMs - targetCue.startTimeMs
                if (abs(rawOffset) > MAX_OFFSET_MS) continue
                val bin =
                    (rawOffset.toDouble() / OFFSET_BIN_MS.toDouble()).roundToLong() *
                        OFFSET_BIN_MS
                votes[bin] = (votes[bin] ?: 0) + 1
            }
        }
        if (votes.isEmpty()) return null

        val candidates = votes.entries
            .sortedWith(
                compareByDescending<Map.Entry<Long, Int>> { it.value }
                    .thenBy { abs(it.key) },
            )
            .take(MAX_OFFSET_CANDIDATES)
            .map { it.key }
            .toMutableList()
        if (0L !in candidates) candidates += 0L

        val scored = ArrayList<OffsetScore>(candidates.size)
        for (offset in candidates) {
            val first = scoreOffset(reference, targetSample, offset) ?: continue
            val refinedOffset = offset + median(first.signedResiduals)
            val refined = scoreOffset(reference, targetSample, refinedOffset) ?: first
            scored += refined
        }
        if (scored.isEmpty()) return null

        val best = scored.maxWithOrNull(
            compareBy<OffsetScore> { it.score }
                .thenBy { it.participation }
                .thenByDescending { it.meanResidualMs },
        ) ?: return null

        val second = scored.asSequence()
            .filter { abs(it.offsetMs - best.offsetMs) >= DISTINCT_OFFSET_MS }
            .maxByOrNull { it.score }
        val margin = best.score - (second?.score ?: 0.0)

        return Match(
            referenceKey = track.key,
            offsetMs = best.offsetMs,
            score = best.score,
            margin = margin,
            participation = best.participation,
            meanResidualMs = best.meanResidualMs,
        )
    }

    private fun scoreOffset(
        reference: List<SubtitleSyncCue>,
        targetSample: List<SubtitleSyncCue>,
        offsetMs: Long,
    ): OffsetScore? {
        var hits = 0
        var residualTotal = 0.0
        val signedResiduals = ArrayList<Long>(targetSample.size)

        for (cue in targetSample) {
            val shifted = cue.startTimeMs + offsetMs
            val insertion = lowerBound(reference, shifted)

            var nearestSigned: Long? = null
            if (insertion < reference.size) {
                nearestSigned = reference[insertion].startTimeMs - shifted
            }
            if (insertion > 0) {
                val previous = reference[insertion - 1].startTimeMs - shifted
                if (nearestSigned == null || abs(previous) < abs(nearestSigned)) {
                    nearestSigned = previous
                }
            }

            val signed = nearestSigned ?: continue
            val residual = abs(signed)
            if (residual > MATCH_TOLERANCE_MS) continue

            hits++
            residualTotal += residual.toDouble()
            signedResiduals += signed
        }

        if (hits == 0) return null
        val participation = hits.toDouble() / targetSample.size.toDouble()
        val meanResidual = residualTotal / hits.toDouble()
        val residualScore = exp(-meanResidual / 450.0)
        val score = participation * 0.82 + residualScore * 0.18

        return OffsetScore(
            offsetMs = offsetMs,
            score = score.coerceIn(0.0, 1.0),
            participation = participation,
            meanResidualMs = meanResidual,
            signedResiduals = signedResiduals,
        )
    }

    private fun evenlySample(
        cues: List<SubtitleSyncCue>,
        maxSamples: Int,
    ): List<SubtitleSyncCue> {
        if (cues.size <= maxSamples) return cues
        val lastIndex = cues.lastIndex
        return (0 until maxSamples)
            .map { sampleIndex ->
                cues[(sampleIndex.toLong() * lastIndex / (maxSamples - 1)).toInt()]
            }
            .distinctBy { it.startTimeMs }
    }

    private fun lowerBound(
        cues: List<SubtitleSyncCue>,
        timeMs: Long,
    ): Int {
        var low = 0
        var high = cues.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cues[middle].startTimeMs < timeMs) low = middle + 1 else high = middle
        }
        return low
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            ((sorted[middle - 1] + sorted[middle]) / 2.0).roundToLong()
        }
    }

    private data class OffsetScore(
        val offsetMs: Long,
        val score: Double,
        val participation: Double,
        val meanResidualMs: Double,
        val signedResiduals: List<Long>,
    )
}
