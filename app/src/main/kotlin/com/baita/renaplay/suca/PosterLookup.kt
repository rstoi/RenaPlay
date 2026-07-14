package com.baita.renaplay.suca

import android.content.Context
import com.baita.renaplay.browse.MediaKind
import com.baita.renaplay.browse.TitleCleaner
import com.baita.renaplay.data.CachedPoster
import com.baita.renaplay.data.PosterCacheStore
import com.baita.renaplay.data.SucaSession

/**
 * Resolve o pôster (TMDB, via Suca Media) de um título local, com cache persistido em
 * [PosterCacheStore] — usado por todas as telas que mostram cards/pôsteres (Browse, Episódios,
 * Detalhe) para não repetir buscas de rede para o mesmo título.
 */
object PosterLookup {
    /** Chamar de dentro de um contexto de I/O (Dispatchers.IO) — faz uma chamada de rede síncrona. */
    suspend fun resolve(context: Context, session: SucaSession, kind: MediaKind, title: String): CachedPoster? {
        if (!session.capabilities.search || title.isBlank()) return null
        val cacheKey = "$kind:$title"
        val cached = PosterCacheStore.get(context, cacheKey)
        if (cached != null) return cached

        val found = runCatching {
            val result = SucaApiClient(context).search(session, TitleCleaner.searchQuery(title))
            (result as? SucaResult.Success)?.value?.let { selectBestMatch(it, kind, title) }
        }.getOrNull()
        // Guarda também o título do TMDB (em português): a arte do pôster traz o nome traduzido
        // ("Antes da Meia-Noite"), e o card exibia o nome do arquivo em inglês ("Before Midnight").
        // Card e pôster diziam coisas diferentes sobre o mesmo filme, e a biblioteca parecia errada.
        val resolved = CachedPoster(
            tmdbId = found?.id, posterUrl = found?.posterUrl, titulo = found?.title?.takeIf { it.isNotBlank() })
        // Só guarda ACERTO. Guardar o "não achei" congelava o filme sem pôster para sempre: bastava
        // uma busca falhar uma vez — rede fora, título ainda sujo, TMDB de mau humor — e o app nunca
        // mais perguntava. Foi o que aconteceu com The Arctic Convoy.
        if (resolved.posterUrl != null || resolved.tmdbId != null) {
            PosterCacheStore.put(context, cacheKey, resolved.tmdbId, resolved.posterUrl, resolved.titulo)
        }
        return resolved
    }

    /**
     * Exige pôster E o tipo de mídia certo (filme vs série) — sem isso, um documentário "The
     * Making of Mad Men" (mediaType=movie) podia passar como pôster de uma série chamada
     * "Mad Men" só por casar o texto.
     */
    fun matches(item: SucaSearchItem, kind: MediaKind): Boolean {
        if (item.posterPath == null) return false
        val expectedType = if (kind == MediaKind.SERIES) "tv" else "movie"
        return item.mediaType == expectedType
    }

    private val YEAR_SUFFIX = Regex("\\((\\d{4})\\)$")

    /**
     * Entre os resultados que já batem o tipo de mídia certo, prioriza o que tem o ano de
     * lançamento mais próximo do título local — sem isso, títulos comuns como "Taken" casam com
     * o item errado (existe um filme de 2008, uma minissérie de 2002 e uma série de 2017, todos
     * chamados "Taken").
     */
    fun selectBestMatch(results: List<SucaSearchItem>, kind: MediaKind, title: String): SucaSearchItem? {
        val candidates = results.filter { matches(it, kind) }
        if (candidates.isEmpty()) return null
        val expectedYear = YEAR_SUFFIX.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: return candidates.first()
        return candidates.minByOrNull { item ->
            val year = item.releaseYear?.toIntOrNull()
            if (year == null) Int.MAX_VALUE else kotlin.math.abs(year - expectedYear)
        }
    }
}
