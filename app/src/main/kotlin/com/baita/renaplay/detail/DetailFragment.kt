package com.baita.renaplay.detail

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.DetailsSupportFragment
import androidx.leanback.widget.Action
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.DetailsOverviewRow
import androidx.leanback.widget.FullWidthDetailsOverviewRowPresenter
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnActionClickedListener
import androidx.lifecycle.lifecycleScope
import com.baita.renaplay.R
import com.baita.renaplay.browse.CardPresenter
import com.baita.renaplay.browse.Colecoes
import com.baita.renaplay.browse.MediaItem
import com.baita.renaplay.browse.MediaKind
import com.baita.renaplay.browse.TitleCleaner
import com.baita.renaplay.browse.toTmdbMediaType
import com.baita.renaplay.data.PosterCacheStore
import com.baita.renaplay.data.ServerConfig
import com.baita.renaplay.data.LibraryCacheStore
import com.baita.renaplay.data.ServerConfigStore
import com.baita.renaplay.data.SucaAuthStore
import com.baita.renaplay.conversion.ConversionManager
import com.baita.renaplay.player.PlaybackActivity
import com.baita.renaplay.subtitles.SubtitleSearchActivity
import com.baita.renaplay.suca.PosterLookup
import com.baita.renaplay.suca.SucaApiClient
import com.baita.renaplay.suca.SucaResult
import com.squareup.picasso.Picasso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ACTION_PLAY = 1L
private const val ACTION_SUBTITLES = 2L
private const val ACTION_VIEW_EPISODES = 3L
private const val ACTION_CONVERT = 4L

class DetailFragment : DetailsSupportFragment() {

    private lateinit var config: ServerConfig
    private lateinit var item: MediaItem
    private lateinit var display: DetailDisplay
    private lateinit var overviewRow: DetailsOverviewRow
    private lateinit var rowsAdapter: ArrayObjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cfg = ServerConfigStore.load(requireContext())
        if (cfg == null) {
            requireActivity().finish()
            return
        }
        config = cfg

        val extras = requireActivity().intent
        item = MediaItem(
            id = extras.getStringExtra(DetailActivity.EXTRA_ID) ?: "",
            title = extras.getStringExtra(DetailActivity.EXTRA_TITLE) ?: "",
            kind = MediaKind.valueOf(extras.getStringExtra(DetailActivity.EXTRA_KIND) ?: MediaKind.MOVIE.name),
            path = extras.getStringExtra(DetailActivity.EXTRA_PATH) ?: "",
            isDirectory = extras.getBooleanExtra(DetailActivity.EXTRA_IS_DIRECTORY, false),
            episodeCount = extras.getIntExtra(DetailActivity.EXTRA_EPISODE_COUNT, 0),
            seasonCount = extras.getIntExtra(DetailActivity.EXTRA_SEASON_COUNT, 0),
            tmdbId = extras.getIntExtra(DetailActivity.EXTRA_TMDB_ID, -1).takeIf { it != -1 }
        )
        display = DetailDisplay(item)

        rowsAdapter = ArrayObjectAdapter(ClassPresenterSelector().apply {
            addClassPresenter(DetailsOverviewRow::class.java, buildOverviewPresenter())
            addClassPresenter(ListRow::class.java, ListRowPresenter())
        })
        buildOverviewRow()
        adapter = rowsAdapter
        mostrarColecao()

        BackgroundManager.getInstance(requireActivity()).apply {
            if (!isAttached) attach(requireActivity().window)
            drawable = ContextCompat.getDrawable(requireContext(), R.drawable.gradient_soft_background)
        }

