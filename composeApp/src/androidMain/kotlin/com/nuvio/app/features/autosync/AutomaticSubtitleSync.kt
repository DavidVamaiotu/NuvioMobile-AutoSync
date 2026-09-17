package com.nuvio.app.features.autosync

import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.SUBTITLE_DELAY_MAX_MS
import com.nuvio.app.features.player.SUBTITLE_DELAY_MIN_MS
import com.nuvio.app.features.player.SubtitleSyncCue
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Minimal Android-only automatic subtitle sync.
 *
 * The external subtitle is parsed by Nuvio's existing [PlayerSubtitleCueParser]. Embedded cue
 * timestamps are observed by [AutoSyncExtractorsFactory]. Only a single global delay is applied.
 */
internal object AutomaticSubtitleSync {
    private const val POLL_INTERVAL_MS = 750L
    private const val MAX_WAIT_MS = 30_000L
    private const val MIN_REFERENCE_CUES = 4
    private const val MIN_REFERENCE_SPAN_MS = 12_000L
    private const val MAX_OFFSET_MS = 60_000L
    private const val CANDIDATE_BUCKET_MS = 250L
    private const val MATCH_TOLERANCE_MS = 850L
    private const val STRONG_RESIDUAL_MS = 250L

    suspend fun findDelayCorrectionMs(
        sourceKey: String,
        subtitleUrl: String,
        subtitleHeaders: Map<String, String>,
        preferredLanguage: String?,
        onReferenceReady: () -> Unit = {},
    ): Int? {
        val addonCues = runCatching {
            PlayerSubtitleCueParser.parse(
                text = httpGetTextWithHeaders(subtitleUrl, subtitleHeaders),
                sourceUrl = subtitleUrl,
            )
        }.getOrDefault(emptyList())
        if (addonCues.size < MIN_REFERENCE_CUES) return null

        var waitedMs = 0L
        var lastReferenceSize = -1
        var referenceReadyNotified = false
        while (waitedMs <= MAX_WAIT_MS) {
            val reference = EmbeddedSubtitleCueStore.bestTrack(sourceKey, preferredLanguage)
            if (reference.size >= MIN_REFERENCE_CUES &&
                reference.last().startTimeMs - reference.first().startTimeMs >= MIN_REFERENCE_SPAN_MS &&
                reference.size != lastReferenceSize
            ) {
                lastReferenceSize = reference.size
                if (!referenceReadyNotified) {
                    referenceReadyNotified = true
                    onReferenceReady()
                }
                val result = align(reference, addonCues)
                if (result != null) {
                    return result.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
                }
            }
            delay(POLL_INTERVAL_MS)
            waitedMs += POLL_INTERVAL_MS
        }
        return null
    }

    private fun align(reference: List<SubtitleSyncCue>, target: List<SubtitleSyncCue>): Int? {
        val candidates = candidateOffsets(reference, target)
        if (candidates.isEmpty()) return null
        val evaluations = candidates.map { evaluate(reference, target, it) }
            .sortedByDescending { it.score }
        val best = evaluations.firstOrNull() ?: return null
        val second = evaluations.firstOrNull { abs(it.offsetMs - best.offsetMs) > MATCH_TOLERANCE_MS * 2L }
        val margin = if (second == null) 1.0 else {
            ((best.score - second.score) / max(best.score, 0.001)).coerceIn(0.0, 1.0)
        }

        val referenceParticipation = best.matches.toDouble() / reference.size
        val highConfidence = best.matches >= MIN_REFERENCE_CUES &&
            referenceParticipation >= 0.70 &&
            best.referenceCoverage >= 0.55 &&
            best.residualMs <= STRONG_RESIDUAL_MS &&
            best.offsetAgreement >= 0.75 &&
            best.spacingScore >= 0.70 &&
            margin >= 0.02

        return best.offsetMs.toInt().takeIf { highConfidence }
    }

