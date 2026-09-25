package com.nuvio.app.features.player.seekpreview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A set of seek-preview thumbnails queried by playback position: either a Seekr track (sprite
 * sheets from a server) or one generated on the device from the playing stream.
 */
internal interface SeekPreviewTrack {
    /** Signed milliseconds added to a requested position before the lookup (see [SeekrTrack]). */
    var offsetMs: Long

    /** Built from the playing stream itself, so it never needs Preview Sync. */
    val isLocal: Boolean get() = false

    /** Bumped whenever thumbnails are added, so a visible preview re-reads its frames. */
    val revision: StateFlow<Int> get() = StaticRevision

    /** Progress of on-device generation, for the debug readout; null for Seekr. */
    val localStats: StateFlow<LocalSeekPreviewStats>? get() = null

    /** The thumbnail covering [positionMs] (after [offsetMs]) with its cue window, or null. */
    suspend fun thumbnailFor(positionMs: Long): SeekrThumbnail?

    /** Downloads or loads what the track needs ahead of the first lookup. */
    suspend fun prefetch() = Unit

    /** Stops any background work; the track is not used again. */
    fun close() = Unit
}

private val StaticRevision: StateFlow<Int> = MutableStateFlow(0)
