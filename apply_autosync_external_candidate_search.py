#!/usr/bin/env python3
from pathlib import Path
import re
import subprocess

ROOT = Path.cwd()
if not (ROOT / "composeApp").exists():
    raise SystemExit("Run this from the root of NuvioMobile-AutoSync.")

branch = subprocess.check_output(
    ["git", "rev-parse", "--abbrev-ref", "HEAD"], text=True
).strip()
if branch != "autosync-v2-timeline":
    raise SystemExit(f"Wrong branch: {branch}. Switch to autosync-v2-timeline first.")

if subprocess.run(["git", "diff", "--quiet"]).returncode != 0:
    raise SystemExit("Tracked working-tree changes exist. Commit/stash them first.")
if subprocess.run(["git", "diff", "--cached", "--quiet"]).returncode != 0:
    raise SystemExit("Staged changes exist. Commit/stash them first.")

retimer_path = ROOT / "composeApp/src/commonMain/kotlin/com/nuvio/app/features/autosync/AutoSyncTimelineRetime.kt"
sync_path = ROOT / "composeApp/src/androidMain/kotlin/com/nuvio/app/features/autosync/AutomaticSubtitleSync.kt"
engine_common_path = ROOT / "composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerEngine.kt"
engine_android_path = ROOT / "composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt"
runtime_ui_path = ROOT / "composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeUi.kt"
test_path = ROOT / "composeApp/src/commonTest/kotlin/com/nuvio/app/features/autosync/AutoSyncTimelineRetimeTest.kt"

for p in (retimer_path, sync_path, engine_common_path, engine_android_path, runtime_ui_path, test_path):
    if not p.exists():
        raise SystemExit(f"Missing expected file: {p}")

retimer = retimer_path.read_text()
sync = sync_path.read_text()
engine_common = engine_common_path.read_text()
engine_android = engine_android_path.read_text()
runtime_ui = runtime_ui_path.read_text()
tests = test_path.read_text()

# 1) Scale probe first; only then delay-only if scale is close to 1.0.
if "private const val MIN_CUES = 8" in retimer:
    retimer = retimer.replace("private const val MIN_CUES = 8", "private const val MIN_CUES = 4", 1)

if "private const val DELAY_ONLY_SCALE_TOLERANCE" not in retimer:
    marker = "    private const val DELAY_ONLY_MIN_CUES = 4\n"
    if marker not in retimer:
        raise SystemExit("Expected delay-only constants were not found.")
    retimer = retimer.replace(marker, marker + "    private const val DELAY_ONLY_SCALE_TOLERANCE = 0.0015\n", 1)

if "SMALL_SAMPLE_ACTIVITY_MIN_SCORE" not in retimer:
    marker = "    private const val DELAY_ONLY_DISTINCT_OFFSET_MS = 3_000L\n"
    if marker not in retimer:
        raise SystemExit("Could not find delay-only constant tail.")
    retimer = retimer.replace(marker, marker + (
        "    private const val SMALL_SAMPLE_CUE_LIMIT = 8\n"
        "    private const val SMALL_SAMPLE_ACTIVITY_MIN_SCORE = 0.72\n"
        "    private const val SMALL_SAMPLE_ACTIVITY_MIN_MARGIN = 0.03\n"
        "    private const val SMALL_SAMPLE_REQUIRED_COVERAGE_SEGMENTS = 2\n"
    ), 1)

old_order = '''        findDelayOnlyAlignment(reference, target)?.let { delayOnly ->
            return buildDelayOnlyTimeline(target, delayOnly)
        }

        val alignment = discoverActivityAlignment(reference, target) ?: return null
        val result = retimeWithSeed(
'''
new_order = '''        val alignment = discoverActivityAlignment(reference, target) ?: return null

        if (abs(alignment.scale - 1.0) <= DELAY_ONLY_SCALE_TOLERANCE) {
            findDelayOnlyAlignment(reference, target)?.let { delayOnly ->
                return buildDelayOnlyTimeline(target, delayOnly)
            }
        }

        val result = retimeWithSeed(
'''
if old_order not in retimer:
    raise SystemExit("Could not find current delay-first retime block.")
