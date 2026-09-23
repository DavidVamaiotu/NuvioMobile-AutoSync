package com.nuvio.app.features.player.audiosync

import com.nuvio.app.features.player.audiosync.asr.HeardWord
import com.nuvio.app.features.player.audiosync.asr.SubtitleBridge
import com.nuvio.app.features.player.audiosync.asr.WordAnchorMatcher
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsrMatchingTest {
    /** Like real dialogue: a few common words and many that occur only now and then. */
    private val vocabulary = List(600) { index ->
        val syllables = listOf("ka", "lo", "mir", "den", "sa", "tor", "vel", "ni", "ra", "bex")
        syllables[index % 10] + syllables[(index / 10) % 10] + syllables[(index / 100) % 10]
    }

    private fun script(random: Random, lines: Int): List<Triple<Long, Long, String>> {
        var t = 20_000L
        return List(lines) {
            val words = List(3 + random.nextInt(5)) { vocabulary[random.nextInt(vocabulary.size)] }
            val length = 1_200L + random.nextLong(2_500L)
            Triple(t, t + length, words.joinToString(" ")).also { t += length + 500L + random.nextLong(3_000L) }
        }
    }

    /** Words heard at media = subtitle + shift, with some misrecognised. */
    private fun hear(cues: List<Triple<Long, Long, String>>, shiftSec: Double, random: Random, upToLine: Int): List<HeardWord> =
        cues.take(upToLine).flatMapIndexed { line, (start, end, text) ->
            val words = text.split(' ')
            words.mapIndexedNotNull { k, word ->
                if (random.nextFloat() < 0.3f) return@mapIndexedNotNull null
                val t = (start + (end - start) * k / words.size) / 1_000.0 + shiftSec + random.nextDouble(-0.15, 0.15)
                HeardWord(t, word, line)
            }
        }

    @Test
    fun locksWithinAFewLines() {
        val random = Random(1)
        val cues = script(random, 60)
        val matcher = WordAnchorMatcher(cues)
        val fit = assertNotNull(matcher.fit(hear(cues, 7.3, random, upToLine = 6)))
        assertTrue(fit.isConfident, "fit $fit")
        assertTrue(abs(fit.shiftSec - 7.3) < 0.3, "shift ${fit.shiftSec}")
    }

    @Test
    fun unrelatedWordsDoNotLock() {
        val random = Random(2)
        val matcher = WordAnchorMatcher(script(random, 60))
        val heard = List(80) { HeardWord(30.0 + it * 2.5, "unrelated${it % 7}", it / 3) }
        val fit = matcher.fit(heard)
        assertTrue(fit == null || !fit.isConfident, "fit $fit")
    }

    @Test
    fun bridgesTranslationToReference() {
        val random = Random(3)
        val english = script(random, 80)
        // Same line timings, different words, shifted by 4.2 s relative to the English file.
        val translated = english.map { (a, b, _) -> Triple(a - 4_200L, b - 4_200L, "linie tradusa aici") }
        val bridge = assertNotNull(
            SubtitleBridge.align(SubtitleSpeechTrack.fromCues(translated), SubtitleSpeechTrack.fromCues(english)),
        )
        assertTrue(abs(bridge.shiftSec - 4.2) < 0.05, "bridge $bridge")
    }

    @Test
    fun bridgeRejectsUnrelatedFiles() {
        val a = script(Random(4), 80)
        val b = script(Random(5), 80)
        assertNull(SubtitleBridge.align(SubtitleSpeechTrack.fromCues(a), SubtitleSpeechTrack.fromCues(b)))
    }
}
