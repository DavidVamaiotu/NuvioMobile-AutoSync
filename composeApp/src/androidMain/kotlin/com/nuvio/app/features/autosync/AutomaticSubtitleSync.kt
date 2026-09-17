package com.nuvio.app.features.autosync

import android.os.SystemClock
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.SUBTITLE_DELAY_MAX_MS
import com.nuvio.app.features.player.SUBTITLE_DELAY_MIN_MS
import com.nuvio.app.features.player.SubtitleSyncCue
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Android-only automatic subtitle sync.
 *
 * External/add-on subtitles are parsed using Nuvio's existing [PlayerSubtitleCueParser].
 * Embedded timestamps/text are observed by [AutoSyncExtractorsFactory].
 *
 * The matcher is text-independent so different subtitle languages can still synchronize.
 * Verbose debugging DOES log both embedded and add-on cue text so a human can verify that
 * the timing pairs correspond to the same scene/dialogue.
 */
internal object AutomaticSubtitleSync {
    private const val POLL_INTERVAL_MS = 750L
    private const val MAX_WAIT_MS = 45_000L
    private const val MIN_REFERENCE_CUES = 4
    private const val MIN_REFERENCE_SPAN_MS = 8_000L

    private const val MAX_OFFSET_MS = 120_000L
    private const val CANDIDATE_BUCKET_MS = 500L
    private const val MATCH_TOLERANCE_MS = 1_800L
    private const val STRONG_RESIDUAL_MS = 750L

    private const val MAX_LOGGED_CUE_SAMPLES = 20
    private const val MAX_LOGGED_CANDIDATES = 20
    private const val MAX_LOGGED_MATCH_PAIRS = 50

