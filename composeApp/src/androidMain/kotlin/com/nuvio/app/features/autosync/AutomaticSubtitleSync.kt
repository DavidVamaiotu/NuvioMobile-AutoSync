package com.nuvio.app.features.autosync

import android.os.SystemClock
import androidx.media3.common.C
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.player.AutoSyncSubtitleCandidate
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.SubtitleLanguageMatching
import com.nuvio.app.features.player.SubtitleSyncCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToLong

/**
 * Android-only AutoSync V2.
 *
 * The legacy V1 matcher is intentionally absent.
 * V2 first performs a cheap fixed-scale delay-only check. If one constant offset
 * is strong and stable across the movie, it returns a uniform shift immediately.
 * Otherwise it discovers the whole-film affine transform and runs cue/group DP.
 */
internal object AutomaticSubtitleSync {
    private const val MIN_SELECTED_CUES = 1
    private const val MAX_LOGGED_CUE_SAMPLES = 20
    private const val ALTERNATIVE_EXTERNAL_SUBTITLE_BATCH_SIZE = 2
    private const val MAX_PARALLEL_ALTERNATIVE_DOWNLOADS = 6
    private const val MAX_PARALLEL_ALTERNATIVE_MATCHES = 2
    private const val MAX_PARALLEL_PREFLIGHT_MATCHES = 2
    private const val HIGH_SCORE_PREFLIGHT_CHAMPION = 0.915
    private const val EXCEPTIONAL_MATCH_QUALITY = 0.95
    private const val EXCEPTIONAL_MATCH_TARGET_COVERAGE = 0.99
    private const val EXCEPTIONAL_MATCH_REFERENCE_COVERAGE = 0.97
    private const val EXCEPTIONAL_MATCH_SIMPLE_RATIO = 0.98
    private const val REFERENCE_SEARCH_CHECKPOINT = 2
    private const val STRONG_CHECKPOINT_QUALITY = 0.92
    private const val STRONG_CHECKPOINT_TARGET_COVERAGE = 0.98
    private const val STRONG_CHECKPOINT_REFERENCE_COVERAGE = 0.90
    private const val ASYMMETRIC_CHECKPOINT_QUALITY = 0.94
    private const val ASYMMETRIC_CHECKPOINT_TARGET_COVERAGE = 0.995
    private const val ASYMMETRIC_CHECKPOINT_REFERENCE_COVERAGE = 0.84
    private const val ASYMMETRIC_CHECKPOINT_SIMPLE_RATIO = 0.97
    private const val ASYMMETRIC_CHECKPOINT_MAX_GROUP_COST = 0.20
    private const val ASYMMETRIC_CHECKPOINT_MIN_REFERENCE_RATIO = 1.15
    private const val ASYMMETRIC_CHECKPOINT_MAX_TARGET_SKIP_RUN = 2
    private const val FALLBACK_BATCH_STOP_QUALITY = 0.89
    private const val FALLBACK_BATCH_STOP_TARGET_COVERAGE = 0.99
    private const val FALLBACK_BATCH_STOP_REFERENCE_COVERAGE = 0.94
    private const val FALLBACK_BATCH_STOP_SIMPLE_RATIO = 0.97

    // Scheduling-only reference pre-ranker. It never accepts/rejects a match.
    private const val CHEAP_REFERENCE_SAMPLE_CUES = 24
    private const val CHEAP_TARGET_SAMPLE_CUES = 32
    private const val CHEAP_REFERENCE_OFFSET_CANDIDATES = 4
    private const val CHEAP_REFERENCE_MATCH_TOLERANCE_MS = 1_800L

    private const val FALLBACK_CANDIDATE_POLL_MS = 250L
    private const val FALLBACK_CANDIDATE_WAIT_MS = 10_000L

    private const val MIN_FULL_DIALOGUE_CUES = 8
    private const val MIN_FULL_DIALOGUE_CLASSIFICATION_SPAN_MS = 30_000L
    private const val MIN_FULL_DIALOGUE_DENSITY_PER_MINUTE = 2.0
    private const val MIN_FULL_DIALOGUE_TEXT_RATIO = 0.45
    private const val MIN_INDEXED_REFERENCE_SPAN_MS = 45_000L

    private const val LIVE_REFERENCE_WAIT_MS = 12_000L
    private const val LIVE_REFERENCE_POLL_MS = 500L
    private const val MIN_LIVE_REFERENCE_CUES = 20
    private const val MIN_LIVE_REFERENCE_SPAN_RATIO = 0.80

    private const val HTTP_429_RETRY_DELAY_MS = 900L
    private const val SUBTITLE_DOWNLOAD_TIMEOUT_MS = 15_000L
    private const val MAX_SUBTITLE_RESPONSE_BYTES = 4 * 1024 * 1024
    private const val MAX_PARSED_SUBTITLE_CACHE_ENTRIES = 32

