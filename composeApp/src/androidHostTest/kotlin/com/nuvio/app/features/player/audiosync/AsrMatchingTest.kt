package com.nuvio.app.features.player.audiosync

import com.nuvio.app.features.player.audiosync.asr.AsrSyncEngine
import com.nuvio.app.features.player.audiosync.asr.HeardWord
import com.nuvio.app.features.player.audiosync.asr.SubtitleBridge
import com.nuvio.app.features.player.audiosync.asr.WordAnchorMatcher
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun sampledSpeechIsRecognisedFirstAcrossPlaces() {
        val order = java.util.Collections.synchronizedList(ArrayList<Int>())
        val done = java.util.concurrent.CountDownLatch(6)
        val engine = AsrSyncEngine(SpeechTimeline(), onLock = {})
        engine.startSession(SubtitleSpeechTrack.fromCues(script(Random(6), 30)), emptyList())
        fun segment(frame: Int) = FloatArray(SileroVad.CHUNK_SAMPLES * 10) { frame.toFloat() }
        engine.onPlayhead(0L)
        listOf(100, 200, 300).forEach { engine.offerSegment(it, segment(it)) }
        // Two segments sampled at one place (~30 min), one at another (~60 min).
        listOf(56_000, 56_100, 112_000).forEach { engine.offerSegment(it, segment(it), spread = true) }
        engine.setRecognizer { samples ->
            order += samples[0].toInt()
            done.countDown()
            emptyList()
        }
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        engine.release()
        assertEquals(listOf(56_000, 112_000, 56_100, 100, 200, 300), order.toList())
    }

    @Test
    fun englishReferenceAtAnotherFrameRateDoesNotSkewTheLock() {
        // Video and translated subtitle agree; the English reference was made for 25 fps.
        val lock = lockAcrossFrameRates(videoMatchesEnglish = false)
        assertEquals(1.0, lock.scale, 0.002, "lock $lock")
        assertTrue(abs(lock.shiftMs) < 150.0, "lock $lock")
        assertTrue(!lock.final, "a rate chosen from a few lines stays open: $lock")
    }

    @Test
    fun translationAtAnotherFrameRateIsCorrected() {
        // Video and English reference agree; the translated subtitle was made for 25 fps.
        val lock = lockAcrossFrameRates(videoMatchesEnglish = true)
        assertEquals(25.0 / 23.976, lock.scale, 0.002, "lock $lock")
    }

    @Test
    fun littleHeardAudioNeverStretches() {
        // As on a phone without sampling: about 70 s heard. Even if a stretch is right, it is not
        // applied on so little evidence; the subtitle keeps its own rate until more is heard.
        val lock = lockAcrossFrameRates(videoMatchesEnglish = true, heardMs = 70_000L)
        assertEquals(1.0, lock.scale, 1e-9, "lock $lock")
        assertTrue(!lock.final, "lock $lock")
    }

    private fun lockAcrossFrameRates(
        videoMatchesEnglish: Boolean,
        heardMs: Long = Long.MAX_VALUE,
    ): com.nuvio.app.features.player.audiosync.asr.AsrLock {
        val rate = 25.0 / 23.976
        val media = script(Random(7), 160)
        fun at25(cues: List<Triple<Long, Long, String>>) =
            cues.map { (a, b, t) -> Triple((a / rate).toLong(), (b / rate).toLong(), t) }
        val english = if (videoMatchesEnglish) media else at25(media)
        val translated = (if (videoMatchesEnglish) at25(media) else media).map { (a, b, _) -> Triple(a, b, "linie tradusa") }
        val target = SubtitleSpeechTrack.fromCues(translated)
        val bridge = assertNotNull(SubtitleBridge.align(target, SubtitleSpeechTrack.fromCues(english)))
        val locks = java.util.Collections.synchronizedList(ArrayList<com.nuvio.app.features.player.audiosync.asr.AsrLock>())
        val latch = java.util.concurrent.CountDownLatch(1)
        val engine = AsrSyncEngine(speech(media, heardMs), onLock = { locks += it; latch.countDown() })
        engine.startSession(target, listOf(com.nuvio.app.features.player.audiosync.asr.ReferenceSubtitle("en", english, bridge)))
        // Twelve lines heard near the start: far too short a span to judge a frame rate from words.
        val random = Random(8)
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        media.take(12).forEachIndexed { line, (start, _, _) ->
            engine.offerSegment((start / frameMs).toInt(), FloatArray(SileroVad.CHUNK_SAMPLES * 10) { line.toFloat() })
        }
        engine.setRecognizer { samples ->
            val line = samples[0].toInt()
            val (start, end, text) = media[line]
            val segmentStartSec = (start / frameMs).toInt() * frameMs / 1_000.0
            val words = text.split(' ')
            words.mapIndexed { k, word ->
                val t = (start + (end - start) * k / words.size) / 1_000.0 + random.nextDouble(-0.1, 0.1)
                (t - segmentStartSec) to word
            }
        }
        assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS), "no lock")
        Thread.sleep(500)
        engine.release()
        return locks.last()
    }

    /** Clean speech exactly where [cues] are. */
    private fun speech(cues: List<Triple<Long, Long, String>>, heardMs: Long = Long.MAX_VALUE): SpeechTimeline {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val timeline = SpeechTimeline()
        val frames = (minOf(cues.maxOf { it.second } + 30_000, heardMs) / frameMs).toInt()
        val speaking = BooleanArray(frames)
        for ((a, b, _) in cues) {
            val from = ((a + SubtitleAudioAligner.DETECTOR_BIAS_MS) / frameMs).toInt()
            val to = ((b + SubtitleAudioAligner.DETECTOR_BIAS_MS) / frameMs).toInt()
            for (f in from until to.coerceAtMost(frames)) speaking[f] = true
        }
        for (f in 0 until frames) timeline.record(f, if (speaking[f]) 0.9f else 0.05f)
        return timeline
    }

    @Test
    fun bridgeRejectsUnrelatedFiles() {
        val a = script(Random(4), 80)
        val b = script(Random(5), 80)
        assertNull(SubtitleBridge.align(SubtitleSpeechTrack.fromCues(a), SubtitleSpeechTrack.fromCues(b)))
    }
}
