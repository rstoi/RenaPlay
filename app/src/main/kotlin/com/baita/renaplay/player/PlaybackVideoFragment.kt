package com.baita.renaplay.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.baita.renaplay.R
import com.baita.renaplay.conversion.ConversionJob
import com.baita.renaplay.conversion.ConversionManager
import com.baita.renaplay.conversion.ConversionPhase
import com.baita.renaplay.data.ServerConfig
import com.baita.renaplay.data.ServerConfigStore
import com.baita.renaplay.data.SucaAuthStore
import com.baita.renaplay.data.WatchProgressStore
import com.baita.renaplay.smb.SmbClientProvider
import com.baita.renaplay.smb.SmbResult
import com.baita.renaplay.suca.SucaApiClient
import com.baita.renaplay.subtitles.SubtitleSearchActivity
import com.baita.renaplay.subtitles.SubtitleMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub")

private enum class TrackPickerType { SUBTITLE, AUDIO }

class PlaybackVideoFragment : VideoSupportFragment() {

    private var player: ExoPlayer? = null
    private var overlay: FrameLayout? = null
    private var openPicker: TrackPickerType? = null
    private var videoTitle: String = ""
    private var videoPath: String = ""
    private var tmdbId: Int? = null
    private var mediaType: String? = null
    private var progressHandler: Handler? = null
    private var conversionDialog: android.app.AlertDialog? = null
    private var undecodableDialog: android.app.AlertDialog? = null
    private var hasAppliedResume = false
    private var hasSentPlayEvent = false
    private var hasWarnedUndecodableVideo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = ServerConfigStore.load(requireContext()) ?: run {
            requireActivity().finish()
            return
        }

        val intent = requireActivity().intent
        videoTitle = intent.getStringExtra(PlaybackActivity.EXTRA_TITLE) ?: ""
        videoPath = intent.getStringExtra(PlaybackActivity.EXTRA_PATH) ?: ""
        tmdbId = intent.getIntExtra(PlaybackActivity.EXTRA_TMDB_ID, -1).takeIf { it != -1 }
        mediaType = intent.getStringExtra(PlaybackActivity.EXTRA_MEDIA_TYPE)
        val subtitleLocalPath = intent.getStringExtra(PlaybackActivity.EXTRA_SUBTITLE_LOCAL_PATH)
        val subtitleSmbPath = intent.getStringExtra(PlaybackActivity.EXTRA_SUBTITLE_SMB_PATH)

        overlay = requireActivity().findViewById(R.id.track_picker_overlay)
        val subtitleView = requireActivity().findViewById<SubtitleView>(R.id.subtitle_view)