    suspend fun findDelayCorrectionMs(
        sourceKey: String,
        subtitleUrl: String,
        subtitleHeaders: Map<String, String>,
        preferredLanguage: String?,
        onReferenceReady: () -> Unit = {},
    ): Int? {
        AutoSyncDebugLog.start(
            sourceKey = sourceKey,
            subtitleUrl = subtitleUrl,
        )

        AutoSyncDebugLog.section("ADD-ON SUBTITLE")
        AutoSyncDebugLog.info("preferredLanguage=${preferredLanguage ?: "<none>"}")
        AutoSyncDebugLog.info("headers=${subtitleHeaders.keys.sorted().joinToString(",").ifBlank { "<none>" }}")
        AutoSyncDebugLog.info("header values intentionally not logged")

        val downloadStarted = SystemClock.elapsedRealtime()
        val subtitleText = runCatching {
            httpGetTextWithHeaders(subtitleUrl, subtitleHeaders)
        }.getOrElse { error ->
            AutoSyncDebugLog.error("add-on download failed", error)
            return null
        }
        AutoSyncDebugLog.info(
            "download=OK chars=${subtitleText.length} elapsed=${SystemClock.elapsedRealtime() - downloadStarted}ms",
        )

        val parseStarted = SystemClock.elapsedRealtime()
        val addonCues = runCatching {
            PlayerSubtitleCueParser.parse(
                text = subtitleText,
                sourceUrl = subtitleUrl,
            )
        }.getOrElse { error ->
            AutoSyncDebugLog.error("add-on parse failed", error)
            return null
        }

        AutoSyncDebugLog.info(
            "parse=OK cues=${addonCues.size} elapsed=${SystemClock.elapsedRealtime() - parseStarted}ms",
        )

        if (addonCues.isNotEmpty()) {
            AutoSyncDebugLog.info(
                "addon span=${AutoSyncDebugLog.formatTimestamp(addonCues.first().startTimeMs)}.." +
                    AutoSyncDebugLog.formatTimestamp(addonCues.last().endTimeMs),
            )
        }

        addonCues
            .take(MAX_LOGGED_CUE_SAMPLES)
            .forEachIndexed { index, cue ->
                AutoSyncDebugLog.cue(
                    prefix = "ADDON",
                    index = index,
                    startMs = cue.startTimeMs,
                    endMs = cue.endTimeMs,
                    text = cue.text,
                )
            }

        if (addonCues.size < MIN_REFERENCE_CUES) {
            AutoSyncDebugLog.warn(
                "REJECT add-on cue count ${addonCues.size} < required $MIN_REFERENCE_CUES",
            )
            return null
        }

        var waitedMs = 0L
        var lastReferenceSignature = ""
        var referenceReadyNotified = false
        var attempt = 0

        while (waitedMs <= MAX_WAIT_MS) {
            val allTracks = EmbeddedSubtitleCueStore.candidateTracks(
                sourceKey = sourceKey,
                preferredLanguage = preferredLanguage,
            )

            val usableTracks = allTracks.filter { track ->
                track.cues.size >= MIN_REFERENCE_CUES &&
                    track.cues.last().startTimeMs - track.cues.first().startTimeMs >= MIN_REFERENCE_SPAN_MS
            }

            val signature = allTracks.joinToString("|") { track ->
                "${track.key}:${track.cues.size}:${track.cues.lastOrNull()?.startTimeMs ?: -1L}"
            }

            if (signature != lastReferenceSignature) {
                lastReferenceSignature = signature
                attempt++

                AutoSyncDebugLog.section("REFERENCE SNAPSHOT #$attempt")
                AutoSyncDebugLog.info(
                    "waited=${waitedMs}ms tracks=${allTracks.size} usable=${usableTracks.size}",
                )

                if (allTracks.isEmpty()) {
                    AutoSyncDebugLog.info("no embedded text tracks captured yet")
                }

                allTracks.forEachIndexed { trackIndex, track ->
                    val span = if (track.cues.size >= 2) {
                        track.cues.last().startTimeMs - track.cues.first().startTimeMs
                    } else {
                        0L
                    }

                    AutoSyncDebugLog.info(
                        "track[$trackIndex] key=${track.key} lang=${track.language ?: "<unknown>"} " +
                            "cues=${track.cues.size} span=${span}ms " +
                            "usable=${track in usableTracks}",
                    )

                    track.cues
                        .take(MAX_LOGGED_CUE_SAMPLES)
                        .forEachIndexed { cueIndex, cue ->
                            AutoSyncDebugLog.cue(
                                prefix = "EMBEDDED ${track.key}",
                                index = cueIndex,
                                startMs = cue.startTimeMs,
                                endMs = cue.endTimeMs,
                                text = cue.text,
                            )
                        }
                }
            }

            if (usableTracks.isNotEmpty()) {
                if (!referenceReadyNotified) {
                    referenceReadyNotified = true
                    onReferenceReady()
                }

                val alignmentResults = usableTracks.mapNotNull { track ->
                    align(
                        track = track,
                        target = addonCues,
                    )
                }

                val best = alignmentResults.maxByOrNull { it.score }

                if (best != null) {
                    AutoSyncDebugLog.section("FINAL MATCH")
                    AutoSyncDebugLog.info(
                        "selected track=${best.trackKey} lang=${best.language ?: "<unknown>"}",
                    )
                    AutoSyncDebugLog.info(
                        "correction=${best.offsetMs}ms score=${"%.4f".format(best.score)}",
                    )

                    return best.offsetMs
                        .toInt()
                        .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
                }

                AutoSyncDebugLog.info("no track passed confidence checks; waiting for more cues")
            }

            delay(POLL_INTERVAL_MS)
            waitedMs += POLL_INTERVAL_MS
        }

        AutoSyncDebugLog.section("TIMEOUT")
        AutoSyncDebugLog.warn("no reliable alignment after ${MAX_WAIT_MS}ms")
        return null
    }

