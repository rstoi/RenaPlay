package com.baita.renaplay.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColecoesTest {

    private fun filme(titulo: String) = MediaItem(
        id = titulo, title = titulo, kind = MediaKind.MOVIE,
        path = "movies/$titulo.mkv", isDirectory = false, category = MediaCategory.FILME
    )

    @Test
    fun `a trilogia Antes vira uma colecao, em ordem de lancamento`() {
        val biblioteca = listOf(
            filme("Antes da Meia-Noite (2013)"),
            filme("Antes do Amanhecer (1995)"),
            filme("Antes do Pôr do Sol (2004)"),
            filme("Pressure (2026)")
        )
        val c = Colecoes.colecaoDe(filme("Antes do Pôr do Sol (2004)"), biblioteca)!!
        assertEquals(3, c.total)
        assertEquals(
            listOf("Antes do Amanhecer (1995)", "Antes do Pôr do Sol (2004)", "Antes da Meia-Noite (2013)"),
            c.filmes.map { it.title }
        )
        assertEquals(2, c.posicaoDe(filme("Antes do Pôr do Sol (2004)")))
    }

    @Test
    fun `Senhor dos Aneis - o nome da colecao e o comeco que todos compartilham`() {
        val biblioteca = listOf(
            filme("O Senhor dos Anéis: A Sociedade do Anel (2001)"),
            filme("O Senhor dos Anéis: As Duas Torres (2002)"),
            filme("O Senhor dos Anéis: O Retorno do Rei (2003)")
        )
        val c = Colecoes.agrupar(biblioteca).single()
        assertEquals("O Senhor dos Anéis", c.nome)
        assertEquals(3, c.total)
        assertTrue(c.filmes.first().title.contains("Sociedade"))
    }

    @Test
    fun `Matrix e Exterminador tambem`() {
        val biblioteca = listOf(
            filme("Matrix Reloaded (2003)"),
            filme("Matrix (1999)"),
            filme("O Exterminador do Futuro (1984)"),
            filme("O Exterminador do Futuro 2: O Julgamento Final (1991)")
        )
        val cols = Colecoes.agrupar(biblioteca).sortedBy { it.total }
        assertEquals(2, cols.size)
        val matrix = cols.first { it.nome.startsWith("Matrix") }
        assertEquals(listOf("Matrix (1999)", "Matrix Reloaded (2003)"), matrix.filmes.map { it.title })
        val exterm = cols.first { it.nome.contains("Exterminador") }
        assertEquals(2, exterm.total)
        assertTrue(exterm.filmes.first().title.contains("(1984)"))
    }

    @Test
    fun `artigo comum nao faz dois filmes virarem trilogia`() {
        // "The Return" e "The Arctic Convoy" só compartilham o artigo — não são a mesma obra.
        val biblioteca = listOf(filme("The Return (2024)"), filme("The Arctic Convoy (2023)"))
        assertTrue(Colecoes.agrupar(biblioteca).isEmpty())
    }

    @Test
    fun `filme sozinho nao e colecao`() {
        assertNull(Colecoes.colecaoDe(filme("Pressure (2026)"), listOf(filme("Pressure (2026)"))))
    }

    @Test
    fun `serie nao entra em colecao de filmes`() {
        val serie = MediaItem(
            id = "s", title = "Mad Men (2007)", kind = MediaKind.SERIES,
            path = "tv/Mad Men", isDirectory = true, category = MediaCategory.SERIE
        )
        assertTrue(Colecoes.agrupar(listOf(serie, serie.copy(id = "s2", path = "tv/Mad Men 2"))).isEmpty())
    }
}
