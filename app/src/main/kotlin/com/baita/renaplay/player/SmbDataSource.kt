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
import java.util.ArrayDeque
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue

const val SMB_URI_SCHEME = "renasmb"

// Extratores (ex: Matroska/EBML, ao ler o índice de Cues) fazem MUITAS leituras de 1-4 bytes para
// parsear campos de tamanho variável. Sem buffer local, cada uma vira uma ida-e-volta de rede
// inteira — dezenas de milhares de round-trips (minutos de "travamento"). Blocos grandes servem
// essas leituras pequenas da memória.
private const val CHUNK_SIZE = 524_288

// Tamanho de CADA requisição SMB individual. Não é o mesmo que o bloco: pedir 1MB numa única
// requisição fez este NAS responder "The parameter is incorrect" — o MaxReadSize negociado do
// SMB2 é menor que isso, e estourá-lo é erro de protocolo, não lentidão. 64KB é o valor seguro
// universalmente aceito; o bloco de 512KB é montado a partir de várias dessas requisições.
private const val MAX_SMB_READ = 65_536

// Quantos blocos ficam sendo baixados AO MESMO TEMPO. Este é o ponto que separa este player de
// VLC/Kodi: eles usam clientes SMB nativos (libsmb2/libsmbclient) que exploram o credit-based
// flow control do SMB2 — várias leituras em voo simultaneamente. O jcifs-ng faz request→response
// serial, então com uma única leitura pendente o throughput fica preso em
// (tamanho do bloco / latência do round-trip), sem relação com a banda disponível. Emular o
// pipeline aqui — N handles independentes na MESMA sessão SMB2, cada um buscando um bloco
// diferente — multiplica o throughput pelo mesmo fator, sem depender de banda extra.
private const val PIPELINE_DEPTH = 12

private class Chunk(val data: ByteArray, val length: Int)

fun smbPathToUri(path: String): Uri = Uri.Builder().scheme(SMB_URI_SCHEME).path(path).build()

class SmbDataSource(private val config: ServerConfig) : BaseDataSource(true) {

    private var uri: Uri? = null
    private var bytesRemaining: Long = 0

    // Reaproveita a sessão autenticada entre reaberturas desta mesma instância (comum durante a
    // preparação do extrator: início do arquivo, depois o índice/Cues no fim). Negociar SMB do
    // zero a cada uma deixou este NAS instável (conexões resetadas), o que o extrator interpreta
    // como "conteúdo malformado".
    private var cachedContext: CIFSContext? = null

    /**
     * Handles independentes: um por leitura em voo (SmbRandomAccessFile não é thread-safe).
     * Criados sob demanda, não todos de uma vez: o ExoPlayer reabre esta fonte várias vezes
     * durante a preparação, às vezes só pra ler alguns KB (ex: o índice/Cues no fim do arquivo) —
     * abrir PIPELINE_DEPTH handles nesses casos seria pagar round-trips de CREATE à toa.
     */
    private val idleHandles = LinkedBlockingQueue<SmbRandomAccessFile>()
    private val allHandles = mutableListOf<SmbRandomAccessFile>()
    private val handleLock = Any()
    private var smbUrl: String = ""
    private var executor: ExecutorService? = null

    /** Blocos já agendados, na ordem em que o player vai consumi-los. */
    private val inFlight = ArrayDeque<Future<Chunk>>()
    private var nextChunkOffset = 0L
    private var endOffset = 0L

    private var current: ByteArray = ByteArray(0)
    private var currentPos = 0
    private var currentLen = 0

    override fun open(dataSpec: DataSpec): Long {
        releaseResources()

        uri = dataSpec.uri
        val path = dataSpec.uri.path ?: throw IOException("Caminho SMB vazio")

        val ctx = cachedContext ?: buildContext().also { cachedContext = it }
        smbUrl = "smb://${config.ip}/${config.share}/${path.trimStart('/')}"

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            SmbFile(smbUrl, ctx).length() - dataSpec.position
        }
        nextChunkOffset = dataSpec.position
        endOffset = dataSpec.position + bytesRemaining
        currentPos = 0
        currentLen = 0

        executor = Executors.newFixedThreadPool(PIPELINE_DEPTH) { r ->
            Thread(r, "SmbPrefetch").apply { isDaemon = true }
        }
        repeat(PIPELINE_DEPTH) { scheduleNextChunk() }

