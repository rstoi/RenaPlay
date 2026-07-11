package com.baita.renaplay.browse

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.baita.renaplay.R
import com.baita.renaplay.brand.Typefaces
import com.baita.renaplay.data.WatchProgressStore
import com.squareup.picasso.Picasso

private const val CARD_WIDTH_PX = 300
private const val FOCUS_SCALE = 1.08f
private const val FOCUS_DURATION_MS = 180L

class CardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.view_media_card, parent, false)
        view.findViewById<TextView>(R.id.card_title).typeface = Typefaces.display(parent.context)
        view.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) FOCUS_SCALE else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(FOCUS_DURATION_MS).start()
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val media = item as MediaItem
        val view = viewHolder.view
        val context = view.context

        view.findViewById<TextView>(R.id.card_title).text = media.title
        val subtitleView = view.findViewById<TextView>(R.id.card_subtitle)
        val subtitle = subtitleFor(media)
        subtitleView.text = subtitle
        subtitleView.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        val imageView = view.findViewById<ImageView>(R.id.card_image)
        if (media.remotePosterUrl != null) {
            imageView.setImageDrawable(ColorDrawable(colorFor(media.title)))
            Picasso.get().load(media.remotePosterUrl).into(imageView)
        } else {
            imageView.setImageDrawable(ColorDrawable(colorFor(media.title)))
        }

        val track = view.findViewById<View>(R.id.card_progress_track)
        val fill = view.findViewById<View>(R.id.card_progress_fill)
        val progress = WatchProgressStore.progress(context, media.path)
        if (progress > 0.02f) {
            track.visibility = View.VISIBLE
            fill.visibility = View.VISIBLE
            fill.layoutParams = fill.layoutParams.apply {
                width = (CARD_WIDTH_PX * progress).toInt().coerceAtLeast(1)
            }
            fill.requestLayout()
        } else {
            track.visibility = View.GONE
            fill.visibility = View.GONE
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val imageView = viewHolder.view.findViewById<ImageView>(R.id.card_image)
        Picasso.get().cancelRequest(imageView)
        imageView.setImageDrawable(null)
    }

    // Não indicamos mais "Filme"/"Série" abaixo do pôster — só o que agrega valor real
    // (quantas temporadas/episódios tem uma série).
    private fun subtitleFor(media: MediaItem): String {
        if (media.kind != MediaKind.SERIES) return ""
        return when {
            media.seasonCount > 1 -> "${media.seasonCount} temporadas"
            media.episodeCount > 0 -> "${media.episodeCount} episódios"
            else -> ""
        }
    }

    private fun colorFor(title: String): Int {
        // Muted, darkened takes on the brand palette (magenta/coral/amber/mint/cyan/violet)
        // so placeholder posters still read as part of the same visual identity.
        val palette = intArrayOf(
            Color.parseColor("#6B1F3C"), // magenta
            Color.parseColor("#6B3A22"), // coral
            Color.parseColor("#6B5A1F"), // amber
            Color.parseColor("#1F5C4C"), // mint
            Color.parseColor("#1F4A6B"), // cyan
            Color.parseColor("#3E2A6B")  // violet
        )
        val index = (title.hashCode().let { if (it < 0) -it else it }) % palette.size
        return palette[index]
    }
}
