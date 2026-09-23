package com.nuvio.app.features.player.audiosync

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds where a subtitle file lines up with detected speech.
 *
 * For each candidate playback-rate ratio the subtitle track is rendered to the 32 ms speech grid
 * and the masked Pearson correlation against speech probability is computed for every lag with
 * FFTs. The mapping is media time = subtitle time * scale + shift.
 */
internal object SubtitleAudioAligner {
    /** Common frame-rate mismatches between subtitle and video releases. 1.0 must stay first. */
    val CANDIDATE_SCALES = doubleArrayOf(
        1.0,
        24_000.0 / 23_976.0,
        23_976.0 / 24_000.0,
        25.0 / 23.976,
        23.976 / 25.0,
        25.0 / 24.0,
        24.0 / 25.0,
    )

    /**
     * Silero reacts ~45 ms after speech starts and subtitles usually lead speech slightly, so the raw
     * correlation peak sits this much after the subtitle author's intended timing.
     */
    const val DETECTOR_BIAS_MS = 100.0

    /** Excluded neighbourhood around the peak when measuring how much it stands out. */
    private const val PROMINENCE_EXCLUSION_MS = 2_500.0

    /** Peaks this close to either end of the searched shift range are flagged as unreliable. */
    private const val EDGE_MARGIN_MS = 1_500.0

    /** Keeps a mistaken ~0.1% ratio from winning on noise over the plain 1.0 hypothesis. */
    private const val SCALE_PREFERENCE_MARGIN = 0.01

    data class Estimate(
        val scale: Double,
        val shiftMs: Double,
        /** Pearson correlation at the peak. */
        val peak: Double,
        /** Peak minus the best correlation more than 2.5 s away from it. */
        val prominence: Double,
        val analysedSeconds: Double,
        val speechSeconds: Double,
        val cueCount: Int,
        /** True when the peak sits at the edge of the searched range, so it may not be a real maximum. */
        val atSearchEdge: Boolean = false,
    )

    /**
     * @param probabilities speech probability per frame starting at [fromFrame], NaN when unknown.
     * @param minShiftMs lowest shift considered; together with [maxShiftMs] bounds the lag search.
     */
    fun estimate(
        probabilities: FloatArray,
        fromFrame: Int,
        track: SubtitleSpeechTrack,
        scales: DoubleArray = CANDIDATE_SCALES,
        minShiftMs: Double,
        maxShiftMs: Double,
    ): Estimate? {
        val n = probabilities.size
        if (n < 2 || track.size == 0) return null
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val minLag = Math.floorDiv((minShiftMs + DETECTOR_BIAS_MS).roundToInt(), frameMs.toInt())
        val maxLag = Math.floorDiv((maxShiftMs + DETECTOR_BIAS_MS).roundToInt(), frameMs.toInt()) + 1
        val lagCount = maxLag - minLag + 1

        // Centre the known probabilities; unknown frames get weight zero.
        var count = 0
        var sum = 0.0
        for (p in probabilities) if (!p.isNaN()) {
            count++
            sum += p
        }
        if (count < 2) return null
        val mean = sum / count
        var energy = 0.0
        val centered = DoubleArray(n)
        val mask = DoubleArray(n)
        var speechSeconds = 0.0
        for (i in 0 until n) {
            val p = probabilities[i]
            if (p.isNaN()) continue
            val c = p - mean
            centered[i] = c
            mask[i] = 1.0
            energy += c * c
            speechSeconds += p * frameMs / 1_000.0
        }
        if (energy <= 1e-9) return null
        val norm = sqrt(energy)

        // g covers frames [fromFrame - maxLag, fromFrame + n - minLag).
        val gLength = n + lagCount - 1
        val size = Fft.sizeFor(n + gLength)
        val fft = Fft(size)
        // Audio side, packed as (centered + i * mask), shared by every scale.
        val audioRe = DoubleArray(size)
        val audioIm = DoubleArray(size)
        centered.copyInto(audioRe)
        mask.copyInto(audioIm)
        fft.transform(audioRe, audioIm)

        var best: Estimate? = null
        var bestPeak = Double.NEGATIVE_INFINITY
        var unitScale: Estimate? = null
        for (scale in scales) {
            val g = track.render(fromFrame - maxLag, fromFrame - maxLag + gLength, scale)
            val estimate = correlate(
                fft, audioRe, audioIm, g, n, count, norm, minLag, maxLag, scale, frameMs,
            ) ?: continue
            val cues = track.countCuesIn(
                fromMs = fromFrame * frameMs,
                toMs = (fromFrame + n) * frameMs,
                scale = scale,
                shiftMs = estimate.shiftMs,
            )
            val complete = estimate.copy(
                analysedSeconds = count * frameMs / 1_000.0,
                speechSeconds = speechSeconds,
                cueCount = cues,
            )
            if (scale == 1.0) unitScale = complete
            if (complete.peak > bestPeak) {
                bestPeak = complete.peak
                best = complete
            }
        }
        val unit = unitScale
        if (unit != null && best != null && best !== unit && unit.peak >= best.peak - SCALE_PREFERENCE_MARGIN &&
            isNearUnit(best.scale)
        ) {
            return unit
        }
        return best
    }

