package com.nuvio.app.features.player.seekpreview

import android.content.Context

internal object SeekrKeyPreferencesAndroid {
    private const val preferencesName = "nuvio_seekr_settings"
    private const val userKeyKey = "user_api_key"
    private const val localEnabledKey = "local_previews_enabled"
    private const val localMobileDataKey = "local_previews_mobile_data"

    fun initialize(context: Context) {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        SeekrKeyRepository.installPersistence(
            load = { preferences.getString(userKeyKey, null) },
            save = { preferences.edit().putString(userKeyKey, it).apply() },
        )
        LocalSeekPreviewSettings.installPersistence(
            load = {
                if (preferences.contains(localEnabledKey)) {
                    preferences.getBoolean(localEnabledKey, true) to preferences.getBoolean(localMobileDataKey, false)
                } else {
                    null
                }
            },
            save = { enabled, mobileData ->
                preferences.edit()
                    .putBoolean(localEnabledKey, enabled)
                    .putBoolean(localMobileDataKey, mobileData)
                    .apply()
            },
        )
    }
}
