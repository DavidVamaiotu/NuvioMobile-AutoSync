package com.nuvio.app.features.autosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PgsCueSemanticParserTest {
    @Test
    fun preservesSilenceBetweenClearAndNextVisiblePresentation() {
        val reference = reference(
            locator(1_000),
            locator(2_000),
            locator(4_000),
            locator(5_000),
            locator(7_000, durationMs = 1_000),
        )
        val result = PgsCueSemanticParser.buildTimeline(
            reference = reference,
            probes = listOf(
                probe(0, 1_000, visible = true),
                probe(1, 2_000, visible = false),
                probe(2, 4_000, visible = true),
                probe(3, 5_000, visible = false),
                probe(4, 7_000, visible = true, durationMs = 1_000),
            ),
        )

        val ready = assertIs<PgsReferenceResolution.Ready>(result)
        assertEquals(
            listOf(
                1_000L to 2_000L,
                4_000L to 5_000L,
                7_000L to 8_000L,
            ),
            ready.track.cues.map { it.startTimeMs to it.endTimeMs },
        )
    }

    @Test
    fun keepsDirectReplacementBoundaryWithoutArtificialGap() {
        val reference = reference(
            locator(1_000),
            locator(2_000),
            locator(3_000),
            locator(4_000, durationMs = 1_000),
        )
        val result = PgsCueSemanticParser.buildTimeline(
            reference = reference,
            probes = listOf(
                probe(0, 1_000, visible = true),
                probe(1, 2_000, visible = true),
                probe(2, 3_000, visible = false),
                probe(3, 4_000, visible = true, durationMs = 1_000),
            ),
        )

        val ready = assertIs<PgsReferenceResolution.Ready>(result)
        assertEquals(
            listOf(
                1_000L to 2_000L,
                2_000L to 3_000L,
                4_000L to 5_000L,
            ),
            ready.track.cues.map { it.startTimeMs to it.endTimeMs },
        )
    }

    @Test
    fun rejectsPartialCoverage() {
        val reference = reference(
            locator(1_000),
            locator(2_000),
            locator(3_000),
        )
        val result = PgsCueSemanticParser.buildTimeline(
            reference = reference,
            probes = listOf(
                probe(0, 1_000, visible = true),
                probe(1, 2_000, visible = false),
            ),
        )

        val unavailable = assertIs<PgsReferenceResolution.Unavailable>(result)
        assertEquals("incomplete-cue-coverage", unavailable.reason)
    }

    @Test
    fun rejectsUnresolvedFinalVisiblePresentation() {
        val reference = reference(
            locator(1_000),
            locator(2_000),
            locator(3_000),
            locator(4_000),
            locator(5_000),
        )
        val result = PgsCueSemanticParser.buildTimeline(
            reference = reference,
            probes = listOf(
                probe(0, 1_000, visible = true),
                probe(1, 2_000, visible = false),
                probe(2, 3_000, visible = true),
                probe(3, 4_000, visible = false),
                probe(4, 5_000, visible = true),
            ),
        )

        val unavailable = assertIs<PgsReferenceResolution.Unavailable>(result)
        assertEquals("unresolved-final-presentation", unavailable.reason)
    }

    private fun reference(vararg cues: PgsCueLocator) = IndexedPgsReference(
        key = "mkv-cues:4",
        language = "en",
        label = "English",
        selectionFlags = 0,
        roleFlags = 0,
        trackNumber = 4,
        segmentDataStart = 100L,
        timestampScaleNs = 1_000_000L,
        cues = cues.toList(),
    )

    private fun locator(
        startMs: Long,
        durationMs: Long? = null,
    ) = PgsCueLocator(
        startTimeMs = startMs,
        cueTimeTicks = startMs,
        durationMs = durationMs,
        clusterPosition = startMs,
        relativePosition = 10L,
        blockNumber = 1L,
    )

    private fun probe(
        index: Int,
        startMs: Long,
        visible: Boolean,
        durationMs: Long? = null,
    ) = PgsPresentationProbe(
        cueIndex = index,
        startTimeMs = startMs,
        durationMs = durationMs,
        visible = visible,
        payloadEnd = 1_000L + index,
    )
}
