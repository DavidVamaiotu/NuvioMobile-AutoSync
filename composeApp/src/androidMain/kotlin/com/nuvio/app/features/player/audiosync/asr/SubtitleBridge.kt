package com.nuvio.app.features.player.audiosync.asr

import com.nuvio.app.features.player.audiosync.SpeechTimeline
import com.nuvio.app.features.player.audiosync.SubtitleAudioAligner
import com.nuvio.app.features.player.audiosync.SubtitleSpeechTrack

/** Reference time = target subtitle time * [scale] + [shiftSec]. */
internal data class BridgeFit(val scale: Double, val shiftSec: Double, val peak: Double, val prominence: Double)

/**
 * Aligns a subtitle in any language to a reference subtitle of the same film by their timing
 * patterns alone. Translations are usually made from an English file and keep its line breaks, so
 * the two on-screen patterns match almost exactly. No audio is needed, so this runs in a few
 * milliseconds as soon as both files are downloaded.
 */
internal object SubtitleBridge {
    private const val MIN_PROMINENCE = 0.15
    private const val MIN_PEAK = 0.35

    fun align(target: SubtitleSpeechTrack, reference: SubtitleSpeechTrack): BridgeFit? {
        if (target.size == 0 || reference.size == 0) return null
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val endMs = reference.endsMs.max()
        val frames = (endMs / frameMs).toInt() + 1
        // The reference's on-screen coverage stands in for "speech" in the audio aligner.
        val coverage = reference.render(0, frames, 1.0)
        val probabilities = FloatArray(frames) { coverage[it].toFloat() }
        val estimate = SubtitleAudioAligner.estimate(
            probabilities = probabilities,
            fromFrame = 0,
            track = target,
            minShiftMs = -WordAnchorMatcher.MAX_SHIFT_SEC * 1_000.0,
            maxShiftMs = WordAnchorMatcher.MAX_SHIFT_SEC * 1_000.0,
            detectorBiasMs = 0.0,
        ) ?: return null
        if (estimate.atSearchEdge || estimate.peak < MIN_PEAK || estimate.prominence < MIN_PROMINENCE) return null
        return BridgeFit(estimate.scale, estimate.shiftMs / 1_000.0, estimate.peak, estimate.prominence)
    }
}
