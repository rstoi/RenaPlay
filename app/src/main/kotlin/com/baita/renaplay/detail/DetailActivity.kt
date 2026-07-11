package com.baita.renaplay.detail

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.baita.renaplay.R

class DetailActivity : FragmentActivity(R.layout.activity_detail) {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_KIND = "kind"
        const val EXTRA_PATH = "path"
        const val EXTRA_IS_DIRECTORY = "is_directory"
        const val EXTRA_EPISODE_COUNT = "episode_count"
        const val EXTRA_SEASON_COUNT = "season_count"
        /** TMDB id já resolvido (ex: pelo Browse) — evita esperar o cache/rede de novo na tela de detalhe. */
        const val EXTRA_TMDB_ID = "tmdb_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.detail_fragment_container, DetailFragment())
                .commitNow()
        }
    }
}
