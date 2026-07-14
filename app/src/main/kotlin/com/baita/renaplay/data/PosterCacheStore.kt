package com.baita.renaplay.data

import android.content.Context
import org.json.JSONObject

/**
 * Resultado (possivelmente vazio) de uma busca por título já resolvida via Suca Media (TMDB).
 * [tmdbId] é o id TMDB do casamento — propagado até o player para telemetria de atividade e
 * busca de legenda, quando disponível.
 */
data class CachedPoster(val tmdbId: Int?, val posterUrl: String?, val titulo: String? = null)

/**
 * Cache persistido de resoluções TMDB via Suca Media, chaveado por título limpo, para não
 * repetir buscas de rede a cada nova varredura SMB. Uma entrada "vazia" (sem posterUrl nem
 * tmdbId) significa "já buscado, sem resultado" — também evita repetir buscas fadadas a falhar.
 */
object PosterCacheStore {
    private const val PREFS = "renaplay_poster_cache"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** null = nunca buscado; [CachedPoster] com ambos os campos nulos = buscado, sem resultado. */
    fun get(context: Context, key: String): CachedPoster? {
        val raw = prefs(context).getString(key.lowercase(), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            CachedPoster(
                tmdbId = if (o.isNull("tmdbId")) null else o.optInt("tmdbId"),
                posterUrl = o.optString("posterUrl").takeIf { it.isNotBlank() && it != "null" },
                titulo = o.optString("titulo").takeIf { it.isNotBlank() && it != "null" }
            )
        }.getOrNull()
    }

    fun put(context: Context, key: String, tmdbId: Int?, posterUrl: String?, titulo: String? = null) {
        val json = JSONObject().apply {
            put("tmdbId", tmdbId ?: JSONObject.NULL)
            put("posterUrl", posterUrl ?: JSONObject.NULL)
            put("titulo", titulo ?: JSONObject.NULL)
        }
        prefs(context).edit().putString(key.lowercase(), json.toString()).apply()
    }
}
