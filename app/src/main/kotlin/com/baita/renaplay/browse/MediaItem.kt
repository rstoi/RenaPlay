package com.baita.renaplay.browse

enum class MediaKind { MOVIE, SERIES }

/** Valor do campo `media_type` esperado pelas rotas do Suca Media (`movie` ou `tv`). */
fun MediaKind.toTmdbMediaType(): String = if (this == MediaKind.SERIES) "tv" else "movie"

enum class MediaCategory { FILME, SERIE, DOCUMENTARIO, MUSICAL, PESSOAL, OUTRO, MINHA_LISTA }

data class MediaItem(
    val id: String,
    val title: String,
    val kind: MediaKind,
    val path: String,
    val isDirectory: Boolean,
    val posterPath: String? = null,
    val episodeCount: Int = 0,
    val seasonCount: Int = 0,
    val category: MediaCategory = MediaCategory.OUTRO,
    /** Poster image loaded from Suca Media's cloud library (TMDB), when available. */
    val remotePosterUrl: String? = null,
    /**
     * TMDB id resolvido via Suca Media (busca ou biblioteca na nuvem), quando disponível.
     * Propagado até o player para que a telemetria de atividade (POST /activity/events) e a
     * busca de legenda via Suca Media consigam casar o evento/busca com o título certo — sem
     * ele, o backend não consegue agregar "assistido novamente"/"última visualização" por título.
     */
    val tmdbId: Int? = null
)
