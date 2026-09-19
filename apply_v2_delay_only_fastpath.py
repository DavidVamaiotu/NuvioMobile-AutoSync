#!/usr/bin/env python3
from pathlib import Path
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
test_path = ROOT / "composeApp/src/commonTest/kotlin/com/nuvio/app/features/autosync/AutoSyncTimelineRetimeTest.kt"

for p in (retimer_path, sync_path, test_path):
    if not p.exists():
        raise SystemExit(f"Missing expected file: {p}")

retimer = retimer_path.read_text()
sync = sync_path.read_text()
tests = test_path.read_text()

old_constants = """    private const val ACTIVITY_SCALE_DEDUP = 0.00035

    // Activity correlation only finds the global corridor. The existing cue/group DP remains
"""
new_constants = """    private const val ACTIVITY_SCALE_DEDUP = 0.00035

    // Cheap delay-only fast path. It always APPLIES scale=1.0; the segment drift tolerance
    // merely allows near-1.0 timelines to qualify when one constant delay remains visually valid.
    private const val DELAY_ONLY_MIN_CUES = 4
    private const val DELAY_ONLY_MIN_SCORE = 0.78
    private const val DELAY_ONLY_MIN_MARGIN = 0.02
    private const val DELAY_ONLY_MIN_SEGMENT_SCORE = 0.68
    private const val DELAY_ONLY_SEGMENT_SEARCH_RADIUS_MS = 1_000L
    private const val DELAY_ONLY_MAX_SEGMENT_OFFSET_DELTA_MS = 500L
    private const val DELAY_ONLY_DISTINCT_OFFSET_MS = 3_000L

    // Activity correlation only finds the global corridor. The existing cue/group DP remains
"""
if old_constants not in retimer:
    raise SystemExit("Could not find activity constants insertion point.")
retimer = retimer.replace(old_constants, new_constants, 1)

old_discover = """        val alignment = discoverActivityAlignment(reference, target) ?: return null
        val result = retimeWithSeed(
"""
new_discover = """        findDelayOnlyAlignment(reference, target)?.let { delayOnly ->
            return buildDelayOnlyTimeline(target, delayOnly)
        }

        val alignment = discoverActivityAlignment(reference, target) ?: return null
        val result = retimeWithSeed(
"""
if old_discover not in retimer:
    raise SystemExit("Could not find discoverAlignment path.")
retimer = retimer.replace(old_discover, new_discover, 1)

marker = """    private fun discoverActivityAlignment(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
    ): ActivityAlignment? {
"""
if marker not in retimer:
    raise SystemExit("Could not find discoverActivityAlignment().")

