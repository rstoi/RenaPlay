package com.baita.renaplay.player

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.leanback.media.PlaybackTransportControlGlue
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.PlaybackControlsRow
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import com.baita.renaplay.R

private const val ACTION_ID_AUDIO_TRACK = 1001L

/**
 * Controles padrão de reprodução (play/pause/seek) + botões extras de Áudio e Legendas,
 * inspirados nos players de referência (Kodi/VLC), abrindo um seletor de faixas sem
 * interromper a reprodução.
 */
class RenaPlaybackGlue(
    context: Context,
    playerAdapter: LeanbackPlayerAdapter,
    private val onSubtitlesClicked: () -> Unit,
    private val onAudioClicked: () -> Unit
) : PlaybackTransportControlGlue<LeanbackPlayerAdapter>(context, playerAdapter) {

    private val subtitlesAction = PlaybackControlsRow.ClosedCaptioningAction(context)
    private val audioAction = AudioTrackAction(context)

    private class AudioTrackAction(context: Context) : PlaybackControlsRow.MultiAction(ACTION_ID_AUDIO_TRACK.toInt()) {
        init {
            setDrawables(arrayOf(ContextCompat.getDrawable(context, R.drawable.ic_language)))
            setLabels(arrayOf(context.getString(R.string.action_audio_track)))
            index = 0
        }
    }

    override fun onCreateSecondaryActions(adapter: ArrayObjectAdapter) {
        super.onCreateSecondaryActions(adapter)
        adapter.add(audioAction)
        adapter.add(subtitlesAction)
    }

    override fun onActionClicked(action: Action) {
        when (action) {
            subtitlesAction -> onSubtitlesClicked()
            audioAction -> onAudioClicked()
            else -> super.onActionClicked(action)
        }
    }
}