retimer = retimer.replace(old_order, new_order, 1)

old_confirmed = '''        val coverageSegments = coverageSegmentsPassed(result, target.size)
        val simpleRatio = simpleGroupRatio(result)
        val confirmed =
            result.confident &&
                alignment.score >= ACTIVITY_MIN_SCORE &&
                alignment.margin >= ACTIVITY_MIN_MARGIN &&
                coverageSegments == 3 &&
                result.targetCoverage >= DISCOVERED_MIN_TARGET_COVERAGE &&
                result.averageGroupCost <= DISCOVERED_MAX_AVERAGE_GROUP_COST &&
                result.longestTargetSkipRun <= MAX_LONGEST_TARGET_SKIP_RUN &&
                simpleRatio >= DISCOVERED_MIN_SIMPLE_GROUP_RATIO
'''
new_confirmed = '''        val coverageSegments = coverageSegmentsPassed(result, target.size)
        val simpleRatio = simpleGroupRatio(result)
        val smallSample = target.size < SMALL_SAMPLE_CUE_LIMIT
        val requiredActivityScore =
            if (smallSample) SMALL_SAMPLE_ACTIVITY_MIN_SCORE else ACTIVITY_MIN_SCORE
        val requiredActivityMargin =
            if (smallSample) SMALL_SAMPLE_ACTIVITY_MIN_MARGIN else ACTIVITY_MIN_MARGIN
        val requiredCoverageSegments =
            if (smallSample) SMALL_SAMPLE_REQUIRED_COVERAGE_SEGMENTS else 3

        val confirmed =
            result.confident &&
                alignment.score >= requiredActivityScore &&
                alignment.margin >= requiredActivityMargin &&
                coverageSegments >= requiredCoverageSegments &&
                result.targetCoverage >= DISCOVERED_MIN_TARGET_COVERAGE &&
                result.averageGroupCost <= DISCOVERED_MAX_AVERAGE_GROUP_COST &&
                result.longestTargetSkipRun <= MAX_LONGEST_TARGET_SKIP_RUN &&
                simpleRatio >= DISCOVERED_MIN_SIMPLE_GROUP_RATIO
'''
if old_confirmed not in retimer:
    raise SystemExit("Could not find full-V2 confidence block.")
retimer = retimer.replace(old_confirmed, new_confirmed, 1)

retimer = retimer.replace(
    "        val requiredSegments = if (target.size < MIN_CUES) 2 else 3\n",
    "        val requiredSegments = if (target.size < SMALL_SAMPLE_CUE_LIMIT) 2 else 3\n",
    1,
)
retimer = retimer.replace(
    "        if (targetSize < 3 || result.groups.isEmpty()) return 0\n",
    "        if (targetSize < MIN_CUES || result.groups.isEmpty()) return 0\n",
    1,
)
old_segment_gate = '''            if (
                coverage >= COVERAGE_SEGMENT_MIN_COVERAGE &&
                averageCost <= COVERAGE_SEGMENT_MAX_AVERAGE_COST &&
                segmentCosts.size >= COVERAGE_SEGMENT_MIN_GROUPS
            ) {
'''
if old_segment_gate in retimer:
    retimer = retimer.replace(old_segment_gate, '''            val requiredGroups = min(COVERAGE_SEGMENT_MIN_GROUPS, length)
            if (
                coverage >= COVERAGE_SEGMENT_MIN_COVERAGE &&
                averageCost <= COVERAGE_SEGMENT_MAX_AVERAGE_COST &&
                segmentCosts.size >= requiredGroups
            ) {
''', 1)

# 2) Common controller candidate feed.
if "data class AutoSyncSubtitleCandidate(" not in engine_common:
    marker = "interface PlayerEngineController {\n"
    if marker not in engine_common:
        raise SystemExit("Could not find PlayerEngineController.")
    engine_common = engine_common.replace(marker, '''data class AutoSyncSubtitleCandidate(
    val url: String,
    val language: String,
    val name: String? = null,
)

''' + marker, 1)

