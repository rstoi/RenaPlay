package com.baita.renaplay.subtitles

interface SubtitleProvider {
    val source: SubtitleSource

    /** Busca legendas pelo nome/título. Nunca lança exceção: falhas retornam lista vazia. */
    suspend fun search(query: String): List<SubtitleResult>
}
