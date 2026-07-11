package com.baita.renaplay.subtitles

enum class SubtitleSource { LOCAL, SUBTITLECAT, ADDIC7ED, OPENSUBTITLES, SUCA_MEDIA }

data class SubtitleResult(
    val source: SubtitleSource,
    val label: String,
    val language: String,
    /** URL http(s) para baixar o .srt, quando a fonte é web. */
    val downloadUrl: String? = null,
    /** Caminho relativo dentro do compartilhamento SMB, quando a fonte é local. */
    val smbPath: String? = null
)
