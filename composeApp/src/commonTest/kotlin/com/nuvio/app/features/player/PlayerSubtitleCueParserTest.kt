package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PlayerSubtitleCueParserTest {
    @Test
    fun ttmlUsesDeclaredFrameRate() {
        val cues = PlayerSubtitleCueParser.parse(
            text = """
                <tt xmlns:ttp="http://www.w3.org/ns/ttml#parameter" ttp:frameRate="25">
                  <body><div>
                    <p begin="00:00:10:12" end="00:00:12:00">Frame rated</p>
                  </div></body>
                </tt>
            """.trimIndent(),
            sourceUrl = "subtitle.ttml",
        )

        val cue = assertNotNull(cues.singleOrNull())
        assertEquals(10_480L, cue.startTimeMs)
        assertEquals(12_000L, cue.endTimeMs)
    }

    @Test
    fun ttmlUsesFrameRateMultiplier() {
        val cues = PlayerSubtitleCueParser.parse(
            text = """
                <tt xmlns:ttp="http://www.w3.org/ns/ttml#parameter"
                    ttp:frameRate="30"
                    ttp:frameRateMultiplier="1000 1001">
                  <body><div>
                    <p begin="00:00:10:15" end="00:00:11:00">Fractional frame rate</p>
                  </div></body>
                </tt>
            """.trimIndent(),
            sourceUrl = "subtitle.ttml",
        )

        val cue = assertNotNull(cues.singleOrNull())
        assertEquals(10_500L, cue.startTimeMs)
        assertEquals(11_000L, cue.endTimeMs)
    }
}
