package com.baita.renaplay.browse

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleCleanerTest {

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
