package com.baita.renaplay.subtitles

import com.baita.renaplay.data.ServerConfig
import com.baita.renaplay.smb.SmbClient
import com.baita.renaplay.smb.SmbResult

private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub")

/** Procura arquivos de legenda já presentes na mesma pasta do vídeo, no servidor SMB. */
class LocalSubtitleProvider(private val client: SmbClient) {

    fun search(config: ServerConfig, videoPath: String): List<SubtitleResult> {
        val dir = videoPath.substringBeforeLast('/', "")
        val videoName = videoPath.substringAfterLast('/')
        val result = client.listFiles(config.ip, config.share, dir, config.user, config.password, config.domain)
        if (result !is SmbResult.Success) return emptyList()

        return result.value
            // Nunca mostra nada além de arquivos de legenda de verdade (extensão + não-diretório).
            .filter { !it.isDirectory && SUBTITLE_EXTENSIONS.contains(it.name.substringAfterLast('.', "").lowercase()) }
            .sortedByDescending { subtitleMatchScore(videoName, it.name) }
            .map {
                SubtitleResult(
                    source = SubtitleSource.LOCAL,
                    label = it.name,
                    language = "",
                    smbPath = it.path
                )
            }
    }
}
