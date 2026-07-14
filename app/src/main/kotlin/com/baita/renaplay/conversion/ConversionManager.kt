package com.baita.renaplay.conversion

import android.content.Context
import android.util.Log
import android.content.Intent
import androidx.core.content.ContextCompat
import com.baita.renaplay.data.ConversionSettingsStore
import com.baita.renaplay.data.ServerConfig
import com.baita.renaplay.player.Hevc264Client
import com.baita.renaplay.player.Hevc264Discovery
import com.baita.renaplay.player.Hevc264StalledException
import com.baita.renaplay.smb.SmbClientProvider
import com.baita.renaplay.smb.SmbResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

private const val TAG = "RenaPlayConv"

enum class ConversionPhase { DISCOVERING, UPLOADING, PROCESSING, CONVERTING, DOWNLOADING, SAVING, DONE, FAILED }

/**
 * Estado de uma conversão. [percent] é da fase corrente (upload, conversão e download têm cada um
 * o seu 0–100). [newPath] só existe quando termina; [error] só quando falha.
 */
data class ConversionJob(
    val path: String,
    val title: String,
    val phase: ConversionPhase,
    val percent: Int = 0,
    val newPath: String? = null,
    val error: String? = null
) {
    val isRunning: Boolean get() = phase != ConversionPhase.DONE && phase != ConversionPhase.FAILED
}

/**
 * Dono do processo de conversão (upload → recodificação → download → troca no SMB).
 *
 * Vive no escopo da APLICAÇÃO, de propósito: converter um filme leva dezenas de minutos — só o
 * upload de 1,3GB passa de 10 — e prender isso ao ciclo de vida do player significaria que sair
 * da tela do vídeo mataria o trabalho no meio. Aqui o usuário dispara a conversão, volta a
 * navegar (ou assiste outra coisa) e, ao reabrir o mesmo vídeo, reencontra o progresso: o player
 * consulta [jobFor] e mostra a tela de progresso em vez de tentar tocar um arquivo que ainda está
 * sendo trocado.
 *
 * O estado é de processo, não persistido: se o app for morto, a conversão morre junto. O arquivo
 * no share continua íntegro de qualquer forma — a troca só acontece depois do download inteiro.
 */
object ConversionManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _jobs = MutableStateFlow<Map<String, ConversionJob>>(emptyMap())
    val jobs: StateFlow<Map<String, ConversionJob>> = _jobs.asStateFlow()

    fun jobFor(path: String): ConversionJob? = _jobs.value[path]

    fun isRunning(path: String): Boolean = jobFor(path)?.isRunning == true

    /** Descarta um job já encerrado (o player chama depois de tratar DONE/FAILED). */
    fun clear(path: String) {
        _jobs.value = _jobs.value - path
    }

    private fun publish(job: ConversionJob) {
        _jobs.value = _jobs.value + (job.path to job)
    }

    /**
     * Procura, em TODOS os conversores da rede, uma conversão já em andamento para [path].
     *
     * Perguntar a um só não basta: com dois conversores no ar, a descoberta devolve o que responder
     * primeiro, e o aparelho que sortear o serviço errado não vê a conversão que está rolando no
     * outro — jura que não há nada em andamento e se oferece para converter de novo.
     */
    private fun jobEmAndamentoNaRede(app: Context, path: String): Pair<Hevc264Client, String>? {
        val nome = path.substringAfterLast('/')
        val urls = LinkedHashSet<String>().apply {
            ConversionSettingsStore.getExplicitServiceUrl(app)?.let { add(it) }
            addAll(Hevc264Discovery.discoverAll())
            ConversionSettingsStore.getServiceUrl(app).takeIf { it.isNotBlank() }?.let { add(it) }
        }
        for (url in urls) {
            val client = Hevc264Client(url)
            val id = client.jobEmAndamento(nome) ?: continue
            return client to id
        }
        return null
    }

    /**
     * Segue o progresso de um job do servidor. Quando a recodificação chega ao fim, o serviço ainda
     * copia o arquivo para o compartilhamento e faz a troca — daí SAVING em vez de "100%" parado.
     */
    private fun seguirProgresso(
        client: Hevc264Client,
        id: String,
        phase: (ConversionPhase, Int) -> Unit
    ) {
        client.streamProgress(id) { p ->
            if (p.error != null) throw IOException(p.error)
            if (p.progress >= 100) phase(ConversionPhase.SAVING, 100)
            else phase(ConversionPhase.CONVERTING, p.progress)
        }
    }

    /**
     * Reencontra uma conversão que o SERVIDOR está tocando sozinho para [path] — depois de o app ser
     * fechado, morto pelo sistema, ou o aparelho desligado. Sem isto, apertar play num filme que
     * está sendo convertido não mostraria nada: o estado vive no processo, e o processo se foi.
     */
    fun acompanharNoServidor(context: Context, config: ServerConfig, path: String, title: String) {
        if (isRunning(path)) return
        val app = context.applicationContext
        scope.launch {
            try {
                val (client, id) = jobEmAndamentoNaRede(app, path) ?: return@launch
                Log.i(TAG, "reencontrada conversão em andamento no servidor: job=$id ($path)")

                var job = ConversionJob(path, title, ConversionPhase.CONVERTING)
                publish(job)
                fun phase(p: ConversionPhase, percent: Int = 0) {
                    job = job.copy(phase = p, percent = percent)
                    publish(job)
                }
                seguirProgresso(client, id, ::phase)
                job = job.copy(
                    phase = ConversionPhase.DONE, percent = 100,
                    newPath = "${path.substringBeforeLast('.')}.mp4")
                publish(job)
            } catch (e: Exception) {
                Log.e(TAG, "falha ao acompanhar conversão do servidor ($path)", e)
                clear(path)
            }
        }
    }

    /**
     * Dispara a conversão de [path]. Idempotente: chamar de novo enquanto já está rodando não
     * inicia um segundo upload — apenas devolve, e quem observa [jobs] segue vendo o mesmo job.
     */
    fun start(context: Context, config: ServerConfig, path: String, title: String) {
        if (isRunning(path)) return
        val app = context.applicationContext
        var job = ConversionJob(path, title, ConversionPhase.DISCOVERING)
        publish(job)

        // Sem isto o processo é morto no meio do trabalho: o Fire TV tem 922MB de RAM e o app,
        // uma vez em background, é o primeiro a cair. O serviço não converte nada — só segura o
        // processo vivo enquanto este job roda. Publicar o job ANTES de subir o serviço evita que
        // ele acorde, não veja job rodando e se encerre na hora.
        ContextCompat.startForegroundService(app, Intent(app, ConversionService::class.java))

        scope.launch {
            fun phase(p: ConversionPhase, percent: Int = 0) {
                if (p != job.phase) Log.i(TAG, "fase=$p arquivo=$path")
                job = job.copy(phase = p, percent = percent)
                publish(job)
            }
            try {
                // Outro aparelho — ou uma sessão anterior deste — pode já ter mandado converter este
                // arquivo. Recomeçar seria recodificar o mesmo filme duas vezes, competindo pela
                // mesma CPU e dobrando a espera de todo mundo. Então: acompanha, não duplica.
                jobEmAndamentoNaRede(app, path)?.let { (client, id) ->
                    Log.i(TAG, "conversão já em andamento para $path (job=$id) — acompanhando")
                    phase(ConversionPhase.CONVERTING)
                    seguirProgresso(client, id, ::phase)
                    job = job.copy(
                        phase = ConversionPhase.DONE, percent = 100,
                        newPath = "${path.substringBeforeLast('.')}.mp4")
                    publish(job)
                    return@launch
                }

                val smb = SmbClientProvider.instance
                val baseUrl = ConversionSettingsStore.getExplicitServiceUrl(app)
                    ?: Hevc264Discovery.discover()
                    ?: ConversionSettingsStore.getServiceUrl(app)
                Log.i(TAG, "serviço=$baseUrl")
                val client = Hevc264Client(baseUrl)
                if (baseUrl.isBlank() || !client.health()) {
                    throw IOException(app.getString(com.baita.renaplay.R.string.convert_error_no_service))
                }

                // MODELO NOVO: o serviço lê do compartilhamento, converte e grava de volta. O
                // aparelho não transporta o vídeo — só acompanha. Se ele for desligado no meio, a
                // conversão continua e é reencontrada depois ([acompanharNoServidor]).
                if (ConversionSettingsStore.isServerSide(app) && client.supportsSmbConvert()) {
                    val id = client.convertSmb(
                        config.ip, config.share, path, config.user, config.password, config.domain)
                    Log.i(TAG, "conversão no servidor, job=$id")
                    phase(ConversionPhase.CONVERTING)
                    seguirProgresso(client, id, ::phase)
                    val finalPath = "${path.substringBeforeLast('.')}.mp4"
                    job = job.copy(phase = ConversionPhase.DONE, percent = 100, newPath = finalPath)
                    publish(job)
                    return@launch
                }

                phase(ConversionPhase.UPLOADING)
                val length = smb.fileLength(
                    config.ip, config.share, path, config.user, config.password, config.domain)
                // O último byte enviado não é o fim do upload: o serviço ainda precisa receber o
                // arquivo inteiro e responder com o id, e num filme isso demora. Mostrar
                // "Enviando… 100%" nessa espera faz a tela parecer travada — daí o PROCESSING.
                val id = client.upload(
                    filename = path.substringAfterLast('/'),
                    contentLength = length,
                    onProgress = { pct ->
                        if (pct >= 100) phase(ConversionPhase.PROCESSING)
                        else phase(ConversionPhase.UPLOADING, pct)
                    }
                ) {
                    smb.openInputStream(
                        config.ip, config.share, path, config.user, config.password, config.domain)
                }

                phase(ConversionPhase.CONVERTING)
                client.streamProgress(id) { p ->
                    if (p.error != null) throw IOException(p.error)
                    phase(ConversionPhase.CONVERTING, p.progress)
                }

                // Grava primeiro num .tmp: uma falha no meio do download nunca destrói o original.
                phase(ConversionPhase.DOWNLOADING)
                val stem = path.substringBeforeLast('.')
                val tmpPath = "$stem.mp4.tmp"
                val finalPath = "$stem.mp4"
                val backupPath = "$path.hevcbak"
                client.download(id, onProgress = { pct -> phase(ConversionPhase.DOWNLOADING, pct) }) { input ->
                    smb.openOutputStream(
                        config.ip, config.share, tmpPath,
                        config.user, config.password, config.domain).use { out ->
                        input.copyTo(out, 1 shl 20)
                    }
                }

                // Só agora a troca. Os deletes antes de cada renameTo existem porque o renameTo do
                // jcifs falha se o destino já existir (restos de tentativas anteriores).
                phase(ConversionPhase.SAVING)
                smb.delete(config.ip, config.share, backupPath,
                    config.user, config.password, config.domain)
                (smb.renameTo(config.ip, config.share, path, backupPath,
                    config.user, config.password, config.domain) as? SmbResult.Failure)
                    ?.let { throw IOException(it.message) }
                smb.delete(config.ip, config.share, finalPath,
                    config.user, config.password, config.domain)
                (smb.renameTo(config.ip, config.share, tmpPath, finalPath,
                    config.user, config.password, config.domain) as? SmbResult.Failure)
                    ?.let { throw IOException(it.message) }

                job = job.copy(phase = ConversionPhase.DONE, percent = 100, newPath = finalPath)
                publish(job)
            } catch (e: Exception) {
                // Sem isto, uma conversão que falha no meio de um filme de 1,3 GB some sem deixar
                // rastro: o app só mostra um toast, e quando o Fire TV recicla o processo (este tem
                // 1 GB de RAM) nem o toast sobra para dizer o que houve.
                Log.e(TAG, "conversão falhou em ${job.phase} (${path})", e)
                val reason = if (e is Hevc264StalledException)
                    app.getString(com.baita.renaplay.R.string.convert_error_stalled)
                else e.message ?: "falha"
                job = job.copy(phase = ConversionPhase.FAILED, error = reason)
                publish(job)
            }
        }
    }
}
