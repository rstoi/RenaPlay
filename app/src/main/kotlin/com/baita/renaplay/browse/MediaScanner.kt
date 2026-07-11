package com.baita.renaplay.browse

import com.baita.renaplay.data.ServerConfig
import com.baita.renaplay.smb.SmbClient
import com.baita.renaplay.smb.SmbEntry
import com.baita.renaplay.smb.SmbResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "m4v", "ts", "wmv", "flv", "webm")
private val EPISODE_PATTERN = Regex("[sS](\\d{1,2})[eE](\\d{1,3})")
// Minisséries às vezes numeram episódios sem letras S/E, ex: "Taken - 01 - Beyond The Sky".
// Exige espaços ao redor dos hífens para não confundir com números soltos em nomes de filme.
private val NUMBERED_EPISODE_PATTERN = Regex("-\\s+(\\d{1,3})\\s+-")

// Pega tanto pastas nomeadas "Season 1"/"Temporada 2" quanto o padrão de release "S01", "S02"
// embutido no nome (ex: "Mad Men S01 (360p re-blurip)") — mas não confunde com a resolução
// "360p" (não há "s" isolado seguido de dígitos ali) nem com "S01E01" (isso é episódio, não pasta).
private val SEASON_FOLDER_PATTERN = Regex(
    "(?i)(?:season|temporada)\\s*0*(\\d{1,2})\\b|\\bs0*(\\d{1,2})(?!\\d*[eE]\\d)\\b"
)
// Uma pasta como "Mad Men S01-S04 (2007-)" não é UMA temporada — ela agrupa várias. Se o nome
// contém um intervalo desses, ela mesma precisa ser explorada mais um nível (suas subpastas é
// que são as temporadas de verdade), em vez de virar uma "temporada" da pasta que a contém.
private val SEASON_RANGE_EXCLUDE = Regex("(?i)\\bs0*\\d{1,2}\\s*-\\s*s?0*\\d{1,2}\\b")
private const val MAX_CONTAINER_DEPTH = 3

data class Season(val number: Int, val label: String, val episodes: List<MediaItem>)

// Extraídas do corpo de MediaScanner (não usam estado da instância) para poder ser testadas
// diretamente com JUnit puro, sem precisar de um SmbClient/varredura real.
internal fun seasonNumber(folderName: String): Int? {
    if (SEASON_RANGE_EXCLUDE.containsMatchIn(folderName)) return null
    val match = SEASON_FOLDER_PATTERN.find(folderName) ?: return null
    val raw = match.groupValues[1].ifBlank { match.groupValues[2] }
    return raw.toIntOrNull()
}

internal fun looksEpisodic(name: String): Boolean =
    EPISODE_PATTERN.containsMatchIn(name) || NUMBERED_EPISODE_PATTERN.containsMatchIn(name)

internal fun episodeNumber(title: String): Int? =
    EPISODE_PATTERN.find(title)?.groupValues?.get(2)?.toIntOrNull()
        ?: NUMBERED_EPISODE_PATTERN.find(title)?.groupValues?.get(1)?.toIntOrNull()

class MediaScanner(private val client: SmbClient) {

    // Buscar pastas em paralelo acelera muito a varredura, mas disparar tudo de uma vez (ex: as
    // 14 subpastas de "movies/") sobrecarrega NAS caseiro — o servidor começa a resetar conexões
    // (SocketException: Connection reset) e itens somem silenciosamente da varredura. Um limite
    // de concorrência mantém a velocidade sem derrubar o servidor.
    private val concurrencyLimiter = Semaphore(4)

    private suspend fun listFilesLimited(config: ServerConfig, path: String): SmbResult<List<SmbEntry>> =
        concurrencyLimiter.withPermit { listFiles(config, path) }

    suspend fun scanRoot(config: ServerConfig): SmbResult<List<MediaItem>> {
        return when (val result = listFiles(config, "")) {
            is SmbResult.Failure -> result
            is SmbResult.Success -> {
                val items = mutableListOf<MediaItem>()
                scanLevel(config, result.value, items, depth = 0)
                SmbResult.Success(items.sortedBy { it.title.lowercase() })
            }
        }
    }

    /**
     * Organiza os vídeos de uma série por temporada. Se a pasta da série tiver subpastas de
     * temporada (numeradas ou não), cada uma vira uma [Season]; caso contrário, todos os vídeos
     * encontrados recursivamente caem numa única temporada implícita.
     */
    suspend fun listSeasons(config: ServerConfig, seriesPath: String): SmbResult<List<Season>> {
        return when (val result = listFiles(config, seriesPath)) {
            is SmbResult.Failure -> result
            is SmbResult.Success -> {
                val seasonFolders = result.value
                    .filter { it.isDirectory }
                    .mapNotNull { entry -> seasonNumber(entry.name)?.let { entry to it } }

                val seasons = if (seasonFolders.isEmpty()) {
                    val episodes = collectVideoFilesRecursive(config, result.value).sortedWith(episodeOrder())
                    if (episodes.isEmpty()) emptyList() else listOf(Season(1, "Episódios", episodes))
                } else {
                    coroutineScope {
                        seasonFolders.sortedBy { it.second }.map { (entry, number) ->
                            async(Dispatchers.IO) {
                                val children = listFilesLimited(config, entry.path)
                                val episodes = (children as? SmbResult.Success)?.value
                                    ?.let { collectVideoFilesRecursive(config, it) }
                                    ?.sortedWith(episodeOrder())
                                    .orEmpty()
                                if (episodes.isEmpty()) null else Season(number, "Temporada $number", episodes)
                            }
                        }.awaitAll().filterNotNull()
                    }
                }
                SmbResult.Success(seasons)
            }
        }
    }

