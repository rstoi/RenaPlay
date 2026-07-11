package com.baita.renaplay.brand

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.baita.renaplay.R

object Typefaces {
    @Volatile private var fraunces: Typeface? = null

    /** Display serif used for titles/headings, mirroring the web app's `font-display` (Fraunces). */
    fun display(context: Context): Typeface {
        fraunces?.let { return it }
        synchronized(this) {
            fraunces?.let { return it }
            val loaded = runCatching {
                ResourcesCompat.getFont(context.applicationContext, R.font.fraunces)
            }.getOrNull() ?: Typeface.SERIF
            fraunces = loaded
            return loaded
        }
    }
}
