package com.baita.renaplay.suca

import android.content.Context
import android.util.Log
import com.baita.renaplay.data.SucaCapabilities
import com.baita.renaplay.data.SucaSession
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

private const val USER_AGENT = "SucaMediaFireTV/1.0"

/**
 * Client for the Suca Media companion web app's device API (see docs/SKIN.md and the
 * public API routes in the paired Lovable project). A device_token is minted once
 * via [claimPairing] and then sent as `Authorization: Bearer` on every other call.
 *
 * Uses [SucaHttpClientProvider]'s dedicated client (not the shared subtitle-provider one) —
 * see that file for why.
 */
class SucaApiClient(context: Context, private val http: OkHttpClient = SucaHttpClientProvider.client(context)) {

    fun claimPairing(
        baseUrl: String,
        code: String,
        deviceName: String,
        deviceType: String,
        model: String,
        os: String,
        version: String
    ): SucaResult<SucaSession> {
        return try {
            val body = JSONObject().apply {
                put("code", code)
                put("device_name", deviceName)
                put("device_type", deviceType)
                put("device_info", JSONObject().apply {
                    put("model", model)
                    put("os", os)
                    put("version", version)
                })
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/api/public/pair/claim")
                .post(body)
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .build()

            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (!response.isSuccessful || json == null) {
                    return SucaResult.Failure(
                        json?.optString("error").takeUnless { it.isNullOrBlank() } ?: "Erro ${response.code}",
                        json?.optString("hint")
                    )
                }
                val deviceToken = json.optString("device_token").ifBlank { return SucaResult.Failure("Resposta sem token") }
                val device = json.optJSONObject("device")
                val caps = json.optJSONObject("capabilities")
                val user = json.optJSONObject("user")
                SucaResult.Success(
                    SucaSession(
                        baseUrl = baseUrl,
                        deviceToken = deviceToken,
                        deviceId = device?.optString("id") ?: "",
                        deviceName = device?.optString("name") ?: deviceName,
                        displayName = user?.optString("display_name")?.takeIf { it.isNotBlank() && it != "null" },
                        capabilities = SucaCapabilities(
                            tmdb = caps?.optBoolean("tmdb") ?: false,
                            subtitles = caps?.optBoolean("subtitles") ?: false,
                            omdb = caps?.optBoolean("omdb") ?: false,
                            search = caps?.optBoolean("search") ?: false
                        )
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("SucaApiClient", "claimPairing failed", e)
            SucaResult.Failure(e.message ?: "Falha de conexão")
        }
    }

    fun refreshConfig(session: SucaSession): SucaResult<SucaSession> {
        return try {
            val request = authedRequest(session, "${session.baseUrl}/api/public/v1/config").build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (!response.isSuccessful || json == null) {
                    return SucaResult.Failure(json?.optString("error") ?: "Erro ${response.code}")
                }
                val caps = json.optJSONObject("capabilities")
                val user = json.optJSONObject("user")
                val device = json.optJSONObject("device")
                SucaResult.Success(
                    session.copy(
                        deviceName = device?.optString("name")?.ifBlank { null } ?: session.deviceName,
                        displayName = user?.optString("display_name")?.takeIf { it.isNotBlank() && it != "null" },
                        capabilities = SucaCapabilities(
                            tmdb = caps?.optBoolean("tmdb") ?: false,
                            subtitles = caps?.optBoolean("subtitles") ?: false,
                            omdb = caps?.optBoolean("omdb") ?: false,
                            search = caps?.optBoolean("search") ?: false
                        )
                    )
                )
            }
        } catch (e: Exception) {
            SucaResult.Failure(e.message ?: "Falha de conexão")
        }
    }

    fun search(session: SucaSession, query: String, language: String = "pt-BR"): SucaResult<List<SucaSearchItem>> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "${session.baseUrl}/api/public/v1/search?q=$encoded&language=$language"
            val request = authedRequest(session, url).build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return SucaResult.Failure("Erro ${response.code}")
                val json = JSONObject(text)
                val results = json.optJSONArray("results") ?: return SucaResult.Success(emptyList())
                val items = (0 until results.length()).mapNotNull { i ->
                    val o = results.optJSONObject(i) ?: return@mapNotNull null
                    val mediaType = o.optString("media_type").ifBlank { if (o.has("first_air_date")) "tv" else "movie" }
                    SucaSearchItem(
                        id = o.optInt("id"),
                        mediaType = mediaType,
                        title = o.optString("title").ifBlank { o.optString("name") },
                        posterPath = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" },
                        backdropPath = o.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" },
                        overview = o.optString("overview"),
                        voteAverage = o.optDouble("vote_average", 0.0),
                        releaseYear = (o.optString("release_date").ifBlank { o.optString("first_air_date") })
                            .takeIf { it.length >= 4 }?.substring(0, 4)
                    )
                }
                SucaResult.Success(items)
            }
        } catch (e: Exception) {
            SucaResult.Failure(e.message ?: "Falha de conexão")
        }
    }

    fun getLibrary(session: SucaSession, list: String? = null): SucaResult<List<SucaLibraryItem>> {
        return try {
            val url = "${session.baseUrl}/api/public/v1/library" + (list?.let { "?list=$it" } ?: "")
            val request = authedRequest(session, url).build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return SucaResult.Failure("Erro ${response.code}")
                val json = JSONObject(text)
                val items = json.optJSONArray("items") ?: return SucaResult.Success(emptyList())
                val parsed = (0 until items.length()).mapNotNull { i ->
                    val o = items.optJSONObject(i) ?: return@mapNotNull null
                    SucaLibraryItem(
                        id = o.optString("id"),
                        tmdbId = o.optInt("tmdb_id"),
                        mediaType = o.optString("media_type"),
                        list = o.optString("list"),
                        titleSnapshot = o.optString("title_snapshot").takeIf { it.isNotBlank() && it != "null" },
                        posterPath = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                    )
                }
                SucaResult.Success(parsed)
            }
        } catch (e: Exception) {
            SucaResult.Failure(e.message ?: "Falha de conexão")
        }
    }

    fun getSubtitles(
        session: SucaSession,
        tmdbId: Int?,
        mediaType: String?,
        language: String,
        query: String? = null,
        season: Int? = null,
        episode: Int? = null
    ): SucaResult<List<SucaSubtitleResult>> {
        return try {
            val params = mutableListOf("language=$language")
            tmdbId?.let { params += "tmdb_id=$it" }
            mediaType?.let { params += "media_type=$it" }
            query?.let { params += "query=${URLEncoder.encode(it, "UTF-8")}" }
            season?.let { params += "season=$it" }
            episode?.let { params += "episode=$it" }
            val url = "${session.baseUrl}/api/public/v1/subtitles?${params.joinToString("&")}"
            val request = authedRequest(session, url).build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (!response.isSuccessful) {
                    return SucaResult.Failure(json?.optString("error") ?: "Erro ${response.code}", json?.optJSONObject("upstream")?.optString("hint"))
                }
                val results = json?.optJSONArray("results") ?: return SucaResult.Success(emptyList())
                val parsed = (0 until results.length()).mapNotNull { i ->
                    val o = results.optJSONObject(i) ?: return@mapNotNull null
                    SucaSubtitleResult(
                        id = o.optString("id").takeIf { it.isNotBlank() && it != "null" },
                        fileId = if (o.isNull("file_id")) null else o.optInt("file_id"),
                        language = o.optString("language").takeIf { it.isNotBlank() && it != "null" },
                        release = o.optString("release").takeIf { it.isNotBlank() && it != "null" },
                        downloadCount = if (o.isNull("download_count")) null else o.optInt("download_count"),
                        hearingImpaired = o.optBoolean("hearing_impaired"),
                        hd = o.optBoolean("hd"),
                        uploader = o.optString("uploader").takeIf { it.isNotBlank() && it != "null" }
                    )
                }
                SucaResult.Success(parsed)
            }
        } catch (e: Exception) {
            SucaResult.Failure(e.message ?: "Falha de conexão")
        }
    }

    /**
     * Resolve um file_id do OpenSubtitles (vindo de [getSubtitles]) num link baixável, via proxy
     * do Suca Media — o device nunca vê a chave OpenSubtitles do usuário, nem na busca nem aqui.
     * Consome uma cota de download da conta OpenSubtitles do dono a cada chamada.
     */
    fun downloadSubtitle(session: SucaSession, fileId: Int): SucaResult<String> {
        return try {
            val body = JSONObject().put("file_id", fileId).toString().toRequestBody("application/json".toMediaType())
            val request = authedRequest(session, "${session.baseUrl}/api/public/v1/subtitles/download")
                .post(body)
                .header("Content-Type", "application/json")
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (!response.isSuccessful || json == null) {
                    return SucaResult.Failure(json?.optString("error") ?: "Erro ${response.code}", json?.optJSONObject("upstream")?.optString("hint"))
                }
                val url = json.optString("url").ifBlank { return SucaResult.Failure("Resposta sem link de download") }
                SucaResult.Success(url)
            }
        } catch (e: Exception) {
            SucaResult.Failure(e.message ?: "Falha de conexão")
        }
    }

    fun heartbeat(session: SucaSession) {
        try {
            val request = authedRequest(session, "${session.baseUrl}/api/public/v1/heartbeat")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().close()
        } catch (_: Exception) {
            // best-effort
        }
    }

    fun logActivity(
        session: SucaSession,
        eventType: String,
        tmdbId: Int? = null,
        mediaType: String? = null,
        title: String? = null,
        positionSeconds: Int? = null,
        durationSeconds: Int? = null,
        source: String = "firetv"
    ) {
        try {
            val body = JSONObject().apply {
                put("event_type", eventType)
                put("tmdb_id", tmdbId)
                put("media_type", mediaType)
                put("title", title)
                put("position_seconds", positionSeconds)
                put("duration_seconds", durationSeconds)
                put("source", source)
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${session.baseUrl}/api/public/activity/events")
                .post(body)
                .header("Authorization", "Bearer ${session.deviceToken}")
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .build()
            http.newCall(request).execute().close()
        } catch (_: Exception) {
            // best-effort: never let telemetry break playback
        }
    }

    private fun authedRequest(session: SucaSession, url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${session.deviceToken}")
            .header("User-Agent", USER_AGENT)
}
