@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.seekpreview.local

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.extractor.ExtractorsFactory
import com.nuvio.app.features.player.seekpreview.SeekPreviewTrack

/** The stream an ExoPlayer is showing, as far as on-device previews need it. */
internal class LocalPreviewSource(
    val sourceKey: String,
    val context: Context,
) {
    @Volatile var released = false
    @Volatile var track: LocalPreviewTrack? = null
}

/**
 * Connects the player to on-device seek previews. The player side makes two calls:
 * [wrapExtractors] (so playback's own keyframes become thumbnails) and [register]/[unregister]
 * around each stream. The preview session then asks [open] for a track.
 */
internal object LocalPreviewSources {
    @Volatile private var current: LocalPreviewSource? = null

    fun register(context: Context, sourceUrl: String): LocalPreviewSource {
        val source = LocalPreviewSource(sourceKey = sourceUrl, context = context.applicationContext)
        current?.takeIf { it.sourceKey != sourceUrl }?.let { stale -> stale.track?.close() }
        current = source
        return source
    }

    fun unregister(source: LocalPreviewSource) {
        source.released = true
        source.track?.close()
        source.track = null
        if (current === source) current = null
    }

    /** A track for the stream now playing, or null when no ExoPlayer stream is registered. */
    fun open(cacheKey: String, durationMs: Long): SeekPreviewTrack? {
        val source = current?.takeIf { !it.released } ?: return null
        source.track?.close()
        val track = LocalPreviewTrack(source, cacheKey, durationMs)
        source.track = track
        track.start()
        return track
    }

    /** Copies the video keyframes of [sourceKey]'s stream to its preview track while demuxed. */
    fun wrapExtractors(factory: ExtractorsFactory, sourceKey: String): ExtractorsFactory =
        VideoKeyframeExtractorsFactory(factory, object : KeyframeSink {
            private fun track() = current?.takeIf { it.sourceKey == sourceKey }?.track

            override fun wantsKeyframes(): Boolean = track()?.wantsKeyframes() == true

            override fun wantsKeyframe(timeUs: Long): Boolean = track()?.wantsKeyframe(timeUs) == true

            override fun onKeyframe(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
                track()?.onKeyframe(format, timeUs, data, offset, size)
            }
        })
}
