package com.baita.renaplay.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nomes reais da biblioteca — é neles que o casamento antigo errava. */
class SubtitleMatcherTest {

    private val pressure = "Pressure.2026.2026.1080p.WEBRip.10Bit.DDP5.1.x265-NeoNoir.mkv"
    private val arctic =
        "The.Arctic.Convoy.AKA.Konvoi.2023.NOR.1080p.Blu-ray.AV1.OPUS.5.1-heTOrico.mkv"

    @Test
    fun `legenda do proprio filme casa mesmo com nomes bem diferentes`() {
        assertNotNull(SubtitleMatcher.pontuar(pressure, "Pressure.2026.srt"))
    }

    @Test
    fun `legenda de outro filme nao casa so por compartilhar etiquetas de release`() {
        // As duas compartilham "1080p", "2023"... e nada do título. Antes, isso bastava.
        val outra = "The.Arctic.Convoy.2023.2160p.4K.BluRay.x265.10bit.AAC5.1-[YTS.MX].English-pt-BR.srt"
        assertNull(SubtitleMatcher.pontuar(pressure, outra))
    }

    @Test
    fun `a legenda do Arctic casa com o Arctic`() {
        val srt = "The.Arctic.Convoy.2023.2160p.4K.BluRay.x265.10bit.AAC5.1-[YTS.MX].English-pt-BR.srt"
        assertNotNull(SubtitleMatcher.pontuar(arctic, srt))
    }

    @Test
    fun `episodio errado e recusado`() {
        val video = "Mad Men S01E02 Ladies Room.mkv"
        assertNull(SubtitleMatcher.pontuar(video, "Mad.Men.S01E03.720p.srt"))
        assertNotNull(SubtitleMatcher.pontuar(video, "Mad.Men.S01E02.WEBRip.srt"))
    }

    @Test
    fun `legenda sem episodio nao serve para episodio`() {
        assertNull(SubtitleMatcher.pontuar("Mad Men S01E02 Ladies Room.mkv", "Mad.Men.1080p.srt"))
    }

    @Test
    fun `mesmo titulo com anos diferentes sao obras diferentes`() {
        assertNull(SubtitleMatcher.pontuar("Taken.2002.1080p.mkv", "Taken.2008.1080p.srt"))
        assertNotNull(SubtitleMatcher.pontuar("Taken.2002.1080p.mkv", "Taken.2002.BluRay.srt"))
    }

    @Test
    fun `legenda em portugues ganha da legenda em ingles`() {
        val pt = SubtitleMatcher.pontuar(pressure, "Pressure.2026.pt-BR.srt")!!
        val en = SubtitleMatcher.pontuar(pressure, "Pressure.2026.English.srt")!!
        assertTrue("pt=$pt en=$en", pt > en)
    }

    @Test
    fun `nome-base identico vence tudo`() {
        val exata = SubtitleMatcher.pontuar(pressure, "Pressure.2026.2026.1080p.WEBRip.10Bit.DDP5.1.x265-NeoNoir.srt")!!
        val outra = SubtitleMatcher.pontuar(pressure, "Pressure.2026.srt")!!
        assertTrue("exata=$exata outra=$outra", exata > outra)
    }

    @Test
    fun `consulta web usa titulo e ano, sem resolucao nem codec`() {
        assertEquals("pressure 2026", SubtitleMatcher.consulta(pressure))
    }

    @Test
    fun `consulta de episodio leva o SxxExx`() {
        assertEquals("mad men S01E02", SubtitleMatcher.consulta("Mad.Men.S01E02.1080p.WEBRip.x265.srt"))
    }

    @Test
    fun `titulo alternativo depois de AKA e ignorado`() {
        assertEquals(listOf("the", "arctic", "convoy"), SubtitleMatcher.analisar(arctic).palavras)
    }
}
