package com.nuvio.app.features.player.audiosync.asr

import com.nuvio.app.features.player.audiosync.SileroVad
import com.nuvio.app.features.player.audiosync.SpeechTimeline
import com.nuvio.app.features.player.audiosync.SubtitleAudioAligner
import com.nuvio.app.features.player.audiosync.SubtitleSpeechTrack
import java.util.PriorityQueue
import kotlin.math.abs

/** Speech recogniser returning (seconds from segment start, word) pairs. */
internal fun interface SpeechToText {
    fun transcribe(samples: FloatArray): List<Pair<Double, String>>
}

/** A same-language (English) subtitle the heard words can be matched against. */
internal class ReferenceSubtitle(
    val key: String,
    cues: List<Triple<Long, Long, String>>,
    /** How the target subtitle maps onto this one; null when the reference is the target itself. */
    val bridge: BridgeFit?,
) {
    val matcher = WordAnchorMatcher(cues)
}

/** Target subtitle mapping found from recognised words. */
internal data class AsrLock(
    val scale: Double,
    val shiftMs: Double,
    val referenceKey: String,
    val anchorScore: Double,
    val fineTuned: Boolean,
    /** False while the frame rate is still assumed (short span, no bridge); an update may follow. */
    val final: Boolean,
)

/**
 * Layer 2 of the audio sync: recognises English words in the dialogue and pins subtitles to them.
 *
 * Speech segments arrive from [SpeechSegmenter]s (look-ahead and live), are transcribed on one
 * low-priority worker, and every recognised word is kept for the whole stream, so switching
 * subtitles re-uses what was already heard. Each reference subtitle is matched against the words;
 * the first confident match is turned into a target mapping (through the timing bridge for
 * translated subtitles) and fine-tuned against the speech timeline.
 */
