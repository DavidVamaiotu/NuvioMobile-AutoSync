package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutoSyncTimelineRetimeTest {
    @Test
    fun providedConstantOffsetBecomesEmbeddedTimeline() {
        val reference = irregularTimeline(120)
        val target = shift(reference, -2_500L)
        val result = assertNotNull(AutoSyncTimelineRetimer.retime(reference, target, 1.0, 2_500.0))
        assertTrue(result.confident)
        assertEquals("provided", result.alignmentSource)
        assertEquals(1.0, result.targetCoverage)
    }

    @Test
    fun activityAlignmentFindsConstantOffsetWithoutV1Seed() {
        val reference = irregularTimeline(220)
        val target = shift(reference, -12_750L)
        val result = assertNotNull(AutoSyncTimelineRetimer.retime(reference, target, 1.0, -52_500.0, true))
        assertTrue(result.confident)
        assertEquals("delay-only-validated", result.alignmentSource)
        assertTrue(abs(result.alignmentInterceptMs - 12_750.0) <= 500.0)
        assertTrue(abs(result.alignmentScale - 1.0) <= 0.0015)
        assertTrue(result.activityScore >= 0.55)
        assertTrue(result.activityMargin >= 0.02)
        assertEquals(3, result.coverageSegmentsPassed)
        assertTrue(result.groups.isNotEmpty())
        assertTrue(result.referenceCoverage > 0.0)
    }

    @Test
    fun delayOnlyFastPathWorksWithTooFewCuesForDp() {
        val reference = irregularTimeline(6)
        val target = shift(reference, -4_200L)
        val result = assertNotNull(
            AutoSyncTimelineRetimer.retime(reference, target, 1.0, 0.0, true),
        )
        assertTrue(result.confident)
        assertEquals("delay-only-validated", result.alignmentSource)
        assertEquals(1.0, result.alignmentScale)
        assertTrue(abs(result.alignmentInterceptMs - 4_200.0) <= 500.0)
        assertTrue(result.coverageSegmentsPassed >= 2)
        assertTrue(result.groups.isNotEmpty())
        assertTrue(result.referenceCoverage > 0.0)
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

        val relaxedDelayOnly = AutoSyncTimelineRetimer.findDelayOnlyAlignment(
            reference = reference,
            target = target,
            allowAmbiguousMargin = true,
        )
        assertTrue(relaxedDelayOnly == null)

        val result = assertNotNull(
            AutoSyncTimelineRetimer.retime(reference, target, 1.0, 0.0, true),
        )
        assertTrue(result.confident)
        assertEquals("activity-correlation", result.alignmentSource)
    }

    @Test
    fun relaxedDelayOnlyAcceptsDenseSdhLikeReferenceAtOneGlobalOffset() {
        val base = irregularTimeline(220)
        val reference = buildList {
            addAll(base)
            base.forEachIndexed { index, cue ->
                if (index % 2 == 0) {
                    val start = cue.endTimeMs + 250L
                    add(SubtitleSyncCue(start, start + 900L, "sdh extra $index"))
                }
            }
        }.sortedBy { it.startTimeMs }
        val target = shift(base, -900L)

        val result = assertNotNull(
            AutoSyncTimelineRetimer.findDelayOnlyAlignment(
                reference = reference,
                target = target,
                allowAmbiguousMargin = true,
            ),
        )
        assertTrue(abs(result.offsetMs - 900.0) <= 500.0)
        assertEquals(3, result.segmentsPassed)
    }

    @Test
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

    @Test
    fun activityAlignmentFindsCommonFpsDrift() {
        val reference = irregularTimeline(260)
        val scale = 25.0 / 23.976
        val target = reference.map { cue -> SubtitleSyncCue((cue.startTimeMs / scale).toLong(), (cue.endTimeMs / scale).toLong(), cue.text) }
        val result = assertNotNull(AutoSyncTimelineRetimer.retime(reference, target, 1.0, 0.0, true))
        assertTrue(result.confident)
        assertTrue(abs(result.alignmentScale - scale) <= 0.0015)
        assertTrue(abs(result.alignmentInterceptMs) <= 500.0)
    }

    @Test
    fun externalTimelineNormalizationSortsAndDeduplicatesOnlyExactDuplicates() {
        val normalized = AutoSyncTimelineRetimer.normalizeExternalTimeline(
            listOf(
                SubtitleSyncCue(5_000L, 6_000L, "later"),
                SubtitleSyncCue(1_000L, 2_000L, "first"),
                SubtitleSyncCue(1_000L, 2_000L, "first"),
                SubtitleSyncCue(1_000L, 2_000L, "different simultaneous text"),
            ),
        )

        assertEquals(3, normalized.size)
        assertEquals(listOf(1_000L, 1_000L, 5_000L), normalized.map { it.startTimeMs })
        assertEquals(
            listOf("first", "different simultaneous text", "later"),
            normalized.map { it.text },
        )
    }

    @Test
    fun estimatedReferenceEndsAreDownweightedWithoutChangingNormalScoring() {
        val target = (0 until 40).map { index ->
            val start = 10_000L + index * 10_000L
            SubtitleSyncCue(start, start + 900L, "target $index")
        }
        val reference = target.mapIndexed { index, cue ->
            SubtitleSyncCue(
                startTimeMs = cue.startTimeMs,
                endTimeMs = cue.startTimeMs + 4_800L,
                text = "reference $index",
            )
        }
        val estimatedStarts = reference.mapTo(hashSetOf()) { it.startTimeMs }

        val estimated = assertNotNull(
            AutoSyncTimelineRetimer.retime(
                reference = reference,
                target = target,
                coarseScale = 1.0,
                coarseInterceptMs = 0.0,
                referenceEstimatedEndStartsMs = estimatedStarts,
            ),
        )
        val explicit = assertNotNull(
            AutoSyncTimelineRetimer.retime(
                reference = reference,
                target = target,
                coarseScale = 1.0,
                coarseInterceptMs = 0.0,
            ),
        )

        assertTrue(estimated.averageGroupCost < explicit.averageGroupCost)
        assertTrue(estimated.confident)
    }

    @Test
    fun matchedGroupsPreserveExternalCueDurations() {
        val reference = irregularTimeline(80).map { cue ->
            cue.copy(endTimeMs = cue.startTimeMs + 4_800L)
        }
        val target = shift(
            reference.mapIndexed { index, cue ->
                cue.copy(
                    endTimeMs = cue.startTimeMs + 700L + (index % 5) * 180L,
                    text = "translated $index",
                )
            },
            -1_300L,
        )

        val originalDurations = target.map { it.endTimeMs - it.startTimeMs }
        val result = assertNotNull(
            AutoSyncTimelineRetimer.retime(
                reference = reference,
                target = target,
                coarseScale = 1.0,
                coarseInterceptMs = 1_300.0,
            ),
        )

        assertTrue(result.confident)
        assertEquals(
            originalDurations,
            result.cues.map { it.endTimeMs - it.startTimeMs },
        )
    }

    @Test
    fun retimingDoesNotCreateNewSequentialCueOverlaps() {
        val target = listOf(
            SubtitleSyncCue(10_000L, 13_000L, "a"),
            SubtitleSyncCue(13_100L, 15_500L, "b"),
            SubtitleSyncCue(16_000L, 18_500L, "c"),
            SubtitleSyncCue(19_000L, 21_500L, "d"),
            SubtitleSyncCue(22_000L, 24_500L, "e"),
            SubtitleSyncCue(25_000L, 27_500L, "f"),
        )
        val reference = listOf(
            SubtitleSyncCue(11_000L, 12_900L, "r0"),
            SubtitleSyncCue(13_500L, 14_900L, "r1"),
            SubtitleSyncCue(16_800L, 18_000L, "r2"),
            SubtitleSyncCue(19_800L, 21_000L, "r3"),
            SubtitleSyncCue(22_800L, 24_000L, "r4"),
            SubtitleSyncCue(25_800L, 27_000L, "r5"),
        )

        val result = assertNotNull(
            AutoSyncTimelineRetimer.retime(
                reference = reference,
                target = target,
                coarseScale = 1.0,
                coarseInterceptMs = 800.0,
            ),
        )

        for (index in 0 until result.cues.lastIndex) {
            if (target[index].endTimeMs <= target[index + 1].startTimeMs) {
                assertTrue(
                    result.cues[index].endTimeMs <= result.cues[index + 1].startTimeMs,
                    "AutoSync introduced overlap at cue $index",
                )
            }
        }
    }

    @Test
    fun heavilyMergedTranslationAcceptsExpectedThreeToOneGrouping() {
        val reference = irregularTimeline(180)
        val target = reference.chunked(3).mapIndexed { index, group ->
            SubtitleSyncCue(
                startTimeMs = group.first().startTimeMs,
                endTimeMs = group.last().endTimeMs,
                text = "merged translation $index",
            )
        }

        val result = assertNotNull(
            AutoSyncTimelineRetimer.retime(
                reference = reference,
                target = target,
                coarseScale = 1.0,
                coarseInterceptMs = 0.0,
                discoverAlignment = true,
            ),
        )

        assertTrue(result.confident)
        assertTrue(result.threeToOneGroups > 0)
        assertTrue(result.targetCoverage >= 0.90)
        assertTrue(result.referenceCoverage >= 0.80)
        assertTrue(result.simpleGroupRatio >= 0.55)
    }

    @Test
    fun splitCuesAreStillHandledByExistingDp() {
        val reference = irregularTimeline(100)
        val target = buildList {
            reference.forEachIndexed { index, cue ->
                if (index % 10 == 0) {
                    val middle = (cue.startTimeMs + cue.endTimeMs) / 2L
                    add(SubtitleSyncCue(cue.startTimeMs, middle, "part a"))
                    add(SubtitleSyncCue(middle + 1L, cue.endTimeMs, "part b"))
                } else add(cue.copy(text = "translated $index"))
            }
        }
        val result = assertNotNull(AutoSyncTimelineRetimer.retime(reference, target, 1.0, 0.0))
        assertTrue(result.confident)
        assertEquals(10, result.oneToTwoGroups)
    }

    @Test
    fun activityAlignmentToleratesMissingIntroAndOutro() {
        val reference = irregularTimeline(260)
        val target = shift(reference.subList(25, 235), -8_000L)
        val result = assertNotNull(AutoSyncTimelineRetimer.retime(reference, target, 1.0, 0.0, true))
        assertTrue(result.confident)
        assertTrue(abs(result.alignmentInterceptMs - 8_000.0) <= 500.0)
    }

    @Test
    fun activityAlignmentToleratesExtraReferenceSdhActivity() {
        val base = irregularTimeline(260)
        val reference = buildList {
            addAll(base)
            for (index in 8 until base.lastIndex step 13) {
                val start = base[index].endTimeMs + 250L
                add(SubtitleSyncCue(start, start + 900L, "sound effect $index"))
            }
        }.sortedBy { it.startTimeMs }
        val target = shift(base, -6_400L)
        val result = assertNotNull(AutoSyncTimelineRetimer.retime(reference, target, 1.0, 0.0, true))
        assertTrue(result.confident)
        assertTrue(abs(result.alignmentInterceptMs - 6_400.0) <= 600.0)
    }

    @Test
    fun activityAlignmentRejectsUnrelatedTimeline() {
        val reference = irregularTimeline(220)
        val target = (0 until 205).map { index ->
            val start = index * 4_100L + (index % 7) * 430L
            SubtitleSyncCue(start, start + 700L + (index % 5) * 310L, "unrelated $index")
        }
        val result = AutoSyncTimelineRetimer.retime(reference, target, 1.0, 52_500.0, true)
        assertTrue(result == null || !result.confident)
    }

    @Test
    fun activityAlignmentRejectsAmbiguousRepeatedCadence() {
        val reference = repeatedCadenceTimeline(260)
        val target = shift(reference, -12_750L)
        val result = AutoSyncTimelineRetimer.retime(reference, target, 1.0, 0.0, true)
        assertTrue(result == null || !result.confident)
    }

    @Test
    fun activityAlignmentRejectsMidFilmDiscontinuity() {
        val reference = irregularTimeline(240)
        val target = reference.mapIndexed { index, cue ->
            if (index < reference.size / 2) cue else cue.copy(startTimeMs = cue.startTimeMs - 5_000L, endTimeMs = cue.endTimeMs - 5_000L)
        }
        val result = AutoSyncTimelineRetimer.retime(reference, target, 1.0, 0.0, true)
        assertTrue(result == null || !result.confident)
    }

    private fun shift(cues: List<SubtitleSyncCue>, deltaMs: Long) = cues.map { cue ->
        cue.copy(startTimeMs = cue.startTimeMs + deltaMs, endTimeMs = cue.endTimeMs + deltaMs)
    }

    private fun irregularTimeline(count: Int): List<SubtitleSyncCue> {
        var start = 30_000L
        return (0 until count).map { index ->
            if (index > 0) start += 1_400L + ((index * 977L) % 4_300L)
            SubtitleSyncCue(start, start + 900L + ((index * 313L) % 1_700L), "irregular $index")
        }
    }

    private fun repeatedCadenceTimeline(count: Int): List<SubtitleSyncCue> {
        val cadence = longArrayOf(1_900L, 3_100L, 2_400L, 4_200L, 2_100L, 3_700L, 2_800L)
        var start = 30_000L
        return (0 until count).map { index ->
            if (index > 0) start += cadence[(index - 1) % cadence.size] + (index % 5) * 73L
            SubtitleSyncCue(start, start + 1_000L + (index % 4) * 190L, "cadence $index")
        }
    }
}
