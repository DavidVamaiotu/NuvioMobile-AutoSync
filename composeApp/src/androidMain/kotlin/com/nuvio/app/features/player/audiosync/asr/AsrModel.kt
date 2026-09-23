package com.nuvio.app.features.player.audiosync.asr

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The English recognition model (about 74 MB), downloaded once on first use and kept in app
 * storage. It is fetched only on an unmetered connection so it never costs mobile data.
 */
internal object AsrModel {
    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"
    const val DOWNLOAD_MB = 74

    private const val TAG = "NuvioAudioSync"
    private const val VERSION = "gigaspeech-2023-12-12"
    private const val MIRROR = "https://github.com/DavidVamaiotu/NuvioMobile-AutoSync/releases/download/asr-models/"
    private const val UPSTREAM = "https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-gigaspeech-2023-12-12/resolve/main/"

    /** Local name to (mirror name, upstream name, expected size in bytes). */
    private val FILES = listOf(
        Triple(ENCODER, "gigaspeech-encoder.int8.onnx", "encoder-epoch-30-avg-1.int8.onnx") to 72_850_738L,
        Triple(DECODER, "gigaspeech-decoder.int8.onnx", "decoder-epoch-30-avg-1.int8.onnx") to 540_688L,
        Triple(JOINER, "gigaspeech-joiner.int8.onnx", "joiner-epoch-30-avg-1.int8.onnx") to 259_417L,
        Triple(TOKENS, "gigaspeech-tokens.txt", "tokens.txt") to 5_020L,
    )

    private val downloading = AtomicBoolean(false)

    fun directory(context: Context): File = File(context.filesDir, "audiosync/asr/$VERSION")

    fun isReady(context: Context): Boolean {
        val dir = directory(context)
        return FILES.all { (names, size) -> File(dir, names.first).length() == size }
    }

    fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return !manager.isActiveNetworkMetered
    }

    /**
     * Downloads missing files. Blocking; call from a background thread. Returns true when the model
     * is ready afterwards.
     */
    fun download(context: Context): Boolean {
        if (isReady(context)) return true
        if (!downloading.compareAndSet(false, true)) return false
        try {
            val dir = directory(context).apply { mkdirs() }
            for ((names, size) in FILES) {
                val (local, mirrorName, upstreamName) = names
                val file = File(dir, local)
                if (file.length() == size) continue
                val ok = fetch(MIRROR + mirrorName, file, size) || fetch(UPSTREAM + upstreamName, file, size)
                if (!ok) return false
            }
            return isReady(context)
        } finally {
            downloading.set(false)
        }
    }

    private fun fetch(url: String, target: File, expectedSize: Long): Boolean {
        val partial = File(target.path + ".part")
        return try {
            var connection = URL(url).openConnection() as HttpURLConnection
            var redirects = 0
            // HttpURLConnection does not follow cross-host redirects (GitHub release -> CDN).
            while (connection.responseCode in 300..399 && redirects < 5) {
                val location = connection.getHeaderField("Location") ?: break
                connection.disconnect()
                connection = URL(URL(url), location).openConnection() as HttpURLConnection
                redirects++
            }
            if (connection.responseCode != 200) {
                Log.w(TAG, "model download ${connection.responseCode} for $url")
                connection.disconnect()
                return false
            }
            connection.inputStream.use { input ->
                partial.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            connection.disconnect()
            if (partial.length() != expectedSize) {
                Log.w(TAG, "model download size ${partial.length()} != $expectedSize for $url")
                partial.delete()
                return false
            }
            partial.renameTo(target)
        } catch (error: Exception) {
            Log.w(TAG, "model download failed for $url: ${error.message}")
            partial.delete()
            false
        }
    }
}
