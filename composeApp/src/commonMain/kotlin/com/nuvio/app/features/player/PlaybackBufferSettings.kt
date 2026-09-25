package com.nuvio.app.features.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How much of the film the player keeps in memory, so seeks inside it are instant.
 *
 * Nuvio Reshaped owns this setting; persistence is injected by the platform so Nuvio's
 * PlayerSettingsRepository stays untouched. It applies from the next playback.
 */
internal object PlaybackBufferSettings {
    /** 0 keeps Nuvio's own buffer sizes. */
    const val NUVIO_DEFAULT_MB = 0
    val optionsMb = listOf(NUVIO_DEFAULT_MB, 256, 512, 1024)
    private const val DEFAULT_MB = 512

    private val _bufferMb = MutableStateFlow(DEFAULT_MB)
    val bufferMb: StateFlow<Int> = _bufferMb.asStateFlow()

    private var save: (Int) -> Unit = {}

    fun installPersistence(load: () -> Int?, save: (Int) -> Unit) {
        this.save = save
        _bufferMb.value = load()?.takeIf { it in optionsMb } ?: DEFAULT_MB
    }

    fun setBufferMb(mb: Int) {
        if (mb !in optionsMb || _bufferMb.value == mb) return
        _bufferMb.value = mb
        save(mb)
    }

    fun cycle() {
        setBufferMb(optionsMb[(optionsMb.indexOf(_bufferMb.value) + 1) % optionsMb.size])
    }
}
