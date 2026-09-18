package com.nuvio.app.features.autosync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Dedicated AutoSync trace logger.
 *
 * Debug build behavior:
 * - verbose logging is enabled
 * - every line is mirrored to Logcat with tag "NuvioAutoSync"
 * - the latest report is kept in memory
 * - PlayerEngine can call [finishAndCopy] to save the report and copy it to the clipboard
 *
 * This intentionally logs subtitle cue text in verbose mode for debugging alignment.
 * Request headers, auth tokens and complete subtitle/video URLs are not logged.
 */
internal object AutoSyncDebugLog {
    val ENABLED: Boolean
        get() {
            AutoSyncPreferencesRepository.ensureLoaded()
            return AutoSyncPreferencesRepository.debugLogsEnabled.value
        }

    val VERBOSE: Boolean
        get() = ENABLED

    private const val TAG = "NuvioAutoSync"
    private const val MAX_REPORT_CHARS = 160_000
    private const val MAX_CUE_TEXT_CHARS = 500

    private val lock = Any()
    private val buffer = StringBuilder()

    private var sessionId: String = "none"
    private var startedElapsedMs: Long = 0L
    private var active: Boolean = false

    fun start(
        sourceKey: String,
        subtitleUrl: String,
    ) {
        if (!ENABLED) return
        synchronized(lock) {
            sessionId = UUID.randomUUID().toString().take(8)
            startedElapsedMs = SystemClock.elapsedRealtime()
            active = true
            buffer.setLength(0)

            appendRawLocked("=== Nuvio AutoSync verbose debug ===")
            appendRawLocked("session=$sessionId")
            appendRawLocked("started=${wallClock()}")
            appendRawLocked("source=${safeSourceLabel(sourceKey)}")
            appendRawLocked("addon=${safeSourceLabel(subtitleUrl)}")
            appendRawLocked("verbose=$VERBOSE")
            appendRawLocked("")
        }
        Log.i(TAG, "session=$sessionId started")
    }

    fun section(title: () -> String) {
        if (!ENABLED) return
        appendRaw("")
        appendRaw("=== ${title()} ===")
    }

    fun info(message: () -> String) {
        if (!ENABLED) return
        append("INFO", message())
    }

    fun warn(message: () -> String) {
        if (!ENABLED) return
        append("WARN", message())
    }

    fun error(throwable: Throwable? = null, message: () -> String) {
        if (!ENABLED) return
        val text = message()
        val detail = if (throwable == null) {
            text
        } else {
            "$text | ${throwable::class.simpleName}: ${throwable.message.orEmpty()}"
        }
        append("ERROR", detail)
    }

    fun verbose(message: () -> String) {
        if (VERBOSE) append("VERBOSE", message())
    }

    fun cue(
        prefix: String,
        index: Int,
        startMs: Long,
        endMs: Long,
        text: String,
    ) {
        if (!VERBOSE) return
        verbose {
            "$prefix[$index] ${formatTimestamp(startMs)} --> ${formatTimestamp(endMs)} | " +
                quoteCueText(text)
        }
    }

    fun latestReport(): String {
        if (!ENABLED) return ""
        return synchronized(lock) { buffer.toString() }
    }

    /**
     * Completes the report, writes it to the app cache and copies the complete report
     * to the Android clipboard so it can be pasted directly into a bug report/chat.
     */
    fun finishAndCopy(
        context: Context,
        decision: String,
    ): Boolean {
        if (!ENABLED) return false
        section { "SESSION END" }
        info { "decision=$decision" }
        info { "elapsed=${elapsedMs()}ms" }

        val report = latestReport()
        saveReport(context, report)

        val copied = runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText(
                    "Nuvio AutoSync debug $sessionId",
                    report,
                ),
            )
            true
        }.getOrElse {
            error("clipboard copy failed", it)
            false
        }

        synchronized(lock) {
            active = false
        }

        Log.i(TAG, "session=$sessionId finished decision=$decision clipboard=$copied")
        return copied
    }

    private fun saveReport(context: Context, report: String) {
        runCatching {
            val directory = File(context.cacheDir, "autosync-debug").apply { mkdirs() }
            File(directory, "latest.txt").writeText(report)
            File(directory, "autosync-$sessionId.txt").writeText(report)
            info { "cache_report=${directory.absolutePath}/latest.txt" }
        }.onFailure {
            error(it) { "cache report write failed" }
        }
    }

    private fun append(level: String, message: String) {
        val line = "[+${elapsedMs()}ms][$level] $message"
        appendRaw(line)
        when (level) {
            "ERROR" -> Log.e(TAG, line)
            "WARN" -> Log.w(TAG, line)
            else -> Log.d(TAG, line)
        }
    }

    private fun appendRaw(line: String) {
        synchronized(lock) {
            appendRawLocked(line)
        }
    }

    private fun appendRawLocked(line: String) {
        if (buffer.length >= MAX_REPORT_CHARS) return

        val room = MAX_REPORT_CHARS - buffer.length
        if (line.length + 1 <= room) {
            buffer.append(line).append('\n')
        } else if (room > 1) {
            buffer.append(line.take(room - 1)).append('\n')
        }
    }

    private fun elapsedMs(): Long {
        val started = synchronized(lock) { startedElapsedMs }
        return if (started == 0L) 0L else (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
    }

    private fun quoteCueText(text: String): String {
        if (text.isBlank()) return "<text unavailable>"

        val normalized = text
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        val clipped = if (normalized.length > MAX_CUE_TEXT_CHARS) {
            normalized.take(MAX_CUE_TEXT_CHARS) + "…"
        } else {
            normalized
        }

        return "\"$clipped\""
    }

    private fun safeSourceLabel(value: String): String {
        if (value.isBlank()) return "<blank>"

        return runCatching {
            val noQuery = value.substringBefore('?')
            val scheme = noQuery.substringBefore("://", "")
            val rest = if (scheme.isNotBlank()) noQuery.substringAfter("://") else noQuery
            val host = rest.substringBefore('/')
            val file = rest.substringAfterLast('/').takeIf { it.isNotBlank() && it != host }

            buildString {
                if (scheme.isNotBlank()) append(scheme).append("://")
                append(host)
                if (!file.isNullOrBlank()) append("/…/").append(file.take(120))
            }
        }.getOrDefault("<source>")
    }

    fun formatTimestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return "%02d:%02d:%02d.%03d".format(
            Locale.US,
            hours,
            minutes,
            seconds,
            millis,
        )
    }

    private fun wallClock(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