delay_functions = """    internal fun findDelayOnlyAlignment(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
    ): AutoSyncDelayOnlyAlignment? {
        if (reference.size < DELAY_ONLY_MIN_CUES || target.size < DELAY_ONLY_MIN_CUES) return null

        val referenceCoarse = buildActivityTimeline(reference, 1.0, ACTIVITY_COARSE_BIN_MS)
            ?: return null
        val targetCoarse = buildActivityTimeline(target, 1.0, ACTIVITY_COARSE_BIN_MS)
            ?: return null
        val maxOffsetBins = (ACTIVITY_MAX_OFFSET_MS / ACTIVITY_COARSE_BIN_MS).toInt()

        var coarseBest: ActivityCandidate? = null
        val coarseCandidates = ArrayList<ActivityCandidate>(maxOffsetBins * 2 + 1)
        for (offsetBins in -maxOffsetBins..maxOffsetBins) {
            val score = scoreActivityOffset(referenceCoarse, targetCoarse, offsetBins) ?: continue
            val candidate = ActivityCandidate(
                scale = 1.0,
                interceptMs = offsetBins * ACTIVITY_COARSE_BIN_MS,
                score = score,
            )
            coarseCandidates += candidate
            val current = coarseBest
            if (current == null || candidate.score > current.score) coarseBest = candidate
        }

        val coarse = coarseBest ?: return null
        val secondDistinct = coarseCandidates.asSequence()
            .filter { candidate ->
                abs(candidate.interceptMs - coarse.interceptMs) >= DELAY_ONLY_DISTINCT_OFFSET_MS
            }
            .maxByOrNull { it.score }
        val margin = coarse.score - (secondDistinct?.score ?: 0.0)
        if (margin < DELAY_ONLY_MIN_MARGIN) return null

        val referenceFine = buildActivityTimeline(reference, 1.0, ACTIVITY_FINE_BIN_MS)
            ?: return null
        val targetFine = buildActivityTimeline(target, 1.0, ACTIVITY_FINE_BIN_MS)
            ?: return null

        var fineBest: ActivityCandidate? = null
        var offsetMs = coarse.interceptMs - ACTIVITY_FINE_RADIUS_MS
        while (offsetMs <= coarse.interceptMs + ACTIVITY_FINE_RADIUS_MS) {
            val offsetBins = (offsetMs.toDouble() / ACTIVITY_FINE_BIN_MS.toDouble()).roundToInt()
            val score = scoreActivityOffset(referenceFine, targetFine, offsetBins)
            if (score != null) {
                val candidate = ActivityCandidate(
                    scale = 1.0,
                    interceptMs = offsetBins * ACTIVITY_FINE_BIN_MS,
                    score = score,
                )
                val current = fineBest
                if (current == null || candidate.score > current.score) fineBest = candidate
            }
            offsetMs += ACTIVITY_FINE_BIN_MS
        }

        val best = fineBest ?: coarse
        if (best.score < DELAY_ONLY_MIN_SCORE) return null

        val globalOffsetBins =
            (best.interceptMs.toDouble() / ACTIVITY_FINE_BIN_MS.toDouble()).roundToInt()
        val localRadiusBins =
            (DELAY_ONLY_SEGMENT_SEARCH_RADIUS_MS / ACTIVITY_FINE_BIN_MS).toInt()
        val maxDeltaBins =
            (DELAY_ONLY_MAX_SEGMENT_OFFSET_DELTA_MS / ACTIVITY_FINE_BIN_MS).toInt()

        var availableSegments = 0
        var passedSegments = 0
        for (segment in 0..2) {
            var segmentBestScore = Double.NEGATIVE_INFINITY
            var segmentBestOffsetBins = globalOffsetBins
            var hasScore = false

            for (delta in -localRadiusBins..localRadiusBins) {
                val candidateOffsetBins = globalOffsetBins + delta
                val score = scoreActivityOffsetSegment(
                    reference = referenceFine,
                    target = targetFine,
                    offsetBins = candidateOffsetBins,
                    segment = segment,
                ) ?: continue
                hasScore = true
                if (score > segmentBestScore) {
                    segmentBestScore = score
                    segmentBestOffsetBins = candidateOffsetBins
                }
            }

            if (!hasScore) continue
            availableSegments++
            if (
                segmentBestScore >= DELAY_ONLY_MIN_SEGMENT_SCORE &&
                abs(segmentBestOffsetBins - globalOffsetBins) <= maxDeltaBins
            ) {
                passedSegments++
            }
        }

        val requiredSegments = if (target.size < MIN_CUES) 2 else 3
        if (availableSegments < requiredSegments || passedSegments < requiredSegments) return null

        return AutoSyncDelayOnlyAlignment(
            offsetMs = best.interceptMs.toDouble(),
            score = best.score,
            margin = margin,
            segmentsPassed = passedSegments,
        )
    }

    internal fun buildDelayOnlyTimeline(
        target: List<SubtitleSyncCue>,
        alignment: AutoSyncDelayOnlyAlignment,
    ): AutoSyncTimelineRetimeResult {
        val offsetMs = alignment.offsetMs.roundToLong()
        val retimed = target.map { cue ->
            val start = (cue.startTimeMs + offsetMs).coerceAtLeast(0L)
            val end = (cue.endTimeMs + offsetMs).coerceAtLeast(start + 1L)
            AutoSyncRetimedCue(
                originalStartTimeMs = cue.startTimeMs,
                originalEndTimeMs = cue.endTimeMs,
                startTimeMs = start,
                endTimeMs = end,
            )
        }

        return AutoSyncTimelineRetimeResult(
            cues = retimed,
            groups = emptyList(),
            targetCoverage = 1.0,
            referenceCoverage = 0.0,
            skippedTargetCues = 0,
            skippedReferenceCues = 0,
            longestTargetSkipRun = 0,
            averageGroupCost = 0.0,
            oneToOneGroups = 0,
            oneToTwoGroups = 0,
            twoToOneGroups = 0,
            oneToThreeGroups = 0,
            threeToOneGroups = 0,
            twoToTwoGroups = 0,
            confident = true,
            alignmentSource = "delay-only",
            alignmentScale = 1.0,
            alignmentInterceptMs = alignment.offsetMs,
            activityScore = alignment.score,
            activityMargin = alignment.margin,
            coverageSegmentsPassed = alignment.segmentsPassed,
            simpleGroupRatio = 1.0,
        )
    }

    private fun scoreActivityOffsetSegment(
        reference: ActivityTimeline,
        target: ActivityTimeline,
        offsetBins: Int,
        segment: Int,
    ): Double? {
        if (segment !in 0..2) return null
        val activeSpan = target.lastActive - target.firstActive + 1
        if (activeSpan <= 0) return null

        val segmentStart = target.firstActive + activeSpan * segment / 3
        val segmentEnd = if (segment == 2) {
            target.lastActive
        } else {
            target.firstActive + activeSpan * (segment + 1) / 3 - 1
        }
        if (segmentEnd < segmentStart) return null

        var visibleTarget = 0
        var intersection = 0
        for (targetIndex in target.activeIndexes) {
            if (targetIndex < segmentStart) continue
            if (targetIndex > segmentEnd) break
            visibleTarget++
            val shiftedIndex = targetIndex + offsetBins
            if (shiftedIndex in reference.bins.indices && reference.bins[shiftedIndex]) {
                intersection++
            }
        }
        if (visibleTarget <= 0) return null

        val referenceWindowStart = max(0, segmentStart + offsetBins)
        val referenceWindowEnd = min(reference.bins.lastIndex, segmentEnd + offsetBins)
        if (referenceWindowEnd < referenceWindowStart) return null
        val referenceInWindow =
            reference.prefix[referenceWindowEnd + 1] - reference.prefix[referenceWindowStart]
        if (referenceInWindow <= 0) return null

        val precision = intersection.toDouble() / visibleTarget.toDouble()
        val recall = intersection.toDouble() / referenceInWindow.toDouble()
        return precision * 0.72 + recall * 0.28
    }

"""
retimer = retimer.replace(marker, delay_functions + marker, 1)

