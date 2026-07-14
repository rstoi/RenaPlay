package com.baita.renaplay.subtitles

/**
 * Decide se uma legenda é a legenda DAQUELE vídeo.
 *
 * O casamento anterior contava qualquer palavra em comum entre os dois nomes — e nomes de release
 * são quase todos feitos das mesmas palavras. "1080p", "bluray", "x265", "aac", "web" e o ano
 * bastavam para uma legenda de outro filme somar mais pontos que a legenda certa; num diretório com
 * várias legendas soltas, o player abria a errada com toda a confiança.
 *
 * Aqui só contam as palavras do TÍTULO. Resolução, fonte, codec, canais de áudio, idioma e grupo de
 * release são ruído e são jogados fora antes de comparar. Ano e episódio, quando os dois lados os
 * declaram, são eliminatórios: uma legenda de S01E02 nunca serve para o S01E03, e "Taken (2002)"
 * nunca serve para "Taken (2008)".
 */
object SubtitleMatcher {

    /** Ruído de release: nada disso diz que legenda e vídeo são a mesma obra. */
    private val RUIDO = setOf(
        "2160p", "1080p", "720p", "480p", "360p", "240p", "4320p", "4k", "8k", "uhd",
        "hdr", "hdr10", "sdr", "10bit", "8bit", "12bit", "hi10p", "dv", "hlg",
        "bluray", "blueray", "blurip", "brrip", "bdrip", "bdremux", "remux", "rerip", "blu", "ray",
        "webrip", "webdl", "web", "dl", "hdtv", "dvdrip", "dvdscr", "dvd", "hdcam", "cam", "ts",
        "x264", "x265", "h264", "h265", "hevc", "avc", "av1", "vp9", "xvid", "divx",
        "aac", "ac3", "eac3", "ddp", "dd", "dts", "dtshd", "truehd", "atmos", "opus", "flac", "mp3",
        "5", "1", "7", "2", "0", "51", "71",
        "proper", "repack", "internal", "limited", "retail", "extended", "unrated", "remastered",
        "dual", "multi", "dublado", "legendado", "nacional", "audio", "áudio", "subs", "sub",
        "yify", "yts", "rarbg", "eztv", "ettv", "mx", "bz", "gg", "am",
        // idiomas/países que aparecem soltos no nome ("...2023.NOR.1080p...")
        "nor", "swe", "dan", "fin", "ger", "deu", "fre", "fra", "ita", "spa", "esp", "rus", "pol",
        "jpn", "kor", "chi", "hin", "eng", "english", "portugues", "português", "portuguese",
        "brazilian", "pt", "ptbr", "br", "es", "en", "fr", "de", "it",
        // marcas de edição de legenda
        "forced", "forcada", "forçada", "sdh", "cc", "hi",
        "aka"
    )

    private val SEPARADORES = Regex("[^\\p{L}\\p{N}]+")
    // "DDP5", "AAC5", "DTS7", "H264": codec/audio com o número de canais colado. Sem isto, "ddp5"
    // entra como se fosse palavra do título e afunda a cobertura das palavras que importam.
    private val CODEC_COM_DIGITO = Regex("^(ddp|dd|dts|aac|ac3|eac3|truehd|atmos|opus|x|h|mp|vp|av)\\d+$")
    // Grupo de release no fim do nome ("...x265-NeoNoir"): identifica quem postou, não a obra.
    private val GRUPO_FINAL = Regex("-[A-Za-z0-9]{2,}$")
    private val ANO = Regex("\\b(19\\d{2}|20\\d{2})\\b")
    private val EPISODIO_SXXEXX = Regex("(?i)\\bs(\\d{1,2})\\s*e(\\d{1,3})\\b")
    private val EPISODIO_XxYY = Regex("\\b(\\d{1,2})x(\\d{2,3})\\b")
    private val EXTENSOES = setOf("srt", "vtt", "ass", "ssa", "sub", "mkv", "mp4", "avi", "m4v", "mov")
    private val PT = setOf("pt", "ptbr", "br", "portugues", "português", "portuguese", "brazilian", "pob")

    data class Fatos(
        val palavras: List<String>,
        val ano: String?,
        val temporada: Int?,
        val episodio: Int?,
        val portugues: Boolean
    )

