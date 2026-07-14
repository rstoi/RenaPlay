package com.baita.renaplay.brand

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.baita.renaplay.R

object Typefaces {
    @Volatile private var fraunces: Typeface? = null
    @Volatile private var pacifico: Typeface? = null
    @Volatile private var quicksand: Typeface? = null

    /**
     * Fonte da MARCA. Uma serifada de display com itálico sintético era dura e sem graça no logo;
     * um script de traço contínuo dá à marca o que ela não tinha — personalidade.
     */
    fun logo(context: Context): Typeface {
        pacifico?.let { return it }
        synchronized(this) {
            pacifico?.let { return it }
            val loaded = runCatching {
                ResourcesCompat.getFont(context.applicationContext, R.font.pacifico)
            }.getOrNull() ?: Typeface.SERIF
            pacifico = loaded
            return loaded
        }
    }

    /**
     * Fonte dos TÍTULOS na grade. A serifada pesada brigava com os pôsteres e deixava a tela dura;
     * uma sem-serifa arredondada e leve deixa a leitura fluida e o olho vai para a arte do filme.
     */
    fun title(context: Context): Typeface {
        quicksand?.let { return it }
        synchronized(this) {
            quicksand?.let { return it }
            val loaded = runCatching {
                ResourcesCompat.getFont(context.applicationContext, R.font.quicksand)
            }.getOrNull() ?: Typeface.SANS_SERIF
            quicksand = loaded
            return loaded
        }
    }

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
