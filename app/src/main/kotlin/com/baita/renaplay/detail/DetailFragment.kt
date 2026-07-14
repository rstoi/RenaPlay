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
import android.widget.Toast
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnActionClickedListener
import androidx.lifecycle.lifecycleScope
import com.baita.renaplay.R
import com.baita.renaplay.browse.CardPresenter
import com.baita.renaplay.browse.Colecoes
import com.baita.renaplay.browse.MediaItem
import com.baita.renaplay.browse.MediaCategory
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
import com.baita.renaplay.suca.SucaCollection
import com.baita.renaplay.suca.SucaResult
import com.squareup.picasso.Picasso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ACTION_PLAY = 1L
private const val ACTION_SUBTITLES = 2L
private const val ACTION_VIEW_EPISODES = 3L
private const val ACTION_CONVERT = 4L

private const val PREFIXO_FALTANTE = "tmdb-faltante-"

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
        configurarCliquesDaColecao()
        mostrarColecao()

        BackgroundManager.getInstance(requireActivity()).apply {
            if (!isAttached) attach(requireActivity().window)
            drawable = ContextCompat.getDrawable(requireContext(), R.drawable.gradient_soft_background)
        }

        enrichFromSucaMedia()
    }

    private fun configurarCliquesDaColecao() {
        onItemViewClickedListener = androidx.leanback.widget.OnItemViewClickedListener { _, alvo, _, _ ->
            val outro = alvo as? MediaItem ?: return@OnItemViewClickedListener
            if (outro.path.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.collection_missing_toast), Toast.LENGTH_LONG).show()
            } else if (outro.path != item.path) {
                startActivity(
                    Intent(requireContext(), DetailActivity::class.java).apply {
                        putExtra(DetailActivity.EXTRA_ID, outro.id)
                        putExtra(DetailActivity.EXTRA_TITLE, outro.title)
                        putExtra(DetailActivity.EXTRA_KIND, outro.kind.name)
                        putExtra(DetailActivity.EXTRA_PATH, outro.path)
                        putExtra(DetailActivity.EXTRA_IS_DIRECTORY, outro.isDirectory)
                        putExtra(DetailActivity.EXTRA_EPISODE_COUNT, outro.episodeCount)
                        putExtra(DetailActivity.EXTRA_SEASON_COUNT, outro.seasonCount)
                        outro.tmdbId?.let { putExtra(DetailActivity.EXTRA_TMDB_ID, it) }
                    }
                )
            }
        }
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
        if (item.kind != MediaKind.MOVIE) return
        val config = ServerConfigStore.load(requireContext()) ?: return
        val biblioteca = LibraryCacheStore.load(requireContext(), config) ?: return

        lifecycleScope.launch {
            // Primeiro a coleção DE VERDADE, do TMDB (é o "movie set" que o Kodi usa). Ela sabe da
            // trilogia inteira — inclusive das partes que ainda não estão no compartilhamento, o que
            // é metade da graça: dá para ver o que falta.
            val doTmdb = colecaoDoTmdb()
            if (doTmdb != null && doTmdb.parts.size > 1) {
                mostrarPartes(doTmdb, biblioteca)
                return@launch
            }
            // Sem TMDB (não pareado, backend antigo, filme sem coleção): infere pelo nome e pelo ano.
            val local = Colecoes.colecaoDe(item, biblioteca) ?: return@launch
            val cards = ArrayObjectAdapter(CardPresenter())
            cards.addAll(0, local.filmes)
            rowsAdapter.add(
                ListRow(
                    HeaderItem(getString(R.string.collection_row, local.nome, local.posicaoDe(item), local.total)),
                    cards
                )
            )
        }
    }

    private suspend fun colecaoDoTmdb(): SucaCollection? {
        val tmdb = item.tmdbId ?: run { android.util.Log.i("RenaPlayCol", "sem tmdbId"); return null }
        val session = SucaAuthStore.load(requireContext()) ?: run { android.util.Log.i("RenaPlayCol", "não pareado"); return null }
        return withContext(Dispatchers.IO) {
            when (val r = SucaApiClient(requireContext()).collection(session, tmdb)) {
                is SucaResult.Success -> {
                    android.util.Log.i("RenaPlayCol", "coleção TMDB: ${r.value?.name} (${r.value?.parts?.size} partes)")
                    r.value
                }
                is SucaResult.Failure -> {
                    android.util.Log.e("RenaPlayCol", "falha na coleção TMDB: ${r.message}")
                    null
                }
            }
        }
    }

    /**
     * A coleção inteira, em ordem de lançamento. O que está na biblioteca abre; o que falta aparece
     * marcado — é a "previsão da sequência": dá para saber que existe uma parte 3 antes de procurá-la.
     */
    private fun mostrarPartes(colecao: SucaCollection, biblioteca: List<MediaItem>) {
        val cards = ArrayObjectAdapter(CardPresenter())
        var posicao = 0
        colecao.parts.forEachIndexed { i, parte ->
            val naBiblioteca = biblioteca.firstOrNull { it.tmdbId == parte.tmdbId }
                ?: biblioteca.firstOrNull { mesmoTitulo(it.title, parte.title) }
            if (parte.tmdbId == item.tmdbId) posicao = i + 1
            cards.add(
                naBiblioteca?.copy(remotePosterUrl = naBiblioteca.remotePosterUrl ?: parte.posterUrl)
                    ?: MediaItem(
                        id = "$PREFIXO_FALTANTE${parte.tmdbId}",
                        title = getString(R.string.collection_missing, parte.title),
                        kind = MediaKind.MOVIE,
                        path = "",
                        isDirectory = false,
                        category = MediaCategory.FILME,
                        remotePosterUrl = parte.posterUrl,
                        tmdbId = parte.tmdbId
                    )
            )
        }
        val cabecalho = getString(R.string.collection_row, colecao.name, posicao, colecao.parts.size)
        rowsAdapter.add(ListRow(HeaderItem(cabecalho), cards))
    }

    private fun mesmoTitulo(a: String, b: String) =
        a.substringBeforeLast(" (").equals(b, ignoreCase = true)

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
