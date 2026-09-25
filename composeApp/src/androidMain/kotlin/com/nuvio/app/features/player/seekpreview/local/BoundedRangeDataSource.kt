@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.seekpreview.local

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Turns open-ended reads into small HTTP byte ranges.
 *
 * The thumbnail sampler seeks to a keyframe, reads one sample and seeks away again. An
 * open-ended request keeps the server sending (and the socket buffering) far past the sample,
 * which is pure waste. Here each request asks for [initialChunk] bytes; a read that keeps going
 * sequentially doubles the next chunk up to [maxChunk], so container headers and indexes still
 * load in a few requests.
 */
internal class BoundedRangeDataSourceFactory(
    private val upstream: DataSource.Factory,
    private val initialChunk: Long = 256L * 1024L,
    private val maxChunk: Long = 4L * 1024L * 1024L,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        BoundedRangeDataSource(upstream.createDataSource(), initialChunk, maxChunk)
}

private class BoundedRangeDataSource(
    private val upstream: DataSource,
    private val initialChunk: Long,
    private val maxChunk: Long,
) : DataSource {
    private var spec: DataSpec? = null
    /** Absolute position of the next byte to deliver. */
    private var position = 0L
    /** Bytes still wanted by the caller, or LENGTH_UNSET for "until the end". */
    private var remaining = C.LENGTH_UNSET.toLong()
    private var chunk = initialChunk
    private var chunkLeft = 0L
    private var upstreamOpen = false
    private var ended = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme
        if (scheme != "http" && scheme != "https") {
            // Local files and content URIs have no read-ahead cost to trim.
            spec = null
            return upstream.open(dataSpec)
        }
        spec = dataSpec
        position = dataSpec.position
        remaining = dataSpec.length
        chunk = initialChunk
        ended = false
        openChunk()
        // Report the caller's own length when known; otherwise the size is only known per chunk.
        return dataSpec.length
    }

    private fun openChunk(): Long {
        val base = spec ?: error("not open")
        val length = if (remaining == C.LENGTH_UNSET.toLong()) chunk else minOf(chunk, remaining)
        val opened = upstream.open(base.buildUpon().setPosition(position).setLength(length).build())
        upstreamOpen = true
        chunkLeft = if (opened == C.LENGTH_UNSET.toLong()) length else minOf(opened, length)
        return opened
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (spec == null) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (ended || remaining == 0L) return C.RESULT_END_OF_INPUT
        var read = upstream.read(buffer, offset, length)
        if (read == C.RESULT_END_OF_INPUT) {
            upstream.close()
            upstreamOpen = false
            // A short chunk means the resource ended; a full one means keep going.
            if (chunkLeft > 0L) {
                ended = true
                return C.RESULT_END_OF_INPUT
            }
            chunk = (chunk * 2).coerceAtMost(maxChunk)
            try {
                openChunk()
            } catch (error: Exception) {
                // A server that rejects a range past the end (416) has simply run out.
                ended = true
                return C.RESULT_END_OF_INPUT
            }
            read = upstream.read(buffer, offset, length)
            if (read == C.RESULT_END_OF_INPUT) {
                ended = true
                return C.RESULT_END_OF_INPUT
            }
        }
        position += read
        chunkLeft -= read
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
        return read
    }

    override fun getUri(): Uri? = upstream.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (upstreamOpen || spec == null) upstream.close()
        upstreamOpen = false
        spec = null
    }
}
