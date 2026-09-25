package com.nuvio.app.features.player.seekpreview

import kotlinx.coroutines.flow.StateFlow

/**
 * On-device thumbnails where playback has already been, Seekr everywhere else.
 *
 * The on-device track only has frames for parts of the film that were buffered, now or on an
 * earlier watch. Where it has an exact frame that frame wins, since it always lines up; where
 * it would only offer a nearby stand-in, the Seekr frame is shown instead. The manual sync
 * offset only moves Seekr frames: the on-device ones are already on playback's timeline.
 */
internal class HybridSeekPreviewTrack(
    private val local: SeekPreviewTrack,
    private val seekr: SeekrTrack,
) : SeekPreviewTrack {
    override var offsetMs: Long
        get() = seekr.offsetMs
        set(value) {
            seekr.offsetMs = value
        }

    override val revision: StateFlow<Int> get() = local.revision

    override suspend fun thumbnailFor(positionMs: Long): SeekrThumbnail? {
        val own = local.thumbnailFor(positionMs)
        if (own != null && !own.approximate) return own
        return seekr.thumbnailFor(positionMs) ?: own
    }

    override suspend fun prefetch() {
        local.prefetch()
        seekr.prefetch()
    }

    override fun close() {
        local.close()
        seekr.close()
    }
}
