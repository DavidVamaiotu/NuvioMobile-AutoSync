package com.nuvio.app.features.streams

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.CachePolicy
import coil3.request.crossfade

/**
 * Separate ImageLoader for badge images without CacheControlCacheStrategy.
 * Badges are static icons that should stay cached indefinitely.
 */
internal object BadgeImageLoader {

    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: PlatformContext): ImageLoader {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val loader = ImageLoader.Builder(context)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(false)
                .build()
            instance = loader
            return loader
        }
    }
}