if "fun setAutoSyncSubtitleCandidates(" not in engine_common:
    marker = "    fun setSubtitleUri(url: String)\n"
    if marker not in engine_common:
        raise SystemExit("Could not find setSubtitleUri in PlayerEngineController.")
    engine_common = engine_common.replace(marker, marker + "    fun setAutoSyncSubtitleCandidates(candidates: List<AutoSyncSubtitleCandidate>) {}\n", 1)

# 3) Push fetched add-on candidates to controller, no player/media-source changes.
if "setAutoSyncSubtitleCandidates" not in runtime_ui:
    marker = "    val playbackGesturesEnabled = initialLoadCompleted && errorMessage == null\n"
    if marker not in runtime_ui:
        raise SystemExit("Could not find RenderPlayerRuntimeUi state marker.")
    runtime_ui = runtime_ui.replace(marker, marker + '''
    LaunchedEffect(playerController, addonSubtitles) {
        playerController?.setAutoSyncSubtitleCandidates(
            addonSubtitles.map { subtitle ->
                AutoSyncSubtitleCandidate(
                    url = subtitle.url,
                    language = subtitle.language,
                    name = subtitle.display,
                )
            },
        )
    }
''', 1)

# 4) AutomaticSubtitleSync orchestration.
if "import com.nuvio.app.features.player.AutoSyncSubtitleCandidate" not in sync:
    sync = sync.replace(
        "import com.nuvio.app.features.player.PlayerSubtitleCueParser\n",
        "import com.nuvio.app.features.player.AutoSyncSubtitleCandidate\n"
        "import com.nuvio.app.features.player.PlayerSubtitleCueParser\n"
        "import com.nuvio.app.features.player.SubtitleLanguageMatching\n",
        1,
    )

if "MAX_ALTERNATIVE_EXTERNAL_SUBTITLES" not in sync:
    marker = "    private const val MAX_LOGGED_CUE_SAMPLES = 20\n"
    if marker not in sync:
        raise SystemExit("Could not find AutoSync constants.")
    sync = sync.replace(marker, marker + "    private const val MAX_ALTERNATIVE_EXTERNAL_SUBTITLES = 4\n", 1)

start = sync.find("    suspend fun findTimelineRetime(")
end = sync.find("    private suspend fun loadSelectedSubtitle(", start)
if start < 0 or end < 0:
    raise SystemExit("Could not isolate findTimelineRetime().")

