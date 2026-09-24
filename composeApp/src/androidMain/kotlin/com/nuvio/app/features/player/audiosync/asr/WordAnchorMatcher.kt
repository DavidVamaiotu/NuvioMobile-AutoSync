package com.nuvio.app.features.player.audiosync.asr

import kotlin.math.abs

/** A recognised word at media time [timeSec]; [segment] groups words from one speech segment. */
internal data class HeardWord(val timeSec: Double, val text: String, val segment: Int)

/** Media = subtitle time * [scale] + [shiftSec], plus how strongly the heard words support it. */
internal data class AnchorFit(
    val scale: Double,
    val shiftSec: Double,
    val score: Double,
    val segments: Int,
    val runnerUp: Double,
    val spanSec: Double,
    /** Media time (median) of the agreeing words: where [shiftSec] holds exactly. */
    val anchorSec: Double = 0.0,
) {
    val isConfident: Boolean
        get() = segments >= MIN_SEGMENTS && score >= MIN_SCORE && score >= RATIO * maxOf(runnerUp, 0.5)

    companion object {
        private const val MIN_SEGMENTS = 3
        private const val MIN_SCORE = 3.0
        private const val RATIO = 2.5
    }
}

/**
 * Matches recognised words against the words of one same-language subtitle file and finds the
 * mapping most of them agree on. Every recognised word that also occurs in the subtitle (a few
 * times at most) votes for "media time − subtitle time"; neighbouring words that match in order
 * vote much more strongly. A tight cluster of votes from several separate speech segments is an
 * unambiguous anchor, usually after the first handful of lines.
 */
internal class WordAnchorMatcher(cues: List<Triple<Long, Long, String>>) {
    private class SubWord(val timeSec: Double, val word: String, val firstInCue: Boolean)

    private val words = ArrayList<SubWord>()
    private val index = HashMap<String, MutableList<Int>>()

    init {
        for ((startMs, endMs, text) in cues.sortedBy { it.first }) {
            val tokens = tokenize(text)
            if (tokens.isEmpty()) continue
            val durationMs = (endMs - startMs).coerceAtLeast(1L)
            tokens.forEachIndexed { k, word ->
                // Word times inside a line are interpolated; the first word is the cue start exactly.
                val timeSec = (startMs + durationMs * k.toDouble() / tokens.size) / 1_000.0
                index.getOrPut(word) { ArrayList(2) }.add(words.size)
                words += SubWord(timeSec, word, k == 0)
            }
        }
    }

    val isEmpty: Boolean get() = words.isEmpty()

    private class Anchor(val mediaSec: Double, val subSec: Double, val weight: Double, val segment: Int, val first: Boolean)

    fun fit(heard: List<HeardWord>, maxShiftSec: Double = MAX_SHIFT_SEC): AnchorFit? {
        val anchors = anchors(heard)
        if (anchors.isEmpty()) return null
        val unit = cluster(anchors, 1.0, maxShiftSec) ?: return null
        val span = anchors.maxOf { it.mediaSec } - anchors.minOf { it.mediaSec }
        // Over a short stretch every frame rate fits equally well; judge the rate only on a long span.
        if (span < MIN_RATE_SPAN_SEC) return unit
        var best = unit
        for (scale in RATE_CANDIDATES) {
            val fit = cluster(anchors, scale, maxShiftSec) ?: continue
            // A rate change drifts further every minute: it needs words from several places.
            if (fit.segments < MIN_RATE_SEGMENTS) continue
            if (fit.score > best.score * RATE_MARGIN && fit.score > unit.score * RATE_MARGIN) best = fit
        }
        return best
    }

    /**
     * Confident fits of the words heard within each [windowSec] of the film, at [scale]: where a
     * release differs from the subtitle by an inserted or removed scene, the offset differs between
     * regions, which one fit over all words cannot show. Sorted by [AnchorFit.anchorSec].
     */
    fun localFits(heard: List<HeardWord>, scale: Double, windowSec: Double = LOCAL_WINDOW_SEC): List<AnchorFit> {
        val anchors = anchors(heard)
        if (anchors.isEmpty()) return emptyList()
        return anchors.groupBy { (it.mediaSec / windowSec).toInt() }
            .values
            .mapNotNull { region -> cluster(region, scale, MAX_SHIFT_SEC)?.takeIf { it.isConfident } }
            .sortedBy { it.anchorSec }
    }

