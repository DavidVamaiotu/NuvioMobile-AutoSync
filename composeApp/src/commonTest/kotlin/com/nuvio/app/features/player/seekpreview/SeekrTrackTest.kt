package com.nuvio.app.features.player.seekpreview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeekrTrackTest {

    private val vtt = """
        WEBVTT

        00:00:00.000 --> 00:00:10.000
        https://sprites.seekr.tv/s/0.jpg?sig=abc#xywh=0,0,320,180

        00:00:10.000 --> 00:00:20.000
        https://sprites.seekr.tv/s/0.jpg?sig=abc#xywh=320,0,320,180

        00:00:20.000 --> 00:00:30.000
        https://sprites.seekr.tv/s/1.jpg?sig=def#xywh=0,180,320,180
    """.trimIndent()

    private fun track() = SeekrTrack(
        cues = SeekrVtt.parse(vtt),
        sheets = SeekrSheetCache(download = { null }, decode = { null }),
    )

    @Test
    fun `vtt cues keep their windows and sprite rectangles`() {
        val cues = SeekrVtt.parse(vtt)

        assertEquals(3, cues.size)
        assertEquals(10_000L, cues[1].startMs)
        assertEquals(20_000L, cues[1].endMs)
        assertEquals(SeekrTile("https://sprites.seekr.tv/s/0.jpg?sig=abc", 320, 0, 320, 180), cues[1].tile)
        assertEquals(SeekrTile("https://sprites.seekr.tv/s/1.jpg?sig=def", 0, 180, 320, 180), cues[2].tile)
    }

    @Test
    fun `vtt without tile fragments yields no cues`() {
        assertTrue(SeekrVtt.parse("WEBVTT\n\n00:00.000 --> 00:10.000\nhttps://x/0.jpg\n").isEmpty())
    }

    @Test
    fun `lookup returns the last cue starting at or before the position`() {
        val track = track()

        assertEquals(0L, track.resolveCue(9_999L)?.startMs)
        assertEquals(10_000L, track.resolveCue(10_000L)?.startMs)
        assertEquals(20_000L, track.resolveCue(25_000L)?.startMs)
    }

    @Test
    fun `lookup clamps to the ends of the track`() {
        val track = track()

        assertEquals(0L, track.resolveCue(-5_000L)?.startMs)
        assertEquals(20_000L, track.resolveCue(999_000L)?.startMs)
    }

    @Test
    fun `offset is added to the position before the lookup`() {
        val track = track()
        // The played release has 10s of extra head content.
        track.offsetMs = -10_000L

        assertEquals(10_000L, track.resolveCue(25_000L)?.startMs)
    }

    @Test
    fun `empty track resolves nothing`() {
        val track = SeekrTrack(cues = emptyList(), sheets = SeekrSheetCache(download = { null }, decode = { null }))

        assertNull(track.resolveCue(0L))
        assertTrue(track.isEmpty)
    }

    @Test
    fun `sheet urls are distinct and in cue order`() {
        assertEquals(
            listOf("https://sprites.seekr.tv/s/0.jpg?sig=abc", "https://sprites.seekr.tv/s/1.jpg?sig=def"),
            track().sheetUrls.toList(),
        )
    }
}
