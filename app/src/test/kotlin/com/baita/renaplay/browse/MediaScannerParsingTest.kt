package com.baita.renaplay.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaScannerParsingTest {

    @Test
    fun `season folder named with word recognized`() {
        assertEquals(1, seasonNumber("Season 1"))
        assertEquals(2, seasonNumber("Temporada 02"))
    }

    @Test
    fun `season folder named with release style S01 recognized`() {
        assertEquals(1, seasonNumber("Mad Men S01 (360p re-blurip)"))
    }

    @Test
    fun `season range folder is not a single season`() {
        assertNull(seasonNumber("Mad Men S01-S04 (2007-)"))
    }

    @Test
    fun `unrelated folder name has no season`() {
        assertNull(seasonNumber("Extras"))
    }

    @Test
    fun `looksEpisodic detects SxxExx and dash-numbered patterns`() {
        assertTrue(looksEpisodic("Show.S01E05.mkv"))
        assertTrue(looksEpisodic("Taken - 03 - Beyond The Sky.mkv"))
        assertFalse(looksEpisodic("Movie.2020.1080p.mkv"))
    }

    @Test
    fun `episodeNumber reads from SxxExx or dash-numbered title`() {
        assertEquals(7, episodeNumber("Show S01E07 720p"))
        assertEquals(3, episodeNumber("Taken - 03 - Beyond The Sky"))
        assertNull(episodeNumber("Movie 2020"))
    }
}