    private fun anchors(heard: List<HeardWord>): List<Anchor> {
        val normalized = heard.map { it.copy(text = normalize(it.text)) }
        val out = ArrayList<Anchor>()
        for (i in normalized.indices) {
            val h = normalized[i]
            if (h.text.length < 3 || h.text in STOP_WORDS) continue
            val occurrences = index[h.text] ?: continue
            if (occurrences.size > MAX_OCCURRENCES) continue
            for (j in occurrences) {
                var weight = 1.0 / occurrences.size
                val next = normalized.getOrNull(i + 1)
                if (next != null && next.segment == h.segment && words.getOrNull(j + 1)?.word == next.text) weight += 1.0
                val previous = normalized.getOrNull(i - 1)
                if (previous != null && previous.segment == h.segment && words.getOrNull(j - 1)?.word == previous.text) {
                    weight += 1.0
                }
                out += Anchor(h.timeSec, words[j].timeSec, weight, h.segment, words[j].firstInCue)
            }
        }
        return out
    }

    private fun cluster(anchors: List<Anchor>, scale: Double, maxShiftSec: Double): AnchorFit? {
        val entries = anchors
            .map { it to (it.mediaSec - scale * it.subSec) }
            .filter { abs(it.second) <= maxShiftSec }
            .sortedBy { it.second }
        if (entries.isEmpty()) return null
        var bestScore = -1.0
        var bestFrom = 0
        var bestTo = 0
        var bestSegments = 0
        var from = 0
        for (to in entries.indices) {
            while (entries[to].second - entries[from].second > WINDOW_SEC) from++
            val window = entries.subList(from, to + 1)
            val segments = window.map { it.first.segment }.toSet().size
            val score = window.sumOf { it.first.weight } * minOf(segments, 6) / 6.0
            if (score > bestScore) {
                bestScore = score
                bestFrom = from
                bestTo = to
                bestSegments = segments
            }
        }
        val inCluster = entries.subList(bestFrom, bestTo + 1)
        val firsts = inCluster.filter { it.first.first }.map { it.second }
        val center = if (firsts.size >= 2) median(firsts) else median(inCluster.map { it.second })
        // Strongest competing cluster more than 2 s away.
        val far = entries.filter { abs(it.second - center) > 2.0 }
        var runnerUp = 0.0
        var f = 0
        for (t in far.indices) {
            while (far[t].second - far[f].second > WINDOW_SEC) f++
            runnerUp = maxOf(runnerUp, far.subList(f, t + 1).sumOf { it.first.weight })
        }
        val times = inCluster.map { it.first.mediaSec }
        return AnchorFit(
            scale = scale,
            shiftSec = center,
            score = bestScore,
            segments = bestSegments,
            runnerUp = runnerUp,
            spanSec = times.max() - times.min(),
            anchorSec = median(times),
        )
    }

    companion object {
        const val MAX_SHIFT_SEC = 60.0
        private const val WINDOW_SEC = 0.7
        private const val MAX_OCCURRENCES = 6
        private const val MIN_RATE_SPAN_SEC = 180.0
        private const val MIN_RATE_SEGMENTS = 5
        private const val LOCAL_WINDOW_SEC = 120.0
        private const val RATE_MARGIN = 1.3
        private val RATE_CANDIDATES = doubleArrayOf(
            24_000.0 / 23_976.0,
            23_976.0 / 24_000.0,
            25.0 / 23.976,
            23.976 / 25.0,
            25.0 / 24.0,
            24.0 / 25.0,
        )

        private val STOP_WORDS = (
            "the a an and or but to of in on at for is it i you he she we they me my your our his her its " +
                "be are was were am do did not no yes so that this with as what who how why oh uh um"
            ).split(' ').toHashSet()

        private val tagPattern = Regex("<[^>]+>|\\{[^}]*\\}")
        private val nonWord = Regex("[^\\p{L}\\p{Nd}']")

        fun normalize(word: String): String = word.lowercase().replace(nonWord, "").trim('\'')

        fun tokenize(text: String): List<String> =
            text.replace(tagPattern, " ").split(Regex("\\s+")).map(::normalize).filter { it.isNotEmpty() }

        private fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}
