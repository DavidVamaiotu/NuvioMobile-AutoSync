package com.nuvio.app.features.player

import android.app.ActivityManager
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl

/**
 * Applies [PlaybackBufferSettings] to both Android engines, capped to what the device can hold.
 *
 * ExoPlayer keeps its buffer on the Java heap, so it gets at most half of the app's heap;
 * libmpv keeps it in native memory, so it gets at most an eighth of the device's RAM.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object PlaybackBufferAndroid {
    private const val PREFERENCES = "nuvio_playback_buffer_settings"
    private const val BUFFER_MB_KEY = "playback_buffer_mb"
    private const val MB = 1024L * 1024L

    // Nuvio's own values. The back buffer stays at Nuvio's 30 s: it shares ExoPlayer's byte
    // budget, so a longer one could crowd out the data ahead of playback.
    private const val EXO_BACK_BUFFER_MS = 30_000
    private const val NUVIO_EXO_MAX_BUFFER_MS = 70_000

    // With a byte budget the size limit decides how far ahead to read, not the clock.
    private const val EXO_MAX_BUFFER_MS = 30 * 60_000

    // DefaultLoadControl's own video + audio target, which Nuvio uses today.
    private val defaultExoTargetBytes =
        DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE.toLong() + DefaultLoadControl.DEFAULT_AUDIO_BUFFER_SIZE

    private var totalRamBytes = 0L

    fun initialize(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        PlaybackBufferSettings.installPersistence(
            load = { if (preferences.contains(BUFFER_MB_KEY)) preferences.getInt(BUFFER_MB_KEY, 0) else null },
            save = { mb -> preferences.edit().putInt(BUFFER_MB_KEY, mb).apply() },
        )
        val memoryInfo = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memoryInfo)
        totalRamBytes = memoryInfo.totalMem
    }

    private fun budgetBytes(): Long? =
        PlaybackBufferSettings.bufferMb.value.takeIf { it > 0 }?.let { it * MB }

    fun exoLoadControl(): DefaultLoadControl {
        val budget = budgetBytes()
            ?.coerceAtMost(Runtime.getRuntime().maxMemory() / 2)
            ?.takeIf { it > defaultExoTargetBytes }
        val builder = DefaultLoadControl.Builder()
            .setBackBuffer(EXO_BACK_BUFFER_MS, true)
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                if (budget == null) NUVIO_EXO_MAX_BUFFER_MS else EXO_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
        if (budget != null) builder.setTargetBufferBytes(budget.toInt())
        return builder.build()
    }

    /** Forward and back cache for libmpv, or null to keep Nuvio's values. */
    fun mpvCacheBytes(): Pair<Long, Long>? {
        var budget = budgetBytes() ?: return null
        if (totalRamBytes > 0) budget = budget.coerceAtMost(totalRamBytes / 8)
        // Two thirds ahead of playback, one third behind it.
        return budget * 2 / 3 to budget / 3
    }

}
