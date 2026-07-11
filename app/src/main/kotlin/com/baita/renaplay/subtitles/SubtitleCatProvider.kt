package com.baita.renaplay.subtitles

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder

private const val BASE_URL = "https://www.subtitlecat.com"
private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
private val PORTUGUESE_LANGS = listOf("pt-BR", "pt")

class SubtitleCatProvider(private val http: OkHttpClient) : SubtitleProvider {

    override val source = SubtitleSource.SUBTITLECAT

    override suspend fun search(query: String): List<SubtitleResult> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val html = fetch("$BASE_URL/index.php?search=$encoded")
            val doc = Jsoup.parse(html, BASE_URL)

            val entries = doc.select("table a[href^=subs/]").take(6)
            entries.flatMap { entry -> resolveDownloadLinks(entry.attr("abs:href"), entry.text()) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun resolveDownloadLinks(detailUrl: String, title: String): List<SubtitleResult> {
        return try {
            val html = fetch(detailUrl)
            val doc = Jsoup.parse(html, detailUrl)

            val preferred = PORTUGUESE_LANGS.mapNotNull { lang ->
                doc.selectFirst("a#download_$lang")?.let { lang to it }
            }
            val links = if (preferred.isNotEmpty()) preferred else
                doc.select("a[id^=download_]").take(3).map { it.attr("id").removePrefix("download_") to it }

            links.map { (lang, element) ->
                SubtitleResult(
                    source = SubtitleSource.SUBTITLECAT,
                    label = title,
                    language = lang,
                    downloadUrl = element.attr("abs:href")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            return response.body?.string() ?: throw java.io.IOException("Corpo vazio")
        }
    }
}
