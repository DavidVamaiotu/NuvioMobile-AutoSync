package com.nuvio.app.features.player.seekpreview

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Multiplatform port of the Seekr previews SDK (tv.seekr:seekr-core / seekr-android 0.2.0,
 * Apache License 2.0, https://seekr.tv). The SDK is JVM/Android-only (OkHttp + BitmapFactory),
 * so the lookup, VTT parsing and cue resolution are reproduced here on Ktor and Compose
 * ImageBitmaps to run unchanged on Android and iOS.
 */

internal expect fun createSeekrHttpClient(): HttpClient

/** Decodes an encoded sprite sheet (JPEG/PNG/WebP) into a drawable bitmap, or null. */
internal expect fun decodeSeekrSpriteSheet(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap?

/**
 * Identifies the title (or specific episode) to fetch previews for.
 *
 * The Seekr API resolves identifiers in priority order `show_tmdb_id → show_imdb_id →
 * imdb_id → tmdb_id`, so an [Episode] always takes precedence over any movie ids.
 */
internal sealed interface SeekrContent {
    /** A film. At least one of [tmdbId] / [imdbId] is set. */
    data class Movie(val tmdbId: Int? = null, val imdbId: String? = null) : SeekrContent {
        init {
            require(tmdbId != null || imdbId != null) { "SeekrContent.Movie requires a tmdbId or imdbId" }
        }
    }

    /** A single episode of a series; the API returns 400 for a show id without season/episode. */
    data class Episode(
        val showTmdbId: Int? = null,
        val showImdbId: String? = null,
        val season: Int,
        val episode: Int,
    ) : SeekrContent {
        init {
            require(showTmdbId != null || showImdbId != null) { "SeekrContent.Episode requires a showTmdbId or showImdbId" }
        }
    }
}

/**
 * Talks to the two Seekr hosts:
 *  - `api.seekr.tv` for the key-gated `/sprites` lookup and `/v1/keys/validate`.
 *  - whatever host the signed `vtt_url` points at, for the VTT and sprite sheets, which are
 *    signature-only — no API key is sent there.
 *
 * Network failures and "no previews" both surface as `null`, so a missing preview never
 * disturbs playback.
 */
internal class SeekrClient(
    private val apiKey: String,
    private val http: HttpClient = sharedHttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
) {
    private val base = baseUrl.trimEnd('/')

    /** Fetches the preview track for [content]; [durationMs] lets the API align cues to the timeline. */
    suspend fun loadTrack(content: SeekrContent, durationMs: Long): SeekrTrack? = withContext(Dispatchers.Default) {
        val lookup = fetchLookup(content, durationMs) ?: return@withContext null
        // st=1 asks the sprites Worker to serve cue times already on the client timeline
        // (it applies the scale baked into vtt_url), so no scaling happens here.
        val vtt = fetchText(lookup.vttUrl + "&st=1") ?: return@withContext null
        val cues = SeekrVtt.parse(vtt)
        if (cues.isEmpty()) return@withContext null
        SeekrTrack(
            cues = cues,
            sourceDurationMs = lookup.sourceDurationMs,
            scale = lookup.scale,
            sheets = SeekrSheetCache(::fetchBytes),
        )
    }

    /** Verifies the API key against `/v1/keys/validate`, for a clear message in settings. */
    suspend fun validateKey(): Boolean = withContext(Dispatchers.Default) {
        quietly {
            val response = http.get("$base/v1/keys/validate") { header(API_KEY_HEADER, apiKey) }
            if (!response.status.isSuccess()) return@quietly false
            json.decodeFromString(KeyValidation.serializer(), response.bodyAsText()).valid
        } ?: false
    }

    private suspend fun fetchLookup(content: SeekrContent, durationMs: Long): SpriteLookup? = quietly {
        val response = http.get("$base/sprites") {
            header(API_KEY_HEADER, apiKey)
            parameter("duration_ms", durationMs.toString())
            when (content) {
                is SeekrContent.Movie -> {
                    content.imdbId?.let { parameter("imdb_id", it) }
                    content.tmdbId?.let { parameter("tmdb_id", it.toString()) }
                }
                is SeekrContent.Episode -> {
                    content.showTmdbId?.let { parameter("show_tmdb_id", it.toString()) }
                    content.showImdbId?.let { parameter("show_imdb_id", it) }
                    parameter("season", content.season.toString())
                    parameter("episode", content.episode.toString())
                }
            }
        }
        if (!response.status.isSuccess()) return@quietly null
        json.decodeFromString(SpriteLookup.serializer(), response.bodyAsText())
    }

    private suspend fun fetchText(url: String): String? = quietly {
        val response = http.get(url)
        if (response.status.isSuccess()) response.bodyAsText() else null
    }

    private suspend fun fetchBytes(url: String): ByteArray? = withContext(Dispatchers.Default) {
        quietly {
            val response = http.get(url)
            if (response.status.isSuccess()) response.body<ByteArray>() else null
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.seekr.tv/"
        private const val API_KEY_HEADER = "X-API-Key"
        private val json = Json { ignoreUnknownKeys = true }
        private val sharedHttpClient: HttpClient by lazy { createSeekrHttpClient() }
    }
}

private suspend inline fun <T> quietly(block: () -> T?): T? =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

/** `/sprites` response: a signed VTT URL plus the timebase scale factor. */
@Serializable
internal data class SpriteLookup(
    @SerialName("vtt_url") val vttUrl: String,
    @SerialName("scale") val scale: Double = 1.0,
    // 0 when an older backend omits it; treated as "unknown", not a real duration.
    @SerialName("source_duration_ms") val sourceDurationMs: Long = 0,
)

/** `/v1/keys/validate` response. */
@Serializable
internal data class KeyValidation(
    @SerialName("valid") val valid: Boolean = false,
)