    private fun candidateOffsets(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
    ): List<Long> {
        val buckets = mutableMapOf<Long, Int>()
        for (referenceCue in reference.take(24)) {
            for (targetCue in target) {
                val difference = referenceCue.startTimeMs - targetCue.startTimeMs
                if (abs(difference) > MAX_OFFSET_MS) continue
                val bucket = floorBucket(difference, CANDIDATE_BUCKET_MS)
                buckets[bucket] = (buckets[bucket] ?: 0) + 1
            }
        }
        return (buckets.entries
            .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { abs(it.key) })
            .take(16)
            .map { it.key } + 0L).distinct()
    }

    private fun evaluate(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        offsetMs: Long,
    ): Evaluation {
        val residuals = mutableListOf<Long>()
        val referenceIndexes = mutableListOf<Int>()
        val targetIndexes = mutableListOf<Int>()
        var targetIndex = 0

        for (referenceIndex in reference.indices) {
            val referenceStart = reference[referenceIndex].startTimeMs
            while (targetIndex < target.size &&
                target[targetIndex].startTimeMs + offsetMs < referenceStart - MATCH_TOLERANCE_MS
            ) {
                targetIndex++
            }
            if (targetIndex >= target.size) break

            var bestIndex = targetIndex
            var bestResidual = referenceStart - (target[targetIndex].startTimeMs + offsetMs)
            if (targetIndex + 1 < target.size) {
                val nextResidual = referenceStart - (target[targetIndex + 1].startTimeMs + offsetMs)
                if (abs(nextResidual) < abs(bestResidual)) {
                    bestIndex = targetIndex + 1
                    bestResidual = nextResidual
                }
            }
            if (abs(bestResidual) <= MATCH_TOLERANCE_MS) {
                residuals += bestResidual
                referenceIndexes += referenceIndex
                targetIndexes += bestIndex
                targetIndex = bestIndex + 1
            }
        }

        if (residuals.isEmpty()) return Evaluation(offsetMs, 0, 0.0, Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0)
        val residualMs = median(residuals.map { abs(it).toDouble() })
        val agreement = residuals.count { abs(it) <= STRONG_RESIDUAL_MS }.toDouble() / residuals.size
        val referenceCoverage = if (referenceIndexes.size < 2) 0.0 else {
            val matchedSpan = reference[referenceIndexes.last()].startTimeMs - reference[referenceIndexes.first()].startTimeMs
            matchedSpan.toDouble() / max(reference.last().startTimeMs - reference.first().startTimeMs, 1L)
        }.coerceIn(0.0, 1.0)
        val spacingScore = spacingScore(reference, target, referenceIndexes, targetIndexes)
        val participation = residuals.size.toDouble() / reference.size
        val residualScore = exp(-residualMs / 500.0)
        val score = (
            participation * 0.35 +
                referenceCoverage * 0.25 +
                residualScore * 0.20 +
                agreement * 0.10 +
                spacingScore * 0.10
            ).coerceIn(0.0, 1.0)

        return Evaluation(
            offsetMs = offsetMs,
            matches = residuals.size,
            referenceCoverage = referenceCoverage,
            residualMs = residualMs,
            offsetAgreement = agreement,
            spacingScore = spacingScore,
            score = score,
        )
    }

    private fun spacingScore(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        referenceIndexes: List<Int>,
        targetIndexes: List<Int>,
    ): Double {
        if (referenceIndexes.size < 3) return if (referenceIndexes.isEmpty()) 0.0 else 0.5
        val errors = (0 until referenceIndexes.lastIndex).map { index ->
            val referenceGap = reference[referenceIndexes[index + 1]].startTimeMs - reference[referenceIndexes[index]].startTimeMs
            val targetGap = target[targetIndexes[index + 1]].startTimeMs - target[targetIndexes[index]].startTimeMs
            abs(referenceGap - targetGap).toDouble()
        }
        return exp(-median(errors) / 2_000.0).coerceIn(0.0, 1.0)
    }

    private fun floorBucket(value: Long, bucketSize: Long): Long {
        val quotient = value / bucketSize
        val remainder = value % bucketSize
        return if (remainder < 0L) (quotient - 1L) * bucketSize else quotient * bucketSize
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.POSITIVE_INFINITY
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private data class Evaluation(
        val offsetMs: Long,
        val matches: Int,
        val referenceCoverage: Double,
        val residualMs: Double,
        val offsetAgreement: Double,
        val spacingScore: Double,
        val score: Double,
    )
}

/** Thread-safe accumulation of the embedded text timing already passing through Media3. */
internal object EmbeddedSubtitleCueStore {
    private data class Track(
        var language: String?,
        val cues: LinkedHashMap<String, SubtitleSyncCue> = linkedMapOf(),
    )

    private val lock = Any()
    private val sources = mutableMapOf<String, MutableMap<String, Track>>()

    fun reset(sourceKey: String) {
        if (sourceKey.isBlank()) return
        synchronized(lock) {
            sources.clear()
            sources[sourceKey] = linkedMapOf()
        }
    }

    fun record(
        sourceKey: String,
        trackKey: String,
        language: String?,
        cue: SubtitleSyncCue,
    ) {
        if (sourceKey.isBlank() || trackKey.isBlank()) return
        synchronized(lock) {
            val track = sources.getOrPut(sourceKey) { linkedMapOf() }
                .getOrPut(trackKey) { Track(language) }
            track.language = track.language ?: language
            track.cues["${cue.startTimeMs}:${cue.endTimeMs}:${cue.text}"] = cue
        }
    }

    fun bestTrack(sourceKey: String, preferredLanguage: String?): List<SubtitleSyncCue> =
        synchronized(lock) {
            val preferred = preferredLanguage?.trim()?.lowercase().orEmpty()
            sources[sourceKey].orEmpty().values
                .filter { it.cues.size >= 3 }
                .maxWithOrNull(
                    compareBy<Track> { languageRank(it.language, preferred) }
                        .thenBy { it.cues.size },
                )
                ?.cues
                ?.values
                ?.sortedBy { it.startTimeMs }
                .orEmpty()
        }

    private fun languageRank(language: String?, preferred: String): Int {
        val normalized = language?.trim()?.lowercase().orEmpty()
        return when {
            preferred.isNotBlank() && normalized == preferred -> 3
            normalized == "en" || normalized.startsWith("en-") -> 2
            normalized.isNotBlank() -> 1
            else -> 0
        }
    }
}
