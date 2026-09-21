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

    private val _aggressiveMode = MutableStateFlow(true)
    val aggressiveMode: StateFlow<Boolean> = _aggressiveMode.asStateFlow()

    private val _debugLogsEnabled = MutableStateFlow(false)
    val debugLogsEnabled: StateFlow<Boolean> = _debugLogsEnabled.asStateFlow()

    private var loadedProfileId: Int? = null
    private var loadPersistedValue: (() -> Boolean?)? = null
    private var savePersistedValue: ((Boolean) -> Unit)? = null
    private var loadAggressiveModePersistedValue: (() -> Boolean?)? = null
    private var saveAggressiveModePersistedValue: ((Boolean) -> Unit)? = null
    private var loadDebugLogsPersistedValue: (() -> Boolean?)? = null
    private var saveDebugLogsPersistedValue: ((Boolean) -> Unit)? = null
    private var lastStartupSessionKey: Int? = null
    private var lastStartupPlaybackKey: String? = null

    fun installPersistence(
        load: () -> Boolean?,
        save: (Boolean) -> Unit,
        loadAggressiveMode: () -> Boolean? = { null },
        saveAggressiveMode: (Boolean) -> Unit = {},
        loadDebugLogs: () -> Boolean? = { null },
        saveDebugLogs: (Boolean) -> Unit = {},
    ) {
        loadPersistedValue = load
        savePersistedValue = save
        loadAggressiveModePersistedValue = loadAggressiveMode
        saveAggressiveModePersistedValue = saveAggressiveMode
        loadDebugLogsPersistedValue = loadDebugLogs
        saveDebugLogsPersistedValue = saveDebugLogs
        loadedProfileId = null
    }

    fun ensureLoaded() {
        val profileId = ProfileRepository.activeProfileId
        if (loadedProfileId == profileId) return

        _preferredSubtitleAutoSyncOnStart.value = loadPersistedValue?.invoke() ?: false
        _aggressiveMode.value = loadAggressiveModePersistedValue?.invoke() ?: true
        _debugLogsEnabled.value = loadDebugLogsPersistedValue?.invoke() ?: false
        loadedProfileId = profileId
        lastStartupSessionKey = null
        lastStartupPlaybackKey = null
    }

    fun setPreferredSubtitleAutoSyncOnStart(enabled: Boolean) {
        ensureLoaded()
        if (_preferredSubtitleAutoSyncOnStart.value == enabled) return
        _preferredSubtitleAutoSyncOnStart.value = enabled
        savePersistedValue?.invoke(enabled)
    }

    fun setAggressiveMode(enabled: Boolean) {
        ensureLoaded()
        if (_aggressiveMode.value == enabled) return
        _aggressiveMode.value = enabled
        saveAggressiveModePersistedValue?.invoke(enabled)
    }

    fun setDebugLogsEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_debugLogsEnabled.value == enabled) return
        _debugLogsEnabled.value = enabled
        saveDebugLogsPersistedValue?.invoke(enabled)
    }

    fun claimStartupRun(sessionKey: Int, playbackKey: String): Boolean {
        ensureLoaded()
        if (!_preferredSubtitleAutoSyncOnStart.value) return false
        if (lastStartupSessionKey == sessionKey && lastStartupPlaybackKey == playbackKey) return false

        lastStartupSessionKey = sessionKey
        lastStartupPlaybackKey = playbackKey
        return true
    }
}