    private fun isNearUnit(scale: Double): Boolean = abs(scale - 1.0) < 0.002

    private fun correlate(
        fft: Fft,
        audioRe: DoubleArray,
        audioIm: DoubleArray,
        g: DoubleArray,
        n: Int,
        count: Int,
        norm: Double,
        minLag: Int,
        maxLag: Int,
        scale: Double,
        frameMs: Double,
    ): Estimate? {
        val size = fft.size
        // Subtitle side, packed as (g + i * g^2).
        val subRe = DoubleArray(size)
        val subIm = DoubleArray(size)
        for (i in g.indices) {
            subRe[i] = g[i]
            subIm[i] = g[i] * g[i]
        }
        fft.transform(subRe, subIm)

        // Unpack X = FFT(centered), M = FFT(mask), G = FFT(g), G2 = FFT(g^2) from the packed spectra
        // and form conj(X) * G + i * conj(M) * G (-> c1 + i * cm) and conj(M) * G2 (-> cm2).
        val c1cmRe = DoubleArray(size)
        val c1cmIm = DoubleArray(size)
        val cm2Re = DoubleArray(size)
        val cm2Im = DoubleArray(size)
        for (k in 0 until size) {
            val j = if (k == 0) 0 else size - k
            val aRe = audioRe[k]; val aIm = audioIm[k]
            val aRej = audioRe[j]; val aImj = -audioIm[j]
            val xRe = 0.5 * (aRe + aRej); val xIm = 0.5 * (aIm + aImj)
            val mRe = 0.5 * (aIm - aImj); val mIm = -0.5 * (aRe - aRej)
            val sRe = subRe[k]; val sIm = subIm[k]
            val sRej = subRe[j]; val sImj = -subIm[j]
            val gRe = 0.5 * (sRe + sRej); val gIm = 0.5 * (sIm + sImj)
            val g2Re = 0.5 * (sIm - sImj); val g2Im = -0.5 * (sRe - sRej)
            // conj(X) * G
            val p1Re = xRe * gRe + xIm * gIm
            val p1Im = xRe * gIm - xIm * gRe
            // conj(M) * G
            val p2Re = mRe * gRe + mIm * gIm
            val p2Im = mRe * gIm - mIm * gRe
            c1cmRe[k] = p1Re - p2Im
            c1cmIm[k] = p1Im + p2Re
            cm2Re[k] = mRe * g2Re + mIm * g2Im
            cm2Im[k] = mRe * g2Im - mIm * g2Re
        }
        fft.transform(c1cmRe, c1cmIm, inverse = true)
        fft.transform(cm2Re, cm2Im, inverse = true)

        // Index k = maxLag - lag: audio frame i lines up with g[i + k] = subtitle frame (i - lag).
        val lagCount = maxLag - minLag + 1
        val correlation = DoubleArray(lagCount) { Double.NaN }
        val inv = 1.0 / size
        for (k in 0 until lagCount) {
            val c1 = c1cmRe[k] * inv
            val cm = c1cmIm[k] * inv
            val cm2 = cm2Re[k] * inv
            val variance = cm2 - cm * cm / count
            if (variance > 1e-6 * count && cm > 0.5) {
                correlation[k] = c1 / (norm * sqrt(variance))
            }
        }
        var peakIndex = -1
        for (k in 0 until lagCount) {
            val value = correlation[k]
            if (!value.isNaN() && (peakIndex < 0 || value > correlation[peakIndex])) peakIndex = k
        }
        if (peakIndex < 0) return null
        val peak = correlation[peakIndex]
        val exclusion = (PROMINENCE_EXCLUSION_MS / frameMs).toInt()
        var runnerUp = Double.NEGATIVE_INFINITY
        for (k in 0 until lagCount) {
            val value = correlation[k]
            if (!value.isNaN() && abs(k - peakIndex) > exclusion && value > runnerUp) runnerUp = value
        }
        var refinedIndex = peakIndex.toDouble()
        if (peakIndex in 1 until lagCount - 1) {
            val left = correlation[peakIndex - 1]
            val right = correlation[peakIndex + 1]
            val curvature = left - 2 * peak + right
            if (!left.isNaN() && !right.isNaN() && curvature < 0) {
                refinedIndex += (0.5 * (left - right) / curvature).coerceIn(-0.5, 0.5)
            }
        }
        val lagFrames = maxLag - refinedIndex
        val edgeFrames = (EDGE_MARGIN_MS / frameMs).toInt()
        return Estimate(
            scale = scale,
            shiftMs = lagFrames * frameMs - DETECTOR_BIAS_MS,
            peak = peak,
            prominence = if (runnerUp.isFinite()) peak - runnerUp else peak,
            analysedSeconds = n * frameMs / 1_000.0,
            speechSeconds = 0.0,
            cueCount = 0,
            atSearchEdge = peakIndex < edgeFrames || peakIndex >= lagCount - edgeFrames,
        )
    }
}
