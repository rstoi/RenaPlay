package com.baita.renaplay.suca

sealed class SucaResult<out T> {
    data class Success<T>(val value: T) : SucaResult<T>()
    data class Failure(val message: String, val hint: String? = null) : SucaResult<Nothing>()
}

data class SucaSearchItem(
    val id: Int,
    val mediaType: String,
    val title: String,
    val posterPath: String?,
    val backdropPath: String?,
    val overview: String,
    val voteAverage: Double,
    val releaseYear: String?
) {
    val posterUrl: String? get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
    val backdropUrl: String? get() = backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }
}

data class SucaLibraryItem(
    val id: String,
    val tmdbId: Int,
    val mediaType: String,
    val list: String,
    val titleSnapshot: String?,
    val posterPath: String?
) {
    val posterUrl: String? get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
}

data class SucaSubtitleResult(
    val id: String?,
    val fileId: Int?,
    val language: String?,
    val release: String?,
    val downloadCount: Int?,
    val hearingImpaired: Boolean,
    val hd: Boolean,
    val uploader: String?
)

/**
 * Coleção do TMDB (o "movie set" que o Kodi usa): a trilogia inteira, em ordem de lançamento,
 * inclusive as partes que ainda não estão no compartilhamento.
 */
data class SucaCollection(
    val id: Int,
    val name: String,
    val posterUrl: String?,
    val parts: List<SucaCollectionPart>
)

data class SucaCollectionPart(
    val tmdbId: Int,
    val title: String,
    val releaseYear: String?,
    val posterUrl: String?,
    val overview: String
)
