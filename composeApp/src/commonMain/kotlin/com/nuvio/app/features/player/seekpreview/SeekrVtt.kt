package com.nuvio.app.features.player.seekpreview

/** A sprite rectangle: which sheet to load and the region of it holding one thumbnail. */
internal data class SeekrTile(
    val sheetUrl: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

/** One parsed WebVTT cue: a time window mapped to a sprite tile. */
internal data class SeekrVttCue(
    val startMs: Long,
    val endMs: Long,
    val tile: SeekrTile,
)

/**
 * Parser for the Seekr WebVTT sprite manifest.
 *
 * Plain WebVTT: a `WEBVTT` header, then one cue per thumbnail. Each cue is a timing line
 * (`START --> END`) followed by one payload line — the signed tile URL with a trailing
 * `#xywh=x,y,w,h` fragment.
 */
internal object SeekrVtt {

    fun parse(vtt: String): List<SeekrVttCue> {
        val cues = ArrayList<SeekrVttCue>()
        val lines = vtt.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (!line.contains("-->")) continue
            val parts = line.split("-->")
            if (parts.size != 2) continue
            val startMs = parseTime(parts[0].trim())
            val endMs = parseTime(parts[1].trim())
            val payload = lines.drop(i + 1).firstOrNull { it.isNotBlank() }?.trim()
            val tile = payload?.let(::parseTile) ?: continue
            cues += SeekrVttCue(startMs = startMs, endMs = endMs, tile = tile)
        }
        // The position lookup binary-searches, so guarantee ordering rather than trust the source.
        cues.sortBy { it.startMs }
        return cues
    }

    private fun parseTile(payload: String): SeekrTile? {
        val hashIdx = payload.lastIndexOf('#')
        if (hashIdx < 0) return null
        val fragment = payload.substring(hashIdx + 1)
        if (!fragment.startsWith("xywh=")) return null
        val coords = fragment.removePrefix("xywh=").split(",")
        if (coords.size < 4) return null
        return SeekrTile(
            sheetUrl = payload.substring(0, hashIdx),
            x = coords[0].trim().toIntOrNull() ?: return null,
            y = coords[1].trim().toIntOrNull() ?: return null,
            w = coords[2].trim().toIntOrNull() ?: return null,
            h = coords[3].trim().toIntOrNull() ?: return null,
        )
    }

    private fun parseTime(time: String): Long {
        val parts = time.split(":")
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: 0L
                val m = parts[1].toLongOrNull() ?: 0L
                val s = parts[2].toDoubleOrNull() ?: 0.0
                h * 3_600_000L + m * 60_000L + (s * 1000).toLong()
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: 0L
                val s = parts[1].toDoubleOrNull() ?: 0.0
                m * 60_000L + (s * 1000).toLong()
            }
            else -> 0L
        }
    }
}
