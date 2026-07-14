package com.baita.renaplay.brand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.baita.renaplay.R

/**
 * Renders the "Suca" (gradient italic) + "media" (white) wordmark used across the web app,
 * and a combined logo badge (mark + wordmark) for Leanback's BrowseSupportFragment.badgeDrawable
 * (which only accepts a static Drawable, not a live Compose-like gradient text view).
 */
object BrandWordmark {

    private val GRADIENT_STOPS = intArrayOf(0xFFFF8A5C.toInt(), 0xFFFF5E7E.toInt(), 0xFF9B5CFF.toInt())
    private val GRADIENT_POSITIONS = floatArrayOf(0f, 0.55f, 1f)

    fun createBitmap(context: Context, textSizePx: Float): Bitmap {
        val typeface = Typefaces.logo(context)
        val sucaPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
            this.typeface = typeface
            textSize = textSizePx
        }

        val sucaWidth = sucaPaint.measureText("Suca")
        val width = sucaWidth.toInt().coerceAtLeast(1)
        val metrics = sucaPaint.fontMetrics
        val height = (metrics.bottom - metrics.top).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val baseline = -metrics.top

        sucaPaint.shader = LinearGradient(
            0f, 0f, sucaWidth, 0f,
            GRADIENT_STOPS, GRADIENT_POSITIONS, Shader.TileMode.CLAMP
        )
        canvas.drawText("Suca", 0f, baseline, sucaPaint)
        return bitmap
    }

    /** Wordmark only, as a Drawable (e.g. for an ImageView title). */
    fun createDrawable(context: Context, textSizePx: Float): Drawable =
        BitmapDrawable(context.resources, createBitmap(context, textSizePx))

    /** Logo mark + wordmark combined, sized to [heightPx], for BrowseSupportFragment.badgeDrawable. */
    fun createBadgeDrawable(context: Context, heightPx: Int): Drawable {
        val markDrawable = ContextCompat.getDrawable(context, R.drawable.ic_suca_logo)!!
        val markBitmap = markDrawable.toBitmap(width = heightPx, height = heightPx)

        val textSizePx = heightPx * 0.62f
        val wordmarkBitmap = createBitmap(context, textSizePx)
        val gap = (heightPx * 0.18f).toInt()

        val totalWidth = markBitmap.width + gap + wordmarkBitmap.width
        val out = Bitmap.createBitmap(totalWidth, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val markTop = (heightPx - markBitmap.height) / 2f
        canvas.drawBitmap(markBitmap, 0f, markTop, null)
        val wordmarkTop = (heightPx - wordmarkBitmap.height) / 2f
        canvas.drawBitmap(wordmarkBitmap, (markBitmap.width + gap).toFloat(), wordmarkTop, null)
        return BitmapDrawable(context.resources, out)
    }
}
