package com.baita.renaplay.subtitles

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

private const val API_BASE = "https://api.opensubtitles.com/api/v1"
private const val USER_AGENT = "RenaPlay v1.0"

/** Prefixo usado no downloadUrl para adiar a resolução do link real (custa 1 download da cota). */
const val OPENSUBTITLES_FILE_ID_PREFIX = "opensubtitles-file-id:"

class OpenSubtitlesProvider(
    private val http: OkHttpClient,
    private val apiKeyProvider: () -> String
) : SubtitleProvider {

    override val source = SubtitleSource.OPENSUBTITLES

    override suspend fun search(query: String): List<SubtitleResult> {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) return emptyList()

        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$API_BASE/subtitles?query=$encoded&languages=pt-br,pt,en"
            val request = Request.Builder().url(url)
                .header("Api-Key", apiKey)
                .header("User-Agent", USER_AGENT)
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val data = JSONObject(body).optJSONArray("data") ?: return emptyList()

                (0 until minOf(data.length(), 8)).mapNotNull { i ->
                    val attributes = data.getJSONObject(i).optJSONObject("attributes") ?: return@mapNotNull null
                    val fileId = attributes.optJSONArray("files")?.optJSONObject(0)?.optInt("file_id") ?: return@mapNotNull null
                    SubtitleResult(
                        source = SubtitleSource.OPENSUBTITLES,
                        label = attributes.optString("release").ifBlank { query },
                        language = attributes.optString("language", ""),
                        downloadUrl = "$OPENSUBTITLES_FILE_ID_PREFIX$fileId"
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Resolve o link de download real a partir do file_id (consome cota da conta). */
    fun resolveDownloadUrl(fileId: Int): String? {
        val apiKey = apiKeyProvider()
        if (apiKey.isBlank()) return null

        return try {
            val payload = JSONObject().put("file_id", fileId).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$API_BASE/download")
                .post(payload)
                .header("Api-Key", apiKey)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                JSONObject(body).optString("link").ifBlank { null }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun testApiKey(apiKey: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("$API_BASE/infos/user")
                .header("Api-Key", apiKey)
                .header("User-Agent", USER_AGENT)
                .build()
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}
