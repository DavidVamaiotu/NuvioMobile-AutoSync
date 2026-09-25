package com.nuvio.app.features.player.seekpreview

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

internal actual fun createSeekrHttpClient(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
}

internal actual fun openLocalSeekPreviewTrack(cacheKey: String, durationMs: Long): SeekPreviewTrack? =
    com.nuvio.app.features.player.seekpreview.local.LocalPreviewSources.open(cacheKey, durationMs)

internal actual fun decodeSeekrSpriteSheet(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