new_find = r'''    suspend fun findTimelineRetime(
        sourceKey: String,
        selectedSubtitleUrl: String,
        selectedSubtitleHeaders: Map<String, String>,
        preferredLanguage: String?,
        alternativeSubtitles: List<AutoSyncSubtitleCandidate> = emptyList(),
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

            val indexedTimeline = indexedTimelineDeferred.await()
            val selected = selectedSubtitleDeferred.await()

            AutoSyncDebugLog.section { "SELECTED SUBTITLE" }
            if (selected == null) {
                AutoSyncDebugLog.warn {
                    "selected subtitle could not be downloaded or parsed; searching alternatives"
                }
            } else {
                logLoadedExternalSubtitle(
                    label = "SELECTED",
                    url = selectedSubtitleUrl,
                    loaded = selected,
                    sampleLimit = MAX_LOGGED_CUE_SAMPLES,
                )
            }

            val selectedMetadata =
                alternativeSubtitles.firstOrNull { it.url == selectedSubtitleUrl }
            val selectedLanguage =
                selectedMetadata?.language?.takeIf { it.isNotBlank() }
                    ?: preferredLanguage?.takeIf { it.isNotBlank() }

            val alternatives = alternativeSubtitles
                .asSequence()
                .filter { it.url.isNotBlank() && it.url != selectedSubtitleUrl }
                .filter { candidate ->
                    selectedLanguage.isNullOrBlank() ||
                        SubtitleLanguageMatching.matchesLanguageCode(
                            candidate.language,
                            selectedLanguage,
                        )
                }
                .distinctBy { it.url }
                .take(MAX_ALTERNATIVE_EXTERNAL_SUBTITLES)
                .toList()

            var seedTarget = selected?.cues
            if (seedTarget.isNullOrEmpty() && alternatives.isNotEmpty()) {
                val loaded = loadSelectedSubtitle(alternatives.first().url, emptyMap())
                if (loaded != null) seedTarget = loaded.cues
            }

            if (seedTarget.isNullOrEmpty()) {
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
                )
            }

            if (referenceTracks.isEmpty()) {
                AutoSyncDebugLog.section { "FINAL RECOMMENDATION" }
                AutoSyncDebugLog.warn {
                    "REJECT no complete embedded subtitle timeline is available for V2"
                }
                return@supervisorScope null
            }

            onReferenceReady()

            if (selected != null) {
                val selectedEvaluation = evaluateExternalCandidate(
                    label = "SELECTED",
                    url = selectedSubtitleUrl,
                    target = selected.cues,
                    referenceTracks = referenceTracks,
                )
                val selectedBest = selectedEvaluation.best
                if (selectedBest?.timeline?.confident == true) {
                    return@supervisorScope AutoSyncResolvedTimeline(
                        subtitleUrl = selectedSubtitleUrl,
                        subtitleHeaders = selectedSubtitleHeaders,
                        timeline = selectedBest.timeline,
                    )
                }

                AutoSyncDebugLog.section { "EXTERNAL SUBTITLE FALLBACK" }
                AutoSyncDebugLog.info {
                    "selected subtitle did not produce a confident result; " +
                        "trying up to ${alternatives.size} same-language alternatives"
                }
            }

            for ((index, candidate) in alternatives.withIndex()) {
                val loaded = loadSelectedSubtitle(
                    url = candidate.url,
                    headers = emptyMap(),
                ) ?: continue

                logLoadedExternalSubtitle(
                    label = "ALTERNATIVE[$index]",
                    url = candidate.url,
                    loaded = loaded,
                    sampleLimit = 3,
                )

                val evaluation = evaluateExternalCandidate(
                    label = "ALTERNATIVE[$index]",
                    url = candidate.url,
                    target = loaded.cues,
                    referenceTracks = referenceTracks,
                )
                val best = evaluation.best
                if (best?.timeline?.confident == true) {
                    AutoSyncDebugLog.section { "FINAL RECOMMENDATION" }
                    AutoSyncDebugLog.info {
                        "V2 selected better external subtitle index=$index " +
                            "url=${candidate.url} name=${candidate.name ?: "<none>"} " +
                            "cues=${loaded.cues.size} alignment=${best.timeline.alignmentSource} " +
                            "quality=${fmt(directTimelineQualityScore(best))}"
                    }
                    return@supervisorScope AutoSyncResolvedTimeline(
                        subtitleUrl = candidate.url,
                        subtitleHeaders = emptyMap(),
                        timeline = best.timeline,
                    )
                }
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
    ): CandidateEvaluation = withContext(Dispatchers.Default) {
        val timingGroups = groupEquivalentReferenceTimelines(referenceTracks)
        val representatives = timingGroups.mapNotNull { group ->
            group.members.minWithOrNull(
                compareBy<ReferenceTrack> { isSdhReferenceTrack(it) }
                    .thenBy { it.key },
            )
        }.sortedWith(
            compareByDescending<ReferenceTrack> {
                referenceSuitabilityScore(it, target)
            }.thenBy {
                isSdhReferenceTrack(it)
            }.thenBy {
                it.key
            },
        )

        AutoSyncDebugLog.section { "$label EMBEDDED REFERENCE ORDER" }
        representatives.forEachIndexed { index, track ->
            AutoSyncDebugLog.info {
                "[$index] reference=${track.key} label=${track.label ?: "<none>"} " +
                    "sdh=${isSdhReferenceTrack(track)} cues=${track.cues.size} " +
                    "cueRatio=${fmt(referenceCueRatio(track, target))} " +
                    "suitability=${fmt(referenceSuitabilityScore(track, target))}"
            }
        }

        val attempts = ArrayList<TimelineRetimeMatch>(representatives.size)
        for (track in representatives) {
            val timeline = buildTimelineRetimeResult(track, target) ?: continue
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

            if (timeline.confident && timeline.alignmentSource == "delay-only") {
                AutoSyncDebugLog.section { "$label RESULT" }
                AutoSyncDebugLog.info {
                    "delay-only accepted url=$url reference=${track.key} " +
                        "scale=1.000000 offset=${"%.1f".format(timeline.alignmentInterceptMs)}ms"
                }
                return@withContext CandidateEvaluation(best = match, attempts = attempts)
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

'''
sync = sync[:start] + new_find + sync[end:]

