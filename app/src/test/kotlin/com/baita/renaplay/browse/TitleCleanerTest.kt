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
    fun `seriesTitleFromPath keeps the year, so the right Taken is matched`() {
        // O ano é o que separa a minissérie de 2002 da série homônima de 2017 — sem ele, o
        // PosterLookup pega o primeiro resultado e as duas telas mostram pôsteres diferentes.
        assertEquals(
            "Taken (2002)",
            TitleCleaner.seriesTitleFromPath("tv shows/taken 2002/Taken - 09 - John.avi")
        )
    }

    @Test
    fun `seriesTitleFromPath ignores season and quality subfolders`() {
        // Os caminhos reais da biblioteca: a pasta da série está sempre logo abaixo de "tv shows",
        // e o que vem depois (Season 4, "Mad Men S01 (360p re-blurip)") não interessa.
        assertEquals(
            "In Treatment (2008)",
            TitleCleaner.seriesTitleFromPath(
                "tv shows/In Treatment 2008/Season 4/In Treatment (2008) - S04E12 - Brooke.mkv"
            )
        )
        // O clean() ainda derruba o "S01-S04" da pasta, então sobra o nome da série com o ano —
        // exatamente o mesmo texto que a lista de séries usa para buscar o pôster.
        assertEquals(
            "Mad Men (2007)",
            TitleCleaner.seriesTitleFromPath(
                "tv shows/Mad Men S01-S04 (2007-)/Mad Men S01 (360p re-blurip)/Mad Men S01E02 Ladies Room.mp4"
            )
        )
    }

    @Test
    fun `seriesTitleFromPath returns null when there is no series folder`() {
        assertEquals(null, TitleCleaner.seriesTitleFromPath("tv shows/episodio-solto.mkv"))
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

    @Test
    fun `tira AV1, OPUS, Blu-ray com hifen, idioma solto e titulo alternativo`() {
        assertEquals(
            "The Arctic Convoy (2023)",
            TitleCleaner.clean("The.Arctic.Convoy.AKA.Konvoi.2023.NOR.1080p.Blu-ray.AV1.OPUS.5.1-heTOrico.mkv")
        )
    }
}
