package com.baita.renaplay.conversion

import android.content.Context
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
                job = job.copy(phase = p, percent = percent)
                publish(job)
            }
            try {
                val smb = SmbClientProvider.instance
                val baseUrl = Hevc264Discovery.discover()
                    ?: ConversionSettingsStore.getServiceUrl(app)
                val client = Hevc264Client(baseUrl)
                if (baseUrl.isBlank() || !client.health()) {
                    throw IOException(app.getString(com.baita.renaplay.R.string.convert_error_no_service))
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
                val reason = if (e is Hevc264StalledException)
                    app.getString(com.baita.renaplay.R.string.convert_error_stalled)
                else e.message ?: "falha"
                job = job.copy(phase = ConversionPhase.FAILED, error = reason)
                publish(job)
            }
        }
    }
}
