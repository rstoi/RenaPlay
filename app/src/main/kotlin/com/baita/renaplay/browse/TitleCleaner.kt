package com.baita.renaplay.browse

/**
 * Normaliza nomes de arquivo/pasta de release (scene naming) em títulos apresentáveis,
 * removendo tags técnicas (resolução, fonte, codec, áudio, grupo de release) que não
 * dizem respeito ao conteúdo em si.
 */
object TitleCleaner {

    private val TAGS = listOf(
        "2160p", "4320p", "1080p", "720p", "480p", "360p", "240p", "4k", "8k",
        "hdr10", "hdr", "sdr", "uhd", "hd", "sd", "10bit", "8bit", "12bit", "hi10p",
        "bluray", "blu ray", "blu-ray", "blueray", "blurip", "re blurip", "rerip", "brrip", "bdrip", "bdremux", "remux",
        "dcprip", "dcp",
        "webrip", "web dl", "web-dl", "webdl", "web", "hdtv", "hdtvrip", "dvdrip", "dvdscr", "dvd",
        "hdcam", "telesync", "cam", "ppv",
        "x264", "x265", "h264", "h265", "hevc", "avc", "av1", "vp9", "xvid", "divx",
        "aac", "ac3", "eac3", "ddp5 1", "ddp7 1", "ddp", "dd5 1", "dd7 1", "dd",
        "dts", "atmos", "truehd", "opus", "flac", "5 1", "7 1", "2 0",
        "extended", "unrated", "directors cut", "director s cut", "proper", "repack",
        "internal", "limited", "retail", "dual audio", "dual áudio", "dual", "multi",
        "dublado", "legendado", "nacional",
        "yify", "yts", "rarbg", "eztv", "ettv",
        // Idioma/país solto no nome do release: "The.Arctic.Convoy...2023.NOR.1080p..." — some do
        // título e, sem isso, o TMDB não casa e o filme fica sem pôster.
        "nor", "swe", "dan", "fin", "ger", "deu", "fre", "fra", "ita", "spa", "esp", "rus", "pol",
        "jpn", "kor", "chi", "hin", "eng", "vostfr"
    )

    // Título alternativo ("The Arctic Convoy AKA Konvoi"): o que vem depois do AKA é o nome noutro
    // idioma. Mantê-lo faz a busca no TMDB procurar por uma string que não existe em lugar nenhum.
    private val TITULO_ALTERNATIVO = Regex("(?i)\\s+aka\\s+.*$")

    // Regex(..., IGNORE_CASE) do Kotlin só dobra maiúscula/minúscula no charset ASCII por padrão —
    // "Á" não casava com "á" (ex: "Dual Áudio" sobrevivia à limpeza). Precisa também de
    // Pattern.UNICODE_CASE para acentos.
    private val TAG_PATTERN = java.util.regex.Pattern.compile(
        "\\b(" + TAGS.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")\\b",
        java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE
    ).toRegex()
    private val YEAR_PATTERN = Regex("\\b(19\\d{2}|20\\d{2})\\b")
    private val OPEN_YEAR_RANGE = Regex("\\(\\s*(19\\d{2}|20\\d{2})\\s*-?\\s*\\)")
    private val SEASON_RANGE = Regex("(?i)\\bS\\d{1,2}\\s*-\\s*S?\\d{1,2}\\b")
    private val TRAILING_GROUP_TAG = Regex("-[A-Za-z0-9]{2,}$")
    // Grupos inteiros entre colchetes/parênteses são quase sempre metadado de release (grupo,
    // fonte, site) — ex: "[YTS.MX]", "[GeekFilmes.org]", "[5.1]" — removidos por completo, não só
    // os caracteres do colchete. O ano já foi capturado antes disso, então é seguro remover
    // "(2013)" junto sem perdê-lo.
    private val BRACKET_GROUP = Regex("\\[[^\\]]*]|\\([^)]*\\)")

