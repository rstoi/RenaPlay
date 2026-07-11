package com.baita.renaplay.browse

// Padrões típicos de nomes gerados automaticamente por câmeras, celulares, PVRs/DVRs e webcams.
// Verificados antes de tudo, pois um "IMG_1234.mp4" nunca deve virar "Filme".
private val PERSONAL_PATTERNS = listOf(
    Regex("^IMG[-_]?\\d+", RegexOption.IGNORE_CASE),
    Regex("^VID[-_]?\\d+", RegexOption.IGNORE_CASE),
    Regex("^MOV[-_]?\\d+", RegexOption.IGNORE_CASE),
    Regex("^MVI[-_]?\\d+", RegexOption.IGNORE_CASE),
    Regex("^MAH\\d+", RegexOption.IGNORE_CASE),
    Regex("^DJI[-_]?\\d+", RegexOption.IGNORE_CASE),
    Regex("^GOPR\\d+", RegexOption.IGNORE_CASE),
    Regex("^G[HXP]\\d{6,}", RegexOption.IGNORE_CASE),
    Regex("^PXL[-_]\\d{8}", RegexOption.IGNORE_CASE),
    Regex("whatsapp\\s*(video|image)", RegexOption.IGNORE_CASE),
    Regex("screen[-_ ]?record", RegexOption.IGNORE_CASE),
    Regex("^\\d{8}[-_]\\d{6}"),
    Regex("^CH\\d{1,2}[-_]\\d{6,}", RegexOption.IGNORE_CASE),
    Regex("dashcam|trailcam|webcam", RegexOption.IGNORE_CASE),
    Regex("^REC[-_]?\\d+", RegexOption.IGNORE_CASE)
)

private val DOCUMENTARY_KEYWORDS = listOf(
    "documentary", "documentario", "documentário", "docuseries",
    "national geographic", "natgeo", "bbc earth", "discovery channel"
)

private val MUSICAL_KEYWORDS = listOf(
    "concert", "ao vivo", "live at", "live in", "musical", "unplugged", "turnê", "turne", "tour "
)

// Contexto (nome do arquivo OU de qualquer pasta ancestral) que indica vídeo caseiro/pessoal:
// eventos de família, viagens, etc. — verificado tanto no nome do arquivo quanto no caminho,
// pois esse tipo de vídeo costuma ficar em pastas como "Família/", "Viagem 2023/" etc.
private val PERSONAL_KEYWORDS = listOf(
    "aniversario", "aniversário", "casamento", "formatura", "batizado",
    "ferias", "férias", "viagem", "familia", "família", "bebe", "bebê", "niver",
    "gravidez", "cha de", "chá de", "reuniao de familia", "reunião de família",
    "video caseiro", "vídeo caseiro", "home video", "wedding", "birthday",
    "graduation", "vacation", "family trip"
)

// Nomes crus sem qualquer padrão legível de título (ex: "$RZ9SN2U"): sem espaço, apenas
// letras/dígitos/"$" e com pelo menos um dígito, para não capturar palavras reais por engano.
private val JUNK_NAME = Regex("^(?=.*[0-9])[A-Za-z0-9$]{3,14}$")

fun classifyCategory(fileName: String, path: String = "", kind: MediaKind): MediaCategory {
    if (PERSONAL_PATTERNS.any { it.containsMatchIn(fileName) }) return MediaCategory.PESSOAL

    val lowerName = fileName.lowercase()
    val lowerPath = path.lowercase()
    if (PERSONAL_KEYWORDS.any { lowerName.contains(it) || lowerPath.contains(it) }) return MediaCategory.PESSOAL

    if (DOCUMENTARY_KEYWORDS.any { lowerName.contains(it) }) return MediaCategory.DOCUMENTARIO
    if (MUSICAL_KEYWORDS.any { lowerName.contains(it) }) return MediaCategory.MUSICAL

    val baseName = fileName.substringBeforeLast('.')
    if (JUNK_NAME.matches(baseName)) return MediaCategory.OUTRO

    return when (kind) {
        MediaKind.SERIES -> MediaCategory.SERIE
        MediaKind.MOVIE -> MediaCategory.FILME
    }
}
