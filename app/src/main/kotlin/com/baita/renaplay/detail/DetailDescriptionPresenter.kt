package com.baita.renaplay.detail

import androidx.leanback.widget.AbstractDetailsDescriptionPresenter
import com.baita.renaplay.browse.MediaKind

class DetailDescriptionPresenter : AbstractDetailsDescriptionPresenter() {
    override fun onBindDescription(viewHolder: ViewHolder, item: Any) {
        val display = item as DetailDisplay
        val media = display.media
        viewHolder.title.text = display.tmdbTitle ?: media.title

        val kindLabel = when {
            media.kind == MediaKind.MOVIE -> "Filme"
            media.seasonCount > 1 -> "Série · ${media.seasonCount} temporadas"
            media.episodeCount > 0 -> "Série · ${media.episodeCount} episódios"
            else -> "Série"
        }
        val ratingLabel = display.rating?.let { " · ★ %.1f".format(it) } ?: ""
        val yearLabel = display.year?.let { " · $it" } ?: ""
        viewHolder.subtitle.text = "$kindLabel$yearLabel$ratingLabel"

        viewHolder.body.text = display.overview.orEmpty()
    }
}
