package com.nuvio.app.features.autosync

import android.os.SystemClock
import androidx.media3.common.C
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.SubtitleRepository
import com.nuvio.app.features.player.subtitleLanguageKey
import com.nuvio.app.features.streams.StreamSubtitle
import com.nuvio.app.features.player.SUBTITLE_DELAY_MAX_MS
import com.nuvio.app.features.player.SUBTITLE_DELAY_MIN_MS
import com.nuvio.app.features.player.SubtitleSyncCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Android-only automatic subtitle sync.
 *
 * External/add-on subtitles are parsed using Nuvio's existing [PlayerSubtitleCueParser].
 * Embedded timestamps are first loaded independently from a Matroska Cues index when possible.
 * [AutoSyncExtractorsFactory] remains the fallback for sources that cannot be indexed independently.
 *
 * The matcher is text-independent so different subtitle languages can still synchronize.
 * Verbose debugging DOES log both embedded and add-on cue text so a human can verify that
 * the timing pairs correspond to the same scene/dialogue.
 */
internal object AutomaticSubtitleSync {
    private const val POLL_INTERVAL_MS = 750L
    private const val LIVE_REFERENCE_IDLE_TIMEOUT_MS = 45_000L
    private const val LIVE_REFERENCE_ABSOLUTE_TIMEOUT_MS = 150_000L
    private const val MIN_REFERENCE_CUES = 4
    private const val MIN_ACCEPT_MATCHES = 8
    private const val MIN_REFERENCE_SPAN_MS = 8_000L
    private const val REFERENCE_DEDUP_WINDOW_MS = 125L

    private const val NORMAL_PARTICIPATION_THRESHOLD = 0.55
    private const val TARGET_PARTICIPATION_THRESHOLD = 0.75
    private const val MIN_ASYMMETRIC_TARGET_CUES = 40
    private const val MIN_ASYMMETRIC_SPAN_RATIO = 0.65
    private const val MIN_ASYMMETRIC_TARGET_DENSITY_PER_MINUTE = 1.5

    private const val UNIT_SCALE_FALLBACK_MIN_MATCHES = 40
    private const val UNIT_SCALE_FALLBACK_MIN_TARGET_PARTICIPATION = 0.90
    private const val UNIT_SCALE_FALLBACK_MIN_REFERENCE_COVERAGE = 0.75
    private const val UNIT_SCALE_FALLBACK_MIN_SCORE = 0.70
    private const val UNIT_SCALE_FALLBACK_MIN_CONSECUTIVE = 20

    private const val STRONG_ACCEPT_MATCHES = 20
    private const val STRONG_ACCEPT_RESIDUAL_MS = 250.0
    private const val STRONG_ACCEPT_AGREEMENT = 0.80
    private const val STRONG_ACCEPT_SPACING = 0.85
    private const val STRONG_ACCEPT_MARGIN = 0.10

    private val MIN_APPLICABLE_OFFSET_MS = SUBTITLE_DELAY_MIN_MS.toLong()
    private val MAX_APPLICABLE_OFFSET_MS = SUBTITLE_DELAY_MAX_MS.toLong()
    private const val CANDIDATE_BUCKET_MS = 500L
    private const val MATCH_TOLERANCE_MS = 1_800L
    private const val STRONG_RESIDUAL_MS = 750L
    private const val MIN_OFFSET_MARGIN = 0.025

    private const val CONTIGUOUS_SEGMENT_MAX_GAP_MS = 30_000L
    private const val MIN_FROZEN_REFERENCE_CUES = 8
    private const val MIN_FULL_DIALOGUE_CLASSIFICATION_SPAN_MS = 30_000L
    private const val FALLBACK_FROZEN_REFERENCE_SPAN_MS = 30_000L
    private const val MIN_FROZEN_REFERENCE_SPAN_MS = 45_000L
    private const val PREFERRED_FROZEN_REFERENCE_SPAN_MS = 75_000L
    private const val PREFERRED_REFERENCE_WAIT_MS = 12_000L
    private const val SDH_RANKING_SCORE_PENALTY = 0.015
    private const val INDEPENDENT_SCALE_RANKING_WEIGHT = 0.35
    private const val MATCHED_SCALE_RANKING_WEIGHT = 0.35
    private const val SCALE_DISAGREEMENT_RANKING_WEIGHT = 0.50
    private const val MAX_SCALE_RANKING_PENALTY = 0.010

    private const val MIN_SCALE_VALIDATION_MATCHES = 8
    private const val MIN_SCALE_VALIDATION_SPAN_MS = 20_000L
    private const val MIN_SCALE_PAIR_GAP_MS = 8_000L
    private const val MAX_TIMELINE_SCALE_DEVIATION = 0.008
    private const val MAX_SCALE_ESTIMATOR_DISAGREEMENT = 0.004
    private const val MAX_MATCHED_SCALE_SAMPLE_PAIRS = 32
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
    private const val MAX_PARALLEL_MATCH_GROUPS = 2
    private const val HTTP_429_RETRY_DELAY_MS = 900L
    private const val SUBTITLE_DOWNLOAD_TIMEOUT_MS = 15_000L
    private const val MAX_SUBTITLE_RESPONSE_BYTES = 4 * 1024 * 1024
    private const val MAX_OFFSET_VOTE_REFERENCE_CUES = 48
    private const val MAX_RECOMMENDATION_CACHE_ENTRIES = 16
    private const val MAX_PARSED_CANDIDATE_CACHE_ENTRIES = 64
    private const val MAX_LIVE_REFERENCE_CACHE_ENTRIES = 4
    private const val REFERENCE_MATCH_BATCH_SIZE = 8

    // Only stop scanning additional embedded tracks when the current alignment is essentially exact.
    // This keeps the same matcher/confidence rules while avoiding dozens of redundant comparisons.
    private const val EARLY_REFERENCE_ACCEPT_SCORE = 0.9995
    private const val EARLY_REFERENCE_ACCEPT_MATCHES = 20
    private const val EARLY_REFERENCE_ACCEPT_RESIDUAL_MS = 2.0
    private const val EARLY_REFERENCE_ACCEPT_SCALE_DEVIATION = 0.0005

    private const val EARLY_CANDIDATE_ACCEPT_SCORE = 0.95
    private const val EARLY_CANDIDATE_ACCEPT_PARTICIPATION = 0.90
    private const val EARLY_CANDIDATE_ACCEPT_RESIDUAL_MS = 100.0
    private const val EARLY_CANDIDATE_ACCEPT_MARGIN = 0.15
    private const val EARLY_CANDIDATE_ACCEPT_CONSECUTIVE = 100
    private const val EARLY_CANDIDATE_ACCEPT_SCALE_DEVIATION = 0.0005
    private const val EARLY_CANDIDATE_ACCEPT_SCALE_DISAGREEMENT = 0.00075

    private val recommendationCacheLock = Any()
    private val recommendationCache =
        mutableMapOf<RecommendationCacheKey, CachedRecommendation>()

