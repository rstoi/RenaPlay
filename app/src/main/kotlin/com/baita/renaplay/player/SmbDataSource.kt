package com.baita.renaplay.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.baita.renaplay.data.ServerConfig
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException
import java.util.Properties

const val SMB_URI_SCHEME = "renasmb"

// Extratores (ex: Matroska/EBML, ao ler o índice de Cues) fazem MUITAS leituras de 1-4 bytes
// para parsear campos de tamanho variável. Sem buffer local, cada uma dessas leituras vira uma
// ida-e-volta de rede inteira via SMB — para um bloco de Cues de ~100KB isso significa dezenas
// de milhares de round-trips (minutos de "travamento"). Um buffer de leitura antecipada resolve
// isso: só vamos à rede a cada 64KB, e servimos as leituras pequenas da memória.
private const val READ_AHEAD_BUFFER_SIZE = 65536

fun smbPathToUri(path: String): Uri = Uri.Builder().scheme(SMB_URI_SCHEME).path(path).build()

class SmbDataSource(private val config: ServerConfig) : BaseDataSource(true) {

    private var file: SmbRandomAccessFile? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0

    // Extratores reabrem a mesma instância em posições diferentes (ex: início do arquivo, depois
    // perto do fim para ler o moov/Cues). Reaproveitar a sessão autenticada entre essas chamadas
    // evita negociar SMB do zero a cada uma — negociar demais em sequência rápida deixou este NAS
    // instável (conexões resetadas/timeouts), o que o extrator interpreta como "conteúdo malformado".
    private var cachedContext: CIFSContext? = null

    private val readAheadBuffer = ByteArray(READ_AHEAD_BUFFER_SIZE)
    private var bufferPos = 0
    private var bufferLen = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val path = dataSpec.uri.path ?: throw IOException("Caminho SMB vazio")

        // Fecha o handle anterior (se houver) antes de abrir um novo — sem isso, cada reabertura
        // desta mesma instância (comum durante a preparação do extrator) vazava o handle antigo.
        file?.close()
        file = null

        val ctx = cachedContext ?: run {
            val props = Properties().apply {
                setProperty("jcifs.smb.client.responseTimeout", "15000")
                setProperty("jcifs.smb.client.connTimeout", "8000")
            }
            BaseContext(PropertyConfiguration(props))
                .withCredentials(NtlmPasswordAuthenticator(config.domain.ifBlank { null }, config.user, config.password))
                .also { cachedContext = it }
        }

        val url = "smb://${config.ip}/${config.share}/${path.trimStart('/')}"
        val smbFile = SmbFile(url, ctx)
        // Acesso aleatório de verdade (seek via protocolo SMB), em vez de
        // SmbFileInputStream.skip() — mais correto para os saltos que o ExoPlayer faz
        // (ex: ler o índice/Cues do MKV, que fica no final do arquivo).
        val raf = SmbRandomAccessFile(smbFile, "r")
        raf.seek(dataSpec.position)
        file = raf
        bufferPos = 0
        bufferLen = 0

        val rafLength = raf.length()
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            rafLength - dataSpec.position
        }
        android.util.Log.e("SmbDataSourceDbg", "open: position=${dataSpec.position} length=${dataSpec.length} rafLength=$rafLength bytesRemaining=$bytesRemaining path=$path")

        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining <= 0) return C.RESULT_END_OF_INPUT

        if (bufferPos >= bufferLen) {
            val toFetch = minOf(READ_AHEAD_BUFFER_SIZE.toLong(), bytesRemaining).toInt()
            val n = try {
                file?.read(readAheadBuffer, 0, toFetch) ?: -1
            } catch (e: Exception) {
                android.util.Log.e("SmbDataSourceDbg", "read: EXCEPTION toFetch=$toFetch bytesRemaining=$bytesRemaining", e)
                throw e
            }
            if (n <= 0) {
                android.util.Log.e("SmbDataSourceDbg", "read: EOF/n<=0 n=$n toFetch=$toFetch bytesRemaining=$bytesRemaining")
                return C.RESULT_END_OF_INPUT
            }
            if (n < toFetch) {
                android.util.Log.e("SmbDataSourceDbg", "read: SHORT n=$n toFetch=$toFetch bytesRemaining=$bytesRemaining")
            }
            bufferPos = 0
            bufferLen = n
        }

        val available = bufferLen - bufferPos
        val bytesToCopy = minOf(available.toLong(), length.toLong(), bytesRemaining).toInt()
        System.arraycopy(readAheadBuffer, bufferPos, buffer, offset, bytesToCopy)
        bufferPos += bytesToCopy

        bytesRemaining -= bytesToCopy
        bytesTransferred(bytesToCopy)
        return bytesToCopy
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            file?.close()
        } finally {
            file = null
            bufferPos = 0
            bufferLen = 0
            transferEnded()
        }
    }
}

class SmbDataSourceFactory(private val config: ServerConfig) : DataSource.Factory {
    override fun createDataSource(): DataSource = SmbDataSource(config)
}
