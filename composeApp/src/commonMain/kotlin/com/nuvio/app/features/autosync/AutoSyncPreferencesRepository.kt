package com.nuvio.app.features.autosync

import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AutoSync-owned preference state.
 *
 * Persistence is injected by the platform so this feature does not add fields or methods to
 * Nuvio's general PlayerSettingsRepository/PlayerSettingsStorage architecture.
 */
internal object AutoSyncPreferencesRepository {
    private val _preferredSubtitleAutoSyncOnStart = MutableStateFlow(false)
    val preferredSubtitleAutoSyncOnStart: StateFlow<Boolean> =
        _preferredSubtitleAutoSyncOnStart.asStateFlow()

    private val _debugLogsEnabled = MutableStateFlow(false)
    val debugLogsEnabled: StateFlow<Boolean> = _debugLogsEnabled.asStateFlow()

    private var loadedProfileId: Int? = null
    private var loadPersistedValue: (() -> Boolean?)? = null
    private var savePersistedValue: ((Boolean) -> Unit)? = null
    private var loadDebugLogsPersistedValue: (() -> Boolean?)? = null
    private var saveDebugLogsPersistedValue: ((Boolean) -> Unit)? = null
    private var lastStartupSessionKey: Int? = null
    private var lastStartupVideoKey: String? = null

    fun installPersistence(
        load: () -> Boolean?,
        save: (Boolean) -> Unit,
        loadDebugLogs: () -> Boolean? = { null },
        saveDebugLogs: (Boolean) -> Unit = {},
    ) {
        loadPersistedValue = load
        savePersistedValue = save
        loadDebugLogsPersistedValue = loadDebugLogs
        saveDebugLogsPersistedValue = saveDebugLogs
        loadedProfileId = null
    }

    fun ensureLoaded() {
        val profileId = ProfileRepository.activeProfileId
        if (loadedProfileId == profileId) return

        _preferredSubtitleAutoSyncOnStart.value = loadPersistedValue?.invoke() ?: false
        _debugLogsEnabled.value = loadDebugLogsPersistedValue?.invoke() ?: false
        loadedProfileId = profileId
        lastStartupSessionKey = null
        lastStartupVideoKey = null
    }

    fun setPreferredSubtitleAutoSyncOnStart(enabled: Boolean) {
        ensureLoaded()
        if (_preferredSubtitleAutoSyncOnStart.value == enabled) return
        _preferredSubtitleAutoSyncOnStart.value = enabled
        savePersistedValue?.invoke(enabled)
    }

    fun setDebugLogsEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_debugLogsEnabled.value == enabled) return
        _debugLogsEnabled.value = enabled
        saveDebugLogsPersistedValue?.invoke(enabled)
    }

    fun claimStartupRun(sessionKey: Int, videoKey: String): Boolean {
        ensureLoaded()
        if (!_preferredSubtitleAutoSyncOnStart.value) return false
        if (lastStartupSessionKey == sessionKey && lastStartupVideoKey == videoKey) return false

        lastStartupSessionKey = sessionKey
        lastStartupVideoKey = videoKey
        return true
    }
}
