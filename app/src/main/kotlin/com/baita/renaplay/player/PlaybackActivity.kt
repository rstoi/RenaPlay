package com.baita.renaplay.player

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.baita.renaplay.R

class PlaybackActivity : FragmentActivity(R.layout.activity_playback) {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_PATH = "path"
        const val EXTRA_SUBTITLE_LOCAL_PATH = "subtitle_local_path"
        const val EXTRA_SUBTITLE_SMB_PATH = "subtitle_smb_path"
        /** TMDB id resolvido via Suca Media, quando disponível — ver [com.baita.renaplay.browse.MediaItem.tmdbId]. */
        const val EXTRA_TMDB_ID = "tmdb_id"
        /** "movie" ou "tv" — ver [com.baita.renaplay.browse.toTmdbMediaType]. */
        const val EXTRA_MEDIA_TYPE = "media_type"
    }

    /**
     * As teclas de salto precisam chegar ao fragment ANTES do Leanback: com o overlay fechado, ele
     * engole esquerda/direita para abrir os controles, e o filme nunca avança.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val fragment = supportFragmentManager.findFragmentById(R.id.playback_fragment_container)
        if (fragment is PlaybackVideoFragment && fragment.onKey(event.keyCode, event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.playback_fragment_container, PlaybackVideoFragment())
                .commitNow()
        }
    }
}