    private fun listFiles(config: ServerConfig, path: String): SmbResult<List<SmbEntry>> =
        client.listFiles(config.ip, config.share, path, config.user, config.password, config.domain)

    private fun episodeOrder(): Comparator<MediaItem> = compareBy { episodeNumber(it.title) ?: Int.MAX_VALUE }

    private suspend fun collectVideoFilesRecursive(config: ServerConfig, entries: List<SmbEntry>): List<MediaItem> = coroutineScope {
        val (dirs, files) = entries.partition { it.isDirectory }
        val direct = files.filter { isVideoFile(it.name) }.map { entry ->
            MediaItem(
                id = entry.path,
                title = TitleCleaner.clean(entry.name, keepYear = false),
                kind = MediaKind.SERIES,
                path = entry.path,
                isDirectory = false,
                category = MediaCategory.SERIE
            )
        }
        // Subpastas (ex: extras/samples) buscadas em paralelo, não uma de cada vez.
        val nested = dirs.map { entry ->
            async(Dispatchers.IO) {
                val sub = listFilesLimited(config, entry.path)
                if (sub is SmbResult.Success) collectVideoFilesRecursive(config, sub.value) else emptyList()
            }
        }.awaitAll().flatten()
        direct + nested
    }

    /**
     * Varre uma lista de entradas classificando cada uma como filme ou série. Pastas "contêiner"
     * sem vídeo direto (ex: uma pasta "midia" que só tem "movies" e "tv shows" dentro) são
     * exploradas mais um nível, até [MAX_CONTAINER_DEPTH], em vez de ignoradas. Os filhos de
     * todas as subpastas deste nível são buscados em paralelo — com dezenas de pastas (cada uma
     * exigindo sua própria autenticação SMB), buscar uma de cada vez deixava a varredura inicial
     * lenta demais.
     */
    private suspend fun scanLevel(config: ServerConfig, entries: List<SmbEntry>, out: MutableList<MediaItem>, depth: Int) {
        if (depth > MAX_CONTAINER_DEPTH) return

        val (dirs, files) = entries.partition { it.isDirectory }

        for (entry in files) {
            if (isVideoFile(entry.name)) {
                out += MediaItem(
                    id = entry.path,
                    title = TitleCleaner.clean(entry.name),
                    kind = MediaKind.MOVIE,
                    path = entry.path,
                    isDirectory = false,
                    category = classifyCategory(entry.name, entry.path, MediaKind.MOVIE)
                )
            }
        }
        if (dirs.isEmpty()) return

        val childrenByEntry = coroutineScope {
            dirs.map { entry -> entry to async(Dispatchers.IO) { listFilesLimited(config, entry.path) } }
                .map { (entry, deferred) -> entry to deferred.await() }
        }

        val deeperContainers = mutableListOf<SmbEntry>()

        for ((entry, children) in childrenByEntry) {
            if (children !is SmbResult.Success) continue

            val videoFiles = children.value.filter { !it.isDirectory && isVideoFile(it.name) }
            val seasonSubfolders = children.value
                .filter { it.isDirectory }
                .mapNotNull { sf -> seasonNumber(sf.name)?.let { sf to it } }
            val looksLikeSeries = videoFiles.any { looksEpisodic(it.name) } || seasonSubfolders.isNotEmpty()
            val subfolders = children.value.filter { it.isDirectory }

            when {
                looksLikeSeries -> out += MediaItem(
                    id = entry.path,
                    title = TitleCleaner.clean(entry.name),
                    kind = MediaKind.SERIES,
                    path = entry.path,
                    isDirectory = true,
                    // Contagem exata de episódios exigiria varrer cada temporada agora (custoso);
                    // quando há pastas de temporada, mostramos a contagem de temporadas em vez
                    // disso — os episódios em si são resolvidos sob demanda em listSeasons().
                    episodeCount = videoFiles.size,
                    seasonCount = seasonSubfolders.size,
                    category = classifyCategory(entry.name, entry.path, MediaKind.SERIES)
                )
                // Pasta "folha" de um único release (vídeo + talvez .srt/.nfo, sem subpastas):
                // um filme só, usando o nome da pasta como título (geralmente mais limpo que o
                // nome do arquivo em si).
                videoFiles.size == 1 && subfolders.isEmpty() -> out += MediaItem(
                    id = entry.path,
                    title = TitleCleaner.clean(entry.name),
                    kind = MediaKind.MOVIE,
                    path = videoFiles.first().path,
                    isDirectory = false,
                    category = classifyCategory(entry.name, entry.path, MediaKind.MOVIE)
                )
                // Pasta "contêiner" misto (ex: "movies/" com filmes soltos E filmes cada um em
                // sua própria subpasta, como "Haywire (2011) [1080p]"): os vídeos soltos deste
                // nível viram filmes individuais, e cada subpasta é classificada recursivamente
                // por si só — nada é descartado só porque este nível já tinha vídeo direto.
                else -> {
                    videoFiles.forEach { file ->
                        out += MediaItem(
                            id = file.path,
                            title = TitleCleaner.clean(file.name),
                            kind = MediaKind.MOVIE,
                            path = file.path,
                            isDirectory = false,
                            category = classifyCategory(file.name, file.path, MediaKind.MOVIE)
                        )
                    }
                    deeperContainers += subfolders
                }
            }
        }

        if (deeperContainers.isNotEmpty()) {
            scanLevel(config, deeperContainers, out, depth + 1)
        }
    }

    private fun isVideoFile(name: String): Boolean =
        VIDEO_EXTENSIONS.contains(name.substringAfterLast('.', "").lowercase())
}
