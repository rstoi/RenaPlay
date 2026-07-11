package com.baita.renaplay

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.baita.renaplay.browse.BrowseActivity
import com.baita.renaplay.data.ServerConfigStore
import com.baita.renaplay.setup.ServerSetupActivity

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = ServerConfigStore.load(this)
        val next = if (config == null) ServerSetupActivity::class.java else BrowseActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }
}