        enrichFromSucaMedia()
    }

    private fun buildOverviewPresenter(): FullWidthDetailsOverviewRowPresenter {
        val presenter = FullWidthDetailsOverviewRowPresenter(DetailDescriptionPresenter())
        presenter.backgroundColor = ContextCompat.getColor(requireContext(), R.color.rena_surface)
        presenter.onActionClickedListener = OnActionClickedListener { action ->
            when (action.id) {
                ACTION_PLAY -> playItem(item)
                ACTION_SUBTITLES -> openSubtitleSearch(item)
                ACTION_VIEW_EPISODES -> openEpisodes(item)
                ACTION_CONVERT -> convertItem(item)
            }
        }
        return presenter
    }

    /**
     * "Antes do Amanhecer", "Antes do Pôr do Sol" e "Antes da Meia-Noite" são um filme em três
     * partes, e a biblioteca os mostrava como três estranhos em ordem alfabética. Aqui a coleção
     * aparece inteira, em ordem de lançamento, com o filme atual marcado — e dá para pular direto
     * para a próxima parte.
     */
    private fun mostrarColecao() {
        if (item.kind != MediaKind.MOVIE) { android.util.Log.i("RenaPlayCol", "não é filme"); return }
        val config = ServerConfigStore.load(requireContext()) ?: run { android.util.Log.i("RenaPlayCol", "sem config"); return }
        val biblioteca = LibraryCacheStore.load(requireContext(), config) ?: run { android.util.Log.i("RenaPlayCol", "sem cache"); return }
        android.util.Log.i("RenaPlayCol", "biblioteca=${biblioteca.size} item='${item.title}' path='${item.path}'")
        android.util.Log.i("RenaPlayCol", "coleções=" + Colecoes.agrupar(biblioteca).joinToString { "${it.nome}(${it.total})" })
        val colecao = Colecoes.colecaoDe(item, biblioteca) ?: run { android.util.Log.i("RenaPlayCol", "sem coleção para este filme"); return }

        val cards = ArrayObjectAdapter(CardPresenter())
        cards.addAll(0, colecao.filmes)
        val posicao = colecao.posicaoDe(item)
        val titulo = getString(R.string.collection_row, colecao.nome, posicao, colecao.total)
        rowsAdapter.add(ListRow(HeaderItem(titulo), cards))
    }

    private fun buildOverviewRow() {
        overviewRow = DetailsOverviewRow(display)
        overviewRow.imageDrawable = placeholderPoster()

        val actions = ArrayObjectAdapter()
        if (item.kind == MediaKind.MOVIE) {
            actions.add(Action(ACTION_PLAY, getString(R.string.action_play)))
            actions.add(Action(ACTION_SUBTITLES, getString(R.string.action_subtitles)))
            actions.add(Action(ACTION_CONVERT, getString(R.string.action_convert)))
        } else {
            actions.add(Action(ACTION_VIEW_EPISODES, getString(R.string.action_view_episodes)))
        }
        overviewRow.actionsAdapter = actions

        rowsAdapter.add(overviewRow)
    }

    /**
     * Best-effort TMDB enrichment via Suca Media's device API: matches this local item's
     * cleaned title against v1/search results and, on a confident hit, swaps the placeholder
     * poster/synopsis for the real TMDB ones. Silently no-ops if unpaired, unreachable, or no
     * good match — the local file always remains playable either way.
     */
    private fun enrichFromSucaMedia() {
        val session = SucaAuthStore.load(requireContext()) ?: return
        if (!session.capabilities.search) return

        val context = requireContext().applicationContext
        // lifecycleScope (não viewLifecycleOwner.lifecycleScope): isso roda a partir de onCreate,
        // antes da view do fragment existir.
        lifecycleScope.launch {
            // item.title já chega limpo (sem tags de qualidade/codec/grupo) via MediaScanner.
            // Para a busca em si, tira também o "(YYYY)" final — o parêntese com o ano prejudica
            // o casamento no backend de busca (ver TitleCleaner.searchQuery).
            val query = item.title
            if (query.isBlank()) return@launch
            val cacheKey = "${item.kind}:$query"

            // Pôster em cache (ex: já resolvido pelo Browse) aparece na hora, sem esperar a rede.
            PosterCacheStore.get(context, cacheKey)?.let { cached ->
                item = item.copy(tmdbId = cached.tmdbId)
                cached.posterUrl?.let { showPoster(it) }
            }

            val searchQuery = TitleCleaner.searchQuery(query)
            val result = withContext(Dispatchers.IO) { SucaApiClient(context).search(session, searchQuery) }
            if (result !is SucaResult.Success) return@launch
            val match = PosterLookup.selectBestMatch(result.value, item.kind, query)
            PosterCacheStore.put(context, cacheKey, match?.id, match?.posterUrl)
            if (match == null) return@launch

            // Propagado até o Intent de reprodução (playItem) para telemetria de atividade e
            // busca de legenda via Suca Media conseguirem casar com o título certo.
            item = item.copy(tmdbId = match.id)

            display.tmdbTitle = match.title
            display.overview = match.overview
            display.rating = match.voteAverage.takeIf { it > 0 }
            display.year = match.releaseYear
            rowsAdapter.notifyArrayItemRangeChanged(0, 1)

            match.backdropUrl?.let { showBackdrop(it) }

            val posterUrl = match.posterUrl ?: return@launch
            showPoster(posterUrl)
        }
    }

    private suspend fun showPoster(posterUrl: String) {
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { Picasso.get().load(posterUrl).get() }.getOrNull()
        } ?: return
        overviewRow.imageDrawable = BitmapDrawable(resources, bitmap)
        rowsAdapter.notifyArrayItemRangeChanged(0, 1)
    }

    private suspend fun showBackdrop(backdropUrl: String) {
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { Picasso.get().load(backdropUrl).get() }.getOrNull()
        } ?: return
        if (isAdded) BackgroundManager.getInstance(requireActivity()).setBitmap(bitmap)
    }

    private fun placeholderPoster(): BitmapDrawable {
        val width = 300
        val height = 450
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(ContextCompat.getColor(requireContext(), R.color.rena_accent))
        return BitmapDrawable(resources, bitmap)
    }

    private fun playItem(target: MediaItem) {
        val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_TITLE, target.title)
            putExtra(PlaybackActivity.EXTRA_PATH, target.path)
            target.tmdbId?.let {
                putExtra(PlaybackActivity.EXTRA_TMDB_ID, it)
                putExtra(PlaybackActivity.EXTRA_MEDIA_TYPE, target.kind.toTmdbMediaType())
            }
        }
        startActivity(intent)
    }

    /**
     * Converte para H.264 8-bit sob demanda, sem esperar o player descobrir que não decodifica.
     * Existe por dois motivos: nem todo Fire TV falha nos mesmos arquivos (o stick novo decodifica
     * HEVC 10-bit que o antigo não toca, e é o antigo que precisa do arquivo convertido), e a
     * conversão não tinha como ser disparada — nem acompanhada — de propósito.
     *
     * Abre o player em seguida porque é ele quem mostra o progresso do job corrente.
     */
    private fun convertItem(target: MediaItem) {
        val config = ServerConfigStore.load(requireContext()) ?: return
        ConversionManager.start(requireContext(), config, target.path, target.title)
        playItem(target)
    }

    private fun openSubtitleSearch(target: MediaItem) {
        val intent = Intent(requireContext(), SubtitleSearchActivity::class.java).apply {
            putExtra(SubtitleSearchActivity.EXTRA_TITLE, target.title)
            putExtra(SubtitleSearchActivity.EXTRA_PATH, target.path)
            target.tmdbId?.let {
                putExtra(SubtitleSearchActivity.EXTRA_TMDB_ID, it)
                putExtra(SubtitleSearchActivity.EXTRA_MEDIA_TYPE, target.kind.toTmdbMediaType())
            }
        }
        startActivity(intent)
    }

    private fun openEpisodes(target: MediaItem) {
        val intent = Intent(requireContext(), EpisodesActivity::class.java).apply {
            putExtra(EpisodesActivity.EXTRA_TITLE, target.title)
            putExtra(EpisodesActivity.EXTRA_PATH, target.path)
        }
        startActivity(intent)
    }
}
