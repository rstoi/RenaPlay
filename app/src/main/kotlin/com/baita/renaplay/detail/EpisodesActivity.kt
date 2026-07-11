package com.baita.renaplay.detail

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.baita.renaplay.R

class EpisodesActivity : FragmentActivity(R.layout.activity_browse) {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_PATH = "path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.browse_fragment_container, EpisodesFragment())
                .commitNow()
        }
    }
}
