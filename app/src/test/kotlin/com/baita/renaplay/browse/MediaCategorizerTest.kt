package com.baita.renaplay.browse

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaCategorizerTest {

    @Test
    fun `camera generated names are personal`() {
        assertEquals(MediaCategory.PESSOAL, classifyCategory("IMG_1234.mp4", "", MediaKind.MOVIE))
    }

    @Test
    fun `personal keyword in ancestor folder is personal even with a clean file name`() {
        assertEquals(
            MediaCategory.PESSOAL,
            classifyCategory("clip.mp4", "Familia/Viagem 2023/clip.mp4", MediaKind.MOVIE)
        )
    }

    @Test
    fun `documentary keyword is documentario`() {
        assertEquals(
            MediaCategory.DOCUMENTARIO,
            classifyCategory("BBC Earth - Planet.mkv", "", MediaKind.MOVIE)
        )
    }

    @Test
    fun `junk release name with no readable title is outro`() {
        assertEquals(MediaCategory.OUTRO, classifyCategory("RZ9SN2U1.mkv", "", MediaKind.MOVIE))
    }

    @Test
    fun `ordinary release name falls back to kind`() {
        assertEquals(
            MediaCategory.FILME,
            classifyCategory("The.Matrix.1999.1080p.mkv", "", MediaKind.MOVIE)
        )
        assertEquals(
            MediaCategory.SERIE,
            classifyCategory("Breaking.Bad.S01E01.mkv", "", MediaKind.SERIES)
        )
    }
}