    private val parsedSubtitleCacheLock = Any()
    private val parsedSubtitleCache =
        object : LinkedHashMap<ParsedSubtitleCacheKey, List<SubtitleSyncCue>>(
            MAX_PARSED_SUBTITLE_CACHE_ENTRIES,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<ParsedSubtitleCacheKey, List<SubtitleSyncCue>>?,
            ): Boolean = size > MAX_PARSED_SUBTITLE_CACHE_ENTRIES
        }

    suspend fun findTimelineRetime(
        sourceKey: String,
        selectedSubtitleUrl: String,
        selectedSubtitleHeaders: Map<String, String>,
        preferredLanguage: String?,
        alternativeSubtitles: List<AutoSyncSubtitleCandidate> = emptyList(),
        alternativeSubtitlesProvider: (() -> List<AutoSyncSubtitleCandidate>)? = null,
        onReferenceReady: () -> Unit = {},
        sourceHeaders: Map<String, String> = emptyMap(),
    ): AutoSyncResolvedTimeline? {
        AutoSyncDebugLog.start(
            sourceKey = sourceKey,
            subtitleUrl = selectedSubtitleUrl,
        )

        return supervisorScope {
            val indexedTimelineDeferred = async {
                EmbeddedSubtitleTimelineLoader.load(
                    sourceUrl = sourceKey,
                    sourceHeaders = sourceHeaders,
                )
            }
            val selectedSubtitleDeferred = async {
                loadSelectedSubtitle(
                    url = selectedSubtitleUrl,
                    headers = selectedSubtitleHeaders,
                )
            }

            // V1 scheduling efficiency: overlap same-language candidate downloads with the
            // embedded MKV index. Scoring is still entirely V2.
            val alternativeDownloadSemaphore = Semaphore(MAX_PARALLEL_ALTERNATIVE_DOWNLOADS)
            val prefetchSnapshot =
                (alternativeSubtitlesProvider?.invoke() ?: alternativeSubtitles)
                    .distinctBy { it.url }
            val prefetchLanguage =
                prefetchSnapshot.firstOrNull { it.url == selectedSubtitleUrl }
                    ?.language
                    ?.takeIf { it.isNotBlank() }
                    ?: preferredLanguage?.takeIf { it.isNotBlank() }
            val prefetchedAlternativeLoads =
                linkedMapOf<String, Deferred<LoadedSubtitle?>>()

            prefetchSnapshot
                .asSequence()
                .filter { it.url.isNotBlank() && it.url != selectedSubtitleUrl }
                .filter { candidate ->
                    prefetchLanguage.isNullOrBlank() ||
                        SubtitleLanguageMatching.matchesLanguageCode(
                            candidate.language,
                            prefetchLanguage,
                        )
                }
                .distinctBy { it.url }
                .forEach { candidate ->
                    prefetchedAlternativeLoads[candidate.url] = async {
                        alternativeDownloadSemaphore.withPermit {
                            loadSelectedSubtitle(
                                url = candidate.url,
                                headers = emptyMap(),
                            )
                        }
                    }
                }

            val indexedTimeline = indexedTimelineDeferred.await()
            var selected =
                if (selectedSubtitleDeferred.isCompleted) {
                    selectedSubtitleDeferred.await()
                } else {
                    null
                }
            val selectedPendingAfterIndex = !selectedSubtitleDeferred.isCompleted

            AutoSyncDebugLog.section { "SELECTED SUBTITLE" }
            if (selected != null) {
                logLoadedExternalSubtitle(
                    label = "SELECTED",
                    url = selectedSubtitleUrl,
                    loaded = selected,
                    sampleLimit = MAX_LOGGED_CUE_SAMPLES,
                )
            } else if (selectedPendingAfterIndex) {
                AutoSyncDebugLog.info {
                    "selected subtitle still loading when embedded indexing became ready; " +
                        "using already-prefetched same-language candidates without blocking"
                }
            } else {
                AutoSyncDebugLog.warn {
                    "selected subtitle could not be downloaded or parsed; searching alternatives"
                }
            }

            fun currentExternalCandidates(): List<AutoSyncSubtitleCandidate> =
                (alternativeSubtitlesProvider?.invoke() ?: alternativeSubtitles)
                    .distinctBy { it.url }

            fun selectedLanguage(
                candidates: List<AutoSyncSubtitleCandidate>,
            ): String? =
                candidates.firstOrNull { it.url == selectedSubtitleUrl }
                    ?.language
                    ?.takeIf { it.isNotBlank() }
                    ?: preferredLanguage?.takeIf { it.isNotBlank() }

            fun sameLanguageAlternatives(
                candidates: List<AutoSyncSubtitleCandidate>,
                language: String?,
            ): List<AutoSyncSubtitleCandidate> =
                candidates
                    .asSequence()
                    .filter { it.url.isNotBlank() && it.url != selectedSubtitleUrl }
                    .filter { candidate ->
                        language.isNullOrBlank() ||
                            SubtitleLanguageMatching.matchesLanguageCode(
                                candidate.language,
                                language,
                            )
                    }
                    .distinctBy { it.url }
                    .toList()

            var availableCandidates = currentExternalCandidates()
            var language = selectedLanguage(availableCandidates)
            var alternatives = sameLanguageAlternatives(availableCandidates, language)

            suspend fun awaitSameLanguageAlternatives() {
                if (alternativeSubtitlesProvider == null || alternatives.isNotEmpty()) return

                val waitStartedMs = SystemClock.elapsedRealtime()
                while (alternatives.isEmpty()) {
                    availableCandidates = currentExternalCandidates()
                    language = selectedLanguage(availableCandidates)
                    alternatives = sameLanguageAlternatives(availableCandidates, language)
                    if (alternatives.isNotEmpty()) break

                    val elapsedMs = SystemClock.elapsedRealtime() - waitStartedMs
                    val remainingMs = FALLBACK_CANDIDATE_WAIT_MS - elapsedMs
                    if (remainingMs <= 0L) break
                    delay(minOf(FALLBACK_CANDIDATE_POLL_MS, remainingMs))
                }

                AutoSyncDebugLog.info {
                    "fallback candidate refresh total=${availableCandidates.size} " +
                        "sameLanguage=${alternatives.size} " +
                        "waited=${SystemClock.elapsedRealtime() - waitStartedMs}ms"
                }
            }

            if (selected == null) {
                awaitSameLanguageAlternatives()
            }

            var seedTarget = selected?.cues
            if (seedTarget.isNullOrEmpty() && alternatives.isNotEmpty()) {
                val pendingSeedLoads = linkedMapOf<String, Deferred<LoadedSubtitle?>>().apply {
                    alternatives.forEach { candidate ->
                        prefetchedAlternativeLoads[candidate.url]?.let { job ->
                            put(candidate.url, job)
                        }
                    }
                }

                var loadedSeed: LoadedSubtitle? = null
                while (loadedSeed == null && pendingSeedLoads.isNotEmpty()) {
                    val completed = select<Pair<String, LoadedSubtitle?>> {
                        pendingSeedLoads.forEach { (url, job) ->
                            job.onAwait { url to it }
                        }
                    }
                    pendingSeedLoads.remove(completed.first)
                    loadedSeed = completed.second
                }

                if (loadedSeed == null) {
                    val notPrefetched = alternatives.firstOrNull {
                        it.url !in prefetchedAlternativeLoads
                    }
                    if (notPrefetched != null) {
                        loadedSeed = loadSelectedSubtitle(notPrefetched.url, emptyMap())
                    }
                }

                if (loadedSeed != null) seedTarget = loadedSeed.cues
            }

            // If there is no usable fallback seed, preserve the old selected-subtitle behavior:
            // wait for its original request rather than rejecting early just because it was slow.
            if (seedTarget.isNullOrEmpty() && !selectedSubtitleDeferred.isCompleted) {
                selected = selectedSubtitleDeferred.await()
                if (selected != null) {
                    logLoadedExternalSubtitle(
                        label = "SELECTED",
                        url = selectedSubtitleUrl,
                        loaded = selected,
                        sampleLimit = MAX_LOGGED_CUE_SAMPLES,
                    )
                    seedTarget = selected?.cues
                }
            }

            if (seedTarget.isNullOrEmpty()) {
                selectedSubtitleDeferred.cancel()
                AutoSyncDebugLog.section { "FINAL RECOMMENDATION" }
                AutoSyncDebugLog.warn {
                    "REJECT no usable external subtitle candidate could be parsed"
                }
                return@supervisorScope null
            }

            var referenceTracks: List<ReferenceTrack> = emptyList()

            AutoSyncDebugLog.section { "INDEXED EMBEDDED REFERENCE" }
            if (indexedTimeline != null) {
                AutoSyncDebugLog.info {
                    "source=${indexedTimeline.source} tracks=${indexedTimeline.tracks.size} " +
                        "requests=${indexedTimeline.rangeRequests} bytes=${indexedTimeline.bytesDownloaded} " +
                        "load=${indexedTimeline.loadMs}ms"
                }
                val profiles = indexedTimeline.tracks.map(::buildReferenceProfile)
                if (AutoSyncDebugLog.ENABLED) {
                    profiles.forEachIndexed { index, profile ->
                        val track = profile.track
                        AutoSyncDebugLog.info {
                            "indexed[$index] track=${track.key} lang=${track.language ?: "<unknown>"} " +
                                "label=${track.label ?: "<none>"} selectionFlags=${track.selectionFlags} " +
                                "roleFlags=${track.roleFlags} cues=${profile.cueCount} " +
                                "span=${profile.spanMs}ms density=${fmt(profile.densityPerMinute)}/min " +
                                "fullDialogue=${profile.fullDialogue}"
                        }
                    }
                }
                referenceTracks = orderReferenceProfiles(
                    profiles.filter { profile ->
                        profile.fullDialogue &&
                            profile.cueCount >= MIN_FULL_DIALOGUE_CUES &&
                            profile.spanMs >= MIN_INDEXED_REFERENCE_SPAN_MS
                    },
                ).map { it.track.copy(cues = it.track.cues.toList()) }
            } else {
                AutoSyncDebugLog.info {
                    "indexed timeline unavailable; checking Media3 for a near-complete embedded timeline"
                }
            }

            if (referenceTracks.isEmpty()) {
                referenceTracks = awaitNearCompleteLiveReferences(
                    sourceKey = sourceKey,
                    preferredLanguage = preferredLanguage,
                    target = seedTarget,
                    waitMs = if (indexedTimeline?.skipLiveFallbackWait == true) 0L else LIVE_REFERENCE_WAIT_MS,
                )
            }

            if (referenceTracks.isEmpty()) {
                selectedSubtitleDeferred.cancel()
                AutoSyncDebugLog.section { "FINAL RECOMMENDATION" }
                AutoSyncDebugLog.warn {
                    "REJECT no complete embedded subtitle timeline is available for V2"
                }
                return@supervisorScope null
            }

            onReferenceReady()

            val referenceActivityCache =
                mutableMapOf<String, AutoSyncTimelineRetimer.PreparedActivity?>()

            // The Nuvio-selected subtitle is usually automatic, so treat it as one timing
            // candidate rather than giving it expensive V2 priority. A cheap fixed-delay
            // preflight ranks all same-language candidates first.
            if (selected == null && selectedSubtitleDeferred.isCompleted) {
                selected = selectedSubtitleDeferred.await()
                if (selected != null) {
                    logLoadedExternalSubtitle(
                        label = "SELECTED",
                        url = selectedSubtitleUrl,
                        loaded = selected,
                        sampleLimit = MAX_LOGGED_CUE_SAMPLES,
                    )
                }
            }

            awaitSameLanguageAlternatives()

            val selectedCandidateMetadata =
                availableCandidates.firstOrNull { it.url == selectedSubtitleUrl }
                    ?: AutoSyncSubtitleCandidate(
                        url = selectedSubtitleUrl,
                        language = language ?: preferredLanguage.orEmpty(),
                        name = "Selected",
                    )

            // Put the selected subtitle into the same pool. It remains eligible, but is no
            // longer privileged merely because Nuvio auto-selected it.
            alternatives = (alternatives + selectedCandidateMetadata)
                .distinctBy { it.url }

            val alternativeLoads =
                linkedMapOf<String, Deferred<LoadedSubtitle?>>().apply {
                    putAll(prefetchedAlternativeLoads)
                }
            val loadedByUrl = mutableMapOf<String, LoadedSubtitle>()
            val preflightByUrl = mutableMapOf<String, AutoSyncDelayPreflight.Match>()
            val preflightV2Cache = mutableMapOf<String, CandidateEvaluation>()
            val timingEvaluationCache = mutableListOf<CachedTimingEvaluation>()

            fun cachedTimingEvaluation(
                target: List<SubtitleSyncCue>,
            ): CandidateEvaluation? = synchronized(timingEvaluationCache) {
                var reused: CandidateEvaluation? = null
                for (cached in timingEvaluationCache) {
                    reused = reuseShiftEquivalentEvaluation(
                        evaluation = cached.evaluation,
                        representativeTarget = cached.target,
                        target = target,
                    )
                    if (reused != null) break
                }
                reused
            }

            fun cacheTimingEvaluation(
                target: List<SubtitleSyncCue>,
                evaluation: CandidateEvaluation,
            ) {
                synchronized(timingEvaluationCache) {
                    if (
                        timingEvaluationCache.none { cached ->
                            constantTimelineShiftMs(cached.target, target) != null
                        }
                    ) {
                        timingEvaluationCache += CachedTimingEvaluation(
                            target = target.toList(),
                            evaluation = evaluation,
                        )
                    }
                }
            }

            fun headersForCandidate(url: String): Map<String, String> =
                if (url == selectedSubtitleUrl) selectedSubtitleHeaders else emptyMap()

            fun scheduleAlternativeLoads() {
                alternatives.forEach { candidate ->
                    if (candidate.url !in alternativeLoads) {
                        alternativeLoads[candidate.url] = async {
                            if (candidate.url == selectedSubtitleUrl) {
                                selected ?: selectedSubtitleDeferred.await()
                            } else {
                                alternativeDownloadSemaphore.withPermit {
                                    loadSelectedSubtitle(
                                        url = candidate.url,
                                        headers = emptyMap(),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            scheduleAlternativeLoads()

            val preflightSemaphore = Semaphore(MAX_PARALLEL_PREFLIGHT_MATCHES)
            val preflightJobs =
                linkedMapOf<String, Deferred<CompletedPreflight>>()

            fun schedulePreflightJobs() {
                alternatives.forEach { candidate ->
                    if (candidate.url !in preflightJobs) {
                        preflightJobs[candidate.url] = async {
                            val loaded = alternativeLoads[candidate.url]?.await()
                            val match =
                                if (loaded == null) {
                                    null
                                } else {
                                    preflightSemaphore.withPermit {
                                        withContext(Dispatchers.Default) {
                                            AutoSyncDelayPreflight.bestMatch(
                                                referenceTracks = referenceTracks,
                                                target = loaded.cues,
                                                referenceActivityCache = referenceActivityCache,
                                            )
                                        }
                                    }
                                }
                            CompletedPreflight(
                                candidate = candidate,
                                loaded = loaded,
                                match = match,
                            )
                        }
                    }
                }
            }

            schedulePreflightJobs()

            AutoSyncDebugLog.section { "DELAY-ONLY PREFLIGHT" }
            AutoSyncDebugLog.info {
                "completion-driven V2 delay scan candidates=${alternatives.size} " +
                    "downloads=$MAX_PARALLEL_ALTERNATIVE_DOWNLOADS " +
                    "preflightWorkers=$MAX_PARALLEL_PREFLIGHT_MATCHES"
            }

            val pendingPreflights =
                linkedMapOf<String, Deferred<CompletedPreflight>>().apply {
                    putAll(preflightJobs)
                }
            var bestPreflightScoreSeen = Double.NEGATIVE_INFINITY

            while (pendingPreflights.isNotEmpty()) {
                val completed = select<CompletedPreflight> {
                    pendingPreflights.forEach { (_, job) ->
                        job.onAwait { it }
                    }
                }
                pendingPreflights.remove(completed.candidate.url)

                val candidate = completed.candidate
                val loaded = completed.loaded
                val match = completed.match
                val highScoreChampion =
                    match != null &&
                        match.score >= HIGH_SCORE_PREFLIGHT_CHAMPION &&
                        match.segmentsPassed >= 3 &&
                        match.score > bestPreflightScoreSeen
                if (match != null && match.score > bestPreflightScoreSeen) {
                    bestPreflightScoreSeen = match.score
                }

                if (loaded != null) {
                    loadedByUrl[candidate.url] = loaded
                }
                if (match != null) {
                    preflightByUrl[candidate.url] = match
                    AutoSyncDebugLog.info {
                        "PREFLIGHT url=${candidate.url} reference=${match.referenceKey} " +
                            "offset=${"%.1f".format(match.offsetMs)}ms " +
                            "score=${fmt(match.score)} margin=${fmt(match.margin)} " +
                            "segments=${match.segmentsPassed} " +
                            "strong=${AutoSyncDelayPreflight.isReallyGood(match)}"
                    }
                }

                if (
                    loaded != null &&
                    match != null &&
                    (
                        AutoSyncDelayPreflight.isReallyGood(match) ||
                            highScoreChampion
                        )
                ) {
                    AutoSyncDebugLog.info {
                        "PREFLIGHT ${
                            if (AutoSyncDelayPreflight.isReallyGood(match)) {
                                "strong V2 delay evidence"
                            } else {
                                "high-score champion"
                            }
                        }; validating immediately " +
                            "url=${candidate.url} reference=${match.referenceKey}"
                    }

                    val cachedTiming = cachedTimingEvaluation(loaded.cues)
                    val evaluation = cachedTiming ?: evaluateExternalCandidate(
                        label = "PREFLIGHT",
                        url = candidate.url,
                        target = loaded.cues,
                        referenceTracks = referenceTracks,
                        referenceActivityCache = referenceActivityCache,
                        preferredReferenceKey = match.referenceKey,
                        preflightHint = match,
                    )
                    if (cachedTiming == null) {
                        cacheTimingEvaluation(loaded.cues, evaluation)
                    } else {
                        AutoSyncDebugLog.info {
                            "PREFLIGHT reused session timing-family V2 validation url=${candidate.url}"
                        }
                    }
                    preflightV2Cache[candidate.url] = evaluation

                    val best = evaluation.best
                    val authoritativeFastAccept =
                        best != null &&
                            best.timeline.confident &&
                            best.timeline.alignmentSource == "delay-only-validated" &&
                            (
                                isExceptionalMatch(best) ||
                                    isStrongCheckpointMatch(best)
                                )

                    if (authoritativeFastAccept) {
                        alternativeLoads.values
                            .filterNot { it.isCompleted }
                            .forEach { it.cancel() }
                        preflightJobs.values
                            .filterNot { it.isCompleted }
                            .forEach { it.cancel() }
                        if (!selectedSubtitleDeferred.isCompleted) {
                            selectedSubtitleDeferred.cancel()
                        }

                        AutoSyncDebugLog.section { "FINAL RECOMMENDATION" }
                        AutoSyncDebugLog.info {
                            "V2 accepted completion-driven delay candidate " +
                                "url=${candidate.url} name=${candidate.name ?: "<none>"} " +
                                "reference=${best!!.track.key} " +
                                "quality=${fmt(directTimelineQualityScore(best))}"
                        }

                        return@supervisorScope AutoSyncResolvedTimeline(
                            subtitleUrl = candidate.url,
                            subtitleHeaders = headersForCandidate(candidate.url),
                            timeline = best!!.timeline,
                        )
                    }

                    AutoSyncDebugLog.info {
                        "PREFLIGHT candidate was not strong enough after authoritative V2; " +
                            "continuing completion-driven scan"
                    }
                }

                if (alternativeSubtitlesProvider != null) {
                    availableCandidates = currentExternalCandidates()
                    language = selectedLanguage(availableCandidates)
                    val refreshed = sameLanguageAlternatives(availableCandidates, language)
                    val knownUrls = alternatives.asSequence().map { it.url }.toHashSet()
                    val newlyArrived = refreshed.filter { it.url !in knownUrls }

                    if (newlyArrived.isNotEmpty()) {
                        alternatives = alternatives + newlyArrived
                        scheduleAlternativeLoads()
                        schedulePreflightJobs()
                        newlyArrived.forEach { newCandidate ->
                            preflightJobs[newCandidate.url]?.let { job ->
                                pendingPreflights[newCandidate.url] = job
                            }
                        }
                        AutoSyncDebugLog.info {
                            "preflight candidate refresh added=${newlyArrived.size} " +
                                "total=${alternatives.size}"
                        }
                    }
                }
            }

            // No excellent delay-only candidate survived V2 validation. Pass the preflight
            // evidence forward: V2 now tries candidates in this order, and each candidate tries
            // its preflight-winning embedded reference first.
            alternatives = alternatives.sortedWith(
                compareByDescending<AutoSyncSubtitleCandidate> {
                    preflightByUrl[it.url]?.score ?: Double.NEGATIVE_INFINITY
                }.thenByDescending {
                    preflightByUrl[it.url]?.margin ?: Double.NEGATIVE_INFINITY
                }.thenByDescending {
                    preflightByUrl[it.url]?.segmentsPassed ?: 0
                },
            )

            AutoSyncDebugLog.section { "V2 CANDIDATE ORDER FROM PREFLIGHT" }
            alternatives.forEachIndexed { index, candidate ->
                val hint = preflightByUrl[candidate.url]
                AutoSyncDebugLog.info {
                    "[$index] url=${candidate.url} name=${candidate.name ?: "<none>"} " +
                        if (hint == null) {
                            "preflight=<none>"
                        } else {
                            "reference=${hint.referenceKey} offset=${hint.offsetMs}ms " +
                                "score=${fmt(hint.score)} margin=${fmt(hint.margin)}"
                        }
                }
            }

            var bestAlternative: AutoSyncResolvedTimeline? = null
            var bestAlternativeMatch: TimelineRetimeMatch? = null
            var bestAlternativeIndex = -1
            var bestAlternativeName: String? = null
            var bestAlternativeCueCount = 0

            var batchStartIndex = 0
            var stopFallbackSearch = false

            AutoSyncDebugLog.section { "FULL V2 FALLBACK" }

            while (batchStartIndex < alternatives.size && !stopFallbackSearch) {
                val batchEndExclusive = minOf(
                    batchStartIndex + ALTERNATIVE_EXTERNAL_SUBTITLE_BATCH_SIZE,
                    alternatives.size,
                )

                AutoSyncDebugLog.info {
                    "V2 batch candidates=$batchStartIndex..${batchEndExclusive - 1}"
                }

                val loadedBatch = ArrayList<LoadedAlternative>(
                    batchEndExclusive - batchStartIndex,
                )
                for (index in batchStartIndex until batchEndExclusive) {
                    val candidate = alternatives[index]
                    val loaded =
                        loadedByUrl[candidate.url]
                            ?: alternativeLoads[candidate.url]?.await()
                            ?: continue
                    loadedByUrl[candidate.url] = loaded

                    loadedBatch += LoadedAlternative(
                        index = index,
                        candidate = candidate,
                        loaded = loaded,
                    )
                }

                // Exact constant-shift timing families are structurally identical for V2.
                // Group only when every cue has the same relative start/end timing; no fuzzy reuse.
                val timingGroups = mutableListOf<MutableList<LoadedAlternative>>()
                for (alternative in loadedBatch) {
                    val existing = timingGroups.firstOrNull { group ->
                        constantTimelineShiftMs(
                            group.first().loaded.cues,
                            alternative.loaded.cues,
                        ) != null
                    }
                    if (existing != null) {
                        existing += alternative
                    } else {
                        timingGroups.add(mutableListOf(alternative))
                    }
                }
                val duplicatesSaved = loadedBatch.size - timingGroups.size
                if (duplicatesSaved > 0) {
                    AutoSyncDebugLog.info {
                        "fallback shift-equivalent dedup timelines=${timingGroups.size}/${loadedBatch.size} " +
                            "duplicatesSaved=$duplicatesSaved"
                    }
                }

                // Keep the existing two-worker bound for TV hardware. Candidate ordering is from
                // preflight; the second worker is only speculative parallel work.
                for (groupPair in timingGroups.chunked(MAX_PARALLEL_ALTERNATIVE_MATCHES)) {
                    val evaluatedPair = groupPair.map { group ->
                        async {
                            val representative = group.first()
                            val cachedByUrl = preflightV2Cache[representative.candidate.url]
                            val cachedByTiming =
                                if (cachedByUrl == null) {
                                    cachedTimingEvaluation(representative.loaded.cues)
                                } else {
                                    null
                                }
                            val evaluation =
                                cachedByUrl ?: cachedByTiming ?: evaluateExternalCandidate(
                                    label = "CANDIDATE[${representative.index}]",
                                    url = representative.candidate.url,
                                    target = representative.loaded.cues,
                                    referenceTracks = referenceTracks,
                                    referenceActivityCache = referenceActivityCache,
                                    preferredReferenceKey =
                                        preflightByUrl[representative.candidate.url]?.referenceKey,
                                    preflightHint =
                                        preflightByUrl[representative.candidate.url],
                                )
                            if (cachedByUrl == null && cachedByTiming == null) {
                                cacheTimingEvaluation(representative.loaded.cues, evaluation)
                            } else if (cachedByTiming != null) {
                                AutoSyncDebugLog.info {
                                    "CANDIDATE[${representative.index}] reused session timing-family " +
                                        "V2 validation"
                                }
                            }
                            EvaluatedAlternativeTimingGroup(
                                members = group,
                                evaluation = evaluation,
                            )
                        }
                    }.awaitAll()

                    for (evaluated in evaluatedPair.sortedBy { it.members.first().index }) {
                        val representative = evaluated.members.first()

                        for (alternative in evaluated.members.sortedBy { it.index }) {
                            val memberEvaluation =
                                if (alternative.index == representative.index) {
                                    evaluated.evaluation
                                } else {
                                    reuseShiftEquivalentEvaluation(
                                        evaluation = evaluated.evaluation,
                                        representativeTarget = representative.loaded.cues,
                                        target = alternative.loaded.cues,
                                    ) ?: evaluateExternalCandidate(
                                        label = "CANDIDATE[${alternative.index}]",
                                        url = alternative.candidate.url,
                                        target = alternative.loaded.cues,
                                        referenceTracks = referenceTracks,
                                        referenceActivityCache = referenceActivityCache,
                                        preferredReferenceKey =
                                            preflightByUrl[alternative.candidate.url]?.referenceKey,
                                        preflightHint =
                                            preflightByUrl[alternative.candidate.url],
                                    )
                                }
                            val best = memberEvaluation.best

                            if (alternative.index != representative.index) {
                                val shiftMs = constantTimelineShiftMs(
                                    representative.loaded.cues,
                                    alternative.loaded.cues,
                                ) ?: 0L
                                AutoSyncDebugLog.info {
                                    "CANDIDATE[${alternative.index}] reused exact shift-equivalent " +
                                        "timing result from CANDIDATE[${representative.index}] " +
                                        "shift=${shiftMs}ms"
                                }
                            }

                            if (best == null || !best.timeline.confident) continue

                            val previous = bestAlternativeMatch
                            if (
                                previous == null ||
                                directTimelineQualityScore(best) >
                                    directTimelineQualityScore(previous)
                            ) {
                                bestAlternativeMatch = best
                                bestAlternative = AutoSyncResolvedTimeline(
                                    subtitleUrl = alternative.candidate.url,
                                    subtitleHeaders =
                                        headersForCandidate(alternative.candidate.url),
                                    timeline = best.timeline,
                                )
                                bestAlternativeIndex = alternative.index
                                bestAlternativeName = alternative.candidate.name
                                bestAlternativeCueCount = alternative.loaded.cues.size
                            }

                            if (isExceptionalMatch(best)) {
                                stopFallbackSearch = true
                                break
                            }
                        }

                        if (stopFallbackSearch) break
                    }

                    if (
                        !stopFallbackSearch &&
                        bestAlternativeMatch?.let(::isStrongCheckpointMatch) == true
                    ) {
                        stopFallbackSearch = true
                    }

                    if (stopFallbackSearch) break
                }

                if (
                    stopFallbackSearch ||
                    (
                        bestAlternativeMatch != null &&
                            isFallbackBatchStopMatch(
                                bestAlternativeMatch!!,
                                targetCueCount = bestAlternativeCueCount,
                            )
                        )
                ) {
                    break
                }
                batchStartIndex = batchEndExclusive
            }

            alternativeLoads.values
                .filterNot { it.isCompleted }
                .forEach { it.cancel() }

            val chosenAlternative = bestAlternative
            val chosenAlternativeMatch = bestAlternativeMatch
            if (chosenAlternative != null && chosenAlternativeMatch != null) {
                if (!selectedSubtitleDeferred.isCompleted) {
                    selectedSubtitleDeferred.cancel()
                }
                AutoSyncDebugLog.section { "FINAL RECOMMENDATION" }
                AutoSyncDebugLog.info {
                    "V2 selected preflight-ranked candidate index=$bestAlternativeIndex " +
                        "url=${chosenAlternative.subtitleUrl} name=${bestAlternativeName ?: "<none>"} " +
                        "cues=$bestAlternativeCueCount " +
                        "alignment=${chosenAlternative.timeline.alignmentSource} " +
                        "quality=${fmt(directTimelineQualityScore(chosenAlternativeMatch))}"
                }
                return@supervisorScope chosenAlternative
            }

            AutoSyncDebugLog.section { "FINAL RECOMMENDATION" }
            AutoSyncDebugLog.warn {
                "REJECT no confident match found; original subtitle timing should be kept"
            }
            null
        }
    }

    private suspend fun evaluateExternalCandidate(
        label: String,
        url: String,
        target: List<SubtitleSyncCue>,
        referenceTracks: List<ReferenceTrack>,
        referenceActivityCache: MutableMap<String, AutoSyncTimelineRetimer.PreparedActivity?>,
        preferredReferenceKey: String? = null,
        preflightHint: AutoSyncDelayPreflight.Match? = null,
    ): CandidateEvaluation = withContext(Dispatchers.Default) {
        val targetActivity = AutoSyncTimelineRetimer.prepareUnitActivity(target)

        val representatives = groupEquivalentReferenceTimelines(referenceTracks)
            .mapNotNull { group ->
                group.members.minWithOrNull(
                    compareBy<ReferenceTrack> { isSdhReferenceTrack(it) }
                        .thenBy { it.key },
                )
            }
            .map { track ->
                RankedReferenceCandidate(
                    track = track,
                    cheapAffinity = cheapReferenceAffinity(track.cues, target),
                    suitability = referenceSuitabilityScore(track, target),
                )
            }
            .sortedWith(
                compareByDescending<RankedReferenceCandidate> {
                    if (it.track.key == preferredReferenceKey) 1 else 0
                }.thenByDescending { it.suitability }
                    .thenByDescending { it.cheapAffinity }
                    .thenBy { isSdhReferenceTrack(it.track) }
                    .thenBy { it.track.key },
            )

        preflightHint?.let { hint ->
            AutoSyncDebugLog.info {
                "$label preflight hint reference=${hint.referenceKey} " +
                    "offset=${"%.1f".format(hint.offsetMs)}ms score=${fmt(hint.score)} " +
                    "margin=${fmt(hint.margin)} segments=${hint.segmentsPassed}"
            }
        }

        AutoSyncDebugLog.section { "$label EMBEDDED REFERENCE ORDER" }
        representatives.forEachIndexed { index, ranked ->
            val track = ranked.track
            AutoSyncDebugLog.info {
                "[$index] reference=${track.key} label=${track.label ?: "<none>"} " +
                    "sdh=${isSdhReferenceTrack(track)} cues=${track.cues.size} " +
                    "cueRatio=${fmt(referenceCueRatio(track, target))} " +
                    "suitability=${fmt(ranked.suitability)} " +
                    "cheapAffinity=${fmt(ranked.cheapAffinity)}"
            }
        }

        val attempts = ArrayList<TimelineRetimeMatch>(representatives.size)
        var bestConfident: TimelineRetimeMatch? = null

        for (ranked in representatives) {
            val track = ranked.track
            val preparedReference = synchronized(referenceActivityCache) {
                if (referenceActivityCache.containsKey(track.key)) {
                    referenceActivityCache[track.key]
                } else {
                    val prepared = AutoSyncTimelineRetimer.prepareUnitActivity(track.cues)
                    referenceActivityCache[track.key] = prepared
                    prepared
                }
            }

            val timeline = buildTimelineRetimeResult(
                track = track,
                target = target,
                preparedReferenceActivity = preparedReference,
                preparedTargetActivity = targetActivity,
                delayOnlyHint =
                    preflightHint
                        ?.takeIf { it.referenceKey == track.key }
                        ?.alignment,
            ) ?: continue
            val match = TimelineRetimeMatch(track, timeline)
            attempts += match

            AutoSyncDebugLog.info {
                "$label reference=${track.key} quality=${fmt(directTimelineQualityScore(match))} " +
                    "decision=${if (timeline.confident) "ACCEPT" else "REJECT"} " +
                    "alignment=${timeline.alignmentSource} scale=${"%.6f".format(timeline.alignmentScale)} " +
                    "intercept=${"%.1f".format(timeline.alignmentInterceptMs)}ms " +
                    "activityScore=${fmt(timeline.activityScore)} activityMargin=${fmt(timeline.activityMargin)} " +
                    "targetCoverage=${fmt(timeline.targetCoverage)} referenceCoverage=${fmt(timeline.referenceCoverage)} " +
                    "avgGroupCost=${fmt(timeline.averageGroupCost)} simpleRatio=${fmt(timeline.simpleGroupRatio)}"
            }

            if (timeline.confident) {
                val previous = bestConfident
                if (
                    previous == null ||
                    directTimelineQualityScore(match) > directTimelineQualityScore(previous)
                ) {
                    bestConfident = match
                }

                if (isExceptionalMatch(match)) {
                    AutoSyncDebugLog.info {
                        "$label exceptional reference accepted early reference=${track.key} " +
                            "quality=${fmt(directTimelineQualityScore(match))}"
                    }
                    return@withContext CandidateEvaluation(best = match, attempts = attempts)
                }
            }

            if (attempts.size >= REFERENCE_SEARCH_CHECKPOINT) {
                val checkpointBest = bestConfident
                if (
                    checkpointBest != null &&
                    (
                        isStrongCheckpointMatch(checkpointBest) ||
                            isAsymmetricReferenceCheckpointMatch(
                                checkpointBest,
                                targetCueCount = target.size,
                            )
                        )
                ) {
                    AutoSyncDebugLog.info {
                        "$label reference search stopped after ${attempts.size} usable candidates " +
                            "best=${checkpointBest.track.key} " +
                            "quality=${fmt(directTimelineQualityScore(checkpointBest))}"
                    }
                    break
                }
            }
        }

        val best = attempts.maxWithOrNull(
            compareBy<TimelineRetimeMatch> { if (it.timeline.confident) 1 else 0 }
                .thenBy(::directTimelineQualityScore),
        )

        AutoSyncDebugLog.section { "$label RESULT" }
        if (best == null) {
            AutoSyncDebugLog.warn { "no usable whole-timeline alignment url=$url" }
        } else {
            val timeline = best.timeline
            AutoSyncDebugLog.info {
                "url=$url reference=${best.track.key} groups=${timeline.groups.size} " +
                    "quality=${fmt(directTimelineQualityScore(best))} " +
                    "alignment=${timeline.alignmentSource} scale=${"%.6f".format(timeline.alignmentScale)} " +
                    "decision=${if (timeline.confident) "ACCEPT" else "REJECT"}"
            }
        }

        CandidateEvaluation(best = best, attempts = attempts)
    }

    private fun logLoadedExternalSubtitle(
        label: String,
        url: String,
        loaded: LoadedSubtitle,
        sampleLimit: Int,
    ) {
        AutoSyncDebugLog.info {
            "$label url=$url cues=${loaded.cues.size} " +
                "download=${loaded.downloadMs}ms parse=${loaded.parseMs}ms cached=${loaded.cacheHit}"
        }
        if (AutoSyncDebugLog.ENABLED) {
            loaded.cues.take(sampleLimit).forEachIndexed { index, cue ->
                AutoSyncDebugLog.cue(
                    prefix = label,
                    index = index,
                    startMs = cue.startTimeMs,
                    endMs = cue.endTimeMs,
                    text = cue.text,
                )
            }
        }
    }

    private fun constantTimelineShiftMs(
        representative: List<SubtitleSyncCue>,
        candidate: List<SubtitleSyncCue>,
    ): Long? {
        if (representative.size < 4 || representative.size != candidate.size) return null

        val shiftMs = candidate.first().startTimeMs - representative.first().startTimeMs
        for (index in representative.indices) {
            val left = representative[index]
            val right = candidate[index]
            if (
                right.startTimeMs - left.startTimeMs != shiftMs ||
                right.endTimeMs - left.endTimeMs != shiftMs
            ) {
                return null
            }
        }
        return shiftMs
    }

    private fun reuseShiftEquivalentEvaluation(
        evaluation: CandidateEvaluation,
        representativeTarget: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
    ): CandidateEvaluation? {
        val shiftMs = constantTimelineShiftMs(representativeTarget, target)
            ?: return null

        fun shifted(match: TimelineRetimeMatch): TimelineRetimeMatch? {
            val timeline = match.timeline
            if (timeline.cues.size != target.size) return null

            return match.copy(
                timeline = timeline.copy(
                    cues = timeline.cues.mapIndexed { index, cue ->
                        val targetCue = target[index]
                        cue.copy(
                            originalStartTimeMs = targetCue.startTimeMs,
                            originalEndTimeMs = targetCue.endTimeMs,
                        )
                    },
                    alignmentInterceptMs =
                        timeline.alignmentInterceptMs -
                            timeline.alignmentScale * shiftMs.toDouble(),
                ),
            )
        }

        val shiftedBest = evaluation.best?.let { shifted(it) ?: return null }
        val shiftedAttempts = ArrayList<TimelineRetimeMatch>(evaluation.attempts.size)
        for (attempt in evaluation.attempts) {
            shiftedAttempts += shifted(attempt) ?: return null
        }

        return CandidateEvaluation(
            best = shiftedBest,
            attempts = shiftedAttempts,
        )
    }

    private fun referenceCueRatio(
        track: ReferenceTrack,
        target: List<SubtitleSyncCue>,
    ): Double {
        if (track.cues.isEmpty() || target.isEmpty()) return 0.0
        val smaller = minOf(track.cues.size, target.size).toDouble()
        val larger = maxOf(track.cues.size, target.size).toDouble()
        return if (larger <= 0.0) 0.0 else smaller / larger
    }

    private fun referenceSuitabilityScore(
        track: ReferenceTrack,
        target: List<SubtitleSyncCue>,
    ): Double {
        if (track.cues.isEmpty() || target.isEmpty()) return 0.0
        val cueRatio = referenceCueRatio(track, target)
        val referenceSpan = referenceSpanMs(track.cues).coerceAtLeast(1L)
        val targetSpan = referenceSpanMs(target).coerceAtLeast(1L)
        val spanRatio =
            minOf(referenceSpan, targetSpan).toDouble() /
                maxOf(referenceSpan, targetSpan).toDouble()
        val nonSdhBonus = if (isSdhReferenceTrack(track)) 0.0 else 0.08
        return cueRatio * 0.60 + spanRatio * 0.32 + nonSdhBonus
    }

    private fun cheapReferenceAffinity(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
    ): Double {
        if (reference.size < 4 || target.size < 4) return 0.0

        val referenceSample = evenlySampleCues(reference, CHEAP_REFERENCE_SAMPLE_CUES)
        val targetSample = evenlySampleCues(target, CHEAP_TARGET_SAMPLE_CUES)
        if (referenceSample.isEmpty() || targetSample.isEmpty()) return 0.0

        val scales = mutableListOf(
            1.0,
            25.0 / 23.976,
            23.976 / 25.0,
            25.0 / 24.0,
            24.0 / 25.0,
            24.0 / 23.976,
            23.976 / 24.0,
        )

        val referenceSpan = reference.last().startTimeMs - reference.first().startTimeMs
        val targetSpan = target.last().startTimeMs - target.first().startTimeMs
        if (referenceSpan > 0L && targetSpan > 0L) {
            val observedScale = referenceSpan.toDouble() / targetSpan.toDouble()
            if (
                observedScale.isFinite() &&
                observedScale in 0.94..1.06 &&
                scales.none { abs(it - observedScale) < 0.00035 }
            ) {
                scales += observedScale
            }
        }

        var bestScore = 0.0
        for (scale in scales) {
            val offsets = LinkedHashSet<Long>()

            for (anchor in 0 until CHEAP_REFERENCE_OFFSET_CANDIDATES) {
                val referenceIndex =
                    (anchor.toLong() * referenceSample.lastIndex /
                        (CHEAP_REFERENCE_OFFSET_CANDIDATES - 1)).toInt()
                val targetIndex =
                    (anchor.toLong() * targetSample.lastIndex /
                        (CHEAP_REFERENCE_OFFSET_CANDIDATES - 1)).toInt()

                offsets += referenceSample[referenceIndex].startTimeMs -
                    (targetSample[targetIndex].startTimeMs.toDouble() * scale).roundToLong()
            }

            for (offsetMs in offsets) {
                var hits = 0
                var residualTotal = 0L

                for (cue in targetSample) {
                    val shiftedStart =
                        (cue.startTimeMs.toDouble() * scale).roundToLong() + offsetMs
                    val insertion = lowerBoundCueStart(reference, shiftedStart)

                    var nearest = Long.MAX_VALUE
                    if (insertion < reference.size) {
                        nearest = abs(reference[insertion].startTimeMs - shiftedStart)
                    }
                    if (insertion > 0) {
                        nearest = minOf(
                            nearest,
                            abs(reference[insertion - 1].startTimeMs - shiftedStart),
                        )
                    }

                    if (nearest <= CHEAP_REFERENCE_MATCH_TOLERANCE_MS) {
                        hits++
                        residualTotal += nearest
                    }
                }

                if (hits == 0) continue
                val participation = hits.toDouble() / targetSample.size.toDouble()
                val meanResidual = residualTotal.toDouble() / hits.toDouble()
                val residualScore = exp(-meanResidual / 900.0)
                val score = participation * 0.80 + residualScore * 0.20
                if (score > bestScore) bestScore = score
            }
        }

        return bestScore.coerceIn(0.0, 1.0)
    }

    private fun evenlySampleCues(
        cues: List<SubtitleSyncCue>,
        maxSamples: Int,
    ): List<SubtitleSyncCue> {
        if (cues.size <= maxSamples) return cues
        if (maxSamples <= 1) return listOf(cues.first())

        val lastIndex = cues.lastIndex
        return (0 until maxSamples)
            .map { sampleIndex ->
                cues[(sampleIndex.toLong() * lastIndex / (maxSamples - 1)).toInt()]
            }
            .distinctBy { it.startTimeMs }
    }

    private fun lowerBoundCueStart(
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

    private suspend fun loadSelectedSubtitle(
        url: String,
        headers: Map<String, String>,
    ): LoadedSubtitle? {
        val cacheKey = ParsedSubtitleCacheKey(url, stableHeaderHash(headers))
        synchronized(parsedSubtitleCacheLock) {
            parsedSubtitleCache[cacheKey]
        }?.let { cues ->
            return LoadedSubtitle(cues, 0L, 0L, cacheHit = true)
        }

        val downloadStarted = SystemClock.elapsedRealtime()
        val text = try {
            downloadSubtitleTextWithSingle429Retry(url, headers)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            AutoSyncDebugLog.error(error) { "selected subtitle download failed" }
            return null
        }
        val downloadMs = SystemClock.elapsedRealtime() - downloadStarted

        val parseStarted = SystemClock.elapsedRealtime()
        val cues = try {
            withContext(Dispatchers.Default) {
                AutoSyncTimelineRetimer.normalizeExternalTimeline(
                    PlayerSubtitleCueParser.parse(text = text, sourceUrl = url),
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            AutoSyncDebugLog.error(error) { "selected subtitle parse failed" }
            return null
        }
        val parseMs = SystemClock.elapsedRealtime() - parseStarted

        if (cues.size < MIN_SELECTED_CUES) {
            AutoSyncDebugLog.warn {
                "selected subtitle rejected before V2 cues=${cues.size} required=$MIN_SELECTED_CUES"
            }
            return null
        }

        val immutable = cues.toList()
        synchronized(parsedSubtitleCacheLock) {
            parsedSubtitleCache[cacheKey] = immutable
        }
        return LoadedSubtitle(immutable, downloadMs, parseMs, cacheHit = false)
    }

    private suspend fun downloadSubtitleTextWithSingle429Retry(
        url: String,
        headers: Map<String, String>,
    ): String {
        val first = requestSubtitle(url, headers)
        if (first.status == 429) {
            AutoSyncDebugLog.warn {
                "selected subtitle HTTP 429; retrying once after ${HTTP_429_RETRY_DELAY_MS}ms"
            }
            delay(HTTP_429_RETRY_DELAY_MS)
            return validatedSubtitleBody(requestSubtitle(url, headers))
        }
        return validatedSubtitleBody(first)
    }

    private suspend fun requestSubtitle(
        url: String,
        headers: Map<String, String>,
    ): com.nuvio.app.features.addons.RawHttpResponse =
        withTimeoutOrNull(SUBTITLE_DOWNLOAD_TIMEOUT_MS) {
            httpRequestRaw(
                method = "GET",
                url = url,
                headers = mapOf("Accept" to "*/*") + headers,
                body = "",
                followRedirects = true,
                maxResponseBodyBytes = MAX_SUBTITLE_RESPONSE_BYTES,
            )
        } ?: error("subtitle request timed out after ${SUBTITLE_DOWNLOAD_TIMEOUT_MS}ms")

    private fun validatedSubtitleBody(
        response: com.nuvio.app.features.addons.RawHttpResponse,
    ): String {
        if (response.status !in 200..299) error("subtitle HTTP ${response.status}")
        if (response.body.endsWith("\n...[truncated]")) {
            error("subtitle response exceeded $MAX_SUBTITLE_RESPONSE_BYTES bytes")
        }
        if (response.body.isBlank()) error("empty subtitle response")
        return response.body
    }

    private suspend fun awaitNearCompleteLiveReferences(
        sourceKey: String,
        preferredLanguage: String?,
        target: List<SubtitleSyncCue>,
        waitMs: Long = LIVE_REFERENCE_WAIT_MS,
    ): List<ReferenceTrack> {
        val targetSpan = referenceSpanMs(target).coerceAtLeast(1L)
        val started = SystemClock.elapsedRealtime()
        var lastSignature = ""

        while (true) {
            currentCoroutineContext().ensureActive()
            val prepared = EmbeddedSubtitleCueStore
                .candidateTracks(sourceKey, preferredLanguage)
                .map { track -> track.copy(cues = deduplicateReferenceCues(track.cues)) }

            val profiles = prepared.map(::buildReferenceProfile)
            val ready = orderReferenceProfiles(
                profiles.filter { profile ->
                    profile.fullDialogue &&
                        profile.cueCount >= MIN_LIVE_REFERENCE_CUES &&
                        profile.spanMs.toDouble() / targetSpan.toDouble() >= MIN_LIVE_REFERENCE_SPAN_RATIO
                },
            )

            val signature = prepared.joinToString("|") { track ->
                "${track.key}:g${track.generation}:${track.cues.size}:" +
                    "${track.cues.lastOrNull()?.startTimeMs ?: -1L}"
            }
            if (signature != lastSignature) {
                lastSignature = signature
                AutoSyncDebugLog.section { "LIVE MEDIA3 REFERENCE" }
                AutoSyncDebugLog.info {
                    "tracks=${prepared.size} nearComplete=${ready.size} " +
                        "waited=${SystemClock.elapsedRealtime() - started}ms"
                }
            }

            if (ready.isNotEmpty()) {
                return ready.map { it.track.copy(cues = it.track.cues.toList()) }
            }

            val elapsedMs = SystemClock.elapsedRealtime() - started
            if (elapsedMs >= waitMs) break
            delay(minOf(LIVE_REFERENCE_POLL_MS, waitMs - elapsedMs))
        }

        if (waitMs == 0L) {
            AutoSyncDebugLog.info {
                "Media3 live wait skipped because Matroska Cues has no subtitle entries"
            }
        } else {
            AutoSyncDebugLog.info {
                "Media3 did not expose a near-complete reference within ${waitMs}ms"
            }
        }
        return emptyList()
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
            if (exactGroup != null) exactGroup.members += track
            else bucket += ReferenceTimingGroup(mutableListOf(track))
        }
        return buckets.values.flatten()
    }

    private fun referenceTimingFingerprint(cues: List<SubtitleSyncCue>): ReferenceTimingFingerprint {
        var timingHash = 1_125_899_906_842_597L
        for (cue in cues) {
            timingHash = timingHash * 31L + cue.startTimeMs
            timingHash = timingHash * 31L + cue.endTimeMs
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
            left[index].startTimeMs == right[index].startTimeMs &&
                left[index].endTimeMs == right[index].endTimeMs
        }
    }

    private fun buildReferenceProfile(track: ReferenceTrack): ReferenceProfile {
        val cueCount = track.cues.size
        val spanMs = referenceSpanMs(track.cues)
        val density = if (spanMs <= 0L) 0.0 else cueCount * 60_000.0 / spanMs

        var textCueCount = 0
        var dialogueCueCount = 0
        if (track.generation >= 0L) {
            for (cue in track.cues) {
                if (cue.text.isBlank()) continue
                textCueCount++
                if (isDialogueLikeReferenceCue(cue)) dialogueCueCount++
            }
        }
        val dialogueRatio =
            if (textCueCount == 0) 0.5 else dialogueCueCount.toDouble() / textCueCount

        val fullDialogue =
            cueCount >= MIN_FULL_DIALOGUE_CUES &&
                spanMs >= MIN_FULL_DIALOGUE_CLASSIFICATION_SPAN_MS &&
                !isForcedReferenceTrack(track) &&
                !isCommentaryReferenceTrack(track) &&
                !isDescriptiveReferenceTrack(track) &&
                density >= MIN_FULL_DIALOGUE_DENSITY_PER_MINUTE &&
                (textCueCount < 4 || dialogueRatio >= MIN_FULL_DIALOGUE_TEXT_RATIO)

        val dialogueRoleBonus =
            if ((track.roleFlags and C.ROLE_FLAG_TRANSCRIBES_DIALOG) != 0) 8.0 else 0.0
        val subtitleRoleBonus =
            if ((track.roleFlags and C.ROLE_FLAG_SUBTITLE) != 0) 4.0 else 0.0
        val sdhPenalty = if (isSdhReferenceTrack(track)) 5.0 else 0.0
        val rankingScore =
            cueCount * 2.0 +
                density.coerceAtMost(20.0) * 1.5 +
                dialogueRatio * 20.0 +
                dialogueRoleBonus +
                subtitleRoleBonus -
                sdhPenalty

        return ReferenceProfile(
            track = track,
            cueCount = cueCount,
            spanMs = spanMs,
            densityPerMinute = density,
            fullDialogue = fullDialogue,
            rankingScore = rankingScore,
        )
    }

    private fun orderReferenceProfiles(profiles: List<ReferenceProfile>): List<ReferenceProfile> =
        profiles.sortedWith(
            compareByDescending<ReferenceProfile> { it.fullDialogue }
                .thenByDescending { it.rankingScore }
                .thenBy { isSdhReferenceTrack(it.track) }
                .thenBy { it.track.key },
        )

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
        return track.label.orEmpty().contains("commentary", ignoreCase = true)
    }

    private fun isDescriptiveReferenceTrack(track: ReferenceTrack): Boolean {
        if ((track.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) return true
        val label = track.label.orEmpty().lowercase()
        return label.contains("audio description") || label.contains("descriptive subtitle")
    }

    internal fun isSdhReferenceTrack(track: ReferenceTrack): Boolean {
        if ((track.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0) return true
        val label = track.label.orEmpty().lowercase()
        return label.contains("sdh") ||
            label.contains("shd") ||
            label.contains("hoh") ||
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

    private fun deduplicateReferenceCues(cues: List<SubtitleSyncCue>): List<SubtitleSyncCue> {
        if (cues.size < 2) return cues
        val sorted = cues.sortedBy { it.startTimeMs }
        val out = ArrayList<SubtitleSyncCue>(sorted.size)
        for (cue in sorted) {
            val previous = out.lastOrNull()
            if (
                previous != null &&
                abs(previous.startTimeMs - cue.startTimeMs) <= 125L &&
                (
                    previous.text.isBlank() ||
                        cue.text.isBlank() ||
                        normalizedCueText(previous.text) == normalizedCueText(cue.text)
                    )
            ) {
                if (previous.text.isBlank() && cue.text.isNotBlank()) out[out.lastIndex] = cue
            } else {
                out += cue
            }
        }
        return out
    }

    private fun normalizedCueText(text: String): String =
        text.replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()

    private fun referenceSpanMs(cues: List<SubtitleSyncCue>): Long =
        if (cues.size < 2) 0L else cues.last().endTimeMs - cues.first().startTimeMs

    private fun directTimelineQualityScore(match: TimelineRetimeMatch): Double {
        val result = match.timeline
        val costScore = 1.0 / (1.0 + result.averageGroupCost.coerceAtLeast(0.0))
        val skipPenalty = result.longestTargetSkipRun.coerceAtMost(20) * 0.004
        val sdhPenalty = if (isSdhReferenceTrack(match.track)) 0.010 else 0.0
        return result.targetCoverage * 0.42 +
            result.referenceCoverage * 0.15 +
            costScore * 0.23 +
            result.simpleGroupRatio * 0.20 -
            skipPenalty -
            sdhPenalty
    }

    private fun isExceptionalMatch(match: TimelineRetimeMatch): Boolean {
        val result = match.timeline
        return result.confident &&
            directTimelineQualityScore(match) >= EXCEPTIONAL_MATCH_QUALITY &&
            result.targetCoverage >= EXCEPTIONAL_MATCH_TARGET_COVERAGE &&
            result.referenceCoverage >= EXCEPTIONAL_MATCH_REFERENCE_COVERAGE &&
            result.simpleGroupRatio >= EXCEPTIONAL_MATCH_SIMPLE_RATIO
    }

    private fun isStrongCheckpointMatch(match: TimelineRetimeMatch): Boolean {
        val result = match.timeline
        return result.confident &&
            directTimelineQualityScore(match) >= STRONG_CHECKPOINT_QUALITY &&
            result.targetCoverage >= STRONG_CHECKPOINT_TARGET_COVERAGE &&
            result.referenceCoverage >= STRONG_CHECKPOINT_REFERENCE_COVERAGE
    }

    private fun isAsymmetricReferenceCheckpointMatch(
        match: TimelineRetimeMatch,
        targetCueCount: Int,
    ): Boolean {
        if (targetCueCount <= 0) return false
        val result = match.timeline
        val referenceRatio = match.track.cues.size.toDouble() / targetCueCount.toDouble()

        return result.confident &&
            referenceRatio >= ASYMMETRIC_CHECKPOINT_MIN_REFERENCE_RATIO &&
            directTimelineQualityScore(match) >= ASYMMETRIC_CHECKPOINT_QUALITY &&
            result.targetCoverage >= ASYMMETRIC_CHECKPOINT_TARGET_COVERAGE &&
            result.referenceCoverage >= ASYMMETRIC_CHECKPOINT_REFERENCE_COVERAGE &&
            result.averageGroupCost <= ASYMMETRIC_CHECKPOINT_MAX_GROUP_COST &&
            result.simpleGroupRatio >= ASYMMETRIC_CHECKPOINT_SIMPLE_RATIO &&
            result.longestTargetSkipRun <= ASYMMETRIC_CHECKPOINT_MAX_TARGET_SKIP_RUN
    }

    private fun isFallbackBatchStopMatch(
        match: TimelineRetimeMatch,
        targetCueCount: Int,
    ): Boolean {
        if (isStrongCheckpointMatch(match)) return true
        if (isAsymmetricReferenceCheckpointMatch(match, targetCueCount)) return true

        val result = match.timeline
        return result.confident &&
            directTimelineQualityScore(match) >= FALLBACK_BATCH_STOP_QUALITY &&
            result.targetCoverage >= FALLBACK_BATCH_STOP_TARGET_COVERAGE &&
            result.referenceCoverage >= FALLBACK_BATCH_STOP_REFERENCE_COVERAGE &&
            result.simpleGroupRatio >= FALLBACK_BATCH_STOP_SIMPLE_RATIO
    }

    private fun buildTimelineRetimeResult(
        track: ReferenceTrack,
        target: List<SubtitleSyncCue>,
        preparedReferenceActivity: AutoSyncTimelineRetimer.PreparedActivity?,
        preparedTargetActivity: AutoSyncTimelineRetimer.PreparedActivity?,
        delayOnlyHint: AutoSyncDelayOnlyAlignment? = null,
    ): AutoSyncTimelineRetimeResult? {
        val overSegmentedReference =
            track.cues.size.toLong() * 2L >= target.size.toLong() * 3L
        val relaxDelayMargin =
            isSdhReferenceTrack(track) || overSegmentedReference

        if (relaxDelayMargin) {
            AutoSyncDebugLog.info {
                "delay-only margin relaxation reference=${track.key} " +
                    "sdh=${isSdhReferenceTrack(track)} " +
                    "referenceCues=${track.cues.size} targetCues=${target.size}"
            }
        }

        return AutoSyncTimelineRetimer.retime(
            reference = track.cues,
            target = target,
            coarseScale = 1.0,
            coarseInterceptMs = 0.0,
            discoverAlignment = true,
            allowAmbiguousDelayOnlyMargin = relaxDelayMargin,
            referenceEstimatedEndStartsMs = track.estimatedEndStartsMs,
            preparedReferenceActivity = preparedReferenceActivity,
            preparedTargetActivity = preparedTargetActivity,
            precomputedDelayOnly = delayOnlyHint,
        )
    }

    private fun stableHeaderHash(headers: Map<String, String>): Int {
        var hash = 1
        headers.entries
            .sortedBy { it.key.lowercase() }
            .forEach { (key, value) ->
                hash = 31 * hash + key.lowercase().hashCode()
                hash = 31 * hash + value.hashCode()
            }
        return hash
    }

    private fun fmt(value: Double): String = "%.4f".format(value)

    private data class ParsedSubtitleCacheKey(val url: String, val headerHash: Int)
    private data class LoadedSubtitle(
        val cues: List<SubtitleSyncCue>,
        val downloadMs: Long,
        val parseMs: Long,
        val cacheHit: Boolean,
    )
    private data class ReferenceTimingFingerprint(
        val cueCount: Int,
        val firstStartMs: Long,
        val lastStartMs: Long,
        val timingHash: Long,
    )
    private data class ReferenceTimingGroup(val members: MutableList<ReferenceTrack>)
    private data class ReferenceProfile(
        val track: ReferenceTrack,
        val cueCount: Int,
        val spanMs: Long,
        val densityPerMinute: Double,
        val fullDialogue: Boolean,
        val rankingScore: Double,
    )
    private data class RankedReferenceCandidate(
        val track: ReferenceTrack,
        val cheapAffinity: Double,
        val suitability: Double,
    )
    private data class CandidateEvaluation(
        val best: TimelineRetimeMatch?,
        val attempts: List<TimelineRetimeMatch>,
    )
    private data class CachedTimingEvaluation(
        val target: List<SubtitleSyncCue>,
        val evaluation: CandidateEvaluation,
    )
    private data class LoadedAlternative(
        val index: Int,
        val candidate: AutoSyncSubtitleCandidate,
        val loaded: LoadedSubtitle,
    )
    private data class EvaluatedAlternativeTimingGroup(
        val members: List<LoadedAlternative>,
        val evaluation: CandidateEvaluation,
    )
    private data class CompletedPreflight(
        val candidate: AutoSyncSubtitleCandidate,
        val loaded: LoadedSubtitle?,
        val match: AutoSyncDelayPreflight.Match?,
    )

    private data class TimelineRetimeMatch(
        val track: ReferenceTrack,
        val timeline: AutoSyncTimelineRetimeResult,
    )
}

internal data class AutoSyncResolvedTimeline(
    val subtitleUrl: String,
    val subtitleHeaders: Map<String, String>,
    val timeline: AutoSyncTimelineRetimeResult,
)

internal data class ReferenceTrack(
    val key: String,
    val language: String?,
    val cues: List<SubtitleSyncCue>,
    val label: String? = null,
    val selectionFlags: Int = 0,
    val roleFlags: Int = 0,
    val generation: Long = 0L,
    val estimatedEndStartsMs: Set<Long> = emptySet(),
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

        AutoSyncDebugLog.verbose { "embedded store reset generation=$generation" }
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
            AutoSyncDebugLog.verbose { "embedded store seek generation=$it target=${targetTimeMs ?: -1L}ms" }
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
            AutoSyncDebugLog.verbose { "CAPTURE track=$trackKey lang=${language ?: "<unknown>"} " +
                    "count=$cueCount ${AutoSyncDebugLog.formatTimestamp(cue.startTimeMs)} " +
                    "| \"${cue.text.replace('\n', ' ').take(500).ifBlank { "<text unavailable>" }}\"" }
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