data_marker = "internal data class AutoSyncTimelineRetimeResult(\n"
if data_marker not in retimer:
    raise SystemExit("Could not find AutoSyncTimelineRetimeResult.")
delay_data = """internal data class AutoSyncDelayOnlyAlignment(
    val offsetMs: Double,
    val score: Double,
    val margin: Double,
    val segmentsPassed: Int,
)

"""
retimer = retimer.replace(data_marker, delay_data + data_marker, 1)

if "private const val MIN_SELECTED_CUES = 8" not in sync:
    raise SystemExit("Expected MIN_SELECTED_CUES=8 was not found.")
sync = sync.replace(
    "private const val MIN_SELECTED_CUES = 8",
    "private const val MIN_SELECTED_CUES = 4",
    1,
)

sync = sync.replace(
    """ * The legacy V1 global-delay matcher is intentionally absent.
 * V2 loads the selected external subtitle, obtains complete embedded timing,
 * discovers the whole-film transform, runs cue/group DP and returns a retimed
 * timeline for Media3's native subtitle parser.
""",
    """ * The legacy V1 matcher is intentionally absent.
 * V2 first performs a cheap fixed-scale delay-only check. If one constant offset
 * is strong and stable across the movie, it returns a uniform shift immediately.
 * Otherwise it discovers the whole-film affine transform and runs cue/group DP.
""",
    1,
)

