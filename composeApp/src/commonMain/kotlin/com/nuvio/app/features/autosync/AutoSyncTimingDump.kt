package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue

/**
 * Compact, text-free cue timing export for AutoSync debug reports, so a reported run can be
 * replayed offline against [AutoSyncTimelineRetimer].
 *
 * Line format: `TIMING label=<label> sdh=<0|1> n=<count> data=<cues>` where each cue is
 * `<startDelta>.<duration>` in base 36 (start delta from the previous cue's start), suffixed with
 * `~` when the end was estimated, and cues are comma separated.
 */
internal object AutoSyncTimingDump {
    private const val LINE_PREFIX = "TIMING "

    internal data class Track(
        val label: String,
        val cues: List<SubtitleSyncCue>,
        val estimatedEndStartsMs: Set<Long> = emptySet(),
        val sdh: Boolean = false,
    )

    fun encode(track: Track): String = buildString(track.cues.size * 8 + 64) {
        append(LINE_PREFIX)
        append("label=").append(track.label)
        append(" sdh=").append(if (track.sdh) 1 else 0)
        append(" n=").append(track.cues.size)
        append(" data=")
        var previousStartMs = 0L
        track.cues.forEachIndexed { index, cue ->
            if (index > 0) append(',')
            append((cue.startTimeMs - previousStartMs).toString(36))
            append('.')
            append((cue.endTimeMs - cue.startTimeMs).toString(36))
            if (cue.startTimeMs in track.estimatedEndStartsMs) append('~')
            previousStartMs = cue.startTimeMs
        }
    }

    /**
     * Re-runs the V2 pair matcher for [reference] and [target] with the same gates AutoSync uses.
     * The scheduling-only preflight hint is not replayed; the full V2 path is authoritative.
     */
    fun replay(reference: Track, target: Track): AutoSyncTimelineRetimeResult? =
        AutoSyncTimelineRetimer.retime(
            reference = reference.cues,
            target = target.cues,
            coarseScale = 1.0,
            coarseInterceptMs = 0.0,
            discoverAlignment = true,
            allowAmbiguousDelayOnlyMargin = AutoSyncTimelineRetimer.shouldRelaxDelayOnlyMargin(
                sdhReference = reference.sdh,
                referenceSize = reference.cues.size,
                targetSize = target.cues.size,
            ),
            referenceEstimatedEndStartsMs = reference.estimatedEndStartsMs,
        )

    /** Parses every TIMING line of a debug report, keyed by label. Other lines are ignored. */
    fun parseReport(report: String): Map<String, Track> =
        report.lineSequence()
            .mapNotNull { line -> decode(line.substringAfter(LINE_PREFIX, missingDelimiterValue = "")) }
            .associateBy { it.label }

    private fun decode(fields: String): Track? {
        if (fields.isEmpty()) return null
        val values = fields.split(' ')
            .mapNotNull { field ->
                val separator = field.indexOf('=')
                if (separator <= 0) null else field.substring(0, separator) to field.substring(separator + 1)
            }
            .toMap()
        val label = values["label"] ?: return null
        val data = values["data"].orEmpty()

        val cues = ArrayList<SubtitleSyncCue>()
        val estimated = HashSet<Long>()
        var startMs = 0L
        if (data.isNotEmpty()) {
            for (entry in data.split(',')) {
                val isEstimated = entry.endsWith('~')
                val body = if (isEstimated) entry.dropLast(1) else entry
                val startDelta = body.substringBefore('.').toLongOrNull(36) ?: return null
                val duration = body.substringAfter('.', "").toLongOrNull(36) ?: return null
                startMs += startDelta
                cues += SubtitleSyncCue(startMs, startMs + duration, "")
                if (isEstimated) estimated += startMs
            }
        }
        return Track(
            label = label,
            cues = cues,
            estimatedEndStartsMs = estimated,
            sdh = values["sdh"] == "1",
        )
    }
}
