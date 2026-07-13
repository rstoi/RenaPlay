package com.baita.renaplay.player

import com.baita.renaplay.subtitles.HttpClientProvider
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class Hevc264Progress(
    val status: String,
    val progress: Int,
    val eta: String,
    val done: Boolean,
    val error: String?
)

/**
 * Descoberta do serviço HEVC264 na rede local (spec: UDP 8766 broadcast).
 * Manda "HEVC264?" e espera "HEVC264|hostname|porta"; usa o IP de quem respondeu.
 */
object Hevc264Discovery {
    private const val DISCOVERY_PORT = 8766
    private const val QUERY = "HEVC264?"

    fun discover(timeoutMs: Int = 2500): String? {
        return try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = timeoutMs
                val data = QUERY.toByteArray()
                val addr = InetAddress.getByName("255.255.255.255")
                sock.send(DatagramPacket(data, data.size, addr, DISCOVERY_PORT))

                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(256)
                while (System.currentTimeMillis() < deadline) {
                    val resp = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(resp)
                    } catch (e: SocketTimeoutException) {
                        break
                    }
                    val text = String(resp.data, 0, resp.length).trim()
                    if (text.startsWith("HEVC264|")) {
                        val port = text.split("|").getOrNull(2)?.trim()?.toIntOrNull() ?: 8765
                        return "http://${resp.address.hostAddress}:$port"
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Cliente HTTP do serviço HEVC264 (upload/progress-SSE/download). Chamadas são
 * bloqueantes — usar em Dispatchers.IO. Timeouts de leitura/escrita zerados para
 * aguentar uploads/downloads grandes e o stream SSE de progresso.
 */
class Hevc264Client(baseUrl: String) {
    private val base = baseUrl.trimEnd('/')
    private val http: OkHttpClient = HttpClientProvider.client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun health(): Boolean = try {
        val req = Request.Builder().url("$base/health").get().build()
        http.newCall(req).execute().use { r ->
            r.isSuccessful &&
                JSONObject(r.body?.string().orEmpty()).optString("service") == "HEVC264"
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Upload streaming (sem carregar o arquivo na memória). Retorna o id do job.
     *
     * [onProgress] recebe 0..100 conforme os bytes saem. Um filme de 1,3GB leva ~12 min só para
     * subir, na velocidade de leitura do SMB — sem esse retorno, o diálogo ficava parado em
     * "Enviando o vídeo…" esse tempo todo e parecia travado.
     */
    fun upload(
        filename: String,
        contentLength: Long,
        onProgress: (Int) -> Unit = {},
        openStream: () -> InputStream
    ): String {
        val fileBody = object : RequestBody() {
            override fun contentType() = "video/octet-stream".toMediaTypeOrNull()
            override fun contentLength() = contentLength
            override fun writeTo(sink: BufferedSink) {
                openStream().use { input ->
                    val buffer = ByteArray(1 shl 16)
                    var sent = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        sink.write(buffer, 0, n)
                        sent += n
                        if (contentLength > 0) {
                            val pct = ((sent * 100) / contentLength).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        }
        // /upload_raw manda o arquivo como corpo cru. O /upload multipart faz o serviço gravar o
        // vídeo DUAS vezes — o Werkzeug materializa o multipart num temporário e depois copia
        // 1,3GB para a pasta de trabalho, cópia que só começa depois do último byte chegar. Era
        // esse o silêncio de mais de 20 minutos entre "enviando 100%" e o começo da conversão.
        //
        // O suporte é sondado ANTES de mandar o arquivo, e não descobrindo pelo 404 da resposta:
        // um serviço antigo só recusaria a rota depois de engolir o 1,3GB, e o fallback subiria
        // tudo de novo.
        if (supportsRawUpload()) {
            val name = URLEncoder.encode(filename, "UTF-8")
            val rawReq = Request.Builder().url("$base/upload_raw?name=$name").post(fileBody).build()
            http.newCall(rawReq).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IOException("upload HTTP ${r.code}")
                return JSONObject(text).getString("id")
            }
        }

        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, fileBody)
            .build()
        val req = Request.Builder().url("$base/upload").post(multipart).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("upload HTTP ${r.code}")
            return JSONObject(text).getString("id")
        }
    }

    /**
     * O serviço conhece /upload_raw? Sonda com um corpo vazio: a versão nova recusa por falta de
     * Content-Length (411), a antiga não tem a rota (404). Qualquer resposta que não seja
     * "rota inexistente" significa que ela existe.
     */
    private fun supportsRawUpload(): Boolean = try {
        val probe = Request.Builder()
            .url("$base/upload_raw")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(probe).execute().use { it.code != 404 && it.code != 405 }
    } catch (e: Exception) {
        false
    }

    /** Consome o SSE de /progress/<id>, chamando onProgress a cada evento (bloqueante). */
    fun streamProgress(id: String, onProgress: (Hevc264Progress) -> Unit) {
        val req = Request.Builder().url("$base/progress/$id").get()
            .header("Accept", "text/event-stream").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("progress HTTP ${r.code}")
            val source = r.body?.source() ?: throw IOException("sem corpo em /progress")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substringAfter("data:").trim()
                if (payload.isEmpty()) continue
                val json = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                val status = json.optString("status", "")
                // O serviço manda progress como FRAÇÃO (0.0..1.0), não porcentagem — conferido
                // contra o SSE real: {"status":"running","progress":0.479,"eta":7,...}. Lê-lo com
                // optInt truncava tudo para 0 e a barra ficava parada o tempo todo.
                val prog = (json.optDouble("progress", 0.0) * 100).roundToInt().coerceIn(0, 100)
                // CUIDADO: o serviço manda "error": null e "eta": null em todo evento, e o
                // optString do Android devolve a STRING "null" para um JSON null (não ""). Ler
                // com optString fazia o primeiro evento parecer um erro e a conversão abortava
                // sempre, com a mensagem "Falha na conversão: null". Daí o isNull() explícito.
                val err = if (json.isNull("error")) null
                          else json.optString("error").takeIf { it.isNotBlank() }
                val eta = if (json.isNull("eta")) "" else json.optString("eta")
                val done = err != null || status.equals("done", true)
                onProgress(Hevc264Progress(status, prog, eta, done, err))
                if (done) break
            }
        }
    }

    /**
     * Baixa o convertido, entregando o InputStream para gravação direta (sem temp local).
     * [onProgress] recebe 0..100 — o arquivo volta pela rede e é gravado no SMB, o que também
     * leva minutos num filme.
     */
    fun download(id: String, onProgress: (Int) -> Unit = {}, writeTo: (InputStream) -> Unit) {
        val req = Request.Builder().url("$base/download/$id").get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("download HTTP ${r.code}")
            val body = r.body ?: throw IOException("sem corpo em /download")
            val total = body.contentLength()
            val stream = if (total > 0) ProgressInputStream(body.byteStream(), total, onProgress)
                         else body.byteStream()
            writeTo(stream)
        }
    }
}

/** Conta os bytes lidos e reporta a porcentagem, sem bufferizar nada. */
private class ProgressInputStream(
    private val delegate: InputStream,
    private val total: Long,
    private val onProgress: (Int) -> Unit
) : InputStream() {
    private var read = 0L
    private var lastPct = -1

    private fun advance(n: Int) {
        if (n <= 0) return
        read += n
        val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
        if (pct != lastPct) {
            lastPct = pct
            onProgress(pct)
        }
    }

    override fun read(): Int = delegate.read().also { if (it != -1) advance(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        delegate.read(b, off, len).also { advance(it) }

    override fun available(): Int = delegate.available()
    override fun close() = delegate.close()
}
