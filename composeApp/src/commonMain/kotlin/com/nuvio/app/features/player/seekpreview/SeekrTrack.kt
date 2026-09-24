package com.nuvio.app.features.player.seekpreview

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.concurrent.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A preview thumbnail: the sprite [sheet] plus the region of it holding this frame, together
 * with the resolved cue window it was drawn from.
 *
 * [cueStartMs] is the timestamp the frame represents, not the exact position asked for — a
 * request for 163_174 may resolve to a cue covering 160_000..170_000. It is the cue *grid*
 * time today; the generator snaps extraction to a keyframe within ±3s of it.
 */
internal class SeekrThumbnail(
    val sheet: ImageBitmap,
    val srcOffset: IntOffset,
    val srcSize: IntSize,
    val cueStartMs: Long,
    val cueEndMs: Long,
)

/**
 * The full set of preview thumbnails for one title, queried by playback position. Held for the
 * whole playback session.
 *
 * ### Preview sync offset
 * Sprite sheets are generated from one release of a title, but the release being played can
 * have a different head (a distributor logo, black frames). The backend never rescales the
 * preview timeline, so this shows up as a constant offset — conceptually a subtitle delay.
 * [offsetMs] is added to the position *before* the cue lookup: **negative** when the played
 * release has extra head content (a scene at 10:00 in the source sits at 10:30 in playback →
 * `-30_000`), **positive** for the reverse. In both directions the value is
 * `sourceDurationMs - durationMs`.
 */
internal class SeekrTrack(
    private val cues: List<SeekrVttCue>,
    /** Duration of the media the sprites were generated from; 0 when the backend omits it. */
    val sourceDurationMs: Long = 0,
    /** Timebase scale reported by the backend, already applied server-side. Diagnostics only. */
    val scale: Double = 1.0,
    private val sheets: SeekrSheetCache,
) {
    /** Signed milliseconds added to a requested position before the cue lookup. Safe to set from the UI. */
    @Volatile
    var offsetMs: Long = 0L

    val isEmpty: Boolean get() = cues.isEmpty()

    /** All distinct sprite-sheet URLs referenced by this track, in cue order. */
    val sheetUrls: Set<String> get() = cues.mapTo(LinkedHashSet()) { it.tile.sheetUrl }

    /** Downloads every sprite sheet in parallel so later lookups never wait on the network. */
    suspend fun prefetchSheets() {
        coroutineScope {
            sheetUrls.map { url -> async { sheets.prefetch(url) } }.awaitAll()
        }
    }

    /** The thumbnail covering [positionMs] (after [offsetMs]) with its cue window, or null. */
    suspend fun thumbnailFor(positionMs: Long): SeekrThumbnail? {
        val cue = resolveCue(positionMs) ?: return null
        val sheet = sheets.get(cue.tile.sheetUrl) ?: return null
        val tile = cue.tile
        val tileW = tile.w.takeIf { it > 0 } ?: 320
        val tileH = tile.h.takeIf { it > 0 } ?: 180
        val x = tile.x.coerceIn(0, (sheet.width - 1).coerceAtLeast(0))
        val y = tile.y.coerceIn(0, (sheet.height - 1).coerceAtLeast(0))
        val w = tileW.coerceAtMost((sheet.width - x).coerceAtLeast(1))
        val h = tileH.coerceAtMost((sheet.height - y).coerceAtLeast(1))
        return SeekrThumbnail(
            sheet = sheet,
            srcOffset = IntOffset(x, y),
            srcSize = IntSize(w, h),
            cueStartMs = cue.startMs,
            cueEndMs = cue.endMs,
        )
    }

    /**
     * The last cue whose start is at or before [positionMs] + [offsetMs]. Positions before the
     * first cue or past the last clamp to the first or last cue, so the offset alone never
     * produces null; null only when the track is empty.
     */
    internal fun resolveCue(positionMs: Long): SeekrVttCue? {
        if (cues.isEmpty()) return null
        val correctedPositionMs = positionMs + offsetMs
        var lo = 0
        var hi = cues.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= correctedPositionMs) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (idx >= 0) cues[idx] else cues.first()
    }
}

/**
 * Sprite sheets keyed by URL. Every sheet's encoded bytes are kept for the session so
 * scrubbing never re-downloads, while only the most recently used [maxDecodedSheets] are held
 * decoded — a film's worth of full-size decoded sheets is too much memory for a phone.
 * Per-URL locks let different sheets download in parallel.
 */
internal class SeekrSheetCache(
    private val download: suspend (String) -> ByteArray?,
    private val decode: (ByteArray) -> ImageBitmap? = ::decodeSeekrSpriteSheet,
    private val maxDecodedSheets: Int = 4,
) {
    private val guard = Mutex()
    private val locks = mutableMapOf<String, Mutex>()
    private val encoded = mutableMapOf<String, ByteArray>()
    private val decoded = LinkedHashMap<String, ImageBitmap>()

    suspend fun prefetch(url: String) {
        bytesFor(url)
    }

    suspend fun get(url: String): ImageBitmap? {
        guard.withLock { decoded.remove(url)?.also { decoded[url] = it } }?.let { return it }
        return lockFor(url).withLock {
            guard.withLock { decoded[url] }?.let { return@withLock it }
            val bytes = bytesFor(url) ?: return@withLock null
            val bitmap = withContext(Dispatchers.Default) { runCatching { decode(bytes) }.getOrNull() }
                ?: return@withLock null
            guard.withLock {
                decoded[url] = bitmap
                while (decoded.size > maxDecodedSheets) decoded.remove(decoded.keys.first())
            }
            bitmap
        }
    }

    private suspend fun bytesFor(url: String): ByteArray? {
        guard.withLock { encoded[url] }?.let { return it }
        return lockFor("bytes:$url").withLock {
            guard.withLock { encoded[url] }?.let { return@withLock it }
            val bytes = download(url) ?: return@withLock null
            guard.withLock { encoded[url] = bytes }
            bytes
        }
    }

    private suspend fun lockFor(key: String): Mutex = guard.withLock { locks.getOrPut(key) { Mutex() } }
}
