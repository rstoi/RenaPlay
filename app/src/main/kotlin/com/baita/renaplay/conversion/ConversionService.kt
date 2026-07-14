package com.baita.renaplay.conversion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.baita.renaplay.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Existe por um motivo só: impedir que o processo morra no meio da conversão.
 *
 * O Fire TV Stick tem 922MB de RAM. Assim que o usuário sai do player — e o botão "Continuar em
 * segundo plano" convida a isso — o app vira processo de background e vira o primeiro candidato do
 * Android sob pressão de memória. Num teste real o processo foi morto durante o upload e a
 * conversão morreu junto, sem aviso: o [ConversionManager] guarda o estado em memória, e o usuário
 * reencontrou o vídeo oferecendo converter de novo, como se nada tivesse acontecido.
 *
 * Este serviço não converte nada — quem faz o trabalho continua sendo o [ConversionManager]. Ele
 * só mantém o processo em prioridade de foreground enquanto houver job rodando, espelha o
 * progresso numa notificação e se encerra sozinho quando o trabalho acaba.
 */
class ConversionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(getString(R.string.convert_progress_starting), 0)

        scope.launch {
            ConversionManager.jobs.collect { all ->
                val running = all.values.firstOrNull { it.isRunning }
                if (running != null) {
                    notify(NOTIFICATION_ID, buildNotification(describe(running), running.percent, true))
                    return@collect
                }

                // Nada mais rodando. Antes de sair, avisa como terminou — se o usuário estiver
                // fora do app, esta notificação é a única coisa que ele vai ver.
                all.values.lastOrNull()?.let { done ->
                    val text = when (done.phase) {
                        ConversionPhase.DONE -> getString(R.string.convert_done)
                        ConversionPhase.FAILED ->
                            getString(R.string.convert_error, done.error ?: "falha")
                        else -> null
                    }
                    if (text != null) {
                        notify(RESULT_NOTIFICATION_ID, buildNotification(text, 0, false))
                    }
                }
                stop()
            }
        }
        // START_NOT_STICKY: se o sistema matar o processo mesmo assim, não adianta ressuscitar o
        // serviço — o job vive na memória que se foi junto, e não há o que retomar.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun describe(job: ConversionJob): String = when (job.phase) {
        ConversionPhase.DISCOVERING -> getString(R.string.convert_progress_starting)
        ConversionPhase.UPLOADING -> getString(R.string.convert_progress_uploading_pct, job.percent)
        ConversionPhase.PROCESSING -> getString(R.string.convert_progress_processing)
        ConversionPhase.CONVERTING -> getString(R.string.convert_progress_running, job.percent)
        ConversionPhase.DOWNLOADING -> getString(R.string.convert_progress_downloading_pct, job.percent)
        else -> getString(R.string.convert_progress_saving)
    }

    private fun startInForeground(text: String, percent: Int) {
        val notification = buildNotification(text, percent, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(text: String, percent: Int, ongoing: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.convert_progress_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .apply { if (ongoing) setProgress(100, percent, percent == 0) }
            .build()

    private fun notify(id: Int, notification: Notification) {
        manager().notify(id, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.convert_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager().createNotificationChannel(channel)
    }

    private fun manager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "conversion"
        private const val NOTIFICATION_ID = 1001
        private const val RESULT_NOTIFICATION_ID = 1002
    }
}
