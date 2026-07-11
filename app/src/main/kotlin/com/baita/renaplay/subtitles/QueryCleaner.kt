package com.baita.renaplay.subtitles

private val RELEASE_TAGS = Regex(
    "\\b(1080p|720p|2160p|480p|4k|bluray|brrip|webrip|web-dl|webdl|hdtv|dvdrip|x264|x265|hevc|h264|h265|" +
        "aac|ac3|dts|remux|proper|repack|extended|unrated|dublado|legendado|dual|multi)\\b",
    RegexOption.IGNORE_CASE
)
private val EPISODE_TAG = Regex("[sS]\\d{1,2}[eE]\\d{1,3}.*$")

fun cleanQueryFromTitle(rawTitle: String): String {
    var title = rawTitle.replace('.', ' ').replace('_', ' ')
    title = EPISODE_TAG.replace(title, "")
    title = RELEASE_TAGS.replace(title, "")
    title = title.replace(Regex("\\s+"), " ").trim()
    return title.ifBlank { rawTitle }
}

private val WORD_SPLIT = Regex("[^a-z0-9]+")

/** Palavras (3+ letras) de um nome de arquivo/título, normalizadas para comparação. */
private fun wordTokens(name: String): Set<String> =
    name.substringBeforeLast('.')
        .lowercase()
        .split(WORD_SPLIT)
        .filter { it.length >= 3 }
        .toSet()

/** Quantidade de palavras em comum entre o nome do vídeo e um nome candidato (legenda ou release). */
fun subtitleMatchScore(videoName: String, candidateName: String): Int =
    wordTokens(candidateName).intersect(wordTokens(videoName)).size