        requireActivity().onBackPressedDispatcher.addCallback(this) {
            if (openPicker != null) {
                hideTrackPicker()
            } else {
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }

        val exoPlayer = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(requireContext())
                    .setDataSourceFactory(CompositeDataSourceFactory(requireContext(), config))
            )
            // Buffers maiores que o padrão do ExoPlayer: streaming via SMB tem latência mais
            // variável que HTTP/CDN, então um buffer grande absorve esses picos sem re-bufferizar
            // no meio do vídeo. Mesma ideia do cache de read-ahead do Kodi (advancedsettings
            // memorysize/readfactor): com o pipeline paralelo de [SmbDataSource] o throughput medido
            // (~1,8 MB/s) é várias vezes o bitrate típico dos arquivos, então dá pra encher um
            // buffer grande rápido e ficar com folga.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        60_000, // mín. antes de arriscar rebuffer
                        300_000, // máx. mantido em buffer
                        1_500, // mín. pra iniciar playback (não atrasa o primeiro frame)
                        5_000 // mín. pra retomar após rebuffer
                    )
                    .build()
            )
            .build()
        player = exoPlayer

        // VideoSupportFragment não tem um SubtitleView embutido: sem isso, o ExoPlayer
        // seleciona a faixa de texto normalmente, mas nenhum texto aparece na tela.
        exoPlayer.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                warnIfVideoUndecodable(tracks)
            }

            override fun onCues(cueGroup: CueGroup) {
                subtitleView.setCues(cueGroup.cues)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !hasAppliedResume) {
                    hasAppliedResume = true
                    val resumeMs = WatchProgressStore.lastPosition(requireContext(), videoPath)
                    if (resumeMs > 5_000L && resumeMs < exoPlayer.duration - 5_000L) {
                        exoPlayer.seekTo(resumeMs)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    val eventType = if (!hasSentPlayEvent) "play" else "resume"
                    hasSentPlayEvent = true
                    sendActivityEvent(eventType, exoPlayer)
                } else if (hasSentPlayEvent) {
                    sendActivityEvent("pause", exoPlayer)
                }
            }
        })

        progressHandler = Handler(Looper.getMainLooper())
        progressHandler?.post(object : Runnable {
            override fun run() {
                exoPlayer.let {
                    if (it.duration > 0) {
                        WatchProgressStore.save(
                            requireContext(), videoPath, videoTitle, it.currentPosition, it.duration, tmdbId, mediaType
                        )
                    }
                }
                progressHandler?.postDelayed(this, 5_000)
            }
        })

        val playerAdapter = LeanbackPlayerAdapter(requireContext(), exoPlayer, 1000)
        val glue = RenaPlaybackGlue(
            requireContext(),
            playerAdapter,
            onSubtitlesClicked = { showTrackPicker(TrackPickerType.SUBTITLE) },
            onAudioClicked = { showTrackPicker(TrackPickerType.AUDIO) }
        )
        glue.title = videoTitle
        glue.host = VideoSupportFragmentGlueHost(this)
        glue.playWhenPrepared()

        // Preferência por português tanto pra faixas de áudio quanto de legenda embutidas no
        // próprio arquivo (ex: MKV com trilhas em vários idiomas) — sem isso o player usa
        // qualquer faixa marcada "default" no arquivo, que pode estar em outro idioma (já visto
        // um MKV com faixa de legenda francesa marcada como padrão). Legendas externas resolvidas
        // por [buildSubtitleConfigFromBytes] são sempre marcadas "pt" também, então essa preferência
        // já serve pros dois casos sem precisar esperar a resolução da legenda externa.
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage("pt")
            .setPreferredAudioLanguage("pt")
            .setSelectUndeterminedTextLanguage(true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()

        // Começa a carregar o vídeo já — não espera a resolução de legenda (que pode envolver uma
        // listagem SMB e o download de um arquivo inteiro) pra não atrasar o primeiro frame.
        exoPlayer.setMediaItem(MediaItem.Builder().setUri(smbPathToUri(videoPath)).build())
        exoPlayer.prepare()

        // Converter troca o nome do arquivo: "filme.mkv" vira "filme.mp4" (o original fica como
        // .hevcbak). Um card vindo do cache da biblioteca, ou de "Continuar assistindo", ainda
        // aponta para o nome antigo — e o vídeo simplesmente não abriria. Aqui, se o caminho pedido
        // não existe mais e há um convertido no lugar, o player troca sozinho.
        lifecycleScope.launch { redirecionarParaConvertidoSeSumiu(config) }

        // Reabrir um vídeo cuja conversão ainda está rolando traz a tela de progresso de volta,
        // em vez de tentar tocar um arquivo que está prestes a ser substituído. A conversão pode
        // estar rolando no SERVIDOR, sem nada do app envolvido — inclusive de uma sessão anterior,
        // ou de outro Fire TV — então, sem job local, vale perguntar a ele.
        observeConversion()
        if (ConversionManager.jobFor(videoPath) == null) {
            ConversionManager.acompanharNoServidor(requireContext(), config, videoPath, videoTitle)
        }

        lifecycleScope.launch {
            val subtitleConfig = resolveSubtitleConfig(config, videoPath, subtitleLocalPath, subtitleSmbPath)
                ?: return@launch
            // Legenda externa encontrada depois que o vídeo já começou: reaplica a mídia com ela,
            // preservando a posição atual — um pequeno soluço aceitável só quando existe legenda
            // externa, em vez de sempre atrasar o início do vídeo esperando por ela.
            exoPlayer.setMediaItem(
                MediaItem.Builder()
                    .setUri(smbPathToUri(videoPath))
                    .setSubtitleConfigurations(listOf(subtitleConfig))
                    .build(),
                exoPlayer.currentPosition
            )
            exoPlayer.prepare()
        }
    }

    /**
     * O arquivo pedido sumiu do compartilhamento e existe o convertido no lugar? Toca o convertido.
     */
    private suspend fun redirecionarParaConvertidoSeSumiu(config: ServerConfig) {
        if (videoPath.endsWith(".mp4", ignoreCase = true)) return
        val convertido = "${videoPath.substringBeforeLast('.')}.mp4"
        val trocou = withContext(Dispatchers.IO) {
            val smb = SmbClientProvider.instance
            val original = runCatching {
                smb.exists(config.ip, config.share, videoPath, config.user, config.password, config.domain)
            }.getOrDefault(true)
            if (original) return@withContext false
            runCatching {
                smb.exists(config.ip, config.share, convertido, config.user, config.password, config.domain)
            }.getOrDefault(false)
        }
        if (!trocou) return

        val exo = player ?: return
        videoPath = convertido
        exo.setMediaItem(MediaItem.Builder().setUri(smbPathToUri(convertido)).build())
        exo.prepare()
    }

    private suspend fun resolveSubtitleConfig(
        config: ServerConfig,
        videoPath: String,
        subtitleLocalPath: String?,
        subtitleSmbPath: String?
    ): MediaItem.SubtitleConfiguration? {
        if (subtitleLocalPath != null) {
            val bytes = withContext(Dispatchers.IO) { runCatching { File(subtitleLocalPath).readBytes() }.getOrNull() }
                ?: return null
            return buildSubtitleConfigFromBytes(bytes, subtitleLocalPath)
        }
        if (subtitleSmbPath != null) {
            val bytes = withContext(Dispatchers.IO) { readSmbBytes(config, subtitleSmbPath) } ?: return null
            return buildSubtitleConfigFromBytes(bytes, subtitleSmbPath)
        }

        // Auto-detecta legenda na mesma pasta do vídeo. Nomes de arquivo raramente batem
        // exatamente (ex: vídeo "Pressure.2026.2026.1080p.WEBRip...NeoNoir.mkv" vs legenda
        // "Pressure.2026.srt") — por isso usa casamento por palavras em comum em vez de exigir
        // nome-base idêntico; sem isso, nenhuma legenda externa é encontrada e o player cai de
        // volta pra faixa de texto embutida "default" do arquivo, que pode estar em outro idioma.
        return withContext(Dispatchers.IO) {
            val dir = videoPath.substringBeforeLast('/', "")
            val videoName = videoPath.substringAfterLast('/')
            val result = SmbClientProvider.instance.listFiles(config.ip, config.share, dir, config.user, config.password, config.domain)
            if (result is SmbResult.Success) {
                val match = result.value
                    .filter {
                        !it.isDirectory && SUBTITLE_EXTENSIONS.contains(it.name.substringAfterLast('.', "").lowercase())
                    }
                    // Só entra automaticamente a legenda que o [SubtitleMatcher] aceita: título,
                    // ano e episódio têm de bater. Uma legenda que ele recusa é de outro filme —
                    // e legenda errada é pior do que legenda nenhuma.
                    .mapNotNull { entry -> SubtitleMatcher.pontuar(videoName, entry.name)?.let { entry to it } }
                    .maxByOrNull { it.second }
                    ?.first
                match?.let { entry ->
                    readSmbBytes(config, entry.path)?.let { bytes -> buildSubtitleConfigFromBytes(bytes, entry.path) }
                }
            } else {
                null
            }
        }
    }

    private fun readSmbBytes(config: ServerConfig, path: String): ByteArray? = runCatching {
        SmbClientProvider.instance.openInputStream(config.ip, config.share, path, config.user, config.password, config.domain)
            .use { it.readBytes() }
    }.getOrNull()

    /**
     * Os decodificadores de legenda do Media3 assumem UTF-8 sem detecção de charset — legendas
     * em Windows-1252/Latin-1 (comuns em rips mais antigos) viram texto com "�" no lugar de
     * acentos. Por isso lemos os bytes brutos aqui, normalizamos para UTF-8 de verdade
     * ([SubtitleEncodingFixer]) e gravamos num arquivo temporário local, em vez de apontar o
     * player direto para a fonte original (local ou SMB).
     */
    private fun buildSubtitleConfigFromBytes(rawBytes: ByteArray, path: String): MediaItem.SubtitleConfiguration {
        val extension = path.substringAfterLast('.', "srt")
        val utf8Bytes = SubtitleEncodingFixer.normalizeToUtf8(rawBytes)
        val tempFile = File.createTempFile("subtitle_", ".$extension", requireContext().cacheDir)
        tempFile.writeBytes(utf8Bytes)
        tempFile.deleteOnExit()

        val mimeType = when (extension.lowercase()) {
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        return MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(tempFile))
            .setMimeType(mimeType)
            .setLanguage("pt")
            // Sem essa flag, uma legenda externa carrega mas nunca fica ativa por padrão no player.
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    }

    private fun showTrackPicker(type: TrackPickerType) {
        val overlayView = overlay ?: return
        val exoPlayer = player ?: return

        overlayView.removeAllViews()
        val panel = layoutInflater.inflate(R.layout.view_track_picker, overlayView, false)
        val titleView = panel.findViewById<TextView>(R.id.track_picker_title)
        val listView = panel.findViewById<RecyclerView>(R.id.track_picker_list)
        listView.layoutManager = LinearLayoutManager(requireContext())

        val options = when (type) {
            TrackPickerType.SUBTITLE -> buildSubtitleOptions(exoPlayer)
            TrackPickerType.AUDIO -> buildAudioOptions(exoPlayer)
        }
        titleView.text = getString(
            if (type == TrackPickerType.SUBTITLE) R.string.track_picker_subtitles_title else R.string.track_picker_audio_title
        )

        val adapter = TrackOptionAdapter(options) { option ->
            option.action()
            hideTrackPicker()
        }
        listView.adapter = adapter

        overlayView.addView(panel)
        overlayView.visibility = View.VISIBLE
        openPicker = type

        listView.post {
            val holder = listView.findViewHolderForAdapterPosition(adapter.firstSelectedIndex())
            holder?.itemView?.requestFocus()
        }
    }

    private fun hideTrackPicker() {
        overlay?.visibility = View.GONE
        overlay?.removeAllViews()
        openPicker = null
    }

    private fun buildSubtitleOptions(exoPlayer: ExoPlayer): List<TrackOption> {
        val textGroups = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val anySelected = textGroups.any { it.isSelected }

        val options = mutableListOf<TrackOption>()
        options += TrackOption(getString(R.string.track_option_subtitles_off), !anySelected) {
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }
        options += buildTrackOptions(exoPlayer, textGroups, C.TRACK_TYPE_TEXT, "Legenda")
        options += TrackOption(getString(R.string.track_option_search_online), selected = false) {
            openSubtitleSearch()
        }
        return options
    }

    private fun buildAudioOptions(exoPlayer: ExoPlayer): List<TrackOption> {
        val audioGroups = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val options = buildTrackOptions(exoPlayer, audioGroups, C.TRACK_TYPE_AUDIO, "Áudio")
        return options.ifEmpty {
            listOf(TrackOption(getString(R.string.track_option_no_audio_tracks), selected = true) {})
        }
    }

    private fun buildTrackOptions(
        exoPlayer: ExoPlayer,
        groups: List<Tracks.Group>,
        trackType: Int,
        fallbackPrefix: String
    ): List<TrackOption> {
        val options = mutableListOf<TrackOption>()
        groups.forEach { group ->
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                val label = trackLabel(format, fallbackPrefix, options.size + 1)
                val selected = group.isTrackSelected(i)
                options += TrackOption(label, selected) {
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(trackType, false)
                        .clearOverridesOfType(trackType)
                        .addOverride(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .build()
                }
            }
        }
        return options
    }

    private fun trackLabel(format: Format, fallbackPrefix: String, index: Int): String {
        val label = format.label
        val lang = format.language
        return when {
            !label.isNullOrBlank() -> label
            !lang.isNullOrBlank() -> runCatching { Locale(lang).displayLanguage.replaceFirstChar { it.uppercase() } }
                .getOrDefault(lang)
            else -> "$fallbackPrefix $index"
        }
    }

    /**
     * Este Fire TV Stick (AFTSS/MediaTek, Fire OS 7) NÃO expõe nenhum decodificador HEVC/H.265 a
     * apps de terceiros — verificado com MediaCodecList: só aparecem AVC, H.263, MPEG-2, MPEG-4,
     * VP8 e VP9, embora o /vendor/etc declare decoders HEVC (a Amazon os reserva aos apps dela).
     * Sem decodificador, o ExoPlayer classifica a faixa de vídeo como UNSUPPORTED_SUBTYPE e
     * simplesmente não a seleciona: o áudio toca e a tela fica PRETA, sem erro nenhum — o pior
     * tipo de falha, silenciosa.
     *
     * Quando isso acontece, oferecemos converter o arquivo para H.264 no próprio RenaPlay: o
     * serviço HEVC264 da rede local recodifica o vídeo e o app SUBSTITUI o arquivo no share,
     * guardando o original como .hevcbak. Depois disso o RenaPlay reproduz normalmente, com o
     * decoder H.264 do aparelho.
     *
     * `allowExceedsCapabilities = true` é essencial: o decoder AVC daqui aceita H.264 High 10
     * (avc1.6E001E) e o exibe corretamente, mas o ExoPlayer o marca como EXCEEDS_CAPABILITIES —
     * e a sobrecarga de um argumento de isTrackSupported() só considera FORMAT_HANDLED. Sem esse
     * flag, um vídeo que toca perfeitamente era dado como indecodificável e o app oferecia
     * converter à toa. O que interessa aqui é o caso real de tela preta: nenhum decoder para o
     * codec (UNSUPPORTED_SUBTYPE), quando a faixa sequer é selecionada.
     */
    private fun warnIfVideoUndecodable(tracks: Tracks) {
        if (hasWarnedUndecodableVideo) return
        val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        if (videoGroups.isEmpty()) return
        val anySupported = videoGroups.any { group ->
            (0 until group.length).any {
                group.isTrackSupported(it, /* allowExceedsCapabilities= */ true)
            }
        }
        if (anySupported) return

        hasWarnedUndecodableVideo = true
        val codec = videoGroups.first().getTrackFormat(0).sampleMimeType
            ?.substringAfterLast('/')?.uppercase()
            ?.let { if (it == "HEVC") "H.265 (HEVC)" else it }
            ?: "desconhecido"

        promptConvertReplace(codec)
    }

    /**
     * Diálogo: avisa que este Fire TV não decodifica o codec e oferece converter+substituir.
     */
    private fun promptConvertReplace(codec: String) {
        // Este arquivo já está sendo convertido (por este aparelho, por outro, ou por uma sessão
        // anterior): oferecer "converter e substituir" seria pedir para recodificar o mesmo filme
        // duas vezes. Quem manda na tela, aqui, é o progresso da conversão que já existe.
        if (ConversionManager.isRunning(videoPath)) return
        undecodableDialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.convert_dialog_title))
            .setMessage(getString(R.string.convert_dialog_message, codec))
            .setPositiveButton(getString(R.string.convert_dialog_confirm)) { _, _ -> startConversion() }
            .setNegativeButton(getString(R.string.convert_dialog_cancel), null)
            .show()
    }

    private fun startConversion() {
        val config = ServerConfigStore.load(requireContext()) ?: return
        ConversionManager.start(requireContext(), config, videoPath, videoTitle)
    }

    /**
     * Espelha na tela o job de conversão deste vídeo, se existir. É o mesmo caminho para os dois
     * casos: acabei de disparar a conversão, ou reabri um vídeo cuja conversão já estava rolando
     * (o StateFlow entrega o estado corrente assim que a coleta começa, então a tela de progresso
     * reaparece sozinha). Quem executa é o [ConversionManager], que vive fora desta tela.
     */
    private fun observeConversion() {
        // lifecycleScope, e não viewLifecycleOwner: isto é chamado de onCreate, quando a View
        // ainda não existe — viewLifecycleOwner estoura ali (IllegalStateException).
        lifecycleScope.launch {
            ConversionManager.jobs.collect { all ->
                val job = all[videoPath] ?: return@collect
                renderConversion(job)
            }
        }
    }

    private fun renderConversion(job: ConversionJob) {
        val context = context ?: return
        // A busca por uma conversão em andamento na rede leva alguns segundos (broadcast + consulta
        // a cada serviço), e nesse meio-tempo o player já descobriu que não decodifica o vídeo e
        // abriu o diálogo. Quando a conversão aparece, ele não faz mais sentido.
        if (job.isRunning) {
            undecodableDialog?.dismiss()
            undecodableDialog = null
        }
        when (job.phase) {
            ConversionPhase.DONE -> {
                dismissConversionDialog()
                ConversionManager.clear(job.path)
                Toast.makeText(context, getString(R.string.convert_done), Toast.LENGTH_LONG).show()
                job.newPath?.let { openConverted(it) }
            }
            ConversionPhase.FAILED -> {
                dismissConversionDialog()
                ConversionManager.clear(job.path)
                Toast.makeText(
                    context,
                    getString(R.string.convert_error, job.error ?: "falha"),
                    Toast.LENGTH_LONG
                ).show()
            }
            else -> {
                // Sem decoder de vídeo, deixar o player rodando só daria áudio sobre tela preta.
                player?.pause()
                showConversionDialog().setMessage(phaseMessage(job))
            }
        }
    }

    private fun phaseMessage(job: ConversionJob): String = when (job.phase) {
        ConversionPhase.DISCOVERING -> getString(R.string.convert_progress_starting)
        ConversionPhase.UPLOADING -> getString(R.string.convert_progress_uploading_pct, job.percent)
        ConversionPhase.PROCESSING -> getString(R.string.convert_progress_processing)
        ConversionPhase.CONVERTING -> getString(R.string.convert_progress_running, job.percent)
        ConversionPhase.DOWNLOADING -> getString(R.string.convert_progress_downloading_pct, job.percent)
        else -> getString(R.string.convert_progress_saving)
    }

    private fun showConversionDialog(): android.app.AlertDialog {
        conversionDialog?.let { return it }
        // Cancelável: sair da tela NÃO cancela a conversão — ela continua no ConversionManager, e
        // reabrir o vídeo traz o progresso de volta.
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.convert_progress_title))
            .setMessage(getString(R.string.convert_progress_starting))
            .setNegativeButton(getString(R.string.convert_progress_background)) { _, _ ->
                dismissConversionDialog()
                requireActivity().finish()
            }
            .create()
        dialog.show()
        conversionDialog = dialog
        return dialog
    }

    private fun dismissConversionDialog() {
        conversionDialog?.dismiss()
        conversionDialog = null
    }

    private fun openConverted(newPath: String) {
        val context = context ?: return
        val intent = Intent(context, PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_TITLE, videoTitle)
            putExtra(PlaybackActivity.EXTRA_PATH, newPath)
            tmdbId?.let { putExtra(PlaybackActivity.EXTRA_TMDB_ID, it) }
            mediaType?.let { putExtra(PlaybackActivity.EXTRA_MEDIA_TYPE, it) }
        }
        startActivity(intent)
        requireActivity().finish()
    }


    private fun openSubtitleSearch() {
        val intent = Intent(requireContext(), SubtitleSearchActivity::class.java).apply {
            putExtra(SubtitleSearchActivity.EXTRA_TITLE, videoTitle)
            putExtra(SubtitleSearchActivity.EXTRA_PATH, videoPath)
            tmdbId?.let {
                putExtra(SubtitleSearchActivity.EXTRA_TMDB_ID, it)
                putExtra(SubtitleSearchActivity.EXTRA_MEDIA_TYPE, mediaType)
            }
        }
        startActivity(intent)
        requireActivity().finish()
    }

    /** Best-effort playback telemetry to Suca Media, only when paired. Never blocks the UI. */
    private fun sendActivityEvent(eventType: String, exoPlayer: ExoPlayer) {
        val context = requireContext().applicationContext
        val session = SucaAuthStore.load(context) ?: return
        val positionSeconds = (exoPlayer.currentPosition / 1000).toInt()
        val durationSeconds = (exoPlayer.duration.takeIf { it > 0 } ?: 0L).let { (it / 1000).toInt() }
        val title = videoTitle
        val eventTmdbId = tmdbId
        val eventMediaType = mediaType
        Thread {
            runCatching {
                SucaApiClient(context).logActivity(
                    session = session,
                    eventType = eventType,
                    tmdbId = eventTmdbId,
                    mediaType = eventMediaType,
                    title = title,
                    positionSeconds = positionSeconds,
                    durationSeconds = durationSeconds
                )
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        progressHandler?.removeCallbacksAndMessages(null)
        progressHandler = null
        player?.let {
            if (it.duration > 0) {
                WatchProgressStore.save(
                    requireContext(), videoPath, videoTitle, it.currentPosition, it.duration, tmdbId, mediaType
                )
                if (hasSentPlayEvent) {
                    val fraction = it.currentPosition.toFloat() / it.duration.toFloat()
                    sendActivityEvent(if (fraction >= 0.95f) "complete" else "stop", it)
                }
            }
        }
        player?.release()
        player = null
    }
}
