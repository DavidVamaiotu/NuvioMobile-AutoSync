package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoSyncReferenceRetryPolicyTest {
    @Test
    fun exactTimingDuplicatesShareOneReferenceIdentity() {
        val timing = listOf(
            cue(1_000L, 1_700L),
            cue(3_000L, 3_800L),
            cue(6_000L, 6_900L),
        )
        val tracks = listOf(
            ReferenceTrack(key = "mkv-cues:3", language = "en", cues = timing),
            ReferenceTrack(key = "mkv-cues:4", language = "fr", cues = timing),
            ReferenceTrack(
                key = "mkv-cues:5",
                language = "es",
                cues = listOf(
                    cue(1_000L, 1_700L),
                    cue(3_050L, 3_800L),
                    cue(6_000L, 6_900L),
                ),
            ),
        )

        val equivalent = AutomaticSubtitleSync.equivalentReferenceKeysFor(
            referenceTracks = tracks,
            referenceKey = "mkv-cues:3",
        )

        assertEquals(setOf("mkv-cues:3", "mkv-cues:4"), equivalent)
        assertFalse("mkv-cues:5" in equivalent)
    }

    @Test
    fun unknownReferenceOnlyRejectsItsOwnKey() {
        val equivalent = AutomaticSubtitleSync.equivalentReferenceKeysFor(
            referenceTracks = emptyList(),
            referenceKey = "media3:8",
        )

        assertTrue(equivalent == setOf("media3:8"))
    }

    private fun cue(startMs: Long, endMs: Long): SubtitleSyncCue =
        SubtitleSyncCue(
            startTimeMs = startMs,
            endTimeMs = endMs,
            text = "x",
        )
}
