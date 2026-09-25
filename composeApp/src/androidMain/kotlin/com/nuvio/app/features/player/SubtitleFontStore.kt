package com.nuvio.app.features.player

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

internal class CustomSubtitleFont(
    val file: File,
    /** Family name from the font's `name` table; libass (mpv) matches fonts by it. */
    val familyName: String,
    val typeface: Typeface,
)

/**
 * Keeps at most one user-imported subtitle font in app storage. The font file itself is
 * the persisted setting, so nothing is added to [PlayerSettingsRepository].
 */
internal object SubtitleFontStore {
    private const val TAG = "SubtitleFontStore"
    private const val MAX_FONT_BYTES = 30L * 1024 * 1024

    private val _font = MutableStateFlow<CustomSubtitleFont?>(null)
    val font: StateFlow<CustomSubtitleFont?> = _font.asStateFlow()

    @Volatile
    private var loaded = false

    fun fontsDir(context: Context): File =
        File(context.applicationContext.filesDir, "subtitle_fonts").apply { mkdirs() }

    /** Returns the imported font, loading it from disk on first use. */
    fun current(context: Context): CustomSubtitleFont? {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    _font.value = fontsDir(context).listFiles()
                        ?.firstOrNull { it.isFile }
                        ?.let(::loadFont)
                    loaded = true
                }
            }
        }
        return _font.value
    }

    suspend fun import(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        val extension = displayName?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it == "ttf" || it == "otf" } ?: "ttf"
        val dir = fontsDir(context)
        val staging = File(dir, ".import.tmp")
        val copied = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_FONT_BYTES) error("font too large")
                        output.write(buffer, 0, read)
                    }
                }
            } != null
        }.getOrElse {
            Log.w(TAG, "Font copy failed", it)
            false
        }
        val stagedFont = if (copied) loadFont(staging) else null
        if (stagedFont == null) {
            staging.delete()
            return@withContext false
        }
        dir.listFiles()?.filter { it != staging }?.forEach { it.delete() }
        val target = File(dir, "subtitle_font.$extension")
        if (!staging.renameTo(target)) {
            staging.delete()
            return@withContext false
        }
        synchronized(this@SubtitleFontStore) {
            _font.value = loadFont(target)
            loaded = true
        }
        _font.value != null
    }

    fun clear(context: Context) {
        synchronized(this) {
            fontsDir(context).listFiles()?.forEach { it.delete() }
            _font.value = null
            loaded = true
        }
    }

    private fun loadFont(file: File): CustomSubtitleFont? = runCatching {
        val typeface = Typeface.createFromFile(file)
        if (typeface == Typeface.DEFAULT) return@runCatching null
        val family = readFontFamilyName(file) ?: return@runCatching null
        CustomSubtitleFont(file = file, familyName = family, typeface = typeface)
    }.getOrElse {
        Log.w(TAG, "Unusable font ${file.name}", it)
        null
    }
}

/** Reads name ID 1 (font family) from a TrueType/OpenType `name` table. */
private fun readFontFamilyName(file: File): String? = RandomAccessFile(file, "r").use { raf ->
    val version = raf.readInt()
    if (version != 0x00010000 && version != 0x4F54544F && version != 0x74727565) return null
    val numTables = raf.readUnsignedShort()
    raf.skipBytes(6)
    var nameOffset = -1L
    repeat(numTables) {
        val tag = raf.readInt()
        raf.skipBytes(4)
        val offset = raf.readInt().toLong() and 0xFFFFFFFFL
        raf.skipBytes(4)
        if (tag == 0x6E616D65) nameOffset = offset // "name"
    }
    if (nameOffset < 0) return null
    raf.seek(nameOffset)
    raf.skipBytes(2)
    val count = raf.readUnsignedShort()
    val stringsOffset = nameOffset + raf.readUnsignedShort()
    var macFallback: String? = null
    for (i in 0 until count) {
        raf.seek(nameOffset + 6 + i * 12L)
        val platformId = raf.readUnsignedShort()
        val encodingId = raf.readUnsignedShort()
        val languageId = raf.readUnsignedShort()
        val nameId = raf.readUnsignedShort()
        val length = raf.readUnsignedShort()
        val offset = raf.readUnsignedShort()
        if (nameId != 1 || length == 0) continue
        val bytes = ByteArray(length)
        raf.seek(stringsOffset + offset)
        raf.readFully(bytes)
        when {
            platformId == 3 || platformId == 0 -> {
                val name = String(bytes, Charsets.UTF_16BE).trim()
                if (name.isNotEmpty() && (platformId == 0 || languageId == 0x0409 || encodingId == 0)) {
                    return name
                }
                if (name.isNotEmpty() && macFallback == null) macFallback = name
            }
            platformId == 1 && encodingId == 0 && macFallback == null ->
                macFallback = String(bytes, Charsets.ISO_8859_1).trim().ifEmpty { null }
        }
    }
    macFallback
}
