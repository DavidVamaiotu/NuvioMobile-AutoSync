package com.nuvio.app.features.autosync

import android.content.Context
import com.nuvio.app.core.storage.ProfileScopedKey

internal object AutoSyncPreferencesAndroid {
    private const val preferencesName = "nuvio_autosync_settings"
    private const val preferredSubtitleAutoSyncOnStartKey =
        "preferred_subtitle_auto_sync_on_start"

    fun initialize(context: Context) {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        AutoSyncPreferencesRepository.installPersistence(
            load = {
                val key = ProfileScopedKey.of(preferredSubtitleAutoSyncOnStartKey)
                if (preferences.contains(key)) {
                    preferences.getBoolean(key, false)
                } else {
                    null
                }
            },
            save = { enabled ->
                preferences
                    .edit()
                    .putBoolean(ProfileScopedKey.of(preferredSubtitleAutoSyncOnStartKey), enabled)
                    .apply()
            },
        )
    }
}
