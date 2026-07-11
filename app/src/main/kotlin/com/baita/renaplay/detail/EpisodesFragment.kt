package com.baita.renaplay.detail

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.baita.renaplay.R
import com.baita.renaplay.browse.CardPresenter
import com.baita.renaplay.browse.MediaItem
import com.baita.renaplay.browse.MediaKind
import com.baita.renaplay.browse.toTmdbMediaType
import com.baita.renaplay.data.ServerConfigStore
import com.baita.renaplay.data.SucaAuthStore
import com.baita.renaplay.player.PlaybackActivity
import com.baita.renaplay.smb.SmbClientProvider
import com.baita.renaplay.smb.SmbResult
import com.baita.renaplay.browse.MediaScanner
import com.baita.renaplay.suca.PosterLookup
import com.squareup.picasso.Picasso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EpisodesFragment : BrowseSupportFragment() {

    private val episodeAdapters = mutableListOf<ArrayObjectAdapter>()

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val seriesTitle = requireActivity().intent.getStringExtra(EpisodesActivity.EXTRA_TITLE) ?: ""
        val seriesPath = requireActivity().intent.getStringExtra(EpisodesActivity.EXTRA_PATH) ?: ""

        title = seriesTitle
        headersState = HEADERS_ENABLED

        BackgroundManager.getInstance(requireActivity()).apply {
            if (!isAttached) attach(requireActivity().window)
            drawable = ContextCompat.getDrawable(requireContext(), R.drawable.gradient_soft_background)
        }

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is MediaItem) {
                val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
                    putExtra(PlaybackActivity.EXTRA_TITLE, item.title)
                    putExtra(PlaybackActivity.EXTRA_PATH, item.path)
                    item.tmdbId?.let {
                        putExtra(PlaybackActivity.EXTRA_TMDB_ID, it)
                        putExtra(PlaybackActivity.EXTRA_MEDIA_TYPE, item.kind.toTmdbMediaType())
                    }
                }
                startActivity(intent)
            }
        }

        loadEpisodes(seriesPath, seriesTitle)
    }

    private fun loadEpisodes(seriesPath: String, seriesTitle: String) {
        val config = ServerConfigStore.load(requireContext())
        if (config == null) {
            requireActivity().finish()
            return
        }

        progressBarManager.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                MediaScanner(SmbClientProvider.instance).listSeasons(config, seriesPath)
            }
            progressBarManager.hide()

            when (result) {
                is SmbResult.Success -> {
                    val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
                    episodeAdapters.clear()
                    for (season in result.value) {
                        val episodesAdapter = ArrayObjectAdapter(CardPresenter())
                        episodesAdapter.addAll(0, season.episodes)
                        episodeAdapters += episodesAdapter
                        rowsAdapter.add(ListRow(HeaderItem(season.label), episodesAdapter))
                    }
                    adapter = rowsAdapter
                    enrichWithSeriesPoster(seriesTitle)
                }
                is SmbResult.Failure -> Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Os episódios não têm pôster próprio no TMDB (a busca do Suca Media é por filme/série, não
     * por episódio) — então usamos o pôster da série em todos os cards de episódio e como fundo
     * da tela, em vez de deixar cada card num retângulo de cor lisa.
     */
    private fun enrichWithSeriesPoster(seriesTitle: String) {
        val context = requireContext().applicationContext
        val session = SucaAuthStore.load(context) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val match = withContext(Dispatchers.IO) {
                PosterLookup.resolve(context, session, MediaKind.SERIES, seriesTitle)
            } ?: return@launch

            // Episódios não têm entrada própria no TMDB — herdam o tmdbId da série (mesmo id que
            // vai para telemetria de atividade e busca de legenda via Suca Media ao reproduzir).
            for (episodesAdapter in episodeAdapters) {
                for (index in 0 until episodesAdapter.size()) {
                    val media = episodesAdapter.get(index) as? MediaItem ?: continue
                    episodesAdapter.replace(
                        index,
                        media.copy(
                            remotePosterUrl = media.remotePosterUrl ?: match.posterUrl,
                            tmdbId = media.tmdbId ?: match.tmdbId
                        )
                    )
                }
            }

            val posterUrl = match.posterUrl ?: return@launch
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { Picasso.get().load(posterUrl).get() }.getOrNull()
            } ?: return@launch
            BackgroundManager.getInstance(requireActivity()).setBitmap(bitmap)
        }
    }
}