        transferStarted(dataSpec)
        return bytesRemaining
    }

    /** Pega um handle livre; cria um novo se o pipeline ainda não encheu; senão espera liberar. */
    private fun acquireHandle(): SmbRandomAccessFile {
        synchronized(handleLock) {
            idleHandles.poll()?.let { return it }
            if (allHandles.size < PIPELINE_DEPTH) {
                val ctx = cachedContext ?: buildContext().also { cachedContext = it }
                val handle = SmbRandomAccessFile(SmbFile(smbUrl, ctx), "r")
                allHandles += handle
                return handle
            }
        }
        return idleHandles.take()
    }

    private fun buildContext(): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.responseTimeout", "15000")
            setProperty("jcifs.smb.client.connTimeout", "8000")
            // SMB2+ apenas: SMB1 não tem credit-based flow control, e sem ele o pipeline acima
            // não ganha nada (o servidor serializaria as leituras de qualquer forma).
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            // Assinatura de pacotes custa CPU por bloco e não agrega nada numa LAN doméstica
            // lendo mídia própria (o servidor ainda pode exigi-la; aqui só não a preferimos).
            setProperty("jcifs.smb.client.signingPreferred", "false")
        }
        return BaseContext(PropertyConfiguration(props))
            .withCredentials(NtlmPasswordAuthenticator(config.domain.ifBlank { null }, config.user, config.password))
    }

    /** Agenda a busca do próximo bloco em segundo plano, sem bloquear o chamador. */
    private fun scheduleNextChunk() {
        if (nextChunkOffset >= endOffset) return
        val offset = nextChunkOffset
        val length = minOf(CHUNK_SIZE.toLong(), endOffset - offset).toInt()
        nextChunkOffset += length

        val future = executor?.submit<Chunk> {
            val handle = acquireHandle()
            try {
                val data = ByteArray(length)
                handle.seek(offset)
                var read = 0
                while (read < length) {
                    val n = handle.read(data, read, minOf(MAX_SMB_READ, length - read))
                    if (n <= 0) break
                    read += n
                }
                Chunk(data, read)
            } finally {
                idleHandles.put(handle)
            }
        } ?: return
        inFlight.addLast(future)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining <= 0) return C.RESULT_END_OF_INPUT

        if (currentPos >= currentLen) {
            val future = inFlight.pollFirst() ?: return C.RESULT_END_OF_INPUT
            val chunk = try {
                // Bloqueia só se este bloco específico ainda não chegou. Enquanto o player
                // consumia o bloco anterior, os PIPELINE_DEPTH-1 seguintes já estavam sendo
                // baixados em paralelo — na maioria das vezes já está pronto e isto retorna na hora.
                future.get()
            } catch (e: InterruptedException) {
                // O Loader do ExoPlayer interrompe esta thread quando abandona a leitura atual
                // (ex: extrator pulando pra outra posição) — cancelamento normal, não falha de rede.
                Thread.currentThread().interrupt()
                throw IOException("Leitura cancelada", e)
            } catch (e: Exception) {
                throw IOException("Falha lendo bloco via SMB", e)
            }
            if (chunk.length <= 0) return C.RESULT_END_OF_INPUT

            current = chunk.data
            currentLen = chunk.length
            currentPos = 0
            scheduleNextChunk()
        }

        val available = currentLen - currentPos
        val bytesToCopy = minOf(available.toLong(), length.toLong(), bytesRemaining).toInt()
        System.arraycopy(current, currentPos, buffer, offset, bytesToCopy)
        currentPos += bytesToCopy

        bytesRemaining -= bytesToCopy
        bytesTransferred(bytesToCopy)
        return bytesToCopy
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            releaseResources()
        } finally {
            transferEnded()
        }
    }

    private fun releaseResources() {
        inFlight.forEach { it.cancel(true) }
        inFlight.clear()
        executor?.shutdownNow()
        executor = null
        allHandles.forEach { runCatching { it.close() } }
        allHandles.clear()
        idleHandles.clear()
        currentPos = 0
        currentLen = 0
        current = ByteArray(0)
    }
}

class SmbDataSourceFactory(private val config: ServerConfig) : DataSource.Factory {
    override fun createDataSource(): DataSource = SmbDataSource(config)
}
