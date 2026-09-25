package com.nuvio.app.features.pillnav

import android.content.Context

internal object PillNavPreferencesAndroid {
    private const val preferencesName = "nuvio_pill_nav_settings"
    private const val enabledKey = "enabled"

    fun initialize(context: Context) {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        PillNavRepository.installPersistence(
            load = { preferences.getBoolean(enabledKey, false) },
            save = { preferences.edit().putBoolean(enabledKey, it).apply() },
        )
    }
}
