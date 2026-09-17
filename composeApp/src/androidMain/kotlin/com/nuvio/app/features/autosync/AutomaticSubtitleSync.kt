package com.nuvio.app.features.autosync

import android.os.SystemClock
import androidx.media3.common.C
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.SubtitleRepository
import com.nuvio.app.features.player.subtitleLanguageKey
import com.nuvio.app.features.streams.StreamSubtitle
import com.nuvio.app.features.player.SUBTITLE_DELAY_MAX_MS
import com.nuvio.app.features.player.SUBTITLE_DELAY_MIN_MS
import com.nuvio.app.features.player.SubtitleSyncCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
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
    private const val MIN_ACCEPT_MATCHES = 8
    private const val MIN_REFERENCE_SPAN_MS = 8_000L
    private const val REFERENCE_DEDUP_WINDOW_MS = 125L

    private const val NORMAL_PARTICIPATION_THRESHOLD = 0.55
    private const val STRONG_ACCEPT_MATCHES = 20
    private const val STRONG_ACCEPT_RESIDUAL_MS = 250.0
    private const val STRONG_ACCEPT_AGREEMENT = 0.80
    private const val STRONG_ACCEPT_SPACING = 0.85
    private const val STRONG_ACCEPT_MARGIN = 0.10

    private const val MAX_OFFSET_MS = 120_000L
    private const val CANDIDATE_BUCKET_MS = 500L
    private const val MATCH_TOLERANCE_MS = 1_800L
    private const val STRONG_RESIDUAL_MS = 750L
    private const val MIN_OFFSET_MARGIN = 0.025

    private const val CONTIGUOUS_SEGMENT_MAX_GAP_MS = 30_000L
    private const val MIN_FROZEN_REFERENCE_CUES = 8
    private const val MIN_FULL_DIALOGUE_CLASSIFICATION_SPAN_MS = 30_000L
    private const val FALLBACK_FROZEN_REFERENCE_SPAN_MS = 45_000L
    private const val MIN_FROZEN_REFERENCE_SPAN_MS = 60_000L
    private const val PREFERRED_FROZEN_REFERENCE_SPAN_MS = 75_000L
    private const val PREFERRED_REFERENCE_WAIT_MS = 12_000L
    private const val SDH_RANKING_SCORE_PENALTY = 0.015

    private const val MIN_SCALE_VALIDATION_MATCHES = 8
    private const val MIN_SCALE_VALIDATION_SPAN_MS = 20_000L
    private const val MIN_SCALE_PAIR_GAP_MS = 8_000L
    private const val MAX_TIMELINE_SCALE_DEVIATION = 0.008
    private const val INDEPENDENT_SCALE_WINDOW_CUES = 5
    private const val MIN_INDEPENDENT_SCALE_ANCHOR_MATCHES = 4
    private const val MIN_INDEPENDENT_SCALE_ANCHOR_MARGIN = 0.005
    private const val INDEPENDENT_SCALE_OFFSET_SEARCH_MS = 15_000L
    private const val MAX_INDEPENDENT_SCALE_ANCHOR_RESIDUAL_MS = 900.0
    private const val MIN_INDEPENDENT_SCALE_ANCHOR_SPACING = 0.45
    private const val MAX_INDEPENDENT_SCALE_DISPERSION = 0.015

    private const val MIN_CONSECUTIVE_PATTERN_MATCHES = 6
    private const val MAX_PATTERN_INDEX_STEP = 3
    private const val MAX_PATTERN_GAP_ERROR_MS = 1_500L
    private const val MAX_PATTERN_GAP_ERROR_RATIO = 0.10

    private const val MIN_FULL_DIALOGUE_CUES = 8
    private const val MIN_FULL_DIALOGUE_DENSITY_PER_MINUTE = 2.0
    private const val MIN_FULL_DIALOGUE_TEXT_RATIO = 0.45

    private const val MAX_LOGGED_CUE_SAMPLES = 20
    private const val MAX_LOGGED_CANDIDATES = 20
    private const val MAX_LOGGED_MATCH_PAIRS = 50
    private const val MAX_PARALLEL_SUBTITLE_DOWNLOADS = 6
    private const val MAX_RECOMMENDATION_CACHE_ENTRIES = 16

    private val recommendationCacheLock = Any()
    private val recommendationCache =
        mutableMapOf<RecommendationCacheKey, CachedRecommendation>()

    suspend fun findBestSubtitleRecommendation(
        sourceKey: String,
        selectedSubtitleUrl: String,
        selectedSubtitleHeaders: Map<String, String>,
        streamSubtitles: List<StreamSubtitle>,
        preferredLanguage: String?,
        includeRepositorySubtitles: Boolean = true,
        onReferenceReady: () -> Unit = {},
    ): AutoSyncSubtitleRecommendation? {
        AutoSyncDebugLog.start(
            sourceKey = sourceKey,
            subtitleUrl = selectedSubtitleUrl,
        )

        val repositorySubtitles = if (includeRepositorySubtitles) {
            SubtitleRepository.addonSubtitles.value
        } else {
            emptyList()
        }
        val knownCandidates = buildList {
            streamSubtitles.forEach { subtitle ->
                add(
                    SubtitleCandidate(
                        url = subtitle.url,
                        language = subtitle.language,
                        displayName = subtitle.name?.takeIf { it.isNotBlank() } ?: subtitle.language,
                        headers = subtitle.headers.orEmpty(),
                    ),
                )
            }
            repositorySubtitles.forEach { subtitle ->
                add(
                    SubtitleCandidate(
                        url = subtitle.url,
                        language = subtitle.language,
                        displayName = subtitle.display.ifBlank {
                            subtitle.addonName ?: subtitle.id
                        },
                        headers = emptyMap(),
                    ),
                )
            }
        }.distinctBy { it.url }

        val selectedKnown = knownCandidates.firstOrNull { it.url == selectedSubtitleUrl }
        val selectedLanguage = selectedKnown?.language
            ?.takeIf { it.isNotBlank() }
            ?: preferredLanguage
                ?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
                .orEmpty()
        val selectedLanguageKey = selectedLanguage
            .takeIf { it.isNotBlank() }
            ?.let(::subtitleLanguageKey)

        val selectedCandidate = SubtitleCandidate(
            url = selectedSubtitleUrl,
            language = selectedKnown?.language ?: selectedLanguage,
            displayName = selectedKnown?.displayName ?: "Selected subtitle",
            headers = selectedSubtitleHeaders.ifEmpty { selectedKnown?.headers.orEmpty() },
        )

        val sameLanguageCandidates = if (selectedLanguageKey.isNullOrBlank()) {
            selectedKnown?.let(::listOf) ?: listOf(selectedCandidate)
        } else {
            val ordered = knownCandidates.filter { candidate ->
                subtitleLanguageKey(candidate.language) == selectedLanguageKey
            }
            if (ordered.any { it.url == selectedSubtitleUrl }) {
                ordered
            } else {
                ordered + selectedCandidate
            }
        }.distinctBy { it.url }

        val candidateOrder = sameLanguageCandidates
            .mapIndexed { index, candidate -> candidate.url to index }
            .toMap()

        val candidateFingerprint = sameLanguageCandidates.joinToString("|") { it.url }

        AutoSyncDebugLog.section("SUBTITLE CANDIDATES")
        AutoSyncDebugLog.info(
            "selectedLanguage=${selectedLanguage.ifBlank { "<unknown>" }} " +
                "languageKey=${selectedLanguageKey ?: "<unknown>"} candidates=${sameLanguageCandidates.size}",
        )
        AutoSyncDebugLog.info("header values intentionally not logged")

        val parsedCandidates = sameLanguageCandidates
            .chunked(MAX_PARALLEL_SUBTITLE_DOWNLOADS)
            .flatMap { batch ->
                supervisorScope {
                    batch.map { candidate ->
                        async { loadSubtitleCandidate(candidate) }
                    }.awaitAll()
                }
            }
            .filterNotNull()

        parsedCandidates.forEachIndexed { index, parsed ->
            AutoSyncDebugLog.info(
                "candidate[$index] name=${parsed.candidate.displayName} " +
                    "lang=${parsed.candidate.language.ifBlank { "<unknown>" }} " +
                    "selected=${parsed.candidate.url == selectedSubtitleUrl} cues=${parsed.cues.size} " +
                    "download=${parsed.downloadMs}ms parse=${parsed.parseMs}ms",
            )
        }

        if (parsedCandidates.isEmpty()) {
            AutoSyncDebugLog.warn("REJECT no same-language subtitle candidate could be parsed")
            return null
        }

        parsedCandidates
            .firstOrNull { it.candidate.url == selectedSubtitleUrl }
            ?.cues
            ?.take(MAX_LOGGED_CUE_SAMPLES)
            ?.forEachIndexed { index, cue ->
                AutoSyncDebugLog.cue(
                    prefix = "SELECTED ADDON",
                    index = index,
                    startMs = cue.startTimeMs,
                    endMs = cue.endTimeMs,
                    text = cue.text,
                )
            }

        var waitedMs = 0L
        var lastReferenceSignature = ""
        var attempt = 0
        var frozenReferenceTracks: List<ReferenceTrack>? = null
        var frozenReferenceMode = ""

        while (waitedMs <= MAX_WAIT_MS) {
            val rawTracks = EmbeddedSubtitleCueStore.candidateTracks(
                sourceKey = sourceKey,
                preferredLanguage = preferredLanguage,
            )
            val preparedTracks = rawTracks.map { track ->
                val deduplicated = deduplicateReferenceCues(track.cues)
                track.copy(cues = largestContiguousReferenceSegment(deduplicated))
            }

            val fullDialogueTracks = preparedTracks.filter(::isLikelyFullDialogueTrack)
            val preferredTracks = fullDialogueTracks.filter { track ->
                track.cues.size >= MIN_FROZEN_REFERENCE_CUES &&
                    referenceSpanMs(track.cues) >= PREFERRED_FROZEN_REFERENCE_SPAN_MS
            }
            val minimumTracks = fullDialogueTracks.filter { track ->
                track.cues.size >= MIN_FROZEN_REFERENCE_CUES &&
                    referenceSpanMs(track.cues) >= MIN_FROZEN_REFERENCE_SPAN_MS
            }
            val fallbackTracks = fullDialogueTracks.filter { track ->
                track.cues.size >= MIN_FROZEN_REFERENCE_CUES &&
                    referenceSpanMs(track.cues) >= FALLBACK_FROZEN_REFERENCE_SPAN_MS
            }

            val signature = rawTracks.joinToString("|") { track ->
                "${track.key}:${track.cues.size}:${track.cues.lastOrNull()?.startTimeMs ?: -1L}"
            }

            if (signature != lastReferenceSignature) {
                lastReferenceSignature = signature
                attempt++

                AutoSyncDebugLog.section("REFERENCE SNAPSHOT #$attempt")
                AutoSyncDebugLog.info(
                    "waited=${waitedMs}ms tracks=${preparedTracks.size} fullDialogue=${fullDialogueTracks.size} " +
                        "ready60=${minimumTracks.size} ready75=${preferredTracks.size}",
                )

                if (preparedTracks.isEmpty()) {
                    AutoSyncDebugLog.info("no embedded text tracks captured yet")
                }

                preparedTracks.forEachIndexed { trackIndex, track ->
                    val raw = rawTracks.getOrNull(trackIndex)
                    val rawCueCount = raw?.cues?.size ?: track.cues.size
                    val dedupedCueCount = raw?.let { deduplicateReferenceCues(it.cues).size } ?: track.cues.size
                    val segmentCueCount = track.cues.size
                    val span = referenceSpanMs(track.cues)
                    val density = referenceCueDensityPerMinute(track.cues)

                    AutoSyncDebugLog.info(
                        "track[$trackIndex] key=${track.key} lang=${track.language ?: "<unknown>"} " +
                            "label=${track.label ?: "<none>"} selectionFlags=${track.selectionFlags} roleFlags=${track.roleFlags} " +
                            "rawCues=$rawCueCount dedupedCues=$dedupedCueCount segmentCues=$segmentCueCount " +
                            "span=${span}ms density=${"%.2f".format(density)}/min " +
                            "fullDialogue=${track in fullDialogueTracks} " +
                            "ready60=${track in minimumTracks} ready75=${track in preferredTracks}",
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

            val readyTracks = when {
                preferredTracks.isNotEmpty() -> {
                    frozenReferenceMode = "preferred-75s"
                    minimumTracks.ifEmpty { preferredTracks }
                }
                waitedMs >= PREFERRED_REFERENCE_WAIT_MS && minimumTracks.isNotEmpty() -> {
                    frozenReferenceMode = "minimum-60s"
                    minimumTracks
                }
                waitedMs >= MAX_WAIT_MS && fallbackTracks.isNotEmpty() -> {
                    frozenReferenceMode = "fallback-45s"
                    fallbackTracks
                }
                else -> emptyList()
            }

            if (readyTracks.isNotEmpty()) {
                onReferenceReady()
                val frozen = orderReferenceTracks(readyTracks).map { track ->
                    track.copy(cues = track.cues.toList())
                }
                frozenReferenceTracks = frozen

                AutoSyncDebugLog.section("FROZEN REFERENCES")
                AutoSyncDebugLog.info(
                    "mode=$frozenReferenceMode tracks=${frozen.size}",
                )
                frozen.forEachIndexed { index, track ->
                    AutoSyncDebugLog.info(
                        "reference[$index] track=${track.key} generation=${track.generation} " +
                            "lang=${track.language ?: "<unknown>"} label=${track.label ?: "<none>"} " +
                            "sdh=${isSdhReferenceTrack(track)} cues=${track.cues.size} " +
                            "span=${referenceSpanMs(track.cues)}ms " +
                            "density=${"%.2f".format(referenceCueDensityPerMinute(track.cues))}/min",
                    )
                }
                break
            }

            delay(POLL_INTERVAL_MS)
            waitedMs += POLL_INTERVAL_MS
        }

        val referenceTracks = frozenReferenceTracks ?: run {
            AutoSyncDebugLog.section("TIMEOUT")
            AutoSyncDebugLog.warn(
                "no usable full-dialogue embedded reference with at least " +
                    "${FALLBACK_FROZEN_REFERENCE_SPAN_MS}ms span after ${MAX_WAIT_MS}ms",
            )
            return null
        }

        val referenceFingerprint = referenceSetFingerprint(referenceTracks)
        val cacheKey = RecommendationCacheKey(
            sourceKey = sourceKey,
            languageKey = selectedLanguageKey.orEmpty(),
            candidateFingerprint = candidateFingerprint,
            referenceFingerprint = referenceFingerprint,
        )
        synchronized(recommendationCacheLock) {
            recommendationCache[cacheKey]
        }?.let { cached ->
            AutoSyncDebugLog.section("RECOMMENDATION CACHE")
            AutoSyncDebugLog.info(
                "HIT references=${referenceTracks.size} fingerprint=$referenceFingerprint " +
                    "name=${cached.displayName} correction=${cached.correctionMs}ms score=${fmt(cached.score)}",
            )
            return cached.toRecommendation(selectedSubtitleUrl)
        }

        AutoSyncDebugLog.section("CANDIDATE MATCH SUMMARY")
        val candidateMatches = parsedCandidates.mapIndexedNotNull { candidateIndex, parsed ->
            val attempts = referenceTracks.map { track ->
                track to attemptAlignment(
                    track = track,
                    target = parsed.cues,
                    logDetails = false,
                )
            }

            val acceptedMatches = attempts.mapNotNull { (track, attempt) ->
                attempt.result?.let { alignment ->
                    CandidateMatch(
                        parsed = parsed,
                        track = track,
                        alignment = alignment,
                    )
                }
            }

            val bestAccepted = acceptedMatches.sortedWith(
                compareByDescending<CandidateMatch> { adjustedAlignmentScore(it) }
                    .thenByDescending { it.alignment.matches }
                    .thenBy { abs(it.alignment.timelineScale - 1.0) }
                    .thenBy { it.alignment.residualMs }
                    .thenBy { abs(it.alignment.offsetMs) }
                    .thenBy { it.track.key },
            ).firstOrNull()

            if (bestAccepted != null) {
                val winningAttempt = attempts.first { (track, _) -> track.key == bestAccepted.track.key }.second
                AutoSyncDebugLog.info(
                    "candidate[$candidateIndex] ACCEPT track=${bestAccepted.track.key} " +
                        "label=${bestAccepted.track.label ?: "<none>"} " +
                        "score=${fmt(bestAccepted.alignment.score)} " +
                        "rankScore=${fmt(adjustedAlignmentScore(bestAccepted))} " +
                        "matches=${bestAccepted.alignment.matches} " +
                        "offset=${bestAccepted.alignment.offsetMs}ms " +
                        "scale=${"%.6f".format(bestAccepted.alignment.timelineScale)} " +
                        "consecutive=${winningAttempt.consecutivePatternMatches}",
                )
                bestAccepted
            } else {
                val bestAttempt = attempts.sortedWith(
                    compareBy<Pair<ReferenceTrack, AlignmentAttempt>> { it.second.failedChecks.size }
                        .thenByDescending { it.second.score }
                        .thenByDescending { it.second.matches }
                        .thenBy { it.second.residualMs }
                        .thenBy { it.first.key },
                ).first()
                val track = bestAttempt.first
                val attemptResult = bestAttempt.second
                AutoSyncDebugLog.info(
                    "candidate[$candidateIndex] REJECT bestTrack=${track.key} " +
                        "label=${track.label ?: "<none>"} score=${fmt(attemptResult.score)} " +
                        "matches=${attemptResult.matches} " +
                        "offset=${attemptResult.offsetMs?.let { "${it}ms" } ?: "<none>"} " +
                        "scale=${attemptResult.timelineScale?.let { "%.6f".format(it) } ?: "<unavailable>"} " +
                        "consecutive=${attemptResult.consecutivePatternMatches} " +
                        "failed=${attemptResult.failedChecks.joinToString(",").ifBlank { "unknown" }}",
                )
                null
            }
        }

        if (candidateMatches.isEmpty()) {
            AutoSyncDebugLog.section("FINAL RECOMMENDATION")
            AutoSyncDebugLog.warn(
                "REJECT no same-language subtitle passed any eligible full-dialogue reference track",
            )
            return null
        }

        val ranked = candidateMatches.sortedWith(
            compareByDescending<CandidateMatch> { adjustedAlignmentScore(it) }
                .thenByDescending { it.alignment.matches }
                .thenBy { abs(it.alignment.timelineScale - 1.0) }
                .thenBy { it.alignment.residualMs }
                .thenBy { abs(it.alignment.offsetMs) }
                .thenBy { candidateOrder[it.parsed.candidate.url] ?: Int.MAX_VALUE }
                .thenBy { it.track.key }
                .thenBy { it.parsed.candidate.url },
        )
        val bestMatch = ranked.first()

        AutoSyncDebugLog.section("SUBTITLE RANKING")
        ranked.forEachIndexed { index, match ->
            AutoSyncDebugLog.info(
                "rank=${index + 1} name=${match.parsed.candidate.displayName} " +
                    "lang=${match.parsed.candidate.language.ifBlank { "<unknown>" }} " +
                    "selected=${match.parsed.candidate.url == selectedSubtitleUrl} " +
                    "reference=${match.track.key} label=${match.track.label ?: "<none>"} " +
                    "sdh=${isSdhReferenceTrack(match.track)} correction=${match.alignment.offsetMs}ms " +
                    "scale=${"%.6f".format(match.alignment.timelineScale)} " +
                    "score=${fmt(match.alignment.score)} rankScore=${fmt(adjustedAlignmentScore(match))} " +
                    "matches=${match.alignment.matches} residual=${"%.1f".format(match.alignment.residualMs)}ms",
            )
        }

        AutoSyncDebugLog.section("WINNING SUBTITLE DETAILS")
        attemptAlignment(
            track = bestMatch.track,
            target = bestMatch.parsed.cues,
            logDetails = true,
        )

        val correctionMs = bestMatch.alignment.offsetMs
            .toInt()
            .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)

        AutoSyncDebugLog.section("FINAL RECOMMENDATION")
        AutoSyncDebugLog.info(
            "name=${bestMatch.parsed.candidate.displayName} " +
                "lang=${bestMatch.parsed.candidate.language.ifBlank { "<unknown>" }} " +
                "selected=${bestMatch.parsed.candidate.url == selectedSubtitleUrl} " +
                "reference=${bestMatch.track.key} label=${bestMatch.track.label ?: "<none>"}",
        )
        AutoSyncDebugLog.info(
            "correction=${correctionMs}ms scale=${"%.6f".format(bestMatch.alignment.timelineScale)} " +
                "score=${fmt(bestMatch.alignment.score)} rankScore=${fmt(adjustedAlignmentScore(bestMatch))}",
        )

        val cachedRecommendation = CachedRecommendation(
            url = bestMatch.parsed.candidate.url,
            language = bestMatch.parsed.candidate.language,
            displayName = bestMatch.parsed.candidate.displayName,
            correctionMs = correctionMs,
            score = bestMatch.alignment.score,
        )
        synchronized(recommendationCacheLock) {
            if (recommendationCache.size >= MAX_RECOMMENDATION_CACHE_ENTRIES) {
                recommendationCache.clear()
            }
            recommendationCache[cacheKey] = cachedRecommendation
        }

        return cachedRecommendation.toRecommendation(selectedSubtitleUrl)
    }

    /** Keeps the old single-subtitle API available for any other caller. */
    suspend fun findDelayCorrectionMs(
        sourceKey: String,
        subtitleUrl: String,
        subtitleHeaders: Map<String, String>,
        preferredLanguage: String?,
        onReferenceReady: () -> Unit = {},
    ): Int? = findBestSubtitleRecommendation(
        sourceKey = sourceKey,
        selectedSubtitleUrl = subtitleUrl,
        selectedSubtitleHeaders = subtitleHeaders,
        streamSubtitles = emptyList(),
        preferredLanguage = preferredLanguage,
        includeRepositorySubtitles = false,
        onReferenceReady = onReferenceReady,
    )?.takeIf { it.isCurrentSubtitle }?.correctionMs

    private fun orderReferenceTracks(
        tracks: List<ReferenceTrack>,
    ): List<ReferenceTrack> =
        tracks.sortedWith(
            compareBy<ReferenceTrack> { isSdhReferenceTrack(it) }
                .thenByDescending(::fullDialogueReferenceScore)
                .thenByDescending { track -> track.cues.size }
                .thenByDescending { track -> referenceSpanMs(track.cues) }
                .thenBy { track -> track.key },
        )

    private fun adjustedAlignmentScore(match: CandidateMatch): Double =
        match.alignment.score - if (isSdhReferenceTrack(match.track)) {
            SDH_RANKING_SCORE_PENALTY
        } else {
            0.0
        }

    private fun isLikelyFullDialogueTrack(track: ReferenceTrack): Boolean {
        if (track.cues.size < MIN_FULL_DIALOGUE_CUES) return false
        if (referenceSpanMs(track.cues) < MIN_FULL_DIALOGUE_CLASSIFICATION_SPAN_MS) return false
        if (
            isForcedReferenceTrack(track) ||
            isCommentaryReferenceTrack(track) ||
            isDescriptiveReferenceTrack(track)
        ) return false
        if (referenceCueDensityPerMinute(track.cues) < MIN_FULL_DIALOGUE_DENSITY_PER_MINUTE) return false

        val textCues = track.cues.filter { normalizedCueText(it.text).isNotBlank() }
        if (textCues.size >= 4) {
            val dialogueRatio =
                textCues.count(::isDialogueLikeReferenceCue).toDouble() / textCues.size
            if (dialogueRatio < MIN_FULL_DIALOGUE_TEXT_RATIO) return false
        }

        return true
    }

    private fun fullDialogueReferenceScore(track: ReferenceTrack): Double {
        val textCues = track.cues.filter { normalizedCueText(it.text).isNotBlank() }
        val dialogueRatio = if (textCues.isEmpty()) {
            0.5
        } else {
            textCues.count(::isDialogueLikeReferenceCue).toDouble() / textCues.size
        }
        val density = referenceCueDensityPerMinute(track.cues).coerceAtMost(20.0)
        val dialogueRoleBonus =
            if ((track.roleFlags and C.ROLE_FLAG_TRANSCRIBES_DIALOG) != 0) 8.0 else 0.0
        val subtitleRoleBonus =
            if ((track.roleFlags and C.ROLE_FLAG_SUBTITLE) != 0) 4.0 else 0.0
        val sdhPenalty = if (isSdhReferenceTrack(track)) 5.0 else 0.0

        return track.cues.size * 2.0 +
            density * 1.5 +
            dialogueRatio * 20.0 +
            dialogueRoleBonus +
            subtitleRoleBonus -
            sdhPenalty
    }

    private fun isForcedReferenceTrack(track: ReferenceTrack): Boolean {
        if ((track.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) return true
        val label = track.label.orEmpty().lowercase()
        return label.contains("forced") ||
            label.contains("foreign only") ||
            label.contains("signs only") ||
            label.contains("songs only")
    }

    private fun isCommentaryReferenceTrack(track: ReferenceTrack): Boolean {
        if ((track.roleFlags and C.ROLE_FLAG_COMMENTARY) != 0) return true
        val label = track.label.orEmpty().lowercase()
        return label.contains("commentary")
    }

    private fun isDescriptiveReferenceTrack(track: ReferenceTrack): Boolean {
        if ((track.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) return true
        val label = track.label.orEmpty().lowercase()
        return label.contains("audio description") || label.contains("descriptive subtitle")
    }

    private fun isSdhReferenceTrack(track: ReferenceTrack): Boolean {
        if ((track.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0) return true
        val label = track.label.orEmpty().lowercase()
        return label.contains("sdh") ||
            label.contains("hearing impaired") ||
            label.contains("hearing-impaired") ||
            label.contains("closed caption")
    }

    private fun isDialogueLikeReferenceCue(cue: SubtitleSyncCue): Boolean {
        val text = normalizedCueText(cue.text)
        if (text.isBlank()) return false

        val parentheticalOnly =
            (text.startsWith("(") && text.endsWith(")")) ||
                (text.startsWith("[") && text.endsWith("]"))

        return !parentheticalOnly && text.any { it.isLetterOrDigit() }
    }

    private fun largestContiguousReferenceSegment(
        cues: List<SubtitleSyncCue>,
    ): List<SubtitleSyncCue> {
        if (cues.size < 2) return cues

        val sorted = cues.sortedBy { it.startTimeMs }
        val segments = mutableListOf<MutableList<SubtitleSyncCue>>()
        var current = mutableListOf(sorted.first())
        segments += current

        for (cue in sorted.drop(1)) {
            val previous = current.last()
            if (cue.startTimeMs - previous.startTimeMs > CONTIGUOUS_SEGMENT_MAX_GAP_MS) {
                current = mutableListOf()
                segments += current
            }
            current += cue
        }

        return segments
            .sortedWith(
                compareByDescending<List<SubtitleSyncCue>> { it.size }
                    .thenByDescending { referenceSpanMs(it) }
                    .thenBy { it.firstOrNull()?.startTimeMs ?: Long.MAX_VALUE },
            )
            .firstOrNull()
            ?.toList()
            .orEmpty()
    }

    private fun referenceSpanMs(cues: List<SubtitleSyncCue>): Long =
        if (cues.size < 2) 0L else cues.last().startTimeMs - cues.first().startTimeMs

    private fun referenceCueDensityPerMinute(cues: List<SubtitleSyncCue>): Double {
        val spanMs = referenceSpanMs(cues)
        if (spanMs <= 0L) return 0.0
        return cues.size * 60_000.0 / spanMs
    }

    private fun referenceFingerprint(track: ReferenceTrack): String {
        var timingHash = 1_125_899_906_842_597L
        for (cue in track.cues) {
            timingHash = timingHash * 31L + cue.startTimeMs
            timingHash = timingHash * 31L + cue.endTimeMs
        }
        return buildString {
            append(track.key)
            append(":g")
            append(track.generation)
            append(":")
            append(track.cues.size)
            append(":")
            append(track.cues.firstOrNull()?.startTimeMs ?: -1L)
            append(":")
            append(track.cues.lastOrNull()?.startTimeMs ?: -1L)
            append(":")
            append(timingHash)
        }
    }

    private fun referenceSetFingerprint(tracks: List<ReferenceTrack>): String =
        tracks
            .sortedBy { it.key }
            .joinToString("|") { referenceFingerprint(it) }

    private suspend fun loadSubtitleCandidate(
        candidate: SubtitleCandidate,
    ): ParsedSubtitleCandidate? {
        val downloadStarted = SystemClock.elapsedRealtime()
        val subtitleText = try {
            httpGetTextWithHeaders(candidate.url, candidate.headers)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            AutoSyncDebugLog.error(
                "candidate download failed name=${candidate.displayName}",
                error,
            )
            return null
        }
        val downloadMs = SystemClock.elapsedRealtime() - downloadStarted

        val parseStarted = SystemClock.elapsedRealtime()
        val cues = try {
            PlayerSubtitleCueParser.parse(
                text = subtitleText,
                sourceUrl = candidate.url,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            AutoSyncDebugLog.error(
                "candidate parse failed name=${candidate.displayName}",
                error,
            )
            return null
        }
        val parseMs = SystemClock.elapsedRealtime() - parseStarted

        if (cues.size < MIN_REFERENCE_CUES) {
            AutoSyncDebugLog.warn(
                "candidate rejected before matching name=${candidate.displayName} " +
                    "cues=${cues.size} required=$MIN_REFERENCE_CUES",
            )
            return null
        }

        return ParsedSubtitleCandidate(
            candidate = candidate,
            cues = cues,
            downloadMs = downloadMs,
            parseMs = parseMs,
        )
    }

    private fun attemptAlignment(
        track: ReferenceTrack,
        target: List<SubtitleSyncCue>,
        logDetails: Boolean = true,
    ): AlignmentAttempt {
        val reference = track.cues

        if (logDetails) {
            AutoSyncDebugLog.section(
                "ALIGN track=${track.key} lang=${track.language ?: "<unknown>"}",
            )
        }

        val candidates = candidateOffsets(reference, target)
        if (candidates.isEmpty()) {
            if (logDetails) AutoSyncDebugLog.warn("no candidate offsets")
            return AlignmentAttempt(
                result = null,
                failedChecks = listOf("candidate offsets"),
            )
        }

        if (logDetails) {
            AutoSyncDebugLog.info("candidate offsets=${candidates.size}")
            candidates
                .take(MAX_LOGGED_CANDIDATES)
                .forEachIndexed { index, candidate ->
                    AutoSyncDebugLog.verbose(
                        "CANDIDATE[$index] offset=${candidate.offsetMs}ms votes=${candidate.votes}",
                    )
                }
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
                        if (logDetails) {
                            AutoSyncDebugLog.verbose(
                                "REFINE ${candidate.offsetMs}ms -> ${refinedOffset}ms " +
                                    "using median signed residual=${"%.1f".format(initial.signedResidualMs)}ms",
                            )
                        }
                        add(evaluate(reference, target, refinedOffset))
                    }
                }
            }
        }.sortedByDescending { it.score }

        val best = evaluations.firstOrNull() ?: return AlignmentAttempt(
            result = null,
            failedChecks = listOf("evaluation"),
        )
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
        val normalParticipation = referenceParticipation >= NORMAL_PARTICIPATION_THRESHOLD
        val matchedPairScale = estimateMatchedPairTimelineScale(reference, target, best.pairs)
        val independentScale = estimateIndependentTimelineScale(
            reference = reference,
            target = target,
            expectedOffsetMs = best.offsetMs,
        )
        val timelineScale = independentScale?.scale
        val scaleCompatible = timelineScale != null &&
            abs(timelineScale - 1.0) <= MAX_TIMELINE_SCALE_DEVIATION
        val consecutivePatternMatches = longestConsecutivePatternRun(reference, target, best.pairs)

        val strongAbsoluteEvidence =
            best.matches >= STRONG_ACCEPT_MATCHES &&
                best.residualMs <= STRONG_ACCEPT_RESIDUAL_MS &&
                best.offsetAgreement >= STRONG_ACCEPT_AGREEMENT &&
                best.spacingScore >= STRONG_ACCEPT_SPACING &&
                margin >= STRONG_ACCEPT_MARGIN

        val checks = listOf(
            ConfidenceCheck(
                "matches",
                best.matches >= MIN_ACCEPT_MATCHES,
                "${best.matches} >= $MIN_ACCEPT_MATCHES",
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
                "unambiguous offset",
                margin >= MIN_OFFSET_MARGIN,
                "margin=${fmt(margin)} >= ${fmt(MIN_OFFSET_MARGIN)}",
            ),
            ConfidenceCheck(
                "consecutive timing pattern",
                consecutivePatternMatches >= MIN_CONSECUTIVE_PATTERN_MATCHES,
                "$consecutivePatternMatches >= $MIN_CONSECUTIVE_PATTERN_MATCHES",
            ),
            ConfidenceCheck(
                "timeline scale / FPS",
                scaleCompatible,
                if (independentScale == null) {
                    "insufficient independent anchor evidence for scale validation"
                } else {
                    "scale=${"%.6f".format(independentScale.scale)} " +
                        "deviation=${"%.4f".format(abs(independentScale.scale - 1.0))} " +
                        "anchors=${independentScale.anchorCount} slopes=${independentScale.slopeCount} " +
                        "dispersion=${"%.4f".format(independentScale.dispersion)} " +
                        "<= ${"%.4f".format(MAX_TIMELINE_SCALE_DEVIATION)}"
                },
            ),
            ConfidenceCheck(
                "participation OR strong absolute evidence",
                normalParticipation || strongAbsoluteEvidence,
                "participation=${fmt(referenceParticipation)} >= ${fmt(NORMAL_PARTICIPATION_THRESHOLD)} " +
                    "OR strongAbsoluteEvidence=$strongAbsoluteEvidence",
            ),
        )

        val failedChecks = checks.filterNot { it.passed }.map { it.name }
        val highConfidence = failedChecks.isEmpty()

        if (logDetails) {
            AutoSyncDebugLog.info(
                "BEST offset=${best.offsetMs}ms matches=${best.matches}/${reference.size} " +
                    "participation=${fmt(referenceParticipation)} coverage=${fmt(best.referenceCoverage)} " +
                    "medianResidual=${"%.1f".format(best.residualMs)}ms " +
                    "signedResidual=${"%.1f".format(best.signedResidualMs)}ms " +
                    "agreement=${fmt(best.offsetAgreement)} spacing=${fmt(best.spacingScore)} " +
                    "independentScale=${timelineScale?.let { "%.6f".format(it) } ?: "<unavailable>"} " +
                    "matchedPairScale=${matchedPairScale?.let { "%.6f".format(it) } ?: "<unavailable>"} " +
                    "consecutive=$consecutivePatternMatches score=${fmt(best.score)}",
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
            AutoSyncDebugLog.info(
                "${if (normalParticipation) "PASS" else "FAIL"} normal participation: " +
                    "${fmt(referenceParticipation)} >= ${fmt(NORMAL_PARTICIPATION_THRESHOLD)}",
            )
            AutoSyncDebugLog.info(
                "${if (strongAbsoluteEvidence) "PASS" else "FAIL"} strong absolute evidence: " +
                    "matches=${best.matches}/${STRONG_ACCEPT_MATCHES} " +
                    "residual=${"%.1f".format(best.residualMs)}ms/${"%.0f".format(STRONG_ACCEPT_RESIDUAL_MS)}ms " +
                    "agreement=${fmt(best.offsetAgreement)}/${fmt(STRONG_ACCEPT_AGREEMENT)} " +
                    "spacing=${fmt(best.spacingScore)}/${fmt(STRONG_ACCEPT_SPACING)} " +
                    "margin=${fmt(margin)}/${fmt(STRONG_ACCEPT_MARGIN)}",
            )
            checks.forEach { check ->
                AutoSyncDebugLog.info(
                    "${if (check.passed) "PASS" else "FAIL"} ${check.name}: ${check.detail}",
                )
            }

            AutoSyncDebugLog.info(
                "DECISION=${if (highConfidence) "ACCEPT" else "REJECT"} track=${track.key}",
            )
        }

        val result = if (highConfidence && timelineScale != null) {
            AlignmentResult(
                trackKey = track.key,
                language = track.language,
                offsetMs = best.offsetMs,
                score = best.score,
                matches = best.matches,
                residualMs = best.residualMs,
                timelineScale = timelineScale,
            )
        } else {
            null
        }

        return AlignmentAttempt(
            result = result,
            score = best.score,
            offsetMs = best.offsetMs,
            matches = best.matches,
            residualMs = best.residualMs,
            timelineScale = timelineScale,
            consecutivePatternMatches = consecutivePatternMatches,
            failedChecks = failedChecks,
        )
    }

    private fun estimateIndependentTimelineScale(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        expectedOffsetMs: Long,
    ): IndependentScaleResult? {
        if (reference.size < MIN_REFERENCE_CUES || target.size < MIN_REFERENCE_CUES) return null

        val windowSize = minOf(INDEPENDENT_SCALE_WINDOW_CUES, reference.size)
        val lastStart = (reference.size - windowSize).coerceAtLeast(0)
        val starts = listOf(
            0,
            lastStart / 2,
            lastStart,
        ).distinct()

        val anchors = starts.flatMap { startIndex ->
            findIndependentScaleAnchors(
                referenceWindow = reference.subList(startIndex, startIndex + windowSize),
                target = target,
                expectedOffsetMs = expectedOffsetMs,
            )
        }.distinctBy { "${it.referenceTimeMs}:${it.targetTimeMs}" }
            .sortedBy { it.referenceTimeMs }

        if (anchors.size < 2) return null

        val slopes = buildList {
            for (leftIndex in 0 until anchors.lastIndex) {
                val left = anchors[leftIndex]
                for (rightIndex in leftIndex + 1 until anchors.size) {
                    val right = anchors[rightIndex]
                    val referenceGap = right.referenceTimeMs - left.referenceTimeMs
                    val targetGap = right.targetTimeMs - left.targetTimeMs
                    if (referenceGap < MIN_SCALE_VALIDATION_SPAN_MS || targetGap <= 0L) continue

                    val slope = referenceGap.toDouble() / targetGap.toDouble()
                    if (slope in 0.85..1.15) add(slope)
                }
            }
        }

        if (slopes.isEmpty()) return null

        val scale = median(slopes)
        val dispersion = median(slopes.map { abs(it - scale) })
        if (dispersion > MAX_INDEPENDENT_SCALE_DISPERSION) return null

        return IndependentScaleResult(
            scale = scale,
            anchorCount = anchors.size,
            slopeCount = slopes.size,
            dispersion = dispersion,
        )
    }

    private fun findIndependentScaleAnchors(
        referenceWindow: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        expectedOffsetMs: Long,
    ): List<ScaleAnchor> {
        val candidates = (
            candidateOffsets(referenceWindow, target)
                .filter { abs(it.offsetMs - expectedOffsetMs) <= INDEPENDENT_SCALE_OFFSET_SEARCH_MS } +
                CandidateOffset(expectedOffsetMs, 0)
            ).distinctBy { it.offsetMs }
        if (candidates.isEmpty()) return emptyList()

        val evaluations = buildList {
            for (candidate in candidates) {
                val initial = evaluate(referenceWindow, target, candidate.offsetMs)
                add(initial)
                if (initial.matches >= MIN_REFERENCE_CUES) {
                    val refinedOffset = candidate.offsetMs + initial.signedResidualMs.roundToLong()
                    if (
                        refinedOffset != candidate.offsetMs &&
                        abs(refinedOffset) <= MAX_OFFSET_MS &&
                        abs(refinedOffset - expectedOffsetMs) <= INDEPENDENT_SCALE_OFFSET_SEARCH_MS
                    ) {
                        add(evaluate(referenceWindow, target, refinedOffset))
                    }
                }
            }
        }.sortedByDescending { it.score }

        val best = evaluations.firstOrNull() ?: return emptyList()
        val second = evaluations.firstOrNull {
            abs(it.offsetMs - best.offsetMs) > MATCH_TOLERANCE_MS * 2L
        }
        val margin = if (second == null) {
            1.0
        } else {
            ((best.score - second.score) / max(best.score, 0.001)).coerceIn(0.0, 1.0)
        }

        if (best.matches < minOf(MIN_INDEPENDENT_SCALE_ANCHOR_MATCHES, referenceWindow.size)) return emptyList()
        if (best.residualMs > MAX_INDEPENDENT_SCALE_ANCHOR_RESIDUAL_MS) return emptyList()
        if (best.spacingScore < MIN_INDEPENDENT_SCALE_ANCHOR_SPACING) return emptyList()
        if (margin < MIN_INDEPENDENT_SCALE_ANCHOR_MARGIN) return emptyList()
        if (best.pairs.isEmpty()) return emptyList()

        return best.pairs.map { pair ->
            ScaleAnchor(
                referenceTimeMs = referenceWindow[pair.referenceIndex].startTimeMs,
                targetTimeMs = target[pair.targetIndex].startTimeMs,
            )
        }
    }

    private fun longestConsecutivePatternRun(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        pairs: List<MatchPair>,
    ): Int {
        if (pairs.isEmpty()) return 0
        if (pairs.size == 1) return 1

        var longest = 1
        var current = 1

        for (index in 1 until pairs.size) {
            val previous = pairs[index - 1]
            val next = pairs[index]
            val referenceStep = next.referenceIndex - previous.referenceIndex
            val targetStep = next.targetIndex - previous.targetIndex

            val referenceGap =
                reference[next.referenceIndex].startTimeMs -
                    reference[previous.referenceIndex].startTimeMs
            val targetGap =
                target[next.targetIndex].startTimeMs -
                    target[previous.targetIndex].startTimeMs
            val proportionalTolerance =
                (max(referenceGap, targetGap).coerceAtLeast(0L) * MAX_PATTERN_GAP_ERROR_RATIO)
                    .roundToLong()
            val allowedGapError = max(MAX_PATTERN_GAP_ERROR_MS, proportionalTolerance)

            val continues =
                referenceStep in 1..MAX_PATTERN_INDEX_STEP &&
                    targetStep in 1..MAX_PATTERN_INDEX_STEP &&
                    referenceGap > 0L &&
                    targetGap > 0L &&
                    abs(referenceGap - targetGap) <= allowedGapError

            if (continues) {
                current++
                longest = max(longest, current)
            } else {
                current = 1
            }
        }

        return longest
    }

    private fun estimateMatchedPairTimelineScale(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
        pairs: List<MatchPair>,
    ): Double? {
        if (pairs.size < MIN_SCALE_VALIDATION_MATCHES) return null

        val firstPair = pairs.first()
        val lastPair = pairs.last()
        val targetSpan =
            target[lastPair.targetIndex].startTimeMs - target[firstPair.targetIndex].startTimeMs
        if (targetSpan < MIN_SCALE_VALIDATION_SPAN_MS) return null

        val slopes = buildList {
            for (leftIndex in 0 until pairs.lastIndex) {
                val left = pairs[leftIndex]
                for (rightIndex in leftIndex + 1 until pairs.size) {
                    val right = pairs[rightIndex]
                    val targetGap =
                        target[right.targetIndex].startTimeMs - target[left.targetIndex].startTimeMs
                    if (targetGap < MIN_SCALE_PAIR_GAP_MS) continue

                    val referenceGap =
                        reference[right.referenceIndex].startTimeMs -
                            reference[left.referenceIndex].startTimeMs
                    if (referenceGap <= 0L) continue

                    val slope = referenceGap.toDouble() / targetGap.toDouble()
                    if (slope in 0.85..1.15) add(slope)
                }
            }
        }

        if (slopes.size < 4) return null
        return median(slopes)
    }

    private fun deduplicateReferenceCues(
        cues: List<SubtitleSyncCue>,
    ): List<SubtitleSyncCue> {
        if (cues.size < 2) return cues

        val sorted = cues.sortedBy { it.startTimeMs }
        val deduplicated = ArrayList<SubtitleSyncCue>(sorted.size)

        for (cue in sorted) {
            val previous = deduplicated.lastOrNull()
            if (previous == null) {
                deduplicated += cue
                continue
            }

            val closeInTime =
                abs(cue.startTimeMs - previous.startTimeMs) <= REFERENCE_DEDUP_WINDOW_MS
            val previousText = normalizedCueText(previous.text)
            val currentText = normalizedCueText(cue.text)
            val sameLogicalCue =
                closeInTime &&
                    (previousText.isBlank() || currentText.isBlank() || previousText == currentText)

            if (!sameLogicalCue) {
                deduplicated += cue
                continue
            }

            // Prefer a cue carrying actual text over a timestamp-only observation.
            if (previousText.isBlank() && currentText.isNotBlank()) {
                deduplicated[deduplicated.lastIndex] = cue
            }
        }

        return deduplicated
    }

    private fun normalizedCueText(text: String): String =
        text
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()

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

    private data class RecommendationCacheKey(
        val sourceKey: String,
        val languageKey: String,
        val candidateFingerprint: String,
        val referenceFingerprint: String,
    )

    private data class CachedRecommendation(
        val url: String,
        val language: String,
        val displayName: String,
        val correctionMs: Int,
        val score: Double,
    ) {
        fun toRecommendation(selectedSubtitleUrl: String): AutoSyncSubtitleRecommendation =
            AutoSyncSubtitleRecommendation(
                url = url,
                language = language,
                displayName = displayName,
                correctionMs = correctionMs,
                score = score,
                isCurrentSubtitle = url == selectedSubtitleUrl,
            )
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

    private data class SubtitleCandidate(
        val url: String,
        val language: String,
        val displayName: String,
        val headers: Map<String, String>,
    )

    private data class ParsedSubtitleCandidate(
        val candidate: SubtitleCandidate,
        val cues: List<SubtitleSyncCue>,
        val downloadMs: Long,
        val parseMs: Long,
    )

    private data class CandidateMatch(
        val parsed: ParsedSubtitleCandidate,
        val track: ReferenceTrack,
        val alignment: AlignmentResult,
    )

    private data class AlignmentAttempt(
        val result: AlignmentResult?,
        val score: Double = 0.0,
        val offsetMs: Long? = null,
        val matches: Int = 0,
        val residualMs: Double = Double.POSITIVE_INFINITY,
        val timelineScale: Double? = null,
        val consecutivePatternMatches: Int = 0,
        val failedChecks: List<String> = emptyList(),
    )

    private data class ScaleAnchor(
        val referenceTimeMs: Long,
        val targetTimeMs: Long,
    )

    private data class IndependentScaleResult(
        val scale: Double,
        val anchorCount: Int,
        val slopeCount: Int,
        val dispersion: Double,
    )

    private data class AlignmentResult(
        val trackKey: String,
        val language: String?,
        val offsetMs: Long,
        val score: Double,
        val matches: Int,
        val residualMs: Double,
        val timelineScale: Double,
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

internal data class AutoSyncSubtitleRecommendation(
    val url: String,
    val language: String,
    val displayName: String,
    val correctionMs: Int,
    val score: Double,
    val isCurrentSubtitle: Boolean,
)

internal data class ReferenceTrack(
    val key: String,
    val language: String?,
    val cues: List<SubtitleSyncCue>,
    val label: String? = null,
    val selectionFlags: Int = 0,
    val roleFlags: Int = 0,
    val generation: Long = 0L,
)

/** Thread-safe accumulation of the embedded text timing already passing through Media3. */
internal object EmbeddedSubtitleCueStore {
    private const val SEEK_DEDUP_WINDOW_MS = 1_500L
    private const val SEEK_TARGET_TOLERANCE_MS = 1_000L

    private data class Track(
        var language: String?,
        var label: String?,
        var selectionFlags: Int,
        var roleFlags: Int,
        val cues: LinkedHashMap<String, SubtitleSyncCue> = linkedMapOf(),
    )

    private val lock = Any()
    private val sources = mutableMapOf<String, MutableMap<String, Track>>()
    private val generations = mutableMapOf<String, Long>()
    private val lastSeekTargetMs = mutableMapOf<String, Long?>()
    private val lastSeekWallMs = mutableMapOf<String, Long>()

    fun reset(sourceKey: String) {
        if (sourceKey.isBlank()) return

        var generation = 0L
        synchronized(lock) {
            generation = (generations[sourceKey] ?: 0L) + 1L
            sources.clear()
            sources[sourceKey] = linkedMapOf()
            generations[sourceKey] = generation
            lastSeekTargetMs.remove(sourceKey)
            lastSeekWallMs.remove(sourceKey)
        }

        AutoSyncDebugLog.verbose("embedded store reset generation=$generation")
    }

    fun beginNewGeneration(sourceKey: String, targetTimeMs: Long?) {
        if (sourceKey.isBlank()) return

        val now = SystemClock.elapsedRealtime()
        var generation: Long? = null
        synchronized(lock) {
            val previousTarget = lastSeekTargetMs[sourceKey]
            val previousWall = lastSeekWallMs[sourceKey]
            val duplicateSeek =
                previousWall != null &&
                    now - previousWall <= SEEK_DEDUP_WINDOW_MS &&
                    when {
                        previousTarget == null && targetTimeMs == null -> true
                        previousTarget != null && targetTimeMs != null ->
                            abs(previousTarget - targetTimeMs) <= SEEK_TARGET_TOLERANCE_MS
                        else -> false
                    }

            if (!duplicateSeek) {
                generation = (generations[sourceKey] ?: 0L) + 1L
                generations[sourceKey] = generation!!
                sources[sourceKey] = linkedMapOf()
            }
            lastSeekTargetMs[sourceKey] = targetTimeMs
            lastSeekWallMs[sourceKey] = now
        }

        generation?.let {
            AutoSyncDebugLog.verbose(
                "embedded store seek generation=$it target=${targetTimeMs ?: -1L}ms",
            )
        }
    }

    fun record(
        sourceKey: String,
        trackKey: String,
        language: String?,
        label: String?,
        selectionFlags: Int,
        roleFlags: Int,
        cue: SubtitleSyncCue,
    ) {
        if (sourceKey.isBlank() || trackKey.isBlank()) return

        var newCue = false
        var cueCount = 0

        synchronized(lock) {
            val track = sources
                .getOrPut(sourceKey) { linkedMapOf() }
                .getOrPut(trackKey) {
                    Track(
                        language = language,
                        label = label,
                        selectionFlags = selectionFlags,
                        roleFlags = roleFlags,
                    )
                }

            track.language = track.language ?: language
            track.label = track.label ?: label
            track.selectionFlags = track.selectionFlags or selectionFlags
            track.roleFlags = track.roleFlags or roleFlags

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

        val generation = generations[sourceKey] ?: 0L

        sources[sourceKey]
            .orEmpty()
            .map { (key, track) ->
                ReferenceTrack(
                    key = key,
                    language = track.language,
                    cues = track.cues.values.sortedBy { it.startTimeMs },
                    label = track.label,
                    selectionFlags = track.selectionFlags,
                    roleFlags = track.roleFlags,
                    generation = generation,
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
