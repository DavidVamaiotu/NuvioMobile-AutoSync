package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutoSyncTimingDumpTest {
    @Test
    fun reportTimingLinesRoundTripAndReplay() {
        val reference = (0 until 120).map { index ->
            val start = 30_000L + index * 3_100L + (index * 977L) % 1_300L
            SubtitleSyncCue(start, start + 900L + (index * 313L) % 1_700L, "line $index")
        }
        val target = reference.map { cue ->
            cue.copy(startTimeMs = cue.startTimeMs - 2_000L, endTimeMs = cue.endTimeMs - 2_000L)
        }
        val estimated = setOf(reference[3].startTimeMs, reference[40].startTimeMs)

        val report = listOf(
            "[+12ms][INFO] unrelated log line",
            AutoSyncTimingDump.encode(
                AutoSyncTimingDump.Track("ref:mkv-cues:4", reference, estimated, sdh = true),
            ),
            AutoSyncTimingDump.encode(AutoSyncTimingDump.Track("target:0", target)),
        ).joinToString("\n")

        val tracks = AutoSyncTimingDump.parseReport(report)
        val parsedReference = assertNotNull(tracks["ref:mkv-cues:4"])
        val parsedTarget = assertNotNull(tracks["target:0"])

        assertEquals(reference.map { it.startTimeMs to it.endTimeMs }, parsedReference.cues.map { it.startTimeMs to it.endTimeMs })
        assertEquals(target.map { it.startTimeMs to it.endTimeMs }, parsedTarget.cues.map { it.startTimeMs to it.endTimeMs })
        assertEquals(estimated, parsedReference.estimatedEndStartsMs)
        assertTrue(parsedReference.sdh)

        val replayed = assertNotNull(AutoSyncTimingDump.replay(parsedReference, parsedTarget))
        assertTrue(replayed.confident, replayed.rejectReason)
    }
}
