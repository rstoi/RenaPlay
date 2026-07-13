package com.baita.renaplay.browse

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleCleanerTest {

    @Test
    fun `seriesName strips the episode part so the TMDB search can match the series`() {
        // Os títulos reais da biblioteca que ficavam sem pôster em "Continuar assistindo".
        assertEquals("Mad Men", TitleCleaner.seriesName("Mad Men S01E02 Ladies Room"))
        assertEquals("In Treatment", TitleCleaner.seriesName("In Treatment - S04E12 - Brooke"))
        assertEquals("Taken", TitleCleaner.seriesName("Taken - 09 - John"))
    }

    @Test
    fun `seriesName leaves a non-episode title untouched`() {
        assertEquals("Mad Men", TitleCleaner.seriesName("Mad Men"))
        assertEquals("Pressure (2026)", TitleCleaner.seriesName("Pressure (2026)"))
    }

    @Test
    fun `strips release tags and reattaches year`() {
        assertEquals(
            "The Matrix (1999)",
            TitleCleaner.clean("The.Matrix.1999.1080p.BluRay.x264-GROUP.mkv")
        )
    }

    @Test
    fun `keepYear false omits year even when present`() {
        assertEquals("The Matrix", TitleCleaner.clean("The.Matrix.1999.mkv", keepYear = false))
    }

    @Test
    fun `plain name without year or tags is title-cased`() {
        assertEquals("Breaking Bad", TitleCleaner.clean("breaking.bad.mkv", keepYear = false))
    }

    @Test
    fun `searchQuery drops trailing year`() {
        assertEquals("The Matrix", TitleCleaner.searchQuery("The Matrix (1999)"))
    }

    @Test
    fun `searchQuery is a no-op when there is no trailing year`() {
        assertEquals("The Matrix", TitleCleaner.searchQuery("The Matrix"))
    }
}
