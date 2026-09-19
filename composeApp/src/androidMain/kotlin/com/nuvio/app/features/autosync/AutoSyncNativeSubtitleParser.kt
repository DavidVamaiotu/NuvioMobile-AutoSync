@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.autosync

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import java.util.LinkedHashMap
import java.util.UUID
import androidx.media3.common.util.Consumer
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Injects V2-retimed cue timestamps before Media3's native TextRenderer sees
 * the side-loaded subtitle. Text, styling and rendering stay native.
 */
internal object AutoSyncNativeSubtitleTimelines {
    private const val MAX_ENTRIES = 16
    private const val FORMAT_ID_PREFIX = "nuvio-autosync:"

    private val lock = Any()
    private val timelines =
        object : LinkedHashMap<String, AutoSyncTimelineRetimeResult>(
            MAX_ENTRIES,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, AutoSyncTimelineRetimeResult>?,
            ): Boolean = size > MAX_ENTRIES
        }

    fun formatId(url: String): String =
        FORMAT_ID_PREFIX + UUID.nameUUIDFromBytes(url.toByteArray(Charsets.UTF_8)).toString()

    fun register(url: String, timeline: AutoSyncTimelineRetimeResult) {
        synchronized(lock) { timelines[formatId(url)] = timeline }
    }

    fun clear(url: String) {
        synchronized(lock) { timelines.remove(formatId(url)) }
    }

    fun resolve(format: Format): AutoSyncTimelineRetimeResult? {
        val id = format.id ?: return null
        if (!id.startsWith(FORMAT_ID_PREFIX)) return null
        return synchronized(lock) { timelines[id] }
    }
}

internal class AutoSyncSubtitleParserFactory(
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
) : SubtitleParser.Factory {
    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int =
        delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser {
        val parser = delegate.create(format)
        val timeline = AutoSyncNativeSubtitleTimelines.resolve(format) ?: return parser
        return RetimingSubtitleParser(parser, timeline)
    }
}

private class RetimingSubtitleParser(
    private val delegate: SubtitleParser,
    private val timeline: AutoSyncTimelineRetimeResult,
) : SubtitleParser {
    private companion object {
        const val START_MATCH_TOLERANCE_MS = 500L
    }

    private val retimedCues = timeline.cues.sortedBy { it.originalStartTimeMs }
    private val exactByStart = retimedCues.groupBy { it.originalStartTimeMs }

    override fun getCueReplacementBehavior(): Int = delegate.getCueReplacementBehavior()

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) {
        delegate.parse(
            data,
            offset,
            length,
            outputOptions,
            Consumer { parsed -> output.accept(retime(parsed)) },
        )
    }

    override fun reset() {
        delegate.reset()
    }

    private fun retime(parsed: CuesWithTiming): CuesWithTiming {
        if (parsed.startTimeUs == C.TIME_UNSET) return parsed

        val originalStartMs = parsed.startTimeUs / 1_000L
        val originalEndMs = when {
            parsed.endTimeUs != C.TIME_UNSET -> parsed.endTimeUs / 1_000L
            parsed.durationUs != C.TIME_UNSET ->
                originalStartMs + parsed.durationUs / 1_000L
            else -> originalStartMs + 1L
        }.coerceAtLeast(originalStartMs + 1L)

        val mapped = findMappedCue(originalStartMs, originalEndMs)
        val newStartMs: Long
        val newEndMs: Long
        if (mapped != null) {
            newStartMs = mapped.startTimeMs
            newEndMs = mapped.endTimeMs.coerceAtLeast(newStartMs + 1L)
        } else {
            // If a parser merges/splits cues differently, preserve V2's global corridor.
            newStartMs = transform(originalStartMs)
            newEndMs = transform(originalEndMs).coerceAtLeast(newStartMs + 1L)
        }

        return CuesWithTiming(
            parsed.cues,
            newStartMs.coerceAtLeast(0L) * 1_000L,
            (newEndMs - newStartMs).coerceAtLeast(1L) * 1_000L,
        )
    }

    private fun findMappedCue(
        originalStartMs: Long,
        originalEndMs: Long,
    ): AutoSyncRetimedCue? {
        exactByStart[originalStartMs]?.let { exact ->
            return exact.minByOrNull { cue -> abs(cue.originalEndTimeMs - originalEndMs) }
        }
        if (retimedCues.isEmpty()) return null

        var low = 0
        var high = retimedCues.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (retimedCues[mid].originalStartTimeMs < originalStartMs) low = mid + 1 else high = mid
        }

        val first = (low - 2).coerceAtLeast(0)
        val last = (low + 2).coerceAtMost(retimedCues.lastIndex)
        if (first > last) return null

        var best: AutoSyncRetimedCue? = null
        var bestScore = Long.MAX_VALUE
        for (index in first..last) {
            val cue = retimedCues[index]
            val startError = abs(cue.originalStartTimeMs - originalStartMs)
            if (startError > START_MATCH_TOLERANCE_MS) continue
            val endError = abs(cue.originalEndTimeMs - originalEndMs)
            val score = startError * 4L + endError.coerceAtMost(2_000L)
            if (score < bestScore) {
                best = cue
                bestScore = score
            }
        }
        return best
    }

    private fun transform(timeMs: Long): Long =
        (timeMs.toDouble() * timeline.alignmentScale + timeline.alignmentInterceptMs)
            .roundToLong()
            .coerceAtLeast(0L)
}
