@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.audiosync

import android.media.MediaFormat
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.MediaExtractorCompat
import androidx.media3.extractor.ExtractorsFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Reads a few short stretches of a film's audio far from the playhead, over its own connection, so
 * sync has evidence from across the film within seconds instead of only what playback has reached.
 *
 * The audio itself is delivered by [extractorsFactory] (wrapped with an audio tap) while this class
 * only seeks and advances. Containers interleave audio with video, so each spot costs its share of
 * the whole stream: spots are cut short to stay within [maxBytes] in total. Blocking; run it on a
 * background thread.
 */
internal class AudioSpotSampler(
    private val uri: Uri,
    dataSourceFactory: DataSource.Factory,
    private val extractorsFactory: ExtractorsFactory,
    private val maxBytes: Long,
) {
    class Result(val sampled: Int, val bytes: Long, val failure: String?)

    private val bytes = AtomicLong()
    private val countingFactory = DataSource.Factory {
        dataSourceFactory.createDataSource().apply { addTransferListener(ByteCounter(bytes)) }
    }

    /**
     * Reads [spotMs] of audio from each of [spotsMs] in order, until done, [isCancelled] or out of
     * budget. [onSpot] reports progress after each spot.
     */
    fun run(
        spotsMs: List<Long>,
        spotMs: Long,
        isCancelled: () -> Boolean,
        onSpot: (sampled: Int, bytes: Long) -> Unit,
    ): Result {
        val extractor = MediaExtractorCompat(extractorsFactory, countingFactory)
        var sampled = 0
        try {
            extractor.setDataSource(uri, 0L)
            var audioTracks = 0
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    extractor.selectTrack(index)
                    audioTracks++
                }
            }
            if (audioTracks == 0) return Result(0, bytes.get(), "no audio track")
            for ((index, spotStartMs) in spotsMs.withIndex()) {
                if (isCancelled()) break
                val remaining = maxBytes - bytes.get()
                if (remaining <= 0) break
                val budget = remaining / (spotsMs.size - index)
                val spotStartBytes = bytes.get()
                extractor.seekTo(spotStartMs * 1_000L, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
                val firstUs = extractor.sampleTime
                if (firstUs < 0 || abs(firstUs / 1_000L - spotStartMs) > MAX_SEEK_MISS_MS) {
                    return Result(sampled, bytes.get(), "stream cannot seek")
                }
                val endUs = (spotStartMs + spotMs) * 1_000L
                while (!isCancelled()) {
                    val timeUs = extractor.sampleTime
                    if (timeUs < 0 || timeUs >= endUs) break
                    if (bytes.get() - spotStartBytes >= budget) break
                    if (!extractor.advance()) break
                }
                sampled++
                onSpot(sampled, bytes.get())
            }
            return Result(sampled, bytes.get(), null)
        } catch (error: Exception) {
            return Result(sampled, bytes.get(), error.message ?: error.javaClass.simpleName)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private class ByteCounter(private val bytes: AtomicLong) : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            bytes.addAndGet(bytesTransferred.toLong())
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    private companion object {
        /** A seek landing further than this from the target means the stream has no usable index. */
        const val MAX_SEEK_MISS_MS = 30_000L
    }
}
