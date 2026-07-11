package com.baita.renaplay.subtitles

import android.content.Context
import com.baita.renaplay.data.SucaSession
import com.baita.renaplay.suca.SucaApiClient
import com.baita.renaplay.suca.SucaResult
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class SubtitleDownloader(private val http: OkHttpClient, private val openSubtitles: OpenSubtitlesProvider) {

    /**
     * Baixa a legenda para o cache local do app e retorna o caminho absoluto do arquivo.
     * [sucaSession] é usado para resolver resultados vindos de [SubtitleSource.SUCA_MEDIA] via
     * `POST /v1/subtitles/download` — o device nunca precisa da chave OpenSubtitles nesse caso.
     */
    fun download(context: Context, result: SubtitleResult, sucaSession: SucaSession? = null): File? {
        val realUrl = resolveUrl(context, result, sucaSession) ?: return null
        val extension = realUrl.substringAfterLast('.', "srt").substringBefore('?').ifBlank { "srt" }

        return try {
            val request = Request.Builder().url(realUrl).header("User-Agent", "RenaPlay v1.0").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null

                val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
                val fileName = "sub_${System.currentTimeMillis()}.$extension"
                val file = File(dir, fileName)
                file.writeBytes(bytes)
                file
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveUrl(context: Context, result: SubtitleResult, sucaSession: SucaSession?): String? {
        val url = result.downloadUrl ?: return null
        if (url.startsWith(OPENSUBTITLES_FILE_ID_PREFIX)) {
            val fileId = url.removePrefix(OPENSUBTITLES_FILE_ID_PREFIX).toIntOrNull() ?: return null
            if (result.source == SubtitleSource.SUCA_MEDIA && sucaSession != null) {
                val viaSuca = SucaApiClient(context).downloadSubtitle(sucaSession, fileId)
                return (viaSuca as? SucaResult.Success)?.value
            }
            return openSubtitles.resolveDownloadUrl(fileId)
        }
        return url
    }
}