    // Nomes de PASTA (não têm extensão de verdade) às vezes têm um "." incidental no meio, ex:
    // "...[GeekFilmes.org]" — usar substringBeforeLast('.') cegamente ali cortaria o "]" de
    // fechamento fora, e o BRACKET_GROUP nunca mais conseguiria casar o colchete (que ficou sem
    // par). Só tratamos como extensão de arquivo de verdade se o que vem depois do último ponto
    // for uma extensão conhecida — senão, deixamos o ponto em paz (ele vira espaço a seguir).
    private val KNOWN_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "m4v", "ts", "wmv", "flv", "webm", "srt", "vtt", "ass", "ssa", "sub"
    )

    /**
     * @param keepYear se true, reanexa o ano detectado como "(YYYY)" ao final do título limpo.
     */
    fun clean(rawName: String, keepYear: Boolean = true): String {
        var s = rawName
        if (s.substringAfterLast('.', "").lowercase() in KNOWN_EXTENSIONS) {
            s = s.substringBeforeLast('.')
        }
        s = s.replace('.', ' ').replace('_', ' ')

        val year = YEAR_PATTERN.find(s)?.groupValues?.get(1)

        s = TITULO_ALTERNATIVO.replace(s, " ")
        s = OPEN_YEAR_RANGE.replace(s, " ")
        s = SEASON_RANGE.replace(s, " ")
        s = BRACKET_GROUP.replace(s, " ")
        s = TAG_PATTERN.replace(s, " ")
        s = TRAILING_GROUP_TAG.replace(s, "")
        // Remove o ano já capturado para reanexar de forma consistente no final.
        if (year != null) {
            s = s.replace(year, " ")
        }
        s = s.replace(Regex("\\s+"), " ").trim().trim('-', ' ')

        if (s.isBlank()) return rawName

        s = smartTitleCase(s)

        return if (keepYear && year != null) "$s ($year)" else s
    }

    private val TRAILING_YEAR = Regex("\\s*\\((19\\d{2}|20\\d{2})\\)\\s*$")

    /**
     * Título sem o "(YYYY)" final — usado como termo de busca no TMDB (via Suca Media), já que
     * o parêntese com o ano prejudica o casamento no backend de busca (retorna vazio ou casa
     * errado em vários casos testados, mesmo para títulos bem conhecidos).
     */
    fun searchQuery(title: String): String = TRAILING_YEAR.replace(title, "").trim()

    // Os dois jeitos como um episódio aparece nos títulos desta biblioteca — os mesmos que o
    // MediaScanner reconhece: "Mad Men S01E02 Ladies Room" e "Taken - 09 - John".
    private val EPISODE_TAIL_SXXEXX = Regex("(?i)\\s*[-–]?\\s*S\\d{1,2}E\\d{1,3}.*$")
    private val EPISODE_TAIL_NUMBERED = Regex("\\s*-\\s+\\d{1,3}\\s+-.*$")

    /**
     * Nome da série a partir do título de um episódio ("Mad Men S01E02 Ladies Room" → "Mad Men").
     * O TMDB não indexa episódios por título: buscar a linha inteira não casa com nada, e é por
     * isso que os episódios em "Continuar assistindo" ficavam sem pôster. Um título que não seja
     * de episódio volta inalterado.
     */
    /**
     * Título da série a partir do CAMINHO do episódio:
     * "tv shows/taken 2002/Taken - 09 - John.avi" → "Taken (2002)".
     *
     * É a mesma pasta de onde o [MediaScanner] tira o título da série, e isso não é um detalhe: o
     * ano é o que faz o PosterLookup escolher certo. "Taken" sozinho casa com a série de 2017, e
     * era por isso que a mesma série aparecia com um pôster em "Continuar assistindo" e outro na
     * lista de séries. Derivando daqui, as duas telas usam a mesma chave e chegam ao mesmo pôster.
     *
     * Devolve null quando o caminho não tem pasta de série (episódio solto na raiz), e aí o
     * chamador cai no [seriesName] do título.
     */
    fun seriesTitleFromPath(path: String): String? {
        val dirs = path.split('/').dropLast(1).filter { it.isNotBlank() }
        // [0] é a pasta raiz da categoria ("tv shows"); [1] é a pasta da série. Temporadas e
        // pastas de qualidade ficam abaixo disso e são justamente o que não queremos.
        val folder = dirs.getOrNull(1) ?: return null
        return clean(folder).takeIf { it.isNotBlank() }
    }

    fun seriesName(episodeTitle: String): String {
        val withoutEpisode = EPISODE_TAIL_NUMBERED.replace(
            EPISODE_TAIL_SXXEXX.replace(episodeTitle, ""), ""
        )
        return withoutEpisode.trim().trimEnd('-').trim().ifBlank { episodeTitle }
    }

    private fun smartTitleCase(s: String): String {
        val allSameCase = s == s.lowercase() || s == s.uppercase()
        if (!allSameCase) return s
        return s.split(" ").joinToString(" ") { word ->
            if (word.isEmpty()) word else word.replaceFirstChar { it.uppercase() }
        }
    }
}
