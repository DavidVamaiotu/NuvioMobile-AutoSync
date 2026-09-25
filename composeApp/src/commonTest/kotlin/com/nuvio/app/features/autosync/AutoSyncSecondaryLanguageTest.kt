package com.nuvio.app.features.autosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoSyncSecondaryLanguageTest {
    private val english = AutoSyncSubtitleCandidate(url = "https://subs.example/en.srt", language = "eng")
    private val spanish = AutoSyncSubtitleCandidate(url = "https://subs.example/es.srt", language = "spa")
    private val spanishSecond = AutoSyncSubtitleCandidate(url = "https://subs.example/es-2.srt", language = "es")

    @Test
    fun seedsWithFirstSubtitleInSecondaryLanguage() {
        val seed = secondaryLanguageSearchSeed(
            candidates = listOf(english, spanish, spanishSecond),
            selectedUrl = english.url,
            searchedLanguage = "eng",
            secondaryLanguage = "es",
        )
        assertEquals(spanish, seed)
    }

    @Test
    fun noSeedWithoutSecondaryLanguage() {
        listOf(null, "", "none", "device", "forced", "default").forEach { secondary ->
            assertNull(
                secondaryLanguageSearchSeed(
                    candidates = listOf(english, spanish),
                    selectedUrl = english.url,
                    searchedLanguage = "eng",
                    secondaryLanguage = secondary,
                ),
                "secondary=$secondary",
            )
        }
    }

    @Test
    fun noSeedWhenSecondaryLanguageWasAlreadySearched() {
        assertNull(
            secondaryLanguageSearchSeed(
                candidates = listOf(spanish, spanishSecond),
                selectedUrl = spanish.url,
                searchedLanguage = "spa",
                secondaryLanguage = "es",
            ),
        )
    }

    @Test
    fun noSeedWhenNoSubtitleIsOfferedInSecondaryLanguage() {
        assertNull(
            secondaryLanguageSearchSeed(
                candidates = listOf(english),
                selectedUrl = english.url,
                searchedLanguage = "eng",
                secondaryLanguage = "es",
            ),
        )
    }

    @Test
    fun selectedSubtitleIsNeverTheSeed() {
        assertEquals(
            spanishSecond,
            secondaryLanguageSearchSeed(
                candidates = listOf(spanish, spanishSecond),
                selectedUrl = spanish.url,
                searchedLanguage = "eng",
                secondaryLanguage = "es",
            ),
        )
    }
}
