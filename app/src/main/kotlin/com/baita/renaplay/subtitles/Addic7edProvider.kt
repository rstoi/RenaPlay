package com.baita.renaplay.subtitles

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder

private const val BASE_URL = "https://www.addic7ed.com"
private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

/**
 * addic7ed.com bloqueia scraping agressivo e nem sempre expõe o link de download
 * diretamente no HTML. Esta fonte é best-effort: qualquer falha retorna lista vazia
 * em vez de interromper a busca nas outras fontes.
 */
class Addic7edProvider(private val http: OkHttpClient) : SubtitleProvider {

    override val source = SubtitleSource.ADDIC7ED

    override suspend fun search(query: String): List<SubtitleResult> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val html = fetch("$BASE_URL/search.php?search=$encoded&Submit=Search")
            val doc = Jsoup.parse(html, BASE_URL)

            val episodeLinks = doc.select("a[href^=/serie/]").take(6)
            episodeLinks.flatMap { link -> resolveDownloadLinks(link.attr("abs:href"), link.text()) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun resolveDownloadLinks(pageUrl: String, title: String): List<SubtitleResult> {
        return try {
            val html = fetch(pageUrl)
            val doc = Jsoup.parse(html, pageUrl)

            doc.select("a.buttonDownload[href^=/original/]").take(3).map { element ->
                SubtitleResult(
                    source = SubtitleSource.ADDIC7ED,
                    label = title,
                    language = "",
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