    private fun align(
        track: ReferenceTrack,
        target: List<SubtitleSyncCue>,
    ): AlignmentResult? {
        val reference = track.cues

        AutoSyncDebugLog.section(
            "ALIGN track=${track.key} lang=${track.language ?: "<unknown>"}",
        )

        val candidates = candidateOffsets(reference, target)
        if (candidates.isEmpty()) {
            AutoSyncDebugLog.warn("no candidate offsets")
            return null
        }

        AutoSyncDebugLog.info("candidate offsets=${candidates.size}")
        candidates
            .take(MAX_LOGGED_CANDIDATES)
            .forEachIndexed { index, candidate ->
                AutoSyncDebugLog.verbose(
                    "CANDIDATE[$index] offset=${candidate.offsetMs}ms votes=${candidate.votes}",
                )
            }

        val evaluations = buildList {
            for (candidate in candidates) {
                val initial = evaluate(reference, target, candidate.offsetMs)
                add(initial)

                if (initial.matches >= MIN_REFERENCE_CUES) {
                    val refinedOffset =
                        candidate.offsetMs + initial.signedResidualMs.roundToLong()

                    if (
                        refinedOffset != candidate.offsetMs &&
                        abs(refinedOffset) <= MAX_OFFSET_MS
                    ) {
                        AutoSyncDebugLog.verbose(
                            "REFINE ${candidate.offsetMs}ms -> ${refinedOffset}ms " +
                                "using median signed residual=${"%.1f".format(initial.signedResidualMs)}ms",
                        )
                        add(evaluate(reference, target, refinedOffset))
                    }
                }
            }
        }.sortedByDescending { it.score }

        val best = evaluations.firstOrNull() ?: return null
        val second = evaluations.firstOrNull {
            abs(it.offsetMs - best.offsetMs) > MATCH_TOLERANCE_MS * 2L
        }

        val margin = if (second == null) {
            1.0
        } else {
            ((best.score - second.score) / max(best.score, 0.001))
                .coerceIn(0.0, 1.0)
        }

        val referenceParticipation = best.matches.toDouble() / reference.size

        val strongPattern =
            best.matches >= 7 &&
                best.residualMs <= 500.0 &&
                best.spacingScore >= 0.62

        val checks = listOf(
            ConfidenceCheck(
                "matches",
                best.matches >= MIN_REFERENCE_CUES,
                "${best.matches} >= $MIN_REFERENCE_CUES",
            ),
            ConfidenceCheck(
                "participation",
                referenceParticipation >= 0.55,
                "${fmt(referenceParticipation)} >= 0.5500",
            ),
            ConfidenceCheck(
                "coverage",
                best.referenceCoverage >= 0.35,
                "${fmt(best.referenceCoverage)} >= 0.3500",
            ),
            ConfidenceCheck(
                "median residual",
                best.residualMs <= 700.0,
                "${"%.1f".format(best.residualMs)}ms <= 700ms",
            ),
            ConfidenceCheck(
                "offset agreement",
                best.offsetAgreement >= 0.55,
                "${fmt(best.offsetAgreement)} >= 0.5500",
            ),
            ConfidenceCheck(
                "spacing",
                best.spacingScore >= 0.50,
                "${fmt(best.spacingScore)} >= 0.5000",
            ),
            ConfidenceCheck(
                "score",
                best.score >= 0.58,
                "${fmt(best.score)} >= 0.5800",
            ),
            ConfidenceCheck(
                "margin OR strong pattern",
                margin >= 0.008 || strongPattern,
                "margin=${fmt(margin)} strongPattern=$strongPattern",
            ),
        )

        val highConfidence = checks.all { it.passed }

        AutoSyncDebugLog.info(
            "BEST offset=${best.offsetMs}ms matches=${best.matches}/${reference.size} " +
                "participation=${fmt(referenceParticipation)} coverage=${fmt(best.referenceCoverage)} " +
                "medianResidual=${"%.1f".format(best.residualMs)}ms " +
                "signedResidual=${"%.1f".format(best.signedResidualMs)}ms " +
                "agreement=${fmt(best.offsetAgreement)} spacing=${fmt(best.spacingScore)} " +
                "score=${fmt(best.score)}",
        )

        if (second != null) {
            AutoSyncDebugLog.info(
                "SECOND offset=${second.offsetMs}ms matches=${second.matches} " +
                    "score=${fmt(second.score)} margin=${fmt(margin)}",
            )
        } else {
            AutoSyncDebugLog.info("SECOND <none> margin=1.0000")
        }

        AutoSyncDebugLog.section("BEST MATCHED PAIRS")
        best.pairs
            .take(MAX_LOGGED_MATCH_PAIRS)
            .forEachIndexed { pairIndex, pair ->
                val ref = reference[pair.referenceIndex]
                val addon = target[pair.targetIndex]

                AutoSyncDebugLog.verbose("PAIR[$pairIndex]")
                AutoSyncDebugLog.verbose(
                    "  EMBEDDED ${AutoSyncDebugLog.formatTimestamp(ref.startTimeMs)} " +
                        "| \"${logText(ref.text)}\"",
                )
                AutoSyncDebugLog.verbose(
                    "  ADDON    ${AutoSyncDebugLog.formatTimestamp(addon.startTimeMs)} " +
                        "| \"${logText(addon.text)}\"",
                )
                AutoSyncDebugLog.verbose(
                    "  shifted addon=${AutoSyncDebugLog.formatTimestamp(addon.startTimeMs + best.offsetMs)} " +
                        "residual=${pair.residualMs}ms",
                )
            }

        AutoSyncDebugLog.section("CONFIDENCE")
        checks.forEach { check ->
            AutoSyncDebugLog.info(
                "${if (check.passed) "PASS" else "FAIL"} ${check.name}: ${check.detail}",
            )
        }

        AutoSyncDebugLog.info(
            "DECISION=${if (highConfidence) "ACCEPT" else "REJECT"} track=${track.key}",
        )

        return if (highConfidence) {
            AlignmentResult(
                trackKey = track.key,
                language = track.language,
                offsetMs = best.offsetMs,
                score = best.score,
            )
        } else {
            null
        }
    }

