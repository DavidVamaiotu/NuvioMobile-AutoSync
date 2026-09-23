package com.nuvio.app.features.player.audiosync

import kotlin.math.abs
import kotlin.math.roundToLong

/** Piecewise mapping media time = subtitle time * scale + shift, each piece starting at [fromMediaMs]. */
internal data class SubtitleSyncSegment(
    val fromMediaMs: Long,
    val scale: Double,
    val shiftMs: Double,
)

internal class SubtitleSyncModel(val segments: List<SubtitleSyncSegment>) {
    init {
        require(segments.isNotEmpty())
    }

    fun segmentAt(positionMs: Long): SubtitleSyncSegment =
        segments.lastOrNull { it.fromMediaMs <= positionMs } ?: segments.first()

    /** Delay that makes the subtitle renderer show subtitle time (position - delay) at [positionUs]. */
    fun delayUsAt(positionUs: Long): Long {
        val segment = segmentAt(positionUs / 1_000L)
        val subtitleTimeUs = (positionUs - segment.shiftMs * 1_000.0) / segment.scale
        return (positionUs - subtitleTimeUs).roundToLong()
    }

    override fun toString(): String = segments.joinToString(prefix = "[", postfix = "]") {
        "from=${it.fromMediaMs}ms scale=${"%.5f".format(it.scale)} shift=${it.shiftMs.roundToLong()}ms"
    }
}

/**
 * Decides when the audio evidence is strong enough to move subtitles, then keeps refining the
 * result and watches for a later jump (a subtitle made for a different cut).
 *
 * Pure logic so it can be replayed off-device; [update] is called periodically from a background
 * thread with the growing [SpeechTimeline].
 */
