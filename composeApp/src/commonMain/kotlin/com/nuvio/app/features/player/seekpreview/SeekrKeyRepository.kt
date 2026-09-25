package com.nuvio.app.features.player.seekpreview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's own Seekr API key. The built-in key ([SeekrConfig.API_KEY]) is shared by everyone
 * and Seekr caps each key at a few dozen distinct titles a day, so users can bring their own.
 * Persistence is installed by the platform.
 */
internal object SeekrKeyRepository {
    private val _userKey = MutableStateFlow("")
    val userKey: StateFlow<String> = _userKey.asStateFlow()

    private var save: ((String) -> Unit)? = null

    fun installPersistence(load: () -> String?, save: (String) -> Unit) {
        _userKey.value = load().orEmpty()
        this.save = save
    }

    fun setUserKey(key: String) {
        val trimmed = key.trim()
        _userKey.value = trimmed
        save?.invoke(trimmed)
    }

    /** The user's key when set, else the built-in one; blank when neither exists. */
    fun effectiveKey(): String = _userKey.value.ifBlank { SeekrConfig.API_KEY }
}
