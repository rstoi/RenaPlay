package com.baita.renaplay.data

import android.content.Context
import com.baita.renaplay.browse.MediaCategory
import com.baita.renaplay.browse.MediaItem
import com.baita.renaplay.browse.MediaKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * Último resultado bem-sucedido de [com.baita.renaplay.browse.MediaScanner.scanRoot],
 * persistido para que a tela de navegação possa mostrar a grade instantaneamente na
 * abertura do app (em vez de uma tela vazia com spinner) enquanto uma nova varredura SMB
 * roda em segundo plano. Chaveado por servidor (ip+share) para não misturar itens de um
 * NAS antigo depois de trocar de servidor.
 */
object LibraryCacheStore {
    private const val PREFS = "renaplay_library_cache"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(config: ServerConfig) = "${config.ip}|${config.share}"

    fun load(context: Context, config: ServerConfig): List<MediaItem>? {
        val raw = prefs(context).getString(key(config), null) ?: return null
        return runCatching { parse(raw) }.getOrNull()
    }

    fun save(context: Context, config: ServerConfig, items: List<MediaItem>) {
        prefs(context).edit().putString(key(config), serialize(items)).apply()
    }

    private fun serialize(items: List<MediaItem>): String {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("kind", item.kind.name)
                    put("path", item.path)
                    put("isDirectory", item.isDirectory)
                    put("posterPath", item.posterPath ?: JSONObject.NULL)
                    put("episodeCount", item.episodeCount)
                    put("seasonCount", item.seasonCount)
                    put("category", item.category.name)
                    put("remotePosterUrl", item.remotePosterUrl ?: JSONObject.NULL)
                    put("tmdbId", item.tmdbId ?: JSONObject.NULL)
                }
            )
        }
        return array.toString()
    }

    private fun parse(raw: String): List<MediaItem> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            MediaItem(
                id = o.getString("id"),
                title = o.getString("title"),
                kind = MediaKind.valueOf(o.getString("kind")),
                path = o.getString("path"),
                isDirectory = o.getBoolean("isDirectory"),
                posterPath = o.optString("posterPath").takeIf { it.isNotBlank() && it != "null" },
                episodeCount = o.optInt("episodeCount", 0),
                seasonCount = o.optInt("seasonCount", 0),
                category = MediaCategory.valueOf(o.getString("category")),
                remotePosterUrl = o.optString("remotePosterUrl").takeIf { it.isNotBlank() && it != "null" },
                tmdbId = if (o.isNull("tmdbId")) null else o.optInt("tmdbId")
            )
        }
    }
}