# Keep even tiny selected subtitles so alternatives can be tried.
sync = sync.replace("    private const val MIN_SELECTED_CUES = 4\n", "    private const val MIN_SELECTED_CUES = 1\n", 1)

old_sdh = '''        return label.contains("sdh") ||
            label.contains("hearing impaired") ||
            label.contains("hearing-impaired") ||
            label.contains("closed caption")
'''
if old_sdh in sync:
    sync = sync.replace(old_sdh, '''        return label.contains("sdh") ||
            label.contains("shd") ||
            label.contains("hoh") ||
            label.contains("hearing impaired") ||
            label.contains("hearing-impaired") ||
            label.contains("closed caption")
''', 1)

if "private data class CandidateEvaluation(" not in sync:
    marker = '''    private data class TimelineRetimeMatch(
        val track: ReferenceTrack,
        val timeline: AutoSyncTimelineRetimeResult,
    )
'''
    if marker not in sync:
        raise SystemExit("Could not find TimelineRetimeMatch.")
    sync = sync.replace(marker, '''    private data class CandidateEvaluation(
        val best: TimelineRetimeMatch?,
        val attempts: List<TimelineRetimeMatch>,
    )

''' + marker, 1)

if "internal data class AutoSyncResolvedTimeline(" not in sync:
    marker = "\ninternal data class ReferenceTrack(\n"
    if marker not in sync:
        raise SystemExit("Could not find ReferenceTrack boundary.")
    sync = sync.replace(marker, '''
internal data class AutoSyncResolvedTimeline(
    val subtitleUrl: String,
    val subtitleHeaders: Map<String, String>,
    val timeline: AutoSyncTimelineRetimeResult,
)

internal data class ReferenceTrack(
''', 1)

# 5) Android player candidate state + seamless sidecar switch.
if "var autoSyncSubtitleCandidates by remember" not in engine_android:
    marker = "    var automaticSubtitleSyncJob by remember(playerSourceKey) { mutableStateOf<Job?>(null) }\n"
    if marker not in engine_android:
        raise SystemExit("Could not find automaticSubtitleSyncJob state.")
    engine_android = engine_android.replace(marker, marker + '''    var autoSyncSubtitleCandidates by remember(playerSourceKey) {
        mutableStateOf<List<AutoSyncSubtitleCandidate>>(emptyList())
    }
''', 1)

if "override fun setAutoSyncSubtitleCandidates(" not in engine_android:
    marker = "                override fun setSubtitleUri(url: String) {\n"
    if marker not in engine_android:
        raise SystemExit("Could not find Android setSubtitleUri override.")
    engine_android = engine_android.replace(marker, '''                override fun setAutoSyncSubtitleCandidates(
                    candidates: List<AutoSyncSubtitleCandidate>,
                ) {
                    autoSyncSubtitleCandidates = candidates.distinctBy { it.url }
                }

''' + marker, 1)

start = engine_android.find("                private fun startSubtitleAutoSync(")
end = engine_android.find("                override fun setSubtitleUriWithAutoSync", start)
if start < 0 or end < 0:
    raise SystemExit("Could not isolate Android startSubtitleAutoSync().")