    private val parsedCandidateCacheLock = Any()
    private val parsedCandidateCache = object : LinkedHashMap<ParsedCandidateCacheKey, CachedParsedSubtitle>(
        MAX_PARSED_CANDIDATE_CACHE_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ParsedCandidateCacheKey, CachedParsedSubtitle>?,
        ): Boolean = size > MAX_PARSED_CANDIDATE_CACHE_ENTRIES
    }

    private val liveReferenceCacheLock = Any()
    private val liveReferenceCache = object : LinkedHashMap<String, List<ReferenceTrack>>(
        MAX_LIVE_REFERENCE_CACHE_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<ReferenceTrack>>?,
        ): Boolean = size > MAX_LIVE_REFERENCE_CACHE_ENTRIES
    }

    suspend fun findBestSubtitleRecommendation(
        sourceKey: String,
        selectedSubtitleUrl: String,
        selectedSubtitleHeaders: Map<String, String>,
        streamSubtitles: List<StreamSubtitle>,
        preferredLanguage: String?,
        includeRepositorySubtitles: Boolean = true,
        onReferenceReady: () -> Unit = {},
        sourceHeaders: Map<String, String> = emptyMap(),
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

        return supervisorScope {
            val indexedTimelineDeferred = async {
                EmbeddedSubtitleTimelineLoader.load(
                    sourceUrl = sourceKey,
                    sourceHeaders = sourceHeaders,
                )
            }
            val downloadSemaphore = Semaphore(MAX_PARALLEL_SUBTITLE_DOWNLOADS)
            val pendingLoads = sameLanguageCandidates
                .mapIndexed { index, candidate ->
                    PendingCandidateLoad(
                        index = index,
                        deferred = async {
                            loadSubtitleCandidate(
                                candidate = candidate,
                                downloadSemaphore = downloadSemaphore,
                            )
                        },
                    )
                }
                .toMutableList()

            val indexedTimeline = indexedTimelineDeferred.await()

            var lastReferenceSignature = ""
            var referenceSnapshot = 0
            var frozenReferenceTracks: List<ReferenceTrack>? = null
            var frozenReferenceMode = ""
            val liveWaitStartedMs = SystemClock.elapsedRealtime()
            var firstUsefulReferenceProgressMs: Long? = null
            var lastUsefulReferenceProgressMs: Long? = null
            var timeoutReason = "absolute safety timeout"

            AutoSyncDebugLog.section("INDEXED EMBEDDED REFERENCE")
            if (indexedTimeline == null) {
                AutoSyncDebugLog.info(
                    "Matroska Cues timeline unavailable; falling back to live Media3 capture",
                )
            } else {
                AutoSyncDebugLog.info(
                    "source=${indexedTimeline.source} tracks=${indexedTimeline.tracks.size} " +
                        "requests=${indexedTimeline.rangeRequests} bytes=${indexedTimeline.bytesDownloaded} " +
                        "load=${indexedTimeline.loadMs}ms",
                )
                val indexedPreparedTracks = indexedTimeline.tracks.map { track ->
                    track.copy(cues = deduplicateReferenceCues(track.cues))
                }
                indexedPreparedTracks.forEachIndexed { index, track ->
                    AutoSyncDebugLog.info(
                        "indexed[$index] track=${track.key} lang=${track.language ?: "<unknown>"} " +
                            "label=${track.label ?: "<none>"} selectionFlags=${track.selectionFlags} " +
                            "roleFlags=${track.roleFlags} cues=${track.cues.size} " +
                            "span=${referenceSpanMs(track.cues)}ms " +
                            "fullDialogue=${isLikelyFullDialogueTrack(track)}",
                    )
                }
                val indexedFullDialogueTracks = indexedPreparedTracks.filter { track ->
                    isLikelyFullDialogueTrack(track) &&
                        track.cues.size >= MIN_FROZEN_REFERENCE_CUES &&
                        referenceSpanMs(track.cues) >= MIN_FROZEN_REFERENCE_SPAN_MS
                }
                if (indexedFullDialogueTracks.isNotEmpty()) {
                    onReferenceReady()
                    frozenReferenceMode = "indexed-matroska-cues"
                    frozenReferenceTracks = orderReferenceTracks(indexedFullDialogueTracks).map { track ->
                        track.copy(cues = track.cues.toList())
                    }
                    AutoSyncDebugLog.info(
                        "using complete indexed subtitle timelines tracks=${frozenReferenceTracks.size}",
                    )
                } else {
                    AutoSyncDebugLog.info(
                        "indexed Cues did not contain a usable full-dialogue timeline; falling back to live Media3 capture",
                    )
                }
            }

            if (frozenReferenceTracks == null) {
                val cachedLiveReferences = synchronized(liveReferenceCacheLock) {
                    liveReferenceCache[sourceKey]
                }
                if (!cachedLiveReferences.isNullOrEmpty()) {
                    onReferenceReady()
                    frozenReferenceMode = "cached-live-media3"
                    frozenReferenceTracks = cachedLiveReferences
                    AutoSyncDebugLog.info(
                        "using cached live Media3 reference tracks=${cachedLiveReferences.size} " +
                            "generation=${cachedLiveReferences.firstOrNull()?.generation ?: -1L}",
                    )
                }
            }

            while (frozenReferenceTracks == null) {
                val nowMs = SystemClock.elapsedRealtime()
                val absoluteTimedOut =
                    nowMs - liveWaitStartedMs >= LIVE_REFERENCE_ABSOLUTE_TIMEOUT_MS
                val idleTimedOut = lastUsefulReferenceProgressMs?.let { lastProgressMs ->
                    nowMs - lastProgressMs >= LIVE_REFERENCE_IDLE_TIMEOUT_MS
                } ?: false

                if (absoluteTimedOut || idleTimedOut) {
                    timeoutReason = if (idleTimedOut) {
                        "no useful embedded-reference progress for ${LIVE_REFERENCE_IDLE_TIMEOUT_MS}ms"
                    } else {
                        "absolute live-reference timeout ${LIVE_REFERENCE_ABSOLUTE_TIMEOUT_MS}ms"
                    }
                    break
                }

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
                    "${track.key}:g${track.generation}:${track.cues.size}:" +
                        "${track.cues.lastOrNull()?.startTimeMs ?: -1L}"
                }

                if (signature != lastReferenceSignature) {
                    lastReferenceSignature = signature
                    referenceSnapshot++
                    if (fullDialogueTracks.isNotEmpty()) {
                        if (firstUsefulReferenceProgressMs == null) {
                            firstUsefulReferenceProgressMs = nowMs
                        }
                        lastUsefulReferenceProgressMs = nowMs
                    }

                    AutoSyncDebugLog.section("REFERENCE SNAPSHOT #$referenceSnapshot")
                    val elapsedMs = nowMs - liveWaitStartedMs
                    AutoSyncDebugLog.info(
                        "waited=${elapsedMs}ms tracks=${preparedTracks.size} " +
                            "fullDialogue=${fullDialogueTracks.size} ready30=${fallbackTracks.size} " +
                            "ready45=${minimumTracks.size} ready75=${preferredTracks.size}",
                    )

                    if (preparedTracks.isEmpty()) {
                        AutoSyncDebugLog.info("no embedded text tracks captured yet")
                    }

                    preparedTracks.forEachIndexed { trackIndex, track ->
                        val raw = rawTracks.getOrNull(trackIndex)
                        val rawCueCount = raw?.cues?.size ?: track.cues.size
                        val dedupedCueCount =
                            raw?.let { deduplicateReferenceCues(it.cues).size } ?: track.cues.size
                        val segmentCueCount = track.cues.size
                        val span = referenceSpanMs(track.cues)
                        val density = referenceCueDensityPerMinute(track.cues)

                        AutoSyncDebugLog.info(
                            "track[$trackIndex] key=${track.key} generation=${track.generation} " +
                                "lang=${track.language ?: "<unknown>"} label=${track.label ?: "<none>"} " +
                                "selectionFlags=${track.selectionFlags} roleFlags=${track.roleFlags} " +
                                "rawCues=$rawCueCount dedupedCues=$dedupedCueCount segmentCues=$segmentCueCount " +
                                "span=${span}ms density=${"%.2f".format(density)}/min " +
                                "fullDialogue=${track in fullDialogueTracks} " +
                                "ready30=${track in fallbackTracks} ready45=${track in minimumTracks} " +
                                "ready75=${track in preferredTracks}",
                        )

                        track.cues.take(MAX_LOGGED_CUE_SAMPLES).forEachIndexed { cueIndex, cue ->
                            AutoSyncDebugLog.cue(
                                prefix = "EMBEDDED ${track.key} g${track.generation}",
                                index = cueIndex,
                                startMs = cue.startTimeMs,
                                endMs = cue.endTimeMs,
                                text = cue.text,
                            )
                        }
                    }
                }

                val usefulEvidenceAgeMs = firstUsefulReferenceProgressMs?.let {
                    nowMs - it
                } ?: 0L

                val readyTracks = when {
                    preferredTracks.isNotEmpty() -> {
                        frozenReferenceMode = "preferred-75s"
                        preferredTracks
                    }
                    minimumTracks.isNotEmpty() -> {
                        frozenReferenceMode = "minimum-45s"
                        minimumTracks
                    }
                    fallbackTracks.isNotEmpty() &&
                        usefulEvidenceAgeMs >= PREFERRED_REFERENCE_WAIT_MS -> {
                        frozenReferenceMode = "fallback-30s"
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
                    cacheLiveReferenceTracks(sourceKey, frozen)

                    AutoSyncDebugLog.section("FROZEN REFERENCES")
                    AutoSyncDebugLog.info("mode=$frozenReferenceMode tracks=${frozen.size}")
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
            }

            val referenceTracks = frozenReferenceTracks ?: run {
                pendingLoads.forEach { it.deferred.cancel() }
                AutoSyncDebugLog.section("TIMEOUT")
                AutoSyncDebugLog.warn("no usable full-dialogue embedded reference; $timeoutReason")
                return@supervisorScope null
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
                pendingLoads.forEach { it.deferred.cancel() }
                AutoSyncDebugLog.section("RECOMMENDATION CACHE")
                AutoSyncDebugLog.info(
                    "HIT references=${referenceTracks.size} fingerprint=$referenceFingerprint " +
                        "name=${cached.displayName} correction=${cached.correctionMs}ms score=${fmt(cached.score)}",
                )
                return@supervisorScope cached.toRecommendation(selectedSubtitleUrl)
            }

            val referenceTimingGroups = groupEquivalentReferenceTimelines(referenceTracks)
            AutoSyncDebugLog.section("REFERENCE TIMING DEDUPLICATION")
            AutoSyncDebugLog.info(
                "reference timing timelines=${referenceTimingGroups.size}/${referenceTracks.size} " +
                    "duplicatesSaved=${referenceTracks.size - referenceTimingGroups.size}",
            )
            referenceTimingGroups
                .filter { it.members.size > 1 }
                .forEachIndexed { index, group ->
                    AutoSyncDebugLog.info(
                        "referenceTiming[$index] reused=${group.members.size} tracks=" +
                            group.members.joinToString(",") { track ->
                                "${track.key}:${track.language ?: "<unknown>"}"
                            },
                    )
                }

            AutoSyncDebugLog.section("CANDIDATE MATCH SUMMARY")
            val parsedCandidates = mutableListOf<ParsedSubtitleCandidate>()
            val timingBuckets =
                linkedMapOf<CandidateTimingFingerprint, MutableList<PipelineTimingGroupState>>()
            val pendingMatches = mutableListOf<PendingTimingMatch>()
            val groupResults = mutableListOf<CandidateTimingGroupResult>()
            val candidateMatches = mutableListOf<CandidateMatch>()
            val matchSemaphore = Semaphore(MAX_PARALLEL_MATCH_GROUPS)
            var selectedCueSamplesLogged = false
            var earlyStopped = false

            fun addMemberResult(
                parsed: ParsedSubtitleCandidate,
                summary: CandidateReferenceSummary,
                reusedTiming: Boolean,
            ) {
                val candidateIndex = candidateOrder[parsed.candidate.url] ?: -1
                val bestAccepted = summary.bestAccepted?.let { match ->
                    CandidateMatch(parsed = parsed, track = match.track, alignment = match.alignment)
                }

                if (bestAccepted != null) {
                    candidateMatches += bestAccepted
                    AutoSyncDebugLog.info(
                        "candidate[$candidateIndex] ACCEPT track=${bestAccepted.track.key} " +
                            "label=${bestAccepted.track.label ?: "<none>"} " +
                            "score=${fmt(bestAccepted.alignment.score)} " +
                            "rankScore=${fmt(adjustedAlignmentScore(bestAccepted))} " +
                            "matches=${bestAccepted.alignment.matches} " +
                            "offset=${bestAccepted.alignment.offsetMs}ms " +
                            "scale=${"%.6f".format(bestAccepted.alignment.timelineScale)} " +
                            "pairScale=${bestAccepted.alignment.matchedPairScale?.let { "%.6f".format(it) } ?: "<unavailable>"} " +
                            "scaleDisagreement=${"%.6f".format(bestAccepted.alignment.scaleDisagreement)} " +
                            "consecutive=${summary.winningAttempt?.consecutivePatternMatches ?: 0} " +
                            "references=${summary.attempts.size}/${referenceTracks.size} reusedTiming=$reusedTiming",
                    )
                } else {
                    val bestAttempt = summary.bestRejectedAttempt
                    if (bestAttempt != null) {
                        val track = bestAttempt.first
                        val attemptResult = bestAttempt.second
                        AutoSyncDebugLog.info(
                            "candidate[$candidateIndex] REJECT bestTrack=${track.key} " +
                                "label=${track.label ?: "<none>"} score=${fmt(attemptResult.score)} " +
                                "matches=${attemptResult.matches} " +
                                "offset=${attemptResult.offsetMs?.let { "${it}ms" } ?: "<none>"} " +
                                "scale=${attemptResult.timelineScale?.let { "%.6f".format(it) } ?: "<unavailable>"} " +
                                "pairScale=${attemptResult.matchedPairScale?.let { "%.6f".format(it) } ?: "<unavailable>"} " +
                                "consecutive=${attemptResult.consecutivePatternMatches} " +
                                "references=${summary.attempts.size}/${referenceTracks.size} reusedTiming=$reusedTiming " +
                                "failed=${attemptResult.failedChecks.joinToString(",").ifBlank { "unknown" }}",
                        )
                    }
                }
            }

            while ((pendingLoads.isNotEmpty() || pendingMatches.isNotEmpty()) && !earlyStopped) {
                val event = select<PipelineEvent> {
                    pendingLoads.forEach { load ->
                        load.deferred.onAwait { parsed ->
                            PipelineEvent.CandidateLoaded(load.index, parsed)
                        }
                    }
                    pendingMatches.forEach { match ->
                        match.deferred.onAwait { summary ->
                            PipelineEvent.TimingMatched(match.state, summary)
                        }
                    }
                }

                when (event) {
                    is PipelineEvent.CandidateLoaded -> {
                        pendingLoads.removeAll { it.index == event.index }
                        val parsed = event.parsed
                        if (parsed != null) {
                            parsedCandidates += parsed
                            AutoSyncDebugLog.info(
                                "candidate[${event.index}] name=${parsed.candidate.displayName} " +
                                    "lang=${parsed.candidate.language.ifBlank { "<unknown>" }} " +
                                    "selected=${parsed.candidate.url == selectedSubtitleUrl} cues=${parsed.cues.size} " +
                                    "download=${parsed.downloadMs}ms parse=${parsed.parseMs}ms cached=${parsed.cacheHit}",
                            )

                            if (!selectedCueSamplesLogged && parsed.candidate.url == selectedSubtitleUrl) {
                                selectedCueSamplesLogged = true
                                parsed.cues.take(MAX_LOGGED_CUE_SAMPLES).forEachIndexed { index, cue ->
                                    AutoSyncDebugLog.cue(
                                        prefix = "SELECTED ADDON",
                                        index = index,
                                        startMs = cue.startTimeMs,
                                        endMs = cue.endTimeMs,
                                        text = cue.text,
                                    )
                                }
                            }

                            val fingerprint = candidateTimingFingerprint(parsed.cues)
                            val bucket = timingBuckets.getOrPut(fingerprint) { mutableListOf() }
                            val existingState = bucket.firstOrNull { state ->
                                sameCandidateTiming(state.group.members.first().cues, parsed.cues)
                            }

                            if (existingState != null) {
                                existingState.group.members += parsed
                                existingState.summary?.let { summary ->
                                    addMemberResult(parsed, summary, reusedTiming = true)
                                }
                            } else {
                                val group = CandidateTimingGroup(
                                    fingerprint = fingerprint,
                                    members = mutableListOf(parsed),
                                )
                                val state = PipelineTimingGroupState(group)
                                bucket += state
                                pendingMatches += PendingTimingMatch(
                                    state = state,
                                    deferred = async(Dispatchers.Default) {
                                        matchSemaphore.withPermit {
                                            matchCandidateAgainstReferences(parsed, referenceTimingGroups)
                                        }
                                    },
                                )
                            }
                        }
                    }

                    is PipelineEvent.TimingMatched -> {
                        pendingMatches.removeAll { it.state === event.state }
                        event.state.summary = event.summary
                        groupResults += CandidateTimingGroupResult(event.state.group, event.summary)
                        event.state.group.members.forEachIndexed { memberIndex, parsed ->
                            addMemberResult(parsed, event.summary, reusedTiming = memberIndex > 0)
                        }

                        if (canStopCandidateSearch(event.summary)) {
                            earlyStopped = true
                            val best = event.summary.bestAccepted
                            AutoSyncDebugLog.info(
                                "EARLY STOP candidate search score=${best?.let { fmt(adjustedAlignmentScore(it)) } ?: "<none>"} " +
                                    "remainingDownloads=${pendingLoads.size} remainingMatches=${pendingMatches.size}",
                            )
                            pendingLoads.forEach { it.deferred.cancel() }
                            pendingMatches.forEach { it.deferred.cancel() }
                            pendingLoads.clear()
                            pendingMatches.clear()
                        }
                    }
                }
            }

            val timingGroupCount = timingBuckets.values.sumOf { it.size }
            AutoSyncDebugLog.info(
                "timing timelines=$timingGroupCount/${parsedCandidates.size} " +
                    "duplicatesSaved=${parsedCandidates.size - timingGroupCount} earlyStop=$earlyStopped",
            )

            if (parsedCandidates.isEmpty()) {
                AutoSyncDebugLog.warn("REJECT no same-language subtitle candidate could be parsed")
                return@supervisorScope null
            }
            if (candidateMatches.isEmpty()) {
                AutoSyncDebugLog.section("FINAL RECOMMENDATION")
                AutoSyncDebugLog.warn(
                    "REJECT no same-language subtitle passed any eligible full-dialogue reference track",
                )
                return@supervisorScope null
            }

            val ranked = candidateMatches.sortedWith(
                compareByDescending<CandidateMatch> { adjustedAlignmentScore(it) }
                    .thenByDescending { it.alignment.matches }
                    .thenBy { abs(it.alignment.timelineScale - 1.0) }
                    .thenBy { it.alignment.scaleDisagreement }
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
                        "pairScale=${match.alignment.matchedPairScale?.let { "%.6f".format(it) } ?: "<unavailable>"} " +
                        "scalePenalty=${fmt(alignmentScalePenalty(match.alignment))} " +
                        "score=${fmt(match.alignment.score)} rankScore=${fmt(adjustedAlignmentScore(match))} " +
                        "matches=${match.alignment.matches} residual=${"%.1f".format(match.alignment.residualMs)}ms",
                )
            }

            AutoSyncDebugLog.section("WINNING SUBTITLE DETAILS")
            val winningAttempt = groupResults
                .firstOrNull { groupResult ->
                    groupResult.group.members.any { member ->
                        member.candidate.url == bestMatch.parsed.candidate.url
                    }
                }
                ?.summary?.winningAttempt
                ?.takeIf { attempt -> attempt.result?.trackKey == bestMatch.track.key }
            if (winningAttempt != null) {
                logAlignmentAttempt(bestMatch.track, bestMatch.parsed.cues, winningAttempt)
            } else {
                AutoSyncDebugLog.warn("winning alignment details unavailable without rematching")
            }

            val rawCorrectionMs = bestMatch.alignment.offsetMs
            if (rawCorrectionMs !in MIN_APPLICABLE_OFFSET_MS..MAX_APPLICABLE_OFFSET_MS) {
                AutoSyncDebugLog.section("FINAL RECOMMENDATION")
                AutoSyncDebugLog.warn(
                    "REJECT correction ${rawCorrectionMs}ms is outside Nuvio's applicable range " +
                        "[$MIN_APPLICABLE_OFFSET_MS, $MAX_APPLICABLE_OFFSET_MS]ms",
                )
                return@supervisorScope null
            }
            val correctionMs = rawCorrectionMs.toInt()

            AutoSyncDebugLog.section("FINAL RECOMMENDATION")
            AutoSyncDebugLog.info(
                "name=${bestMatch.parsed.candidate.displayName} " +
                    "lang=${bestMatch.parsed.candidate.language.ifBlank { "<unknown>" }} " +
                    "selected=${bestMatch.parsed.candidate.url == selectedSubtitleUrl} " +
                    "reference=${bestMatch.track.key} label=${bestMatch.track.label ?: "<none>"}",
            )
            AutoSyncDebugLog.info(
                "correction=${correctionMs}ms scale=${"%.6f".format(bestMatch.alignment.timelineScale)} " +
                    "pairScale=${bestMatch.alignment.matchedPairScale?.let { "%.6f".format(it) } ?: "<unavailable>"} " +
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

            cachedRecommendation.toRecommendation(selectedSubtitleUrl)
        }
    }

    /** Keeps the old single-subtitle API available for any other caller. */
    suspend fun findDelayCorrectionMs(
        sourceKey: String,
        subtitleUrl: String,
        subtitleHeaders: Map<String, String>,
        preferredLanguage: String?,
        onReferenceReady: () -> Unit = {},
        sourceHeaders: Map<String, String> = emptyMap(),
    ): Int? = findBestSubtitleRecommendation(
        sourceKey = sourceKey,
        selectedSubtitleUrl = subtitleUrl,
        selectedSubtitleHeaders = subtitleHeaders,
        streamSubtitles = emptyList(),
        preferredLanguage = preferredLanguage,
        includeRepositorySubtitles = false,
        onReferenceReady = onReferenceReady,
        sourceHeaders = sourceHeaders,
    )?.takeIf { it.isCurrentSubtitle }?.correctionMs

    private fun cacheLiveReferenceTracks(
        sourceKey: String,
        tracks: List<ReferenceTrack>,
    ) {
        if (sourceKey.isBlank() || tracks.isEmpty()) return
        val immutableCopy = tracks.map { track -> track.copy(cues = track.cues.toList()) }
        synchronized(liveReferenceCacheLock) {
            liveReferenceCache[sourceKey] = immutableCopy
        }
    }

    private fun groupEquivalentReferenceTimelines(
        referenceTracks: List<ReferenceTrack>,
    ): List<ReferenceTimingGroup> {
        val buckets = linkedMapOf<ReferenceTimingFingerprint, MutableList<ReferenceTimingGroup>>()
        referenceTracks.forEach { track ->
            val fingerprint = referenceTimingFingerprint(track.cues)
            val bucket = buckets.getOrPut(fingerprint) { mutableListOf() }
            val exactGroup = bucket.firstOrNull { group ->
                sameReferenceTiming(group.members.first().cues, track.cues)
            }
            if (exactGroup != null) {
                exactGroup.members += track
            } else {
                bucket += ReferenceTimingGroup(
                    fingerprint = fingerprint,
                    members = mutableListOf(track),
                )
            }
        }
        return buckets.values.flatten()
    }

    private fun referenceTimingFingerprint(
        cues: List<SubtitleSyncCue>,
    ): ReferenceTimingFingerprint {
        var timingHash = 1_125_899_906_842_597L
        for (cue in cues) {
            // AutoSync's alignment model is based on cue start times; cue end times do not
            // participate in offset, spacing, scale, or consecutive-pattern matching.
            timingHash = timingHash * 31L + cue.startTimeMs
        }
        return ReferenceTimingFingerprint(
            cueCount = cues.size,
            firstStartMs = cues.firstOrNull()?.startTimeMs ?: -1L,
            lastStartMs = cues.lastOrNull()?.startTimeMs ?: -1L,
            timingHash = timingHash,
        )
    }

    private fun sameReferenceTiming(
        left: List<SubtitleSyncCue>,
        right: List<SubtitleSyncCue>,
    ): Boolean {
        if (left.size != right.size) return false
        return left.indices.all { index ->
            left[index].startTimeMs == right[index].startTimeMs
        }
    }

    private fun groupEquivalentCandidateTimelines(
        parsedCandidates: List<ParsedSubtitleCandidate>,
    ): List<CandidateTimingGroup> {
        val buckets = linkedMapOf<CandidateTimingFingerprint, MutableList<CandidateTimingGroup>>()
        parsedCandidates.forEach { parsed ->
            val fingerprint = candidateTimingFingerprint(parsed.cues)
            val bucket = buckets.getOrPut(fingerprint) { mutableListOf() }
            val exactGroup = bucket.firstOrNull { group ->
                sameCandidateTiming(group.members.first().cues, parsed.cues)
            }
            if (exactGroup != null) {
                exactGroup.members += parsed
            } else {
                bucket += CandidateTimingGroup(
                    fingerprint = fingerprint,
                    members = mutableListOf(parsed),
                )
            }
        }
        return buckets.values.flatten()
    }

    private fun candidateTimingFingerprint(
        cues: List<SubtitleSyncCue>,
    ): CandidateTimingFingerprint {
        var timingHash = 1_125_899_906_842_597L
        for (cue in cues) {
            timingHash = timingHash * 31L + cue.startTimeMs
            timingHash = timingHash * 31L + cue.endTimeMs
        }
        return CandidateTimingFingerprint(
            cueCount = cues.size,
            firstStartMs = cues.firstOrNull()?.startTimeMs ?: -1L,
            lastStartMs = cues.lastOrNull()?.startTimeMs ?: -1L,
            timingHash = timingHash,
        )
    }

    private fun sameCandidateTiming(
        left: List<SubtitleSyncCue>,
        right: List<SubtitleSyncCue>,
    ): Boolean {
        if (left.size != right.size) return false
        return left.indices.all { index ->
            left[index].startTimeMs == right[index].startTimeMs &&
                left[index].endTimeMs == right[index].endTimeMs
        }
    }

    private fun matchCandidateAgainstReferences(
        parsed: ParsedSubtitleCandidate,
        referenceTimingGroups: List<ReferenceTimingGroup>,
    ): CandidateReferenceSummary {
        val attempts = mutableListOf<Pair<ReferenceTrack, AlignmentAttempt>>()
        var bestAccepted: CandidateMatch? = null
        var winningAttempt: AlignmentAttempt? = null

        for (batch in referenceTimingGroups.chunked(REFERENCE_MATCH_BATCH_SIZE)) {
            for (group in batch) {
                val representative = group.members.first()
                val representativeAttempt = attemptAlignment(
                    track = representative,
                    target = parsed.cues,
                    logDetails = false,
                )

                group.members.forEachIndexed { memberIndex, track ->
                    val attempt = if (memberIndex == 0) {
                        representativeAttempt
                    } else {
                        representativeAttempt.forEquivalentReferenceTrack(track)
                    }

                    // Preserve one logical attempt per embedded track for diagnostics/ranking,
                    // while the expensive timing calculation above ran only once for the group.
                    attempts += track to attempt.copy(debugDetails = null)
                    val alignment = attempt.result ?: return@forEachIndexed
                    val match = CandidateMatch(
                        parsed = parsed,
                        track = track,
                        alignment = alignment,
                    )
                    val previousBest = bestAccepted
                    if (
                        previousBest == null ||
                        candidateMatchComparator.compare(match, previousBest) < 0
                    ) {
                        bestAccepted = match
                        winningAttempt = attempt
                    }
                }
            }

            val currentBest = bestAccepted
            if (currentBest != null && canStopReferenceSearch(currentBest)) {
                break
            }
        }

        val bestRejectedAttempt = if (bestAccepted == null && attempts.isNotEmpty()) {
            attempts.sortedWith(
                compareBy<Pair<ReferenceTrack, AlignmentAttempt>> { it.second.failedChecks.size }
                    .thenByDescending { it.second.score }
                    .thenByDescending { it.second.matches }
                    .thenBy { it.second.residualMs }
                    .thenBy { it.first.key },
            ).first()
        } else {
            null
        }

        return CandidateReferenceSummary(
            bestAccepted = bestAccepted,
            winningAttempt = winningAttempt,
            bestRejectedAttempt = bestRejectedAttempt,
            attempts = attempts,
        )
    }

    private fun AlignmentAttempt.forEquivalentReferenceTrack(
        track: ReferenceTrack,
    ): AlignmentAttempt = copy(
        result = result?.copy(
            trackKey = track.key,
            language = track.language,
        ),
    )

    private val candidateMatchComparator: Comparator<CandidateMatch> =
        compareByDescending<CandidateMatch> { adjustedAlignmentScore(it) }
            .thenByDescending { it.alignment.matches }
            .thenBy { abs(it.alignment.timelineScale - 1.0) }
            .thenBy { it.alignment.residualMs }
            .thenBy { abs(it.alignment.offsetMs) }
            .thenBy { it.track.key }

    private fun canStopReferenceSearch(match: CandidateMatch): Boolean =
        !isSdhReferenceTrack(match.track) &&
            adjustedAlignmentScore(match) >= EARLY_REFERENCE_ACCEPT_SCORE &&
            match.alignment.matches >= EARLY_REFERENCE_ACCEPT_MATCHES &&
            match.alignment.residualMs <= EARLY_REFERENCE_ACCEPT_RESIDUAL_MS &&
            abs(match.alignment.timelineScale - 1.0) <= EARLY_REFERENCE_ACCEPT_SCALE_DEVIATION &&
            (match.alignment.matchedPairScale == null ||
                abs(match.alignment.matchedPairScale - 1.0) <=
                    EARLY_REFERENCE_ACCEPT_SCALE_DEVIATION) &&
            match.alignment.scaleDisagreement <= EARLY_REFERENCE_ACCEPT_SCALE_DEVIATION

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
        match.alignment.score -
            (if (isSdhReferenceTrack(match.track)) SDH_RANKING_SCORE_PENALTY else 0.0) -
            alignmentScalePenalty(match.alignment)

    private fun alignmentScalePenalty(alignment: AlignmentResult): Double {
        val independentDeviation = abs(alignment.timelineScale - 1.0)
        val matchedDeviation = alignment.matchedPairScale
            ?.let { abs(it - 1.0) }
            ?: independentDeviation
        return (
            independentDeviation * INDEPENDENT_SCALE_RANKING_WEIGHT +
                matchedDeviation * MATCHED_SCALE_RANKING_WEIGHT +
                alignment.scaleDisagreement * SCALE_DISAGREEMENT_RANKING_WEIGHT
            ).coerceAtMost(MAX_SCALE_RANKING_PENALTY)
    }

    private fun canStopCandidateSearch(summary: CandidateReferenceSummary): Boolean {
        val match = summary.bestAccepted ?: return false
        val attempt = summary.winningAttempt ?: return false
        val details = attempt.debugDetails ?: return false
        val matchedScale = match.alignment.matchedPairScale ?: return false

        return !isSdhReferenceTrack(match.track) &&
            adjustedAlignmentScore(match) >= EARLY_CANDIDATE_ACCEPT_SCORE &&
            details.effectiveParticipation >= EARLY_CANDIDATE_ACCEPT_PARTICIPATION &&
            match.alignment.residualMs <= EARLY_CANDIDATE_ACCEPT_RESIDUAL_MS &&
            details.margin >= EARLY_CANDIDATE_ACCEPT_MARGIN &&
            attempt.consecutivePatternMatches >= EARLY_CANDIDATE_ACCEPT_CONSECUTIVE &&
            abs(match.alignment.timelineScale - 1.0) <= EARLY_CANDIDATE_ACCEPT_SCALE_DEVIATION &&
            abs(matchedScale - 1.0) <= EARLY_CANDIDATE_ACCEPT_SCALE_DEVIATION &&
            match.alignment.scaleDisagreement <= EARLY_CANDIDATE_ACCEPT_SCALE_DISAGREEMENT
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
            .sortedWith(compareBy<ReferenceTrack> { it.key }.thenBy { it.generation })
            .joinToString("|") { referenceFingerprint(it) }

    private suspend fun loadSubtitleCandidate(
        candidate: SubtitleCandidate,
        downloadSemaphore: Semaphore,
    ): ParsedSubtitleCandidate? {
        val cacheKey = parsedCandidateCacheKey(candidate)
        synchronized(parsedCandidateCacheLock) {
            parsedCandidateCache[cacheKey]
        }?.let { cached ->
            return ParsedSubtitleCandidate(
                candidate = candidate,
                cues = cached.cues,
                downloadMs = 0L,
                parseMs = 0L,
                cacheHit = true,
            )
        }

        val downloadStarted = SystemClock.elapsedRealtime()
        val subtitleText = try {
            downloadSubtitleTextWithSingle429Retry(
                candidate = candidate,
                downloadSemaphore = downloadSemaphore,
            )
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
            withContext(Dispatchers.Default) {
                PlayerSubtitleCueParser.parse(
                    text = subtitleText,
                    sourceUrl = candidate.url,
                )
            }
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

        val immutableCues = cues.toList()
        synchronized(parsedCandidateCacheLock) {
            parsedCandidateCache[cacheKey] = CachedParsedSubtitle(immutableCues)
        }

        return ParsedSubtitleCandidate(
            candidate = candidate,
            cues = immutableCues,
            downloadMs = downloadMs,
            parseMs = parseMs,
            cacheHit = false,
        )
    }

    /**
     * A rate-limited subtitle request gets exactly one retry (two total attempts), never a loop.
     * Each actual request has an AutoSync-specific timeout and bounded response body.
     */
    private suspend fun downloadSubtitleTextWithSingle429Retry(
        candidate: SubtitleCandidate,
        downloadSemaphore: Semaphore,
    ): String {
        val first = requestSubtitleCandidate(candidate, downloadSemaphore)
        if (first.status == 429) {
            AutoSyncDebugLog.warn(
                "candidate HTTP 429 name=${candidate.displayName}; retrying once after " +
                    "${HTTP_429_RETRY_DELAY_MS}ms",
            )
            delay(HTTP_429_RETRY_DELAY_MS)
            return validatedSubtitleBody(
                response = requestSubtitleCandidate(candidate, downloadSemaphore),
                candidate = candidate,
            )
        }
        return validatedSubtitleBody(first, candidate)
    }

    private suspend fun requestSubtitleCandidate(
        candidate: SubtitleCandidate,
        downloadSemaphore: Semaphore,
    ) = downloadSemaphore.withPermit {
        withTimeoutOrNull(SUBTITLE_DOWNLOAD_TIMEOUT_MS) {
            httpRequestRaw(
                method = "GET",
                url = candidate.url,
                headers = mapOf("Accept" to "application/json") + candidate.headers,
                body = "",
                followRedirects = true,
                maxResponseBodyBytes = MAX_SUBTITLE_RESPONSE_BYTES,
            )
        } ?: error("subtitle request timed out after ${SUBTITLE_DOWNLOAD_TIMEOUT_MS}ms")
    }

    private fun validatedSubtitleBody(
        response: com.nuvio.app.features.addons.RawHttpResponse,
        candidate: SubtitleCandidate,
    ): String {
        if (response.status !in 200..299) {
            error("subtitle HTTP ${response.status} for ${candidate.displayName}")
        }
        if (response.body.endsWith("\n...[truncated]")) {
            error(
                "subtitle response exceeded $MAX_SUBTITLE_RESPONSE_BYTES bytes for " +
                    candidate.displayName,
            )
        }
        if (response.body.isBlank()) {
            error("empty subtitle response for ${candidate.displayName}")
        }
        return response.body
    }

    private fun parsedCandidateCacheKey(candidate: SubtitleCandidate): ParsedCandidateCacheKey {
        var headerHash = 1
        candidate.headers.entries
            .sortedBy { it.key.lowercase() }
            .forEach { (key, value) ->
                headerHash = 31 * headerHash + key.lowercase().hashCode()
                headerHash = 31 * headerHash + value.hashCode()
            }
        return ParsedCandidateCacheKey(
            url = candidate.url,
            headerHash = headerHash,
        )
    }

    private fun attemptAlignment(
        track: ReferenceTrack,
        target: List<SubtitleSyncCue>,
        logDetails: Boolean = true,
    ): AlignmentAttempt {
        val reference = track.cues
        val candidates = candidateOffsets(reference, target)
        if (candidates.isEmpty()) {
            if (logDetails) AutoSyncDebugLog.warn("no candidate offsets")
            return AlignmentAttempt(
                result = null,
                failedChecks = listOf("candidate offsets"),
            )
        }

        val refinements = mutableListOf<OffsetRefinement>()
        val evaluations = buildList {
            for (candidate in candidates) {
                val initial = evaluate(reference, target, candidate.offsetMs)
                add(initial)

                if (initial.matches >= MIN_REFERENCE_CUES) {
                    val refinedOffset =
                        candidate.offsetMs + initial.signedResidualMs.roundToLong()

                    if (
                        refinedOffset != candidate.offsetMs &&
                        refinedOffset in MIN_APPLICABLE_OFFSET_MS..MAX_APPLICABLE_OFFSET_MS
                    ) {
                        refinements += OffsetRefinement(
                            originalOffsetMs = candidate.offsetMs,
                            refinedOffsetMs = refinedOffset,
                            signedResidualMs = initial.signedResidualMs,
                        )
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
        val targetParticipation = best.matches.toDouble() / target.size
        val asymmetricTargetEligible = isAsymmetricTargetParticipationEligible(reference, target)
        val effectiveParticipation = if (asymmetricTargetEligible) {
            max(referenceParticipation, targetParticipation)
        } else {
            referenceParticipation
        }
        val normalParticipation =
            referenceParticipation >= NORMAL_PARTICIPATION_THRESHOLD ||
                (asymmetricTargetEligible &&
                    targetParticipation >= TARGET_PARTICIPATION_THRESHOLD)

        val consecutivePatternMatches = longestConsecutivePatternRun(reference, target, best.pairs)
        val strongConstantOffsetEvidence =
            asymmetricTargetEligible &&
                best.matches >= UNIT_SCALE_FALLBACK_MIN_MATCHES &&
                targetParticipation >= UNIT_SCALE_FALLBACK_MIN_TARGET_PARTICIPATION &&
                best.referenceCoverage >= UNIT_SCALE_FALLBACK_MIN_REFERENCE_COVERAGE &&
                best.score >= UNIT_SCALE_FALLBACK_MIN_SCORE &&
                consecutivePatternMatches >= UNIT_SCALE_FALLBACK_MIN_CONSECUTIVE

        val independentScale = estimateIndependentTimelineScale(
            reference = reference,
            target = target,
            expectedOffsetMs = best.offsetMs,
        )
        val independentTimelineScale = independentScale?.scale
        val matchedPairScale = estimateMatchedPairTimelineScale(reference, target, best.pairs)
        val scaleDisagreement = if (independentTimelineScale != null && matchedPairScale != null) {
            abs(independentTimelineScale - matchedPairScale)
        } else {
            0.0
        }
        val resolvedScale = when {
            independentTimelineScale != null -> independentTimelineScale
            matchedPairScale != null -> matchedPairScale
            strongConstantOffsetEvidence -> 1.0
            else -> null
        }
        val scaleSource = when {
            independentTimelineScale != null -> "independent"
            matchedPairScale != null -> "matched-pair"
            strongConstantOffsetEvidence -> "unit-fallback"
            else -> "unavailable"
        }
        val scaleCompatible = when {
            independentTimelineScale != null && matchedPairScale != null ->
                abs(independentTimelineScale - 1.0) <= MAX_TIMELINE_SCALE_DEVIATION &&
                    abs(matchedPairScale - 1.0) <= MAX_TIMELINE_SCALE_DEVIATION
            independentTimelineScale != null ->
                abs(independentTimelineScale - 1.0) <= MAX_TIMELINE_SCALE_DEVIATION
            matchedPairScale != null ->
                abs(matchedPairScale - 1.0) <= MAX_TIMELINE_SCALE_DEVIATION
            strongConstantOffsetEvidence -> true
            else -> false
        }
        val scaleEstimatorsAgree =
            matchedPairScale == null ||
                independentTimelineScale == null ||
                scaleDisagreement <= MAX_SCALE_ESTIMATOR_DISAGREEMENT

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
                when (scaleSource) {
                    "independent" ->
                        "source=independent scale=${resolvedScale?.let { "%.6f".format(it) }} " +
                            "matched=${matchedPairScale?.let { "%.6f".format(it) } ?: "<unavailable>"} " +
                            "anchors=${independentScale?.anchorCount ?: 0} " +
                            "slopes=${independentScale?.slopeCount ?: 0}"
                    "matched-pair" ->
                        "source=matched-pair scale=${resolvedScale?.let { "%.6f".format(it) }}; " +
                            "independent scale unavailable"
                    "unit-fallback" ->
                        "source=unit-fallback scale=1.000000; no reliable scale estimate but " +
                            "constant-offset evidence is exceptionally strong"
                    else ->
                        "no reliable scale estimate and constant-offset evidence is insufficient"
                },
            ),
            ConfidenceCheck(
                "scale estimator agreement",
                scaleEstimatorsAgree,
                when {
                    independentTimelineScale != null && matchedPairScale != null ->
                        "difference=${"%.6f".format(scaleDisagreement)} <= " +
                            "${"%.6f".format(MAX_SCALE_ESTIMATOR_DISAGREEMENT)}"
                    independentTimelineScale != null ->
                        "matched-pair scale unavailable; independent estimator retained"
                    matchedPairScale != null ->
                        "independent scale unavailable; matched-pair estimator retained"
                    strongConstantOffsetEvidence ->
                        "no scale estimators available; unit scale allowed by strong constant-offset evidence"
                    else ->
                        "no scale estimators available"
                },
            ),
            ConfidenceCheck(
                "participation OR strong absolute evidence",
                normalParticipation || strongAbsoluteEvidence,
                "referenceParticipation=${fmt(referenceParticipation)} >= ${fmt(NORMAL_PARTICIPATION_THRESHOLD)} " +
                    "OR targetParticipation=${fmt(targetParticipation)} >= ${fmt(TARGET_PARTICIPATION_THRESHOLD)} " +
                    "(asymmetricEligible=$asymmetricTargetEligible) " +
                    "OR strongAbsoluteEvidence=$strongAbsoluteEvidence",
            ),
        )

        val failedChecks = checks.filterNot { it.passed }.map { it.name }
        val highConfidence = failedChecks.isEmpty()

        val result = if (highConfidence && resolvedScale != null) {
            AlignmentResult(
                trackKey = track.key,
                language = track.language,
                offsetMs = best.offsetMs,
                score = best.score,
                matches = best.matches,
                residualMs = best.residualMs,
                timelineScale = resolvedScale,
                matchedPairScale = matchedPairScale,
                scaleDisagreement = scaleDisagreement,
            )
        } else {
            null
        }

        val attempt = AlignmentAttempt(
            result = result,
            score = best.score,
            offsetMs = best.offsetMs,
            matches = best.matches,
            residualMs = best.residualMs,
            timelineScale = resolvedScale,
            matchedPairScale = matchedPairScale,
            scaleDisagreement = scaleDisagreement,
            consecutivePatternMatches = consecutivePatternMatches,
            failedChecks = failedChecks,
            debugDetails = AlignmentDebugDetails(
                candidates = candidates,
                refinements = refinements,
                best = best,
                second = second,
                margin = margin,
                referenceParticipation = referenceParticipation,
                targetParticipation = targetParticipation,
                effectiveParticipation = effectiveParticipation,
                asymmetricTargetEligible = asymmetricTargetEligible,
                normalParticipation = normalParticipation,
                independentScale = independentScale,
                scaleSource = scaleSource,
                strongConstantOffsetEvidence = strongConstantOffsetEvidence,
                strongAbsoluteEvidence = strongAbsoluteEvidence,
                checks = checks,
                highConfidence = highConfidence,
            ),
        )

        if (logDetails) {
            logAlignmentAttempt(track, target, attempt)
        }
        return attempt
    }

    private fun logAlignmentAttempt(
        track: ReferenceTrack,
        target: List<SubtitleSyncCue>,
        attempt: AlignmentAttempt,
    ) {
        val details = attempt.debugDetails ?: return
        val reference = track.cues
        val best = details.best
        val second = details.second
        val matchedPairScale = attempt.matchedPairScale
        val timelineScale = details.independentScale?.scale

        AutoSyncDebugLog.section(
            "ALIGN track=${track.key} lang=${track.language ?: "<unknown>"}",
        )
        AutoSyncDebugLog.info("candidate offsets=${details.candidates.size}")
        details.candidates
            .take(MAX_LOGGED_CANDIDATES)
            .forEachIndexed { index, candidate ->
                AutoSyncDebugLog.verbose(
                    "CANDIDATE[$index] offset=${candidate.offsetMs}ms votes=${candidate.votes}",
                )
            }
        details.refinements.forEach { refinement ->
            AutoSyncDebugLog.verbose(
                "REFINE ${refinement.originalOffsetMs}ms -> ${refinement.refinedOffsetMs}ms " +
                    "using median signed residual=${"%.1f".format(refinement.signedResidualMs)}ms",
            )
        }

        AutoSyncDebugLog.info(
            "BEST offset=${best.offsetMs}ms matches=${best.matches}/${reference.size} " +
                "referenceParticipation=${fmt(details.referenceParticipation)} " +
                "targetParticipation=${fmt(details.targetParticipation)} " +
                "effectiveParticipation=${fmt(details.effectiveParticipation)} " +
                "coverage=${fmt(best.referenceCoverage)} " +
                "medianResidual=${"%.1f".format(best.residualMs)}ms " +
                "signedResidual=${"%.1f".format(best.signedResidualMs)}ms " +
                "agreement=${fmt(best.offsetAgreement)} spacing=${fmt(best.spacingScore)} " +
                "independentScale=${timelineScale?.let { "%.6f".format(it) } ?: "<unavailable>"} " +
                "matchedPairScale=${matchedPairScale?.let { "%.6f".format(it) } ?: "<unavailable>"} " +
                "resolvedScale=${attempt.timelineScale?.let { "%.6f".format(it) } ?: "<unavailable>"} " +
                "scaleSource=${details.scaleSource} " +
                "consecutive=${attempt.consecutivePatternMatches} score=${fmt(best.score)}",
        )

        if (second != null) {
            AutoSyncDebugLog.info(
                "SECOND offset=${second.offsetMs}ms matches=${second.matches} " +
                    "score=${fmt(second.score)} margin=${fmt(details.margin)}",
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
            "${if (details.normalParticipation) "PASS" else "FAIL"} normal participation: " +
                "reference=${fmt(details.referenceParticipation)} threshold=${fmt(NORMAL_PARTICIPATION_THRESHOLD)} " +
                "target=${fmt(details.targetParticipation)} threshold=${fmt(TARGET_PARTICIPATION_THRESHOLD)} " +
                "asymmetricEligible=${details.asymmetricTargetEligible}",
        )
        AutoSyncDebugLog.info(
            "${if (details.strongConstantOffsetEvidence) "PASS" else "FAIL"} strong constant-offset evidence: " +
                "targetParticipation=${fmt(details.targetParticipation)} " +
                "matches=${best.matches} residual=${"%.1f".format(best.residualMs)}ms " +
                "coverage=${fmt(best.referenceCoverage)} consecutive=${attempt.consecutivePatternMatches}",
        )
        AutoSyncDebugLog.info(
            "${if (details.strongAbsoluteEvidence) "PASS" else "FAIL"} strong absolute evidence: " +
                "matches=${best.matches}/${STRONG_ACCEPT_MATCHES} " +
                "residual=${"%.1f".format(best.residualMs)}ms/${"%.0f".format(STRONG_ACCEPT_RESIDUAL_MS)}ms " +
                "agreement=${fmt(best.offsetAgreement)}/${fmt(STRONG_ACCEPT_AGREEMENT)} " +
                "spacing=${fmt(best.spacingScore)}/${fmt(STRONG_ACCEPT_SPACING)} " +
                "margin=${fmt(details.margin)}/${fmt(STRONG_ACCEPT_MARGIN)}",
        )
        details.checks.forEach { check ->
            AutoSyncDebugLog.info(
                "${if (check.passed) "PASS" else "FAIL"} ${check.name}: ${check.detail}",
            )
        }
        AutoSyncDebugLog.info(
            "DECISION=${if (details.highConfidence) "ACCEPT" else "REJECT"} track=${track.key}",
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
                        refinedOffset in MIN_APPLICABLE_OFFSET_MS..MAX_APPLICABLE_OFFSET_MS &&
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

        val sampledPairs = if (pairs.size <= MAX_MATCHED_SCALE_SAMPLE_PAIRS) {
            pairs
        } else {
            val lastIndex = pairs.lastIndex
            (0 until MAX_MATCHED_SCALE_SAMPLE_PAIRS)
                .map { sampleIndex ->
                    pairs[
                        (sampleIndex.toLong() * lastIndex /
                            (MAX_MATCHED_SCALE_SAMPLE_PAIRS - 1)).toInt()
                    ]
                }
                .distinct()
        }

        val firstPair = sampledPairs.first()
        val lastPair = sampledPairs.last()
        val targetSpan =
            target[lastPair.targetIndex].startTimeMs - target[firstPair.targetIndex].startTimeMs
        if (targetSpan < MIN_SCALE_VALIDATION_SPAN_MS) return null

        val slopes = buildList {
            for (leftIndex in 0 until sampledPairs.lastIndex) {
                val left = sampledPairs[leftIndex]
                for (rightIndex in leftIndex + 1 until sampledPairs.size) {
                    val right = sampledPairs[rightIndex]
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

    private fun sampleReferenceCuesForOffsetVoting(
        reference: List<SubtitleSyncCue>,
    ): List<SubtitleSyncCue> {
        if (reference.size <= MAX_OFFSET_VOTE_REFERENCE_CUES) return reference
        if (MAX_OFFSET_VOTE_REFERENCE_CUES <= 1) return listOf(reference.first())

        val lastIndex = reference.lastIndex
        return (0 until MAX_OFFSET_VOTE_REFERENCE_CUES)
            .map { sampleIndex ->
                val referenceIndex =
                    (sampleIndex.toLong() * lastIndex / (MAX_OFFSET_VOTE_REFERENCE_CUES - 1)).toInt()
                reference[referenceIndex]
            }
            .distinctBy { it.startTimeMs }
    }

    private fun candidateOffsets(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
    ): List<CandidateOffset> {
        val buckets = mutableMapOf<Long, Int>()

        // target is already time-ordered (evaluate() relies on the same invariant). Instead of
        // comparing every sampled reference cue with every target cue, binary-search directly to
        // the first target that can possibly yield an offset Nuvio can actually apply.
        // This produces exactly the same votes/buckets as the previous full nested scan.
        for (referenceCue in sampleReferenceCuesForOffsetVoting(reference)) {
            val minTargetStart = referenceCue.startTimeMs - MAX_APPLICABLE_OFFSET_MS
            val maxTargetStart = referenceCue.startTimeMs - MIN_APPLICABLE_OFFSET_MS
            var targetIndex = lowerBoundCueStart(target, minTargetStart)

            while (targetIndex < target.size) {
                val targetStart = target[targetIndex].startTimeMs
                if (targetStart > maxTargetStart) break

                val difference = referenceCue.startTimeMs - targetStart
                val bucket = floorBucket(difference, CANDIDATE_BUCKET_MS)
                buckets[bucket] = (buckets[bucket] ?: 0) + 1
                targetIndex++
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

    private fun lowerBoundCueStart(
        cues: List<SubtitleSyncCue>,
        targetStartMs: Long,
    ): Int {
        var low = 0
        var high = cues.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cues[middle].startTimeMs < targetStartMs) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low
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

        val referenceParticipation = residuals.size.toDouble() / reference.size
        val targetParticipation = residuals.size.toDouble() / target.size
        val participation = if (isAsymmetricTargetParticipationEligible(reference, target)) {
            max(referenceParticipation, targetParticipation)
        } else {
            referenceParticipation
        }
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

    private fun isAsymmetricTargetParticipationEligible(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
    ): Boolean {
        if (target.size < MIN_ASYMMETRIC_TARGET_CUES) return false

        val referenceSpanMs = referenceSpanMs(reference)
        val targetSpanMs = referenceSpanMs(target)
        if (referenceSpanMs <= 0L || targetSpanMs <= 0L) return false

        val spanRatio =
            minOf(referenceSpanMs, targetSpanMs).toDouble() /
                maxOf(referenceSpanMs, targetSpanMs).toDouble()
        if (spanRatio < MIN_ASYMMETRIC_SPAN_RATIO) return false

        val targetDensity = target.size * 60_000.0 / targetSpanMs
        return targetDensity >= MIN_ASYMMETRIC_TARGET_DENSITY_PER_MINUTE
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
        val cacheHit: Boolean,
    )

    private data class ParsedCandidateCacheKey(
        val url: String,
        val headerHash: Int,
    )

    private data class CachedParsedSubtitle(
        val cues: List<SubtitleSyncCue>,
    )

    private data class ReferenceTimingFingerprint(
        val cueCount: Int,
        val firstStartMs: Long,
        val lastStartMs: Long,
        val timingHash: Long,
    )

    private data class ReferenceTimingGroup(
        val fingerprint: ReferenceTimingFingerprint,
        val members: MutableList<ReferenceTrack>,
    )

    private data class CandidateTimingFingerprint(
        val cueCount: Int,
        val firstStartMs: Long,
        val lastStartMs: Long,
        val timingHash: Long,
    )

    private data class CandidateTimingGroup(
        val fingerprint: CandidateTimingFingerprint,
        val members: MutableList<ParsedSubtitleCandidate>,
    )

    private data class CandidateReferenceSummary(
        val bestAccepted: CandidateMatch?,
        val winningAttempt: AlignmentAttempt?,
        val bestRejectedAttempt: Pair<ReferenceTrack, AlignmentAttempt>?,
        val attempts: List<Pair<ReferenceTrack, AlignmentAttempt>>,
    )

    private data class CandidateTimingGroupResult(
        val group: CandidateTimingGroup,
        val summary: CandidateReferenceSummary,
    )

    private data class PendingCandidateLoad(
        val index: Int,
        val deferred: Deferred<ParsedSubtitleCandidate?>,
    )

    private class PipelineTimingGroupState(
        val group: CandidateTimingGroup,
        var summary: CandidateReferenceSummary? = null,
    )

    private data class PendingTimingMatch(
        val state: PipelineTimingGroupState,
        val deferred: Deferred<CandidateReferenceSummary>,
    )

    private sealed class PipelineEvent {
        data class CandidateLoaded(
            val index: Int,
            val parsed: ParsedSubtitleCandidate?,
        ) : PipelineEvent()

        data class TimingMatched(
            val state: PipelineTimingGroupState,
            val summary: CandidateReferenceSummary,
        ) : PipelineEvent()
    }

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
        val matchedPairScale: Double? = null,
        val scaleDisagreement: Double = 0.0,
        val consecutivePatternMatches: Int = 0,
        val failedChecks: List<String> = emptyList(),
        val debugDetails: AlignmentDebugDetails? = null,
    )

    private data class AlignmentDebugDetails(
        val candidates: List<CandidateOffset>,
        val refinements: List<OffsetRefinement>,
        val best: Evaluation,
        val second: Evaluation?,
        val margin: Double,
        val referenceParticipation: Double,
        val targetParticipation: Double,
        val effectiveParticipation: Double,
        val asymmetricTargetEligible: Boolean,
        val normalParticipation: Boolean,
        val independentScale: IndependentScaleResult?,
        val scaleSource: String,
        val strongConstantOffsetEvidence: Boolean,
        val strongAbsoluteEvidence: Boolean,
        val checks: List<ConfidenceCheck>,
        val highConfidence: Boolean,
    )

    private data class OffsetRefinement(
        val originalOffsetMs: Long,
        val refinedOffsetMs: Long,
        val signedResidualMs: Double,
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
        val matchedPairScale: Double?,
        val scaleDisagreement: Double,
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
    private const val MAX_RETAINED_GENERATIONS = 6

    private data class Track(
        var language: String?,
        var label: String?,
        var selectionFlags: Int,
        var roleFlags: Int,
        val cues: LinkedHashMap<String, SubtitleSyncCue> = linkedMapOf(),
    )

    private data class RetainedGeneration(
        val generation: Long,
        val tracks: MutableMap<String, Track>,
    )

    private val lock = Any()
    private val sources = mutableMapOf<String, MutableMap<String, Track>>()
    private val retainedGenerations = mutableMapOf<String, MutableList<RetainedGeneration>>()
    private val generations = mutableMapOf<String, Long>()
    private val lastSeekTargetMs = mutableMapOf<String, Long?>()
    private val lastSeekWallMs = mutableMapOf<String, Long>()

    fun reset(sourceKey: String) {
        if (sourceKey.isBlank()) return

        var generation = 0L
        synchronized(lock) {
            generation = (generations[sourceKey] ?: 0L) + 1L
            sources[sourceKey] = linkedMapOf()
            retainedGenerations.remove(sourceKey)
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
                val currentGeneration = generations[sourceKey] ?: 0L
                val currentTracks = sources[sourceKey]
                if (currentTracks != null && currentTracks.isNotEmpty()) {
                    val retained = retainedGenerations.getOrPut(sourceKey) { mutableListOf() }
                    retained += RetainedGeneration(currentGeneration, currentTracks)
                    while (retained.size > MAX_RETAINED_GENERATIONS) {
                        retained.removeAt(0)
                    }
                }

                generation = currentGeneration + 1L
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
        val currentGeneration = generations[sourceKey] ?: 0L

        val snapshots = buildList {
            retainedGenerations[sourceKey].orEmpty().forEach { retained ->
                add(retained.generation to retained.tracks)
            }
            sources[sourceKey]?.takeIf { it.isNotEmpty() }?.let { currentTracks ->
                add(currentGeneration to currentTracks)
            }
        }

        snapshots
            .flatMap { (generation, tracks) ->
                tracks.map { (key, track) ->
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
            }
            .filter { it.cues.size >= 3 }
            .sortedWith(
                compareByDescending<ReferenceTrack> {
                    languageRank(it.language, preferred)
                }.thenByDescending {
                    it.cues.size
                }.thenByDescending {
                    it.generation
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
