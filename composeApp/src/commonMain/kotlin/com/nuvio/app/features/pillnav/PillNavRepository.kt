package com.nuvio.app.features.pillnav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the top pill menu replaces Nuvio's navigation bar. Off by default.
 * Persistence is installed by the platform; without it the setting stays off.
 */
internal object PillNavRepository {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var save: ((Boolean) -> Unit)? = null

    val isAvailable: Boolean
        get() = save != null

    fun installPersistence(load: () -> Boolean, save: (Boolean) -> Unit) {
        _enabled.value = load()
        this.save = save
    }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        save?.invoke(enabled)
    }
}
