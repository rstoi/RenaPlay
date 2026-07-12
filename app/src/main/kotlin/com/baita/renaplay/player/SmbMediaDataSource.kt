package com.baita.renaplay.player

import android.media.MediaDataSource
import com.baita.renaplay.data.ServerConfig
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.util.Properties

// MediaMetadataRetriever faz muitas leituras pequenas e próximas entre si ao parsear o header do
// container (moov/EBML) — sem buffer, cada uma vira um round-trip SMB inteiro (mesmo problema já
// resolvido para o player de vídeo em SmbDataSource). Um bloco de 512KB cacheando a última posição
// lida cobre a maioria dos pedidos subsequentes só com memória.
private const val READ_AHEAD_BUFFER_SIZE = 524_288

/**
 * Expõe um arquivo SMB como [MediaDataSource] de acesso aleatório, para uso com
 * [android.media.MediaMetadataRetriever] (extração de frame/thumbnail) sem precisar baixar o
 * arquivo inteiro — só os poucos blocos que o retriever pede (header do container + o frame-chave
 * mais próximo do tempo pedido). Independente de [SmbDataSource]: usado fora do player de vídeo.
 */
class SmbMediaDataSource(config: ServerConfig, path: String) : MediaDataSource() {
    private val raf: SmbRandomAccessFile

    private val buffer = ByteArray(READ_AHEAD_BUFFER_SIZE)
    private var bufferStart = -1L
    private var bufferLen = 0

    init {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.responseTimeout", "15000")
            setProperty("jcifs.smb.client.connTimeout", "8000")
        }
        val ctx = BaseContext(PropertyConfiguration(props))
            .withCredentials(NtlmPasswordAuthenticator(config.domain.ifBlank { null }, config.user, config.password))
        val url = "smb://${config.ip}/${config.share}/${path.trimStart('/')}"
        raf = SmbRandomAccessFile(SmbFile(url, ctx), "r")
    }

    override fun readAt(position: Long, out: ByteArray, offset: Int, size: Int): Int {
        if (bufferStart < 0 || position < bufferStart || position >= bufferStart + bufferLen) {
            raf.seek(position)
            val n = raf.read(buffer, 0, READ_AHEAD_BUFFER_SIZE)
            if (n <= 0) return -1
            bufferStart = position
            bufferLen = n
        }
        val offsetInBuffer = (position - bufferStart).toInt()
        val available = bufferLen - offsetInBuffer
        val toCopy = minOf(available, size)
        System.arraycopy(buffer, offsetInBuffer, out, offset, toCopy)
        return toCopy
    }

    override fun getSize(): Long = raf.length()

    override fun close() {
        raf.close()
    }
}
