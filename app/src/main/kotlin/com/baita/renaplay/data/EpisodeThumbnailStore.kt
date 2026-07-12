package com.baita.renaplay.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.baita.renaplay.player.SmbMediaDataSource
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

private const val JPEG_QUALITY = 85

// Mirar perto do início (não em 25% da duração, como uma primeira versão fazia) — 20s ainda evita
// a maior parte de logos/tela preta de abertura, sem exigir um seek longo dentro do arquivo.
private const val TARGET_MS = 20_000L

// Medido em dispositivo real (Fire TV Stick, Fire OS 9): MediaMetadataRetriever.getFrameAtTime()
// não consegue decodificar frame de arquivos HEVC/x265 nesta plataforma — não é lentidão de rede,
// é falta de suporte do decodificador de thumbnail (a reprodução normal via ExoPlayer funciona
// normalmente com o mesmo arquivo). A chamada trava por ~100s antes de retornar null, então pular
// esses arquivos de antemão evita prender uma vaga de concorrência inteira sem chance de sucesso.
private val LIKELY_UNSUPPORTED_CODEC = Regex("(?i)hevc|x265|h\\.?265")

/**
 * Miniatura por episódio extraída do próprio vídeo (via [SmbMediaDataSource] + MediaMetadataRetriever),
 * para a lista de episódios não repetir o mesmo pôster da série em todos os cards. Cache em disco
 * permanente — a extração (abrir o arquivo pela rede + decodificar um frame) só acontece uma vez
 * por episódio.
 */
object EpisodeThumbnailStore {
    private fun cacheDir(context: Context) = File(context.cacheDir, "episode_thumbs").apply { mkdirs() }

    private fun keyFor(episodePath: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(episodePath.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun fileFor(context: Context, episodePath: String): File =
        File(cacheDir(context), "${keyFor(episodePath)}.jpg")

    /** Retorno síncrono e instantâneo — só olha se já existe em disco, não toca a rede. */
    fun cachedUri(context: Context, episodePath: String): String? {
        val file = fileFor(context, episodePath)
        return if (file.exists() && file.length() > 0) file.toURI().toString() else null
    }

    /**
     * Extrai e cacheia um frame do episódio. Chamar de dentro de um contexto de I/O
     * (Dispatchers.IO) — abre uma conexão SMB e decodifica um frame de vídeo, ambos síncronos.
     * Retorna null (sem exceção) em qualquer falha — formatos/codecs não suportados pelo
     * MediaMetadataRetriever do device, arquivo inacessível etc. — para o chamador cair de volta
     * no pôster da série sem quebrar a tela.
     */
    fun extractAndCache(context: Context, config: ServerConfig, episodePath: String): String? {
        cachedUri(context, episodePath)?.let { return it }
        if (LIKELY_UNSUPPORTED_CODEC.containsMatchIn(episodePath)) return null

        val bitmap = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                SmbMediaDataSource(config, episodePath).use { dataSource ->
                    retriever.setDataSource(dataSource)
                    val durationMs = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    val targetMs = if (durationMs > TARGET_MS * 2) TARGET_MS else 0L
                    retriever.getFrameAtTime(targetMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            } finally {
                retriever.release()
            }
        }.getOrNull() ?: return null

        return runCatching {
            val file = fileFor(context, episodePath)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tmp).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
            tmp.renameTo(file)
            file.toURI().toString()
        }.getOrNull()
    }
}
