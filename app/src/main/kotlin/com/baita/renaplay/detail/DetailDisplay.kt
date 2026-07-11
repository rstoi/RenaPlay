package com.baita.renaplay.detail

import com.baita.renaplay.browse.MediaItem

/**
 * Mutable wrapper bound to the DetailsOverviewRow: starts with just the local [media] item,
 * then gets enriched in place with a TMDB match from Suca Media (poster/synopsis/rating) if
 * the device is paired and a confident title match is found.
 */
class DetailDisplay(val media: MediaItem) {
    var tmdbTitle: String? = null
    var overview: String? = null
    var rating: Double? = null
    var year: String? = null
}
