package com.nuvio.app.features.player.seekpreview

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class SeekrContentMappingTest {

    @Test
    fun `imdb movie id maps to movie`() {
        assertEquals(
            SeekrContent.Movie(tmdbId = null, imdbId = "tt0133093"),
            seekrContentFor("tt0133093", "movie", null, null)
        )
    }

    @Test
    fun `tmdb prefixed id maps to numeric movie id`() {
        assertEquals(
            SeekrContent.Movie(tmdbId = 603, imdbId = null),
            seekrContentFor("tmdb:603", "movie", null, null)
        )
    }

    @Test
    fun `stremio series id drops the season episode suffix`() {
        assertEquals(
            SeekrContent.Episode(showTmdbId = null, showImdbId = "tt0944947", season = 1, episode = 2),
            seekrContentFor("tt0944947:1:2", "series", 1, 2)
        )
    }

    @Test
    fun `series without season or episode falls back to movie lookup`() {
        assertEquals(
            SeekrContent.Movie(tmdbId = null, imdbId = "tt0944947"),
            seekrContentFor("tt0944947", "series", null, null)
        )
    }

    @Test
    fun `unusable ids yield null`() {
        assertNull(seekrContentFor(null, "movie", null, null))
        assertNull(seekrContentFor("", "movie", null, null))
        assertNull(seekrContentFor("kitsu:1234", "series", 1, 1))
    }
}
