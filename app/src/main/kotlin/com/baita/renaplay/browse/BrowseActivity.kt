package com.baita.renaplay.browse

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.baita.renaplay.R

class BrowseActivity : FragmentActivity(R.layout.activity_browse) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.browse_fragment_container, BrowseFragment())
                .commitNow()
        }
    }
}
