package com.nuvio.app.features.autosync

import com.nuvio.app.features.profiles.ProfileRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoSyncIntegrationPolicyTest {
    @BeforeTest
    fun setUp() {
        ProfileRepository.clearInMemory()
        AutoSyncPreferencesRepository.installPersistence(
            load = { true },
            save = {},
        )
    }

    @AfterTest
    fun tearDown() {
        AutoSyncPreferencesRepository.installPersistence(
            load = { false },
            save = {},
        )
        ProfileRepository.clearInMemory()
    }

    @Test
    fun startupClaimIsPerPlaybackSourceWithinSamePlayerSession() {
        val sessionKey = 17

        assertTrue(
            AutoSyncPreferencesRepository.claimStartupRun(
                sessionKey = sessionKey,
                playbackKey = "https://stream.example/movie-a.mkv",
            ),
        )
        assertFalse(
            AutoSyncPreferencesRepository.claimStartupRun(
                sessionKey = sessionKey,
                playbackKey = "https://stream.example/movie-a.mkv",
            ),
        )
        assertTrue(
            AutoSyncPreferencesRepository.claimStartupRun(
                sessionKey = sessionKey,
                playbackKey = "https://stream.example/movie-b.mkv",
            ),
        )
    }

    @Test
    fun disabledAutoSyncDoesNotEnterAutoSyncRun() {
        assertEquals(
            AutoSyncStartAction.ATTACH_ORIGINAL,
            decideAutoSyncStart(enabled = false),
        )
        assertEquals(
            AutoSyncStartAction.RUN,
            decideAutoSyncStart(enabled = true),
        )
    }

    @Test
    fun selectedOnlyScopeNeverExposesAlternativeCandidates() {
        val candidates = listOf(
            AutoSyncSubtitleCandidate("https://subs.example/1.srt", "ro", "one"),
            AutoSyncSubtitleCandidate("https://subs.example/2.srt", "ro", "two"),
        )

        assertTrue(
            AutoSyncCandidateScope.SELECTED_ONLY
                .alternativeCandidates(candidates)
                .isEmpty(),
        )
        assertFalse(AutoSyncCandidateScope.SELECTED_ONLY.usesAlternativeProvider)

        assertEquals(
            candidates,
            AutoSyncCandidateScope.STARTUP_SEARCH.alternativeCandidates(candidates),
        )
        assertTrue(AutoSyncCandidateScope.STARTUP_SEARCH.usesAlternativeProvider)
    }

    @Test
    fun originalSubtitleIsRestoredOnlyWhenAutoSyncSidecarIsGone() {
        assertTrue(
            shouldRestoreOriginalSubtitle(activeSidecarSubtitleKey = null),
        )
        assertFalse(
            shouldRestoreOriginalSubtitle(
                activeSidecarSubtitleKey = "https://subs.example/selected.srt",
            ),
        )
    }
}
