package com.nuvio.app.features.player.seekpreview

import androidx.compose.ui.graphics.ImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

// Seek previews are Android-only for now; nothing on iOS requests a track, so these only need
// to compile.
internal actual fun createSeekrHttpClient(): HttpClient = HttpClient(Darwin) {
    expectSuccess = false
}

internal actual fun decodeSeekrSpriteSheet(bytes: ByteArray): ImageBitmap? = null