    private fun candidateOffsets(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
    ): List<CandidateOffset> {
        val buckets = mutableMapOf<Long, Int>()

        for (referenceCue in reference.take(40)) {
            for (targetCue in target) {
                val difference = referenceCue.startTimeMs - targetCue.startTimeMs
                if (abs(difference) > MAX_OFFSET_MS) continue

                val bucket = floorBucket(difference, CANDIDATE_BUCKET_MS)
                buckets[bucket] = (buckets[bucket] ?: 0) + 1
            }
        }

        val ranked = buckets.entries
            .sortedWith(
                compareByDescending<Map.Entry<Long, Int>> { it.value }
                    .thenBy { abs(it.key) },
            )
            .take(32)
            .map { CandidateOffset(it.key, it.value) }

        return (ranked + CandidateOffset(0L, buckets[0L] ?: 0))
            .distinctBy { it.offsetMs }
    }

    private fun evaluate(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        offsetMs: Long,
    ): Evaluation {
        val residuals = mutableListOf<Long>()
        val referenceIndexes = mutableListOf<Int>()
        val targetIndexes = mutableListOf<Int>()
        val pairs = mutableListOf<MatchPair>()

        var targetIndex = 0

        for (referenceIndex in reference.indices) {
            val referenceStart = reference[referenceIndex].startTimeMs

            while (
                targetIndex < target.size &&
                target[targetIndex].startTimeMs + offsetMs <
                referenceStart - MATCH_TOLERANCE_MS
            ) {
                targetIndex++
            }

            if (targetIndex >= target.size) break

            var bestIndex = targetIndex
            var bestResidual =
                referenceStart - (target[targetIndex].startTimeMs + offsetMs)

            if (targetIndex + 1 < target.size) {
                val nextResidual =
                    referenceStart - (target[targetIndex + 1].startTimeMs + offsetMs)

                if (abs(nextResidual) < abs(bestResidual)) {
                    bestIndex = targetIndex + 1
                    bestResidual = nextResidual
                }
            }

            if (abs(bestResidual) <= MATCH_TOLERANCE_MS) {
                residuals += bestResidual
                referenceIndexes += referenceIndex
                targetIndexes += bestIndex
                pairs += MatchPair(
                    referenceIndex = referenceIndex,
                    targetIndex = bestIndex,
                    residualMs = bestResidual,
                )
                targetIndex = bestIndex + 1
            }
        }

        if (residuals.isEmpty()) {
            return Evaluation(
                offsetMs = offsetMs,
                matches = 0,
                referenceCoverage = 0.0,
                residualMs = Double.POSITIVE_INFINITY,
                signedResidualMs = 0.0,
                offsetAgreement = 0.0,
                spacingScore = 0.0,
                score = 0.0,
                pairs = emptyList(),
            )
        }

        val residualMs = median(residuals.map { abs(it).toDouble() })
        val signedResidualMs = median(residuals.map { it.toDouble() })

        val agreement =
            residuals.count { abs(it) <= STRONG_RESIDUAL_MS }.toDouble() /
                residuals.size

        val referenceCoverage = if (referenceIndexes.size < 2) {
            0.0
        } else {
            val matchedSpan =
                reference[referenceIndexes.last()].startTimeMs -
                    reference[referenceIndexes.first()].startTimeMs

            matchedSpan.toDouble() /
                max(
                    reference.last().startTimeMs - reference.first().startTimeMs,
                    1L,
                )
        }.coerceIn(0.0, 1.0)

        val spacingScore =
            spacingScore(reference, target, referenceIndexes, targetIndexes)

        val participation = residuals.size.toDouble() / reference.size
        val residualScore = exp(-residualMs / 900.0)

        val score = (
            participation * 0.33 +
                referenceCoverage * 0.22 +
                residualScore * 0.23 +
                agreement * 0.12 +
                spacingScore * 0.10
            ).coerceIn(0.0, 1.0)

        return Evaluation(
            offsetMs = offsetMs,
            matches = residuals.size,
            referenceCoverage = referenceCoverage,
            residualMs = residualMs,
            signedResidualMs = signedResidualMs,
            offsetAgreement = agreement,
            spacingScore = spacingScore,
            score = score,
            pairs = pairs,
        )
    }

