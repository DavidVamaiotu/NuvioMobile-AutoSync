package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutoSyncTimelineRetimeTest {
    @Test
    fun constantOffsetBecomesEmbeddedTimeline() {
        val reference = regularTimeline(120)
        val target = reference.map { cue ->
            cue.copy(
                startTimeMs = cue.startTimeMs - 2_500L,
                endTimeMs = cue.endTimeMs - 2_500L,
            )
        }

        val result = assertNotNull(
            AutoSyncTimelineRetimer.retime(
                reference = reference,
                target = target,
                coarseScale = 1.0,
                coarseInterceptMs = 2_500.0,
            ),
        )

        assertTrue(result.confident)
        assertEquals(1.0, result.targetCoverage)
        result.cues.forEachIndexed { index, cue ->
            assertEquals(reference[index].startTimeMs, cue.startTimeMs)
            assertEquals(reference[index].endTimeMs, cue.endTimeMs)
        }
    }

    @Test
    fun fpsDriftIsRemovedByRetiming() {
        val reference = regularTimeline(160)
        val scale = 25.0 / 23.976
        val target = reference.map { cue ->
            SubtitleSyncCue(
                startTimeMs = (cue.startTimeMs / scale).toLong(),
                endTimeMs = (cue.endTimeMs / scale).toLong(),
                text = cue.text,
            )
        }

        val result = assertNotNull(
            AutoSyncTimelineRetimer.retime(
                reference = reference,
                target = target,
                coarseScale = scale,
                coarseInterceptMs = 0.0,
            ),
        )

        assertTrue(result.confident)
        assertTrue(result.targetCoverage > 0.98)
        assertTrue(
            result.cues.zip(reference).all { (retimed, embedded) ->
                abs(retimed.startTimeMs - embedded.startTimeMs) <= 2L
            },
        )
    }

    @Test
    fun splitCuesAreGroupedWithoutTextMatching() {
        val reference = regularTimeline(100)
        val target = buildList {
            reference.forEachIndexed { index, cue ->
                if (index % 10 == 0) {
                    val middle = (cue.startTimeMs + cue.endTimeMs) / 2L
                    add(SubtitleSyncCue(cue.startTimeMs, middle, "part a"))
                    add(SubtitleSyncCue(middle + 1L, cue.endTimeMs, "part b"))
                } else {
                    add(cue.copy(text = "translated $index"))
                }
            }
        }

        val result = assertNotNull(
            AutoSyncTimelineRetimer.retime(reference, target, 1.0, 0.0),
        )

        assertTrue(result.confident)
        assertEquals(10, result.oneToTwoGroups)
        assertEquals(1.0, result.targetCoverage)
    }

    @Test
    fun localTimelineJumpCanRecover() {
        val reference = regularTimeline(140)
        val target = reference.mapIndexed { index, cue ->
            if (index < 70) {
                cue
            } else {
                cue.copy(
                    startTimeMs = cue.startTimeMs - 5_000L,
                    endTimeMs = cue.endTimeMs - 5_000L,
                )
            }
        }

        val result = assertNotNull(
            AutoSyncTimelineRetimer.retime(reference, target, 1.0, 0.0),
        )

        assertTrue(result.confident)
        assertTrue(result.targetCoverage >= 0.95)
        assertTrue(result.longestTargetSkipRun <= 12)
    }

    @Test
    fun ambiguousCoarseSeedUsesIndependentFullFilmAnchors() {
        val reference = irregularTimeline(180)
        val target = reference.map { cue ->
            cue.copy(
                startTimeMs = cue.startTimeMs - 2_500L,
                endTimeMs = cue.endTimeMs - 2_500L,
            )
        }

        val result = assertNotNull(
            AutoSyncTimelineRetimer.retime(
                reference = reference,
                target = target,
                coarseScale = 1.0,
                coarseInterceptMs = 52_500.0,
                requireIndependentAnchors = true,
            ),
        )

        assertTrue(result.confident)
        assertEquals("independent-chain", result.seedSource)
        assertEquals(3, result.anchorSegmentsPassed)
        assertEquals(3, result.seedAnchorSegments)
        assertTrue(result.seedAnchorCount >= 10)
        assertTrue(result.seedAnchorSpanRatio >= 0.76)
        assertTrue(result.seedCandidatesEvaluated in 1..3)
        assertTrue(abs(result.seedInterceptMs - 2_500.0) <= 750.0)
        assertTrue(result.targetCoverage > 0.95)
    }

    @Test
    fun independentChainRejectsTwoSegmentCoincidence() {
        val reference = irregularTimeline(180)
        val target = reference.mapIndexed { index, cue ->
            val shiftMs = if (index < 120) -2_500L else 57_500L
            cue.copy(
                startTimeMs = cue.startTimeMs + shiftMs,
                endTimeMs = cue.endTimeMs + shiftMs,
            )
        }

        val result = AutoSyncTimelineRetimer.retime(
            reference = reference,
            target = target,
            coarseScale = 1.0,
            coarseInterceptMs = 52_500.0,
            requireIndependentAnchors = true,
        )

        assertTrue(result == null || !result.confident)
    }

    @Test
    fun ambiguousUnrelatedTimelineIsNotAccepted() {
        val reference = regularTimeline(180)
        val target = (0 until 170).map { index ->
            val start = index * 4_100L + (index % 7) * 430L
            SubtitleSyncCue(
                startTimeMs = start,
                endTimeMs = start + 700L + (index % 5) * 310L,
                text = "unrelated $index",
            )
        }

        val result = AutoSyncTimelineRetimer.retime(
            reference = reference,
            target = target,
            coarseScale = 1.0,
            coarseInterceptMs = 52_500.0,
            requireIndependentAnchors = true,
        )

        assertTrue(result == null || !result.confident)
    }

    private fun irregularTimeline(count: Int): List<SubtitleSyncCue> {
        var start = 30_000L
        return (0 until count).map { index ->
            if (index > 0) {
                start += 1_400L + ((index * 977L) % 4_300L)
            }
            SubtitleSyncCue(
                startTimeMs = start,
                endTimeMs = start + 900L + ((index * 313L) % 1_700L),
                text = "irregular $index",
            )
        }
    }

    private fun regularTimeline(count: Int): List<SubtitleSyncCue> =
        (0 until count).map { index ->
            val start = index * 3_000L
            SubtitleSyncCue(
                startTimeMs = start,
                endTimeMs = start + 1_500L,
                text = "cue $index",
            )
        }
}
