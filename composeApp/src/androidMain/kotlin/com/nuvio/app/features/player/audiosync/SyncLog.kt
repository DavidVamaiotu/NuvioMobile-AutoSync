package com.nuvio.app.features.player.audiosync

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.nuvio.app.features.player.SubtitleSyncStatus
import com.nuvio.app.features.player.SyncLogActions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Audio subtitle sync's log: written to logcat and kept in memory (the latest lines only) so it can
 * be shared from Settings when a sync goes wrong. Links are reduced to their host, since stream and
 * subtitle URLs can carry account tokens; the log holds timings and decisions, never audio or text.
 */
internal object SyncLog {
    private const val TAG = "NuvioAudioSync"
    private const val MAX_LINES = 1_500
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val url = Regex("""https?://([^/\s?#]+)[^\s]*""")

    fun i(message: String) = add('I', message).also { Log.i(TAG, message) }

    fun w(message: String) = add('W', message).also { Log.w(TAG, message) }

    fun d(message: String) = add('D', message).also { Log.d(TAG, message) }

    private fun add(level: Char, message: String) {
        val redacted = url.replace(message) { "<${it.groupValues[1]}>" }
        synchronized(lines) {
            lines.addLast("${time.format(Date())} $level $redacted")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun text(): String {
        val header = "Nuvio audio sync log · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        return synchronized(lines) { (listOf(header) + lines).joinToString("\n") }
    }

    /** Registers the Settings action that shares the log. Call once at app start. */
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        SubtitleSyncStatus.logActions = object : SyncLogActions {
            override fun share() {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Nuvio audio sync log")
                    putExtra(Intent.EXTRA_TEXT, text())
                }
                val chooser = Intent.createChooser(send, "Share audio sync log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { appContext.startActivity(chooser) }
            }
        }
    }
}
