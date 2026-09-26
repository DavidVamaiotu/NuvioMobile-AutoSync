@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.TreeSet

/**
 * Disk seek cache for ExoPlayer, sized by [PlaybackBufferSettings].
 *
 * ExoPlayer's own buffer lives on the Java heap, so it is capped well below the setting and only
 * keeps 30 s behind playback. Everything playback downloads is also written here, so seeking back
 * into anything already watched this session reads from disk instead of the network. It adds no
 * requests of its own: only what playback fetches anyway is stored.
 *
 * Only the stream now playing is kept: other streams are dropped when a new one starts, and the
 * whole folder is deleted at launch while the setting is on Nuvio's default.
 */
internal object PlaybackSeekCache {
    private const val TAG = "PlaybackSeekCache"
    private const val DIR = "seek_cache"
    private const val MB = 1024L * 1024L

    private var cache: SimpleCache? = null

    private fun cacheDir(context: Context) = File(context.applicationContext.cacheDir, DIR)

    /** Called at launch: removes a leftover cache when the setting no longer uses one. */
    fun cleanUpIfDisabled(context: Context) {
        if (PlaybackBufferSettings.bufferMb.value > 0) return
        val dir = cacheDir(context)
        if (!dir.exists()) return
        Thread({ runCatching { dir.deleteRecursively() } }, "NuvioSeekCacheCleanup").apply {
            isDaemon = true
        }.start()
    }

    /**
     * [upstream] with the disk cache in front of it, or [upstream] itself when the setting is on
     * Nuvio's default or [cacheable] is false (local sources that are already on the device).
     */
    fun wrap(context: Context, sourceUrl: String, cacheable: Boolean, upstream: DataSource.Factory): DataSource.Factory {
        if (!cacheable || PlaybackBufferSettings.bufferMb.value <= 0) return upstream
        updateLimit(context)
        val cache = obtain(context) ?: return upstream
        dropOtherStreams(cache, keep = sourceUrl)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    @Synchronized
    private fun obtain(context: Context): SimpleCache? {
        cache?.let { return it }
        return runCatching {
            val app = context.applicationContext
            SimpleCache(cacheDir(app), SizeEvictor { limitBytes }, StandaloneDatabaseProvider(app))
        }.onFailure { Log.w(TAG, "seek cache unavailable", it) }
            .getOrNull()
            ?.also { cache = it }
    }

    /** The chosen size, but never more than half the free space on the device. */
    private fun updateLimit(context: Context) {
        val chosen = PlaybackBufferSettings.bufferMb.value.coerceAtLeast(0) * MB
        val free = runCatching { context.applicationContext.cacheDir.usableSpace }.getOrDefault(0L)
        limitBytes = if (free > 0) minOf(chosen, free / 2) else chosen
    }

    @Volatile private var limitBytes = 0L

    /** Removes what earlier streams left, off the main thread (it deletes files). */
    private fun dropOtherStreams(cache: SimpleCache, keep: String) {
        val stale = runCatching { cache.keys.filter { it != keep } }.getOrDefault(emptyList())
        if (stale.isEmpty()) return
        Thread({
            stale.forEach { key -> runCatching { cache.removeResource(key) } }
        }, "NuvioSeekCacheCleanup").apply { isDaemon = true }.start()
    }

    /** Least recently used first, against a limit read on each check so setting changes apply. */
    private class SizeEvictor(private val limit: () -> Long) : CacheEvictor {
        private val spans = TreeSet<CacheSpan> { a, b ->
            when {
                a.lastTouchTimestamp != b.lastTouchTimestamp -> a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
                else -> a.compareTo(b)
            }
        }
        private var size = 0L

        override fun requiresCacheSpanTouches() = true

        override fun onCacheInitialized() = Unit

        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
            if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
        }

        override fun onSpanAdded(cache: Cache, span: CacheSpan) {
            spans.add(span)
            size += span.length
            evict(cache, 0)
        }

        override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
            spans.remove(span)
            size -= span.length
        }

        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
            onSpanRemoved(cache, oldSpan)
            onSpanAdded(cache, newSpan)
        }

        private fun evict(cache: Cache, needed: Long) {
            val max = limit()
            while (size + needed > max && spans.isNotEmpty()) {
                cache.removeSpan(spans.first())
            }
        }
    }
}