new_start = r'''                private fun startSubtitleAutoSync(
                    url: String,
                    attachSubtitleOnReject: Boolean,
                ) {
                    automaticSubtitleSyncJob?.cancel()
                    val subtitleHeaders =
                        externalSubtitles.firstOrNull { it.url == url }?.headers.orEmpty()

                    if (!sidecarController.canAttachAddonSubtitleViaSidecar(url, useLibass)) {
                        if (attachSubtitleOnReject) setSubtitleUri(url)
                        Toast.makeText(
                            context,
                            "Auto Sync V2: seamless retiming is unavailable for this subtitle renderer",
                            Toast.LENGTH_LONG,
                        ).show()
                        return
                    }

                    val attached = sidecarController.startSidecarAddonSubtitle(
                        url = url,
                        headers = subtitleHeaders,
                        useLibass = useLibass,
                    )
                    if (!attached) {
                        if (attachSubtitleOnReject) setSubtitleUri(url)
                        Toast.makeText(
                            context,
                            "Auto Sync V2: subtitle could not be attached without reloading playback",
                            Toast.LENGTH_LONG,
                        ).show()
                        return
                    }

                    selectedExternalSubtitleMimeType = PlayerSubtitleUtils.mimeTypeFromUrl(url)
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()

                    automaticSubtitleSyncJob = coroutineScope.launch {
                        Toast.makeText(
                            context,
                            "Auto Sync V2: building embedded timeline…",
                            Toast.LENGTH_SHORT,
                        ).show()

                        val resolved = AutomaticSubtitleSync.findTimelineRetime(
                            sourceKey = sourceUrl,
                            sourceHeaders = sanitizedSourceHeaders,
                            selectedSubtitleUrl = url,
                            selectedSubtitleHeaders = subtitleHeaders,
                            preferredLanguage = playerSettings.preferredSubtitleLanguage,
                            alternativeSubtitles = autoSyncSubtitleCandidates,
                            onReferenceReady = {
                                Toast.makeText(
                                    context,
                                    "Auto Sync V2: matching timelines…",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )

                        if (resolved == null) {
                            val copied = if (AutoSyncDebugLog.ENABLED) {
                                AutoSyncDebugLog.finishAndCopy(
                                    context = context,
                                    decision = "REJECT V2 - original sidecar timing kept",
                                )
                            } else false
                            Toast.makeText(
                                context,
                                if (copied) {
                                    "Auto Sync V2: no reliable match — original subtitle kept • debug log copied"
                                } else {
                                    "Auto Sync V2: no reliable match — original subtitle kept"
                                },
                                Toast.LENGTH_LONG,
                            ).show()
                            return@launch
                        }

                        val chosenUrl = resolved.subtitleUrl
                        val timeline = resolved.timeline

                        if (chosenUrl != url) {
                            if (!sidecarController.canAttachAddonSubtitleViaSidecar(chosenUrl, useLibass)) {
                                Toast.makeText(
                                    context,
                                    "Auto Sync V2: better subtitle found but cannot attach it seamlessly",
                                    Toast.LENGTH_LONG,
                                ).show()
                                return@launch
                            }
                            val switched = sidecarController.startSidecarAddonSubtitle(
                                url = chosenUrl,
                                headers = resolved.subtitleHeaders,
                                useLibass = useLibass,
                            )
                            if (!switched) {
                                Toast.makeText(
                                    context,
                                    "Auto Sync V2: better subtitle found but sidecar switch failed",
                                    Toast.LENGTH_LONG,
                                ).show()
                                return@launch
                            }
                            selectedExternalSubtitleMimeType = PlayerSubtitleUtils.mimeTypeFromUrl(chosenUrl)
                        }

                        val applied = sidecarController.applyAutoSyncTimeline(chosenUrl, timeline)
                        if (!applied) {
                            if (AutoSyncDebugLog.ENABLED) {
                                AutoSyncDebugLog.finishAndCopy(
                                    context = context,
                                    decision = "REJECT V2 - sidecar changed or was unavailable before apply",
                                )
                            }
                            Toast.makeText(
                                context,
                                "Auto Sync V2: match found but subtitle changed — original timing kept",
                                Toast.LENGTH_LONG,
                            ).show()
                            return@launch
                        }

                        subtitleDelayMs = 0
                        autoSyncAppliedListener?.invoke(chosenUrl, 0)

                        AutoSyncDebugLog.info {
                            "AUTO APPLY V2 sidecar=true bufferPreserved=true " +
                                "externalChanged=${chosenUrl != url} groups=${timeline.groups.size} " +
                                "alignment=${timeline.alignmentSource} " +
                                "targetCoverage=${"%.4f".format(timeline.targetCoverage)} " +
                                "referenceCoverage=${"%.4f".format(timeline.referenceCoverage)} finalDelay=0ms"
                        }
                        if (AutoSyncDebugLog.ENABLED) {
                            AutoSyncDebugLog.finishAndCopy(
                                context = context,
                                decision =
                                    "APPLIED V2 sidecar timeline bufferPreserved=true " +
                                        "externalChanged=${chosenUrl != url} url=$chosenUrl " +
                                        "alignment=${timeline.alignmentSource}",
                            )
                        }
                        Toast.makeText(
                            context,
                            if (chosenUrl == url) {
                                "Auto Sync V2: seamless match applied"
                            } else {
                                "Auto Sync V2: switched to a better subtitle and synchronized it"
                            },
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }

'''
engine_android = engine_android[:start] + new_start + engine_android[end:]