    fun analisar(nome: String): Fatos {
        var s = nome.substringAfterLast('/')
        if (s.substringAfterLast('.', "").lowercase() in EXTENSOES) s = s.substringBeforeLast('.')
        s = GRUPO_FINAL.replace(s, "")

        val ano = ANO.find(s)?.groupValues?.get(1)
        val sxxexx = EPISODIO_SXXEXX.find(s)
        val xxyy = if (sxxexx == null) EPISODIO_XxYY.find(s) else null
        val temporada = (sxxexx ?: xxyy)?.groupValues?.get(1)?.toIntOrNull()
        val episodio = (sxxexx ?: xxyy)?.groupValues?.get(2)?.toIntOrNull()

        val brutas = s.split(SEPARADORES).map { it.lowercase() }.filter { it.isNotBlank() }
        val portugues = brutas.any { it in PT } || Regex("(?i)pt[-_. ]?br").containsMatchIn(nome)

        // Corta em "aka": o que vem depois é título alternativo (outro idioma), não ajuda a casar.
        val ate = brutas.indexOf("aka").let { if (it > 0) brutas.take(it) else brutas }

        val palavras = ate
            .filter { it != ano }
            .filterNot { it in RUIDO }
            .filterNot { CODEC_COM_DIGITO.matches(it) }
            .filterNot { EPISODIO_SXXEXX.matches(it) }
            .filterNot { it.all { c -> c.isDigit() } && it.length <= 2 }
            .filter { it.length >= 2 }

        // Um filme pode se chamar "1917": se a limpeza levou tudo, o ano É o título.
        val finais = if (palavras.isEmpty() && ano != null) listOf(ano) else palavras
        return Fatos(finais, ano, temporada, episodio, portugues)
    }

    /**
     * Pontua a legenda [candidato] para o vídeo [video]. Devolve null quando a legenda é de outra
     * coisa — e aí é melhor NENHUMA legenda do que a legenda errada.
     */
    fun pontuar(video: String, candidato: String): Int? {
        val v = analisar(video)
        val c = analisar(candidato)

        // Mesmo nome-base: é a legenda daquele arquivo, ponto final.
        if (semExtensao(video).equals(semExtensao(candidato), ignoreCase = true)) return 1000

        // Episódio manda: com um dos lados declarando episódio, os dois têm de declarar o MESMO.
        val vEp = v.temporada != null && v.episodio != null
        val cEp = c.temporada != null && c.episodio != null
        if (vEp || cEp) {
            if (!vEp || !cEp) return null
            if (v.temporada != c.temporada || v.episodio != c.episodio) return null
        }

        // Anos diferentes e explícitos dos dois lados: são obras diferentes.
        if (v.ano != null && c.ano != null && v.ano != c.ano) return null

        if (v.palavras.isEmpty()) return null
        val comuns = c.palavras.toSet().intersect(v.palavras.toSet())
        val cobertura = comuns.size.toDouble() / v.palavras.size
        // Metade das palavras do título é o mínimo para acreditar que é o mesmo filme. Abaixo disso,
        // sobra coincidência ("The", "Man") e volta o problema que este casador existe para resolver.
        if (cobertura < 0.5) return null

        var pontos = (cobertura * 100).toInt()
        if (vEp && cEp) pontos += 60                       // episódio bateu
        if (v.ano != null && v.ano == c.ano) pontos += 30  // ano bateu
        if (c.portugues) pontos += 25                      // legenda em português vale mais aqui
        return pontos
    }

    private fun semExtensao(nome: String): String {
        val base = nome.substringAfterLast('/')
        return if (base.substringAfterLast('.', "").lowercase() in EXTENSOES) base.substringBeforeLast('.')
        else base
    }

    /** Termo de busca para as fontes web: só o título, o ano e — em série — o SxxExx. */
    fun consulta(nomeOuTitulo: String): String {
        val f = analisar(nomeOuTitulo)
        val partes = mutableListOf<String>()
        partes += f.palavras.joinToString(" ")
        if (f.temporada != null && f.episodio != null) {
            partes += "S%02dE%02d".format(f.temporada, f.episodio)
        } else if (f.ano != null) {
            partes += f.ano
        }
        return partes.joinToString(" ").trim()
    }
}
