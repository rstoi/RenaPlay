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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.playback_fragment_container, PlaybackVideoFragment())
                .commitNow()
        }
    }
}