# 6) Focused test that full V2 still accepts a six-cue supplied alignment.
if "fun fullV2StillRunsWithSixCues()" not in tests:
    marker = "    @Test\n    fun activityAlignmentFindsCommonFpsDrift() {\n"
    if marker not in tests:
        raise SystemExit("Could not find test insertion marker.")
    tests = tests.replace(marker, '''    @Test
    fun fullV2StillRunsWithSixCues() {
        val reference = irregularTimeline(6)
        val target = shift(reference, -2_200L)
        val result = assertNotNull(
            AutoSyncTimelineRetimer.retime(
                reference = reference,
                target = target,
                coarseScale = 1.0,
                coarseInterceptMs = 2_200.0,
                discoverAlignment = false,
            ),
        )
        assertTrue(result.confident)
        assertEquals(1.0, result.targetCoverage)
    }

''' + marker, 1)

for text, needle, label in [
    (retimer, "DELAY_ONLY_SCALE_TOLERANCE", "scale-gated delay-only"),
    (retimer, "SMALL_SAMPLE_ACTIVITY_MIN_SCORE", "small-sample full V2"),
    (engine_common, "AutoSyncSubtitleCandidate", "candidate model"),
    (runtime_ui, "setAutoSyncSubtitleCandidates", "runtime candidate feed"),
    (sync, "MAX_ALTERNATIVE_EXTERNAL_SUBTITLES", "alternative external search"),
    (sync, "evaluateExternalCandidate", "candidate evaluator"),
    (sync, "referenceSuitabilityScore", "embedded ranking"),
    (engine_android, "externalChanged=", "seamless candidate switch"),
]:
    if needle not in text:
        raise SystemExit(f"Missing {label}: {needle}")

retimer_path.write_text(retimer)
sync_path.write_text(sync)
engine_common_path.write_text(engine_common)
engine_android_path.write_text(engine_android)
runtime_ui_path.write_text(runtime_ui)
test_path.write_text(tests)

subprocess.run(["git", "diff", "--check"], check=True)

print("External-candidate AutoSync V2 patch applied.")
subprocess.run(["git", "diff", "--stat"], check=True)
print()
print("Run focused tests:")
print('./gradlew :composeApp:testAndroidHostTest -Pnuvio.android.distribution=full --tests "com.nuvio.app.features.autosync.AutoSyncTimelineRetimeTest" --stacktrace')
