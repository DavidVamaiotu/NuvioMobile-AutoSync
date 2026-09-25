@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.seekpreview.local

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.ExtractorsFactory
import com.nuvio.app.features.player.seekpreview.SeekPreviewTrack

/**
 * The stream an ExoPlayer is showing, as far as on-device previews need it: where to read it
 * from in the background, and whether playback is ready or starving.
 */
internal class LocalPreviewSource(
    val sourceKey: String,
    val context: Context,
    /** Where background fill reads from; null when it must not run (see [backgroundBlockedReason]). */
    val uri: Uri?,
    val dataSourceFactory: DataSource.Factory?,
    val backgroundBlockedReason: String?,
) {
    /** Uptime at which playback first became ready, or 0 before that. */
    @Volatile var readyAtMs = 0L
    @Volatile var buffering = false
    @Volatile var released = false
    @Volatile var track: LocalPreviewTrack? = null
    var listener: Player.Listener? = null
}

/**
 * Connects the player to on-device seek previews. The player side makes two calls:
 * [wrapExtractors] (so playback's own keyframes become thumbnails) and [register]/[unregister]
 * around each stream. The preview session then asks [open] for a track.
 */
internal object LocalPreviewSources {
    @Volatile private var current: LocalPreviewSource? = null

    fun register(
        context: Context,
        player: ExoPlayer,
        sourceUrl: String,
        dataSourceFactory: DataSource.Factory?,
    ): LocalPreviewSource {
        val uri = runCatching { Uri.parse(sourceUrl) }.getOrNull()
        val path = uri?.path.orEmpty().lowercase()
        val host = uri?.host.orEmpty()
        val blocked = when {
            dataSourceFactory == null -> "no data source"
            uri == null || (uri.scheme != "http" && uri.scheme != "https") -> "not an http stream"
            // Seeking a torrent far ahead makes the engine fetch those pieces, slowing playback.
            host == "localhost" || host.startsWith("127.") || host == "[::1]" -> "torrent: buffer only"
            path.endsWith(".m3u8") || path.endsWith(".mpd") -> "playlist: buffer only"
            else -> null
        }
        val source = LocalPreviewSource(
            sourceKey = sourceUrl,
            context = context.applicationContext,
            uri = uri.takeIf { blocked == null },
            dataSourceFactory = dataSourceFactory.takeIf { blocked == null },
            backgroundBlockedReason = blocked,
        )
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = update(playbackState)

            fun update(playbackState: Int) {
                if (playbackState == Player.STATE_READY && source.readyAtMs == 0L) {
                    source.readyAtMs = SystemClock.uptimeMillis()
                }
                source.buffering = playbackState == Player.STATE_BUFFERING
            }
        }
        listener.update(player.playbackState)
        player.addListener(listener)
        source.listener = listener
        current?.takeIf { it.sourceKey != sourceUrl }?.let { stale -> stale.track?.close() }
        current = source
        return source
    }

    fun unregister(source: LocalPreviewSource, player: ExoPlayer) {
        source.released = true
        source.listener?.let(player::removeListener)
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