internal class AudioSyncTracker(
    private val track: SubtitleSpeechTrack,
    private val provisionalPolicy: ProvisionalPolicy = ProvisionalPolicy(),
) {
    /**
     * Thresholds for the early, unconfirmed estimate shown while evidence is still thin. Looser
     * than the lock, but it must repeat on two consecutive runs before it is applied.
     */
    data class ProvisionalPolicy(
        val enabled: Boolean = true,
        val minKnownSeconds: Double = 20.0,
        val minCues: Int = 8,
        val minSpeechSeconds: Double = 5.0,
        val minProminence: Double = 0.15,
        val agreementMs: Double = 300.0,
        val minMovementMs: Double = 120.0,
        /** Consecutive runs that must agree before the estimate is applied. */
        val requiredRepeats: Int = 3,
        /**
         * Frame-rate mismatches are rare and easy to confuse on little evidence, so early estimates
         * assume the normal rate; the confirmed lock still detects them.
         */
        val unitScaleOnly: Boolean = true,
        /**
         * Runs a few seconds apart share almost all their audio, so agreement alone proves little.
         * The agreeing streak must also span at least this much newly analysed audio.
         */
        val minEvidenceGrowthSeconds: Double = 20.0,
        /**
         * Most subtitle offsets are small. When the best match lies within this range, far fewer
         * offsets could have matched by chance, so the looser "near" thresholds below apply and
         * subtitles can move after only a few lines of dialogue.
         */
        val nearRangeMs: Double = 8_000.0,
        val nearMinCues: Int = 5,
        val nearMinProminence: Double = 0.08,
        val nearRequiredRepeats: Int = 2,
        val nearMinEvidenceGrowthSeconds: Double = 10.0,
        /**
         * An early estimate may use a frame-rate correction only when it beats the normal rate by
         * this much correlation; negative disables it.
         */
        val nearRateMargin: Double = -1.0,
    )

    /** Confirmed mapping, or null while still searching. */
    var model: SubtitleSyncModel? = null
        private set

    /** Best unconfirmed mapping so far; superseded by [model] once confirmed. */
    var provisionalModel: SubtitleSyncModel? = null
        private set

    private var previousCandidate: SubtitleAudioAligner.Estimate? = null
    private var agreeingRuns = 0
    private var disagreeingRuns = 0

    /** After a retraction only a confirmed lock may move subtitles again. */
    private var provisionalBlocked = false
    private var streakStartAnalysedSeconds = 0.0

    var lastEstimate: SubtitleAudioAligner.Estimate? = null
        private set

    private var followRuns = 0
    private var pendingJump: SubtitleAudioAligner.Estimate? = null

    sealed interface Outcome {
        data object NotEnoughEvidence : Outcome
        data class Searching(val estimate: SubtitleAudioAligner.Estimate?) : Outcome
        data class Provisional(val model: SubtitleSyncModel, val estimate: SubtitleAudioAligner.Estimate) : Outcome

        /** The early estimate lost its support and was withdrawn; subtitles go back to file timing. */
        data class Retracted(val estimate: SubtitleAudioAligner.Estimate) : Outcome
        data class Locked(val model: SubtitleSyncModel, val estimate: SubtitleAudioAligner.Estimate) : Outcome
        data class Refined(val model: SubtitleSyncModel, val estimate: SubtitleAudioAligner.Estimate) : Outcome
        data class Jumped(val model: SubtitleSyncModel, val estimate: SubtitleAudioAligner.Estimate) : Outcome
        data class Unchanged(val estimate: SubtitleAudioAligner.Estimate?) : Outcome
    }

    /**
     * Takes a mapping found elsewhere (speech recognition, a remembered result) as the confirmed
     * lock; from here on this tracker only refines it and watches for jumps.
     */
    fun adopt(adopted: SubtitleSyncModel) {
        model = adopted
        provisionalModel = null
        pendingJump = null
        agreeingRuns = 0
        disagreeingRuns = 0
    }

    fun update(timeline: SpeechTimeline, playbackPositionMs: Long): Outcome {
        val known = timeline.knownRange() ?: return Outcome.NotEnoughEvidence
        val current = model
        return if (current == null) search(timeline, known) else follow(timeline, known, current, playbackPositionMs)
    }

    private fun search(timeline: SpeechTimeline, known: IntRange): Outcome {
        val to = known.last + 1
        val from = maxOf(known.first, to - MAX_ANALYSIS_FRAMES)
        val knownFrames = timeline.knownFramesIn(from, to)
        val provisionalFrames = (provisionalPolicy.minKnownSeconds * FRAMES_PER_SECOND).toInt()
        val minFrames = if (provisionalPolicy.enabled) minOf(MIN_KNOWN_FRAMES, provisionalFrames) else MIN_KNOWN_FRAMES
        if (knownFrames < minFrames) return Outcome.NotEnoughEvidence
        val probabilities = timeline.snapshot(from, to)
        val estimate = SubtitleAudioAligner.estimate(
            probabilities = probabilities,
            fromFrame = from,
            track = track,
            minShiftMs = -MAX_SHIFT_MS,
            maxShiftMs = MAX_SHIFT_MS,
        ) ?: return Outcome.NotEnoughEvidence
        lastEstimate = estimate
        val requiredProminence = if (isStandardRate(estimate.scale)) {
            ACCEPT_PROMINENCE
        } else {
            ACCEPT_PROMINENCE + NON_STANDARD_RATE_PENALTY
        }
        val lockable = knownFrames >= MIN_KNOWN_FRAMES && hasEnoughEvidence(estimate) &&
            !estimate.atSearchEdge && estimate.prominence >= requiredProminence &&
            confirmedByBothHalves(probabilities, from, estimate)
        if (lockable) {
            val locked = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, estimate.scale, estimate.shiftMs)))
            model = locked
            provisionalModel = null
            return Outcome.Locked(locked, estimate)
        }
        val candidate = if (!provisionalPolicy.unitScaleOnly || estimate.scale == 1.0) {
            estimate
        } else {
            SubtitleAudioAligner.estimate(
                probabilities = probabilities,
                fromFrame = from,
                track = track,
                scales = doubleArrayOf(1.0),
                minShiftMs = -MAX_SHIFT_MS,
                maxShiftMs = MAX_SHIFT_MS,
            ) ?: return Outcome.Searching(estimate)
        }
        val nearUnit = SubtitleAudioAligner.estimate(
            probabilities = probabilities,
            fromFrame = from,
            track = track,
            scales = doubleArrayOf(1.0),
            minShiftMs = -provisionalPolicy.nearRangeMs,
            maxShiftMs = provisionalPolicy.nearRangeMs,
        )
        val nearRated = if (provisionalPolicy.nearRateMargin >= 0 && nearUnit != null) {
            SubtitleAudioAligner.estimate(
                probabilities = probabilities,
                fromFrame = from,
                track = track,
                scales = NON_UNIT_SCALES,
                minShiftMs = -provisionalPolicy.nearRangeMs,
                maxShiftMs = provisionalPolicy.nearRangeMs,
            )?.takeIf { !it.atSearchEdge && it.peak >= nearUnit.peak + provisionalPolicy.nearRateMargin }
        } else {
            null
        }
        val near = nearRated ?: nearUnit
        val useNear = near != null && !near.atSearchEdge &&
            (abs(candidate.shiftMs) <= provisionalPolicy.nearRangeMs || near.peak >= candidate.peak - NEAR_PEAK_TOLERANCE)
        val best = if (useNear) near!! else candidate
        retraction(best, estimate)?.let { return it }
        if (provisionalBlocked) return Outcome.Searching(estimate)
        return provisional(best, near = useNear) ?: Outcome.Searching(estimate)
    }

    /**
     * Applies the current best guess early when it repeats on consecutive runs, so subtitles move
     * within the first minute instead of waiting for the full confirmation. It keeps being updated
     * as evidence grows and is replaced by the confirmed lock.
     */
    private fun provisional(estimate: SubtitleAudioAligner.Estimate, near: Boolean): Outcome? {
        val policy = provisionalPolicy
        val previous = previousCandidate
        previousCandidate = estimate
        if (!policy.enabled) return null
        val minProminence = if (near) policy.nearMinProminence else policy.minProminence
        val requiredProminence = if (isStandardRate(estimate.scale)) {
            minProminence
        } else {
            minProminence + NON_STANDARD_RATE_PENALTY
        }
        val credible = !estimate.atSearchEdge &&
            estimate.cueCount >= (if (near) policy.nearMinCues else policy.minCues) &&
            estimate.speechSeconds >= policy.minSpeechSeconds &&
            estimate.prominence >= requiredProminence
        val agrees = previous != null && previous.scale == estimate.scale &&
            abs(previous.shiftMs - estimate.shiftMs) <= policy.agreementMs
        agreeingRuns = when {
            !credible -> 0
            agrees -> agreeingRuns + 1
            else -> 1
        }
        if (agreeingRuns == 1) streakStartAnalysedSeconds = estimate.analysedSeconds
        val repeats = if (near) policy.nearRequiredRepeats else policy.requiredRepeats
        val growth = if (near) policy.nearMinEvidenceGrowthSeconds else policy.minEvidenceGrowthSeconds
        if (agreeingRuns < repeats) return null
        if (estimate.analysedSeconds - streakStartAnalysedSeconds < growth) return null
        val current = provisionalModel?.segments?.first()
        if (current != null && current.scale == estimate.scale &&
            abs(current.shiftMs - estimate.shiftMs) < policy.minMovementMs
        ) {
            return null
        }
        val updated = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, estimate.scale, estimate.shiftMs)))
        provisionalModel = updated
        return Outcome.Provisional(updated, estimate)
    }

    /**
     * A real match is confirmed after roughly 20-25 dialogue lines. An early estimate that is still
     * unconfirmed well past that, or that newer audio keeps contradicting, was most likely chance:
     * withdraw it rather than leave subtitles at a wrong offset.
     */
    private fun retraction(
        best: SubtitleAudioAligner.Estimate,
        global: SubtitleAudioAligner.Estimate,
    ): Outcome? {
        val applied = provisionalModel?.segments?.first() ?: return null
        disagreeingRuns = if (abs(best.shiftMs - applied.shiftMs) > RETRACT_DISAGREEMENT_MS) disagreeingRuns + 1 else 0
        val stale = global.cueCount >= RETRACT_AFTER_CUES
        if (!stale && disagreeingRuns < RETRACT_DISAGREEING_RUNS) return null
        provisionalModel = null
        provisionalBlocked = stale
        agreeingRuns = 0
        disagreeingRuns = 0
        return Outcome.Retracted(best)
    }

    /**
     * A chance alignment of unrelated subtitles rarely survives being checked on two disjoint halves
     * of the audio, while a real one shows up in both.
     */
    private fun confirmedByBothHalves(
        probabilities: FloatArray,
        from: Int,
        estimate: SubtitleAudioAligner.Estimate,
    ): Boolean {
        var knownTotal = 0
        for (p in probabilities) if (!p.isNaN()) knownTotal++
        var seen = 0
        var split = probabilities.size / 2
        for (i in probabilities.indices) {
            if (!probabilities[i].isNaN()) seen++
            if (seen * 2 >= knownTotal) {
                split = i + 1
                break
            }
        }
        val halves = listOf(
            from to probabilities.copyOfRange(0, split),
            (from + split) to probabilities.copyOfRange(split, probabilities.size),
        )
        return halves.all { (halfFrom, halfProbabilities) ->
            val half = SubtitleAudioAligner.estimate(
                probabilities = halfProbabilities,
                fromFrame = halfFrom,
                track = track,
                scales = doubleArrayOf(estimate.scale),
                minShiftMs = -MAX_SHIFT_MS,
                maxShiftMs = MAX_SHIFT_MS,
            )
            half != null &&
                !half.atSearchEdge &&
                half.cueCount >= MIN_HALF_CUES &&
                abs(half.shiftMs - estimate.shiftMs) <= HALF_AGREEMENT_MS
        }
    }

    private fun isStandardRate(scale: Double): Boolean = abs(scale - 1.0) < 0.002

    private fun follow(
        timeline: SpeechTimeline,
        known: IntRange,
        current: SubtitleSyncModel,
        playbackPositionMs: Long,
    ): Outcome {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val segment = current.segments.last()
        val segmentStartFrame = (segment.fromMediaMs / frameMs).toInt()
        val to = known.last + 1

        // 1. Look for a jump near the playhead (including buffered audio ahead of it).
        val localFrom = maxOf(known.first, segmentStartFrame, (playbackPositionMs / frameMs).toInt() - LOCAL_BACK_FRAMES)
        if (to - localFrom >= MIN_LOCAL_FRAMES && timeline.knownFramesIn(localFrom, to) >= MIN_LOCAL_FRAMES) {
            val local = SubtitleAudioAligner.estimate(
                probabilities = timeline.snapshot(localFrom, to),
                fromFrame = localFrom,
                track = track,
                scales = doubleArrayOf(segment.scale),
                minShiftMs = -MAX_SHIFT_MS,
                maxShiftMs = MAX_SHIFT_MS,
            )
            if (local != null && hasEnoughEvidence(local, minCues = MIN_LOCAL_CUES) && !local.atSearchEdge &&
                abs(local.shiftMs - segment.shiftMs) > JUMP_THRESHOLD_MS &&
                local.prominence >= ACCEPT_PROMINENCE
            ) {
                val pending = pendingJump
                pendingJump = local
                if (pending != null && abs(pending.shiftMs - local.shiftMs) <= STABLE_SHIFT_TOLERANCE_MS) {
                    pendingJump = null
                    val split = findChangePoint(timeline, localFrom, to, segment, local.shiftMs)
                    if (split != null) {
                        val jumped = SubtitleSyncModel(
                            current.segments + SubtitleSyncSegment(split, segment.scale, local.shiftMs),
                        )
                        model = jumped
                        lastEstimate = local
                        return Outcome.Jumped(jumped, local)
                    }
                }
                return Outcome.Unchanged(local)
            }
            pendingJump = null
        }

        // 2. Refine the active segment with everything heard since it started.
        val from = maxOf(known.first, segmentStartFrame, to - MAX_ANALYSIS_FRAMES)
        if (to - from < MIN_KNOWN_FRAMES) return Outcome.Unchanged(null)
        followRuns++
        if (to - from >= RATE_CHECK_MIN_FRAMES && followRuns % RATE_CHECK_EVERY_RUNS == 0) {
            rateCorrection(timeline, from, to, current, segment)?.let { return it }
        }
        val refined = SubtitleAudioAligner.estimate(
            probabilities = timeline.snapshot(from, to),
            fromFrame = from,
            track = track,
            scales = doubleArrayOf(segment.scale),
            minShiftMs = segment.shiftMs - REFINE_RANGE_MS,
            maxShiftMs = segment.shiftMs + REFINE_RANGE_MS,
        ) ?: return Outcome.Unchanged(null)
        lastEstimate = refined
        if (refined.prominence < REFINE_MIN_PROMINENCE || !hasEnoughEvidence(refined)) {
            return Outcome.Unchanged(refined)
        }
        val movement = abs(refined.shiftMs - segment.shiftMs)
        if (movement < REFINE_MIN_MOVEMENT_MS || movement > REFINE_RANGE_MS - 2 * frameMs) {
            return Outcome.Unchanged(refined)
        }
        val updated = SubtitleSyncModel(current.segments.dropLast(1) + segment.copy(shiftMs = refined.shiftMs))
        model = updated
        return Outcome.Refined(updated, refined)
    }

    /**
     * Over a long stretch a 0.1% rate difference (23.976 vs 24 fps) drifts by seconds, but it is
     * indistinguishable from 1.0 in the first minutes. Re-test the neighbouring ratios once enough
     * audio is in and switch when one clearly fits better.
     */
    private fun rateCorrection(
        timeline: SpeechTimeline,
        from: Int,
        to: Int,
        current: SubtitleSyncModel,
        segment: SubtitleSyncSegment,
    ): Outcome? {
        val probabilities = timeline.snapshot(from, to)
        val here = SubtitleAudioAligner.estimate(
            probabilities = probabilities,
            fromFrame = from,
            track = track,
            scales = doubleArrayOf(segment.scale),
            minShiftMs = -MAX_SHIFT_MS,
            maxShiftMs = MAX_SHIFT_MS,
        ) ?: return null
        val neighbour = SubtitleAudioAligner.estimate(
            probabilities = probabilities,
            fromFrame = from,
            track = track,
            scales = doubleArrayOf(segment.scale * NEAR_RATE, segment.scale / NEAR_RATE),
            minShiftMs = -MAX_SHIFT_MS,
            maxShiftMs = MAX_SHIFT_MS,
        ) ?: return null
        if (neighbour.atSearchEdge || neighbour.peak < here.peak + RATE_SWITCH_MARGIN) return null
        val updated = SubtitleSyncModel(
            current.segments.dropLast(1) + segment.copy(scale = neighbour.scale, shiftMs = neighbour.shiftMs),
        )
        model = updated
        lastEstimate = neighbour
        return Outcome.Refined(updated, neighbour)
    }

    /**
     * Returns the media time from which [newShiftMs] explains the speech better than the active
     * segment, by maximising the evidence gained from switching at each frame.
     */
    private fun findChangePoint(
        timeline: SpeechTimeline,
        from: Int,
        to: Int,
        segment: SubtitleSyncSegment,
        newShiftMs: Double,
    ): Long? {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val probabilities = timeline.snapshot(from, to)
        var sum = 0.0
        var count = 0
        for (p in probabilities) if (!p.isNaN()) {
            sum += p
            count++
        }
        if (count == 0) return null
        val mean = sum / count
        val biasFrames = SubtitleAudioAligner.DETECTOR_BIAS_MS / frameMs
        val oldLag = ((segment.shiftMs / frameMs) + biasFrames).roundToLong().toInt()
        val newLag = ((newShiftMs / frameMs) + biasFrames).roundToLong().toInt()
        val oldTrack = track.render(from - oldLag, to - oldLag, segment.scale)
        val newTrack = track.render(from - newLag, to - newLag, segment.scale)
        // Best split maximises the suffix sum of (new - old) agreement.
        var suffix = 0.0
        var bestSuffix = 0.0
        var bestIndex = -1
        for (i in probabilities.indices.reversed()) {
            val p = probabilities[i]
            if (!p.isNaN()) suffix += (p - mean) * (newTrack[i] - oldTrack[i])
            if (suffix > bestSuffix) {
                bestSuffix = suffix
                bestIndex = i
            }
        }
        if (bestIndex < 0) return null
        val splitMs = ((from + bestIndex) * frameMs).roundToLong()
        return splitMs.coerceAtLeast(segment.fromMediaMs + MIN_SEGMENT_MS)
    }

    private fun hasEnoughEvidence(
        estimate: SubtitleAudioAligner.Estimate,
        minCues: Int = MIN_CUES,
    ): Boolean = estimate.cueCount >= minCues && estimate.speechSeconds >= MIN_SPEECH_SECONDS

    companion object {
        private val FRAMES_PER_SECOND = 1_000.0 / SpeechTimeline.FRAME_DURATION_MS
        private val MAX_ANALYSIS_FRAMES = (20 * 60 * FRAMES_PER_SECOND).toInt()
        private val MIN_KNOWN_FRAMES = (60 * FRAMES_PER_SECOND).toInt()
        private val MIN_LOCAL_FRAMES = (120 * FRAMES_PER_SECOND).toInt()
        private val LOCAL_BACK_FRAMES = (180 * FRAMES_PER_SECOND).toInt()
        const val MAX_SHIFT_MS = 60_000.0
        private val RATE_CHECK_MIN_FRAMES = (15 * 60 * FRAMES_PER_SECOND).toInt()
        private const val RATE_CHECK_EVERY_RUNS = 6
        private const val NEAR_RATE = 24_000.0 / 23_976.0
        private const val RATE_SWITCH_MARGIN = 0.004
        private const val MIN_CUES = 20
        private const val MIN_HALF_CUES = 8
        private const val HALF_AGREEMENT_MS = 400.0
        private const val NON_STANDARD_RATE_PENALTY = 0.04
        private const val MIN_LOCAL_CUES = 15
        private const val MIN_SPEECH_SECONDS = 12.0
        const val ACCEPT_PROMINENCE = 0.12
        private const val STABLE_SHIFT_TOLERANCE_MS = 250.0
        private const val JUMP_THRESHOLD_MS = 1_000.0
        private const val REFINE_RANGE_MS = 1_500.0
        private const val REFINE_MIN_PROMINENCE = 0.05
        private const val REFINE_MIN_MOVEMENT_MS = 40.0
        private const val MIN_SEGMENT_MS = 30_000L
        private const val NEAR_PEAK_TOLERANCE = 0.01
        private const val RETRACT_AFTER_CUES = 35
        private const val RETRACT_DISAGREEMENT_MS = 1_000.0
        private const val RETRACT_DISAGREEING_RUNS = 3
        private val NON_UNIT_SCALES = SubtitleAudioAligner.CANDIDATE_SCALES.filter { it != 1.0 }.toDoubleArray()
    }
}
