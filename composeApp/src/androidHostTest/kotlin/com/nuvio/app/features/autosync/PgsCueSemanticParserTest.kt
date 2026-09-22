package com.nuvio.app.features.autosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PgsCueSemanticParserTest {
    @Test
    fun assemblesSegmentBlocksAndPreservesSilence() {
        val probes = buildList<RawProbe> {
            visibleSet(startMs = 1_000, state = 2, objectVersion = 0)
            clearSet(startMs = 2_000)
            visibleSet(startMs = 4_000, objectVersion = 1)
            clearSet(startMs = 5_000)
            visibleSet(startMs = 7_000, objectVersion = 2)
            clearSet(startMs = 8_000)
        }.indexed()
        val result = PgsCueSemanticParser.buildTimeline(
            reference = reference(probes),
            probes = probes,
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
    fun keepsDirectReplacementBoundary() {
        val probes = buildList<RawProbe> {
            visibleSet(startMs = 1_000, state = 2, objectVersion = 0)
            visibleSet(startMs = 2_000, objectVersion = 1)
            clearSet(startMs = 3_000)
        }.indexed()
        val result = PgsCueSemanticParser.buildTimeline(reference(probes), probes)

        val ready = assertIs<PgsReferenceResolution.Ready>(result)
        assertEquals(
            listOf(
                1_000L to 2_000L,
                2_000L to 3_000L,
            ),
            ready.track.cues.map { it.startTimeMs to it.endTimeMs },
        )
    }

    @Test
    fun repeatedIdenticalCompositionDoesNotCreateFakeDialogueBoundary() {
        val probes = buildList<RawProbe> {
            visibleSet(startMs = 1_000, state = 2, objectVersion = 0)
            presentation(startMs = 2_000, objectId = 1)
            end(startMs = 2_000)
            clearSet(startMs = 3_000)
        }.indexed()
        val result = PgsCueSemanticParser.buildTimeline(reference(probes), probes)

        val ready = assertIs<PgsReferenceResolution.Ready>(result)
        assertEquals(
            listOf(1_000L to 3_000L),
            ready.track.cues.map { it.startTimeMs to it.endTimeMs },
        )
    }

    @Test
    fun objectVersionChangeIsAReplacement() {
        val probes = buildList<RawProbe> {
            visibleSet(startMs = 1_000, state = 2, objectVersion = 0)
            presentation(startMs = 2_000, objectId = 1)
            objectData(startMs = 2_000, objectId = 1, version = 1)
            end(startMs = 2_000)
            clearSet(startMs = 3_000)
        }.indexed()
        val result = PgsCueSemanticParser.buildTimeline(reference(probes), probes)

        val ready = assertIs<PgsReferenceResolution.Ready>(result)
        assertEquals(
            listOf(
                1_000L to 2_000L,
                2_000L to 3_000L,
            ),
            ready.track.cues.map { it.startTimeMs to it.endTimeMs },
        )
    }

    @Test
    fun rejectsDisplaySetWithoutEnd() {
        val probes = buildList<RawProbe> {
            presentation(startMs = 1_000, state = 2, objectId = 1)
            palette(startMs = 1_000)
            objectData(startMs = 1_000, objectId = 1, version = 0)
        }.indexed()
        val result = PgsCueSemanticParser.buildTimeline(reference(probes), probes)

        val unavailable = assertIs<PgsReferenceResolution.Unavailable>(result)
        assertEquals("display-set-missing-end", unavailable.reason)
    }

    @Test
    fun rejectsPartialIndexedCoverage() {
        val full = buildList<RawProbe> {
            visibleSet(startMs = 1_000, state = 2, objectVersion = 0)
            clearSet(startMs = 2_000)
        }.indexed()
        val result = PgsCueSemanticParser.buildTimeline(
            reference = reference(full),
            probes = full.dropLast(1),
        )

        val unavailable = assertIs<PgsReferenceResolution.Unavailable>(result)
        assertEquals("incomplete-cue-coverage", unavailable.reason)
    }

    @Test
    fun rejectsUnresolvedFinalVisiblePresentation() {
        val probes = buildList<RawProbe> {
            visibleSet(startMs = 1_000, state = 2, objectVersion = 0)
            clearSet(startMs = 2_000)
            visibleSet(startMs = 3_000, objectVersion = 1)
        }.indexed()
        val result = PgsCueSemanticParser.buildTimeline(reference(probes), probes)

        val unavailable = assertIs<PgsReferenceResolution.Unavailable>(result)
        assertEquals("unresolved-final-presentation", unavailable.reason)
    }

    private fun MutableList<RawProbe>.visibleSet(
        startMs: Long,
        state: Int = 0,
        objectVersion: Int,
    ) {
        add(presentation(startMs, state, objectId = 1))
        add(palette(startMs))
        add(objectData(startMs, objectId = 1, version = objectVersion))
        add(end(startMs))
    }

    private fun MutableList<RawProbe>.clearSet(startMs: Long) {
        add(
            RawProbe(
                startMs = startMs,
                segment = PgsSegmentInfo.Presentation(
                    state = 0,
                    paletteUpdate = false,
                    paletteId = 0,
                    objects = emptyList(),
                ),
            ),
        )
        add(end(startMs))
    }

    private fun presentation(
        startMs: Long,
        state: Int = 0,
        objectId: Int,
        durationMs: Long? = null,
    ) = RawProbe(
        startMs = startMs,
        durationMs = durationMs,
        segment = PgsSegmentInfo.Presentation(
            state = state,
            paletteUpdate = false,
            paletteId = 0,
            objects = listOf(
                PgsSegmentInfo.ObjectRef(
                    objectId = objectId,
                    windowId = 0,
                    x = 100,
                    y = 900,
                ),
            ),
        ),
    )

    private fun palette(startMs: Long) = RawProbe(
        startMs = startMs,
        segment = PgsSegmentInfo.Palette(id = 0, version = 0),
    )

    private fun objectData(
        startMs: Long,
        objectId: Int,
        version: Int,
    ) = RawProbe(
        startMs = startMs,
        segment = PgsSegmentInfo.ObjectData(
            id = objectId,
            version = version,
            sequence = 0xC0,
        ),
    )

    private fun end(startMs: Long) = RawProbe(
        startMs = startMs,
        segment = PgsSegmentInfo.End,
    )

    private fun List<RawProbe>.indexed(): List<PgsSegmentProbe> =
        mapIndexed { index, probe ->
            PgsSegmentProbe(
                cueIndex = index,
                startTimeMs = probe.startMs,
                durationMs = probe.durationMs,
                segment = probe.segment,
            )
        }

    private fun reference(probes: List<PgsSegmentProbe>) = IndexedPgsReference(
        key = "mkv-cues:4",
        language = "en",
        label = "English",
        selectionFlags = 0,
        roleFlags = 0,
        trackNumber = 4,
        segmentDataStart = 100L,
        timestampScaleNs = 1_000_000L,
        cues = probes.mapIndexed { index, probe ->
            PgsCueLocator(
                startTimeMs = probe.startTimeMs,
                cueTimeTicks = probe.startTimeMs,
                durationMs = probe.durationMs,
                clusterPosition = index * 1_000L,
                relativePosition = 10L,
                blockNumber = 1L,
            )
        },
    )

    private data class RawProbe(
        val startMs: Long,
        val durationMs: Long? = null,
        val segment: PgsSegmentInfo,
    )
}