    private fun spacingScore(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        referenceIndexes: List<Int>,
        targetIndexes: List<Int>,
    ): Double {
        if (referenceIndexes.size < 3) {
            return if (referenceIndexes.isEmpty()) 0.0 else 0.5
        }

        val errors = (0 until referenceIndexes.lastIndex).map { index ->
            val referenceGap =
                reference[referenceIndexes[index + 1]].startTimeMs -
                    reference[referenceIndexes[index]].startTimeMs

            val targetGap =
                target[targetIndexes[index + 1]].startTimeMs -
                    target[targetIndexes[index]].startTimeMs

            abs(referenceGap - targetGap).toDouble()
        }

        return exp(-median(errors) / 3_000.0).coerceIn(0.0, 1.0)
    }

    private fun floorBucket(value: Long, bucketSize: Long): Long {
        val quotient = value / bucketSize
        val remainder = value % bucketSize

        return if (remainder < 0L) {
            (quotient - 1L) * bucketSize
        } else {
            quotient * bucketSize
        }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.POSITIVE_INFINITY

        val sorted = values.sorted()
        val middle = sorted.size / 2

        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun fmt(value: Double): String =
        "%.4f".format(value)

    private fun logText(text: String): String {
        if (text.isBlank()) return "<text unavailable>"

        val normalized = text
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        return if (normalized.length > 500) {
            normalized.take(500) + "…"
        } else {
            normalized
        }
    }

    private data class CandidateOffset(
        val offsetMs: Long,
        val votes: Int,
    )

    private data class MatchPair(
        val referenceIndex: Int,
        val targetIndex: Int,
        val residualMs: Long,
    )

    private data class ConfidenceCheck(
        val name: String,
        val passed: Boolean,
        val detail: String,
    )

    private data class AlignmentResult(
        val trackKey: String,
        val language: String?,
        val offsetMs: Long,
        val score: Double,
    )

    private data class Evaluation(
        val offsetMs: Long,
        val matches: Int,
        val referenceCoverage: Double,
        val residualMs: Double,
        val signedResidualMs: Double,
        val offsetAgreement: Double,
        val spacingScore: Double,
        val score: Double,
        val pairs: List<MatchPair>,
    )
}

internal data class ReferenceTrack(
    val key: String,
    val language: String?,
    val cues: List<SubtitleSyncCue>,
)

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

        AutoSyncDebugLog.verbose("embedded store reset")
    }

    fun record(
        sourceKey: String,
        trackKey: String,
        language: String?,
        cue: SubtitleSyncCue,
    ) {
        if (sourceKey.isBlank() || trackKey.isBlank()) return

        var newCue = false
        var cueCount = 0

        synchronized(lock) {
            val track = sources
                .getOrPut(sourceKey) { linkedMapOf() }
                .getOrPut(trackKey) { Track(language) }

            track.language = track.language ?: language

            val cueKey = "${cue.startTimeMs}:${cue.endTimeMs}:${cue.text}"
            newCue = !track.cues.containsKey(cueKey)
            track.cues[cueKey] = cue
            cueCount = track.cues.size
        }

        if (newCue && AutoSyncDebugLog.VERBOSE) {
            AutoSyncDebugLog.verbose(
                "CAPTURE track=$trackKey lang=${language ?: "<unknown>"} " +
                    "count=$cueCount ${AutoSyncDebugLog.formatTimestamp(cue.startTimeMs)} " +
                    "| \"${cue.text.replace('\n', ' ').take(500).ifBlank { "<text unavailable>" }}\"",
            )
        }
    }

    fun candidateTracks(
        sourceKey: String,
        preferredLanguage: String?,
    ): List<ReferenceTrack> = synchronized(lock) {
        val preferred = preferredLanguage?.trim()?.lowercase().orEmpty()

        sources[sourceKey]
            .orEmpty()
            .map { (key, track) ->
                ReferenceTrack(
                    key = key,
                    language = track.language,
                    cues = track.cues.values.sortedBy { it.startTimeMs },
                )
            }
            .filter { it.cues.size >= 3 }
            .sortedWith(
                compareByDescending<ReferenceTrack> {
                    languageRank(it.language, preferred)
                }.thenByDescending {
                    it.cues.size
                },
            )
    }

    private fun languageRank(language: String?, preferred: String): Int {
        val normalized = language?.trim()?.lowercase().orEmpty()
        val preferredBase = preferred.substringBefore('-')
        val normalizedBase = normalized.substringBefore('-')

        return when {
            preferred.isNotBlank() && normalized == preferred -> 4
            preferredBase.isNotBlank() && normalizedBase == preferredBase -> 3
            normalized == "en" || normalized.startsWith("en-") -> 2
            normalized.isNotBlank() -> 1
            else -> 0
        }
    }
}
