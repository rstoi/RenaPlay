package com.baita.renaplay.player

import com.baita.renaplay.subtitles.HttpClientProvider
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
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

    /** Upload streaming (sem carregar o arquivo na memória). Retorna o id do job. */
    fun upload(filename: String, contentLength: Long, openStream: () -> InputStream): String {
        val fileBody = object : RequestBody() {
            override fun contentType() = "video/octet-stream".toMediaTypeOrNull()
            override fun contentLength() = contentLength
            override fun writeTo(sink: BufferedSink) {
                openStream().use { input -> sink.writeAll(input.source()) }
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

    /** Baixa o convertido, entregando o InputStream para gravação direta (sem temp local). */
    fun download(id: String, writeTo: (InputStream) -> Unit) {
        val req = Request.Builder().url("$base/download/$id").get().build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("download HTTP ${r.code}")
            val body = r.body ?: throw IOException("sem corpo em /download")
            writeTo(body.byteStream())
        }
    }
}
