package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutoSyncNoFitTrackerTest {
    @Test
    fun abandonsOnlyAfterThreeIndependentSourcesFoundNoFit() {
        val tracker = AutoSyncNoFitTracker()
        val english = activity(seed = 1)

        assertFalse(tracker.recordNoFit(english))
        assertFalse(tracker.recordNoFit(activity(seed = 1))) // variant of the same source
        assertEquals(1, tracker.sourceCount)
        assertFalse(tracker.recordNoFit(activity(seed = 2)))
        assertTrue(tracker.recordNoFit(activity(seed = 3)))
    }

    @Test
    fun onlyAmbiguousRejectionsCountAsNoFit() {
        val reference = timeline(seed = 1)
        val fit = assertNotNull(AutoSyncTimelineRetimer.retime(reference, reference, 1.0, 0.0, true))

        assertTrue(AutoSyncNoFitTracker.isNoFit(null))
        assertFalse(AutoSyncNoFitTracker.isNoFit(fit))
        assertTrue(AutoSyncNoFitTracker.isNoFit(fit.copy(confident = false, activityMargin = 0.004)))
        assertFalse(AutoSyncNoFitTracker.isNoFit(fit.copy(confident = false, activityMargin = 0.015)))
    }

    private fun activity(seed: Int) =
        assertNotNull(AutoSyncTimelineRetimer.prepareUnitActivity(timeline(seed)))

    private fun timeline(seed: Int): List<SubtitleSyncCue> {
        val random = kotlin.random.Random(seed)
        var start = 30_000L
        return (0 until 300).map { index ->
            if (index > 0) start += 1_200L + random.nextInt(6_000)
            SubtitleSyncCue(start, start + 900L + random.nextInt(2_000), "cue $index")
        }
    }
}
