package com.nuvio.app.features.autosync.audio

import android.util.Log
import com.nuvio.app.features.autosync.AutoSyncDebugLog

/** Audio sync's log: logcat plus AutoSync's debug report, so one report covers both. */
internal object SyncLog {
    private const val TAG = "NuvioAudioSync"

    fun i(message: String) {
        Log.i(TAG, message)
        AutoSyncDebugLog.info { "AUDIO $message" }
    }

    fun w(message: String) {
        Log.w(TAG, message)
        AutoSyncDebugLog.warn { "AUDIO $message" }
    }

    fun d(message: String) {
        Log.d(TAG, message)
    }
}
