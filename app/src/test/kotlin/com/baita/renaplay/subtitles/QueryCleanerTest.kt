package com.baita.renaplay.subtitles

import org.junit.Assert.assertEquals
import org.junit.Test

class QueryCleanerTest {

    @Test
    fun `strips episode marker and everything after it`() {
        assertEquals("Show", cleanQueryFromTitle("Show.S01E05.1080p.WEB-DL"))
    }

    @Test
    fun `strips known release tags`() {
        assertEquals("Movie", cleanQueryFromTitle("Movie.1080p.BluRay"))
    }

    @Test
    fun `falls back to raw title when everything is stripped`() {
        assertEquals("1080p", cleanQueryFromTitle("1080p"))
    }

    @Test
    fun `subtitleMatchScore counts shared words of 3+ letters`() {
        assertEquals(
            2,
            subtitleMatchScore("Show.S01E05.1080p.mkv", "Show.S01E05.srt")
        )
    }

    @Test
    fun `subtitleMatchScore is zero for unrelated names`() {
        assertEquals(0, subtitleMatchScore("Show.S01E05.mkv", "totally.different.srt"))
    }
}
