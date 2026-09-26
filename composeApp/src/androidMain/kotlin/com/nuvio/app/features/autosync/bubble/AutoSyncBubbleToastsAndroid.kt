package com.nuvio.app.features.autosync.bubble

import android.content.Context
import android.widget.Toast

internal object AutoSyncBubbleToastsAndroid {
    private const val preferencesName = "nuvio_autosync_bubble_settings"
    private const val enabledKey = "enabled"

    fun initialize(context: Context) {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        AutoSyncBubbleToasts.installPersistence(
            load = { preferences.getBoolean(enabledKey, false) },
            save = { preferences.edit().putBoolean(enabledKey, it).apply() },
        )
    }
}

/** Shows an AutoSync message in the glass bubble when it is turned on, else as a plain toast. */
internal fun showAutoSyncMessage(context: Context, kind: AutoSyncBubbleKind, message: String) {
    if (!AutoSyncBubbleToasts.post(kind, message)) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