sync = sync.replace(
    "REJECT V2 did not reach direct-timeline confidence; no V1 fallback exists",
    "REJECT V2 did not reach delay-only or direct-timeline confidence",
)

old_final = """            AutoSyncDebugLog.section { "FINAL RECOMMENDATION" }
            AutoSyncDebugLog.info {
                "V2 direct timeline accepted selected subtitle; corrected timeline is ready for sidecar apply"
            }
            timeline
"""
new_final = """            AutoSyncDebugLog.section { "FINAL RECOMMENDATION" }
            AutoSyncDebugLog.info {
                if (timeline.alignmentSource == "delay-only") {
                    "V2 delay-only fast path accepted selected subtitle; " +
                        "scale=1.000000 offset=${"%.1f".format(timeline.alignmentInterceptMs)}ms"
                } else {
                    "V2 direct timeline accepted selected subtitle; corrected timeline is ready for sidecar apply"
                }
            }
            timeline
"""
if old_final not in sync:
    raise SystemExit("Could not find final recommendation block.")
sync = sync.replace(old_final, new_final, 1)

tests = tests.replace(
    'assertEquals("activity-correlation", result.alignmentSource)',
    'assertEquals("delay-only", result.alignmentSource)',
    1,
)

test_marker = """    @Test
    fun activityAlignmentFindsCommonFpsDrift() {
"""
if test_marker not in tests:
    raise SystemExit("Could not find test insertion point.")

new_tests = """    @Test
    fun delayOnlyFastPathWorksWithTooFewCuesForDp() {
        val reference = irregularTimeline(6)
        val target = shift(reference, -4_200L)
        val result = assertNotNull(
            AutoSyncTimelineRetimer.retime(reference, target, 1.0, 0.0, true),
        )
        assertTrue(result.confident)
        assertEquals("delay-only", result.alignmentSource)
        assertEquals(1.0, result.alignmentScale)
        assertTrue(abs(result.alignmentInterceptMs - 4_200.0) <= 500.0)
        assertTrue(result.coverageSegmentsPassed >= 2)
    }

    @Test
    fun delayOnlyFastPathRejectsRealProgressiveDrift() {
        val reference = irregularTimeline(260)
        val scale = 25.0 / 23.976
        val target = reference.map { cue ->
            SubtitleSyncCue(
                (cue.startTimeMs / scale).toLong(),
                (cue.endTimeMs / scale).toLong(),
                cue.text,
            )
        }
        val delayOnly = AutoSyncTimelineRetimer.findDelayOnlyAlignment(reference, target)
        assertTrue(delayOnly == null)

        val result = assertNotNull(
            AutoSyncTimelineRetimer.retime(reference, target, 1.0, 0.0, true),
        )
        assertTrue(result.confident)
        assertEquals("activity-correlation", result.alignmentSource)
    }

"""
tests = tests.replace(test_marker, new_tests + test_marker, 1)

if 'alignmentSource = "delay-only"' not in retimer:
    raise SystemExit("Delay-only result source missing.")
if "findDelayOnlyAlignment(reference, target)" not in retimer:
    raise SystemExit("Delay-only fast path is not wired into V2.")
if "private const val MIN_SELECTED_CUES = 4" not in sync:
    raise SystemExit("Small subtitle files still cannot reach delay-only check.")

retimer_path.write_text(retimer)
sync_path.write_text(sync)
test_path.write_text(tests)

subprocess.run(["git", "diff", "--check"], check=True)

print("V2 delay-only fast-path patch applied.")
print("Only algorithm/orchestration tests were changed; player/sidecar files are untouched.")
print()
subprocess.run(["git", "diff", "--stat"], check=True)
print()
print("Run:")
print("./gradlew :composeApp:testAndroidHostTest -Pnuvio.android.distribution=full "
      '--tests "com.nuvio.app.features.autosync.AutoSyncTimelineRetimeTest" --stacktrace')
