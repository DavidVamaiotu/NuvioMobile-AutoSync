package com.nuvio.app.features.autosync.audio

import androidx.media3.extractor.text.CuesWithTiming
import com.google.common.collect.ImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioSyncRetimeTest {
    private fun cue(startMs: Long, endMs: Long) =
        CuesWithTiming(ImmutableList.of(), startMs * 1_000L, (endMs - startMs) * 1_000L)

    private fun List<CuesWithTiming>.timesMs() = map { it.startTimeUs / 1_000L to it.endTimeUs / 1_000L }

    @Test
    fun constantShiftMovesEveryCue() {
        val model = SubtitleSyncModel(listOf(SubtitleSyncSegment(fromMediaMs = 0L, scale = 1.0, shiftMs = 2_000.0)))

        val retimed = retimeCues(listOf(cue(1_000, 2_000), cue(60_000, 61_500)), model)

        assertEquals(listOf(3_000L to 4_000L, 62_000L to 63_500L), retimed.timesMs())
    }

    @Test
    fun matchesTheDelayTheModelDescribes() {
        val model = SubtitleSyncModel(listOf(SubtitleSyncSegment(fromMediaMs = 0L, scale = 25.0 / 23.976, shiftMs = -800.0)))

        val retimed = retimeCues(listOf(cue(600_000, 602_000)), model).single()

        // The renderer shows subtitle time (position - delay) at a position, so the cue must land
        // where the model's delay maps back to its original time.
        val positionUs = retimed.startTimeUs
        assertEquals(600_000_000L, positionUs - model.delayUsAt(positionUs), absoluteTolerance = 1_000L)
    }

    @Test
    fun eachCueFollowsThePieceItLandsInAndStaysSorted() {
        // A scene added at 10 minutes: later subtitles need 5 more seconds.
        val model = SubtitleSyncModel(
            listOf(
                SubtitleSyncSegment(fromMediaMs = 0L, scale = 1.0, shiftMs = 1_000.0),
                SubtitleSyncSegment(fromMediaMs = 600_000L, scale = 1.0, shiftMs = 6_000.0),
            ),
        )

        val retimed = retimeCues(listOf(cue(700_000, 701_000), cue(100_000, 101_000)), model)

        assertEquals(listOf(101_000L to 102_000L, 706_000L to 707_000L), retimed.timesMs())
    }

    private fun assertEquals(expected: Long, actual: Long, absoluteTolerance: Long) {
        kotlin.test.assertTrue(kotlin.math.abs(expected - actual) <= absoluteTolerance, "expected $expected, was $actual")
    }
}