internal class AsrSyncEngine(
    private val timeline: SpeechTimeline,
    private val onLock: (AsrLock) -> Unit,
    private val log: (String) -> Unit = {},
    /** Runs on the worker thread before it starts (e.g. to lower its priority). */
    private val workerSetup: () -> Unit = {},
) {
    private class Segment(val startFrame: Int, val samples: FloatArray) {
        val endFrame: Int get() = startFrame + samples.size / SileroVad.CHUNK_SAMPLES
    }

    private val lock = Object()

    @Volatile
    private var playheadFrame = 0

    /** Nearest upcoming speech first: that is what will be on screen soonest. */
    private val queue = PriorityQueue<Segment>(compareBy { segmentPriority(it) })
    private var queuedSamples = 0L
    private val covered = ArrayList<IntRange>()
    private val heard = ArrayList<HeardWord>()
    private var segmentCounter = 0

    @Volatile
    private var target: SubtitleSpeechTrack? = null

    @Volatile
    private var references: List<ReferenceSubtitle> = emptyList()

    /** A final lock was reported; recognition pauses until the next session. */
    @Volatile
    private var locked = false

    /** Last reported (not yet final) lock, to report only real changes. */
    private var provisional: AsrLock? = null

    @Volatile
    private var stt: SpeechToText? = null

    @Volatile
    private var released = false
    private var worker: Thread? = null

    /** Heard words so far; exposed for diagnostics. */
    val heardWordCount: Int get() = synchronized(lock) { heard.size }

    /** Snapshot of every word heard so far, in time order. */
    fun heardWords(): List<HeardWord> = synchronized(lock) { heard.toList() }

    fun setRecognizer(recognizer: SpeechToText?) {
        stt = recognizer
        synchronized(lock) { lock.notifyAll() }
    }

    fun onPlayhead(positionMs: Long) {
        playheadFrame = (positionMs / SpeechTimeline.FRAME_DURATION_MS).toInt()
    }

    fun offerSegment(startFrame: Int, samples: FloatArray) {
        val segment = Segment(startFrame, samples)
        synchronized(lock) {
            if (released) return
            val range = segment.startFrame until segment.endFrame
            // The look-ahead and live paths can deliver the same stretch; skip what is mostly known.
            val overlap = covered.sumOf { overlap(it, range) } + queue.sumOf { overlap(it.startFrame until it.endFrame, range) }
            if (overlap * 2 > range.count()) return
            while (queuedSamples + samples.size > MAX_QUEUED_SAMPLES && queue.isNotEmpty()) {
                val dropped = queue.maxByOrNull(::segmentPriority) ?: break
                queue.remove(dropped)
                queuedSamples -= dropped.samples.size
            }
            queue.add(segment)
            queuedSamples += samples.size
            ensureWorker()
            lock.notifyAll()
        }
    }

    /** Starts matching for a new target subtitle; previously heard words are kept. */
    fun startSession(targetTrack: SubtitleSpeechTrack, referenceSubtitles: List<ReferenceSubtitle>) {
        synchronized(lock) {
            target = targetTrack
            references = referenceSubtitles
            locked = false
            provisional = null
            lock.notifyAll()
        }
        evaluate()
    }

    /** Adds a reference that finished downloading after the session started. */
    fun addReference(reference: ReferenceSubtitle) {
        synchronized(lock) {
            if (references.any { it.key == reference.key }) return
            references = references + reference
        }
        evaluate()
    }

    fun stopSession() {
        synchronized(lock) {
            target = null
            references = emptyList()
            locked = false
            provisional = null
        }
    }

    /** New stream: forget everything that was heard. */
    fun clear() {
        synchronized(lock) {
            queue.clear()
            queuedSamples = 0
            covered.clear()
            heard.clear()
            locked = false
            provisional = null
        }
    }

    fun release() {
        synchronized(lock) {
            released = true
            queue.clear()
            lock.notifyAll()
        }
    }

    private fun segmentPriority(segment: Segment): Long {
        val distance = segment.startFrame - playheadFrame
        // Upcoming speech first (nearest first), then the most recent past speech.
        return if (distance >= -BEHIND_GRACE_FRAMES) distance.toLong() else 1_000_000L - distance
    }

    private fun ensureWorker() {
        if (worker != null) return
        worker = Thread({ runWorker() }, "NuvioAsrSync").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun runWorker() {
        runCatching(workerSetup)
        while (true) {
            val (segment, recognizer) = synchronized(lock) {
                while (!released && (queue.isEmpty() || stt == null || target == null || locked)) lock.wait()
                if (released) return
                queue.poll().also { queuedSamples -= it.samples.size } to stt!!
            }
            val words = try {
                recognizer.transcribe(segment.samples)
            } catch (error: Throwable) {
                log("recognition failed: ${error.message}")
                emptyList()
            }
            val startSec = segment.startFrame * SpeechTimeline.FRAME_DURATION_MS / 1_000.0
            synchronized(lock) {
                covered += segment.startFrame until segment.endFrame
                val id = segmentCounter++
                words.forEach { (offsetSec, text) -> heard += HeardWord(startSec + offsetSec, text, id) }
                heard.sortBy { it.timeSec }
            }
            evaluate()
        }
    }

    private fun evaluate() {
        val (words, refs, targetTrack) = synchronized(lock) {
            if (locked) return
            Triple(heard.toList(), references, target ?: return)
        }
        if (words.isEmpty()) return
        var best: Pair<ReferenceSubtitle, AnchorFit>? = null
        for (reference in refs) {
            val fit = reference.matcher.fit(words) ?: continue
            if (!fit.isConfident) continue
            if (best == null || fit.score > best.second.score) best = reference to fit
        }
        val (reference, fit) = best ?: return
        // Compose target -> reference -> media.
        val bridge = reference.bridge
        val scale = fit.scale * (bridge?.scale ?: 1.0)
        val coarseShiftMs = (fit.scale * (bridge?.shiftSec ?: 0.0) + fit.shiftSec) * 1_000.0
        val fine = fineTune(targetTrack, scale, coarseShiftMs)
        val result = AsrLock(
            scale = scale,
            shiftMs = fine ?: coarseShiftMs,
            referenceKey = reference.key,
            anchorScore = fit.score,
            fineTuned = fine != null,
            final = bridge != null || fit.spanSec >= FINAL_SPAN_SEC || fit.scale != 1.0,
        )
        synchronized(lock) {
            if (locked || target !== targetTrack) return
            val previous = provisional
            if (!result.final && previous != null && previous.scale == result.scale &&
                abs(previous.shiftMs - result.shiftMs) < UPDATE_MIN_MS
            ) {
                return
            }
            if (result.final) locked = true else provisional = result
        }
        log(
            "words=${words.size} reference=${reference.key} fit=$fit bridge=$bridge " +
                "coarse=${coarseShiftMs.toLong()}ms fine=${fine?.toLong()}",
        )
        onLock(result)
    }

    /**
     * Word anchors are exact about *which* line is spoken but only approximately about when inside
     * it; the speech timeline pins the edges. Search a narrow window so it cannot jump elsewhere.
     */
    private fun fineTune(track: SubtitleSpeechTrack, scale: Double, coarseShiftMs: Double): Double? {
        val segments = timeline.segments(maxFrames = FINE_TUNE_MAX_FRAMES)
        if (segments.isEmpty()) return null
        val estimate = SubtitleAudioAligner.estimate(
            segments = segments,
            track = track,
            scales = doubleArrayOf(scale),
            minShiftMs = coarseShiftMs - FINE_TUNE_WINDOW_MS,
            maxShiftMs = coarseShiftMs + FINE_TUNE_WINDOW_MS,
        ) ?: return null
        if (estimate.atSearchEdge || estimate.cueCount < FINE_TUNE_MIN_CUES) return null
        if (abs(estimate.shiftMs - coarseShiftMs) > FINE_TUNE_MAX_MOVE_MS) return null
        return estimate.shiftMs
    }

    private fun overlap(a: IntRange, b: IntRange): Int =
        (minOf(a.last, b.last) - maxOf(a.first, b.first) + 1).coerceAtLeast(0)

    companion object {
        /** About 8 minutes of speech at 16 kHz. */
        private const val MAX_QUEUED_SAMPLES = 16_000L * 60 * 8
        private const val BEHIND_GRACE_FRAMES = 94 // 3 s
        private val FINE_TUNE_MAX_FRAMES = (10 * 60 * 1_000 / SpeechTimeline.FRAME_DURATION_MS).toInt()
        private const val FINE_TUNE_WINDOW_MS = 1_500.0
        private const val FINE_TUNE_MAX_MOVE_MS = 1_200.0
        private const val FINE_TUNE_MIN_CUES = 5
        private const val FINAL_SPAN_SEC = 180.0
        private const val UPDATE_MIN_MS = 150.0
    }
}
