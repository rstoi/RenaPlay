package com.baita.renaplay.browse

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
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
import com.baita.renaplay.brand.BrandWordmark
import com.baita.renaplay.data.EpisodeThumbnailStore
import com.baita.renaplay.data.LibraryCacheStore
import com.baita.renaplay.data.ServerConfigStore
import com.baita.renaplay.data.SucaAuthStore
import com.baita.renaplay.data.SucaSession
import com.baita.renaplay.data.WatchProgressStore
import com.baita.renaplay.pairing.PairingActivity
import com.baita.renaplay.detail.DetailActivity
import com.baita.renaplay.player.PlaybackActivity
import com.baita.renaplay.setup.ServerSetupActivity
import com.baita.renaplay.settings.SettingsActivity
import com.baita.renaplay.smb.SmbClientProvider
import com.baita.renaplay.smb.SmbResult
import com.baita.renaplay.suca.PosterLookup
import com.baita.renaplay.suca.SucaApiClient
import com.baita.renaplay.suca.SucaResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

// Categorias cujo título tem chance real de existir no TMDB — vídeos pessoais e "outros"
// (nomes crus tipo "$RZ9SN2U") nunca são pesquisados, pois desperdiçariam chamadas fadadas
// a falhar e poderiam até casar por acidente com um resultado errado.
private val POSTER_ELIGIBLE_CATEGORIES = setOf(
    MediaCategory.FILME, MediaCategory.SERIE, MediaCategory.DOCUMENTARIO, MediaCategory.MUSICAL
)

private const val CONTINUE_WATCHING_ID_PREFIX = "continue-"

class BrowseFragment : BrowseSupportFragment() {

    private val itemAdapters = mutableListOf<ArrayObjectAdapter>()
    private var localItems: List<MediaItem> = emptyList()
    private var statusText: TextView? = null
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusDateFormat = SimpleDateFormat("EEE, d MMM · HH:mm", Locale("pt", "BR"))

    private val statusUpdater = object : Runnable {
        override fun run() {
            updateStatus()
            statusHandler.postDelayed(this, 30_000)
        }
    }

    override fun onResume() {
        super.onResume()
        // Refaz o bind das linhas (sem rescanear o SMB) para que a barra de progresso
        // de "assistido" reflita o que acabou de ser visto ao voltar do player.
        itemAdapters.forEach { it.notifyArrayItemRangeChanged(0, it.size()) }
        statusHandler.post(statusUpdater)
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusUpdater)
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Sem o badge padrão do Leanback (ele fica preso à barra de título por cima da grade,
        // longe da coluna de menu) — a logo e o status são views nossas, sobrepostas.
        badgeDrawable = null
        title = ""
        // brandColor paints the headers panel background — keep it a muted dark surface,
        // not the vivid accent (which is reserved for focus rings and CTAs).
        brandColor = ContextCompat.getColor(requireContext(), R.color.rena_surface)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.rena_accent)
        headersState = HEADERS_HIDDEN
        isHeadersTransitionOnBackEnabled = true

        BackgroundManager.getInstance(requireActivity()).apply {
            if (!isAttached) attach(requireActivity().window)
            drawable = ContextCompat.getDrawable(requireContext(), R.drawable.gradient_soft_background)
        }

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when {
                item is MediaItem && item.id.startsWith(CONTINUE_WATCHING_ID_PREFIX) -> resumePlayback(item)
                item is MediaItem -> openDetail(item)
                item is SettingsAction -> handleSettingsAction(item)
            }
        }

        setupOverlay(view)
        loadLibrary()
    }

    /**
     * Logo (sem o "Suca media" completo — só "Suca", já que o combo com "media" some por pedido)
     * fixada no topo da coluna de menu, e uma linha de status (data/hora + conexão com o app
     * web) no canto oposto. Views soltas sobre a raiz do fragment, fora do sistema de
     * linhas/headers do Leanback — mais simples e seguro do que injetar no dock interno dele.
     */
    private fun setupOverlay(view: View) {
        val root = view as? ViewGroup ?: return
        val density = resources.displayMetrics.density

        val logoView = ImageView(requireContext()).apply {
            setImageDrawable(BrandWordmark.createBadgeDrawable(requireContext(), (40 * density).toInt()))
            adjustViewBounds = true
        }
        root.addView(
            logoView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = (16 * density).toInt()
                leftMargin = (48 * density).toInt()
            }
        )

        val status = TextView(requireContext()).apply {
            setTextColor(ContextCompat.getColor(requireContext(), R.color.rena_text_secondary))
            textSize = 13f
            gravity = Gravity.END
            isFocusable = true
            isFocusableInTouchMode = false
            setOnClickListener { startActivity(Intent(requireContext(), SettingsActivity::class.java)) }
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = (20 * density).toInt()
                rightMargin = (48 * density).toInt()
            }
        )
        statusText = status
        updateStatus()
    }

    private fun updateStatus() {
        val context = context ?: return
        val now = statusDateFormat.format(java.util.Date()).replaceFirstChar { it.uppercase() }
        val connected = SucaAuthStore.load(context) != null
        val connectionLabel = if (connected) "● Suca Media conectado" else "○ Suca Media não conectado"
        val libraryLabel = if (localItems.isNotEmpty()) " · ${localItems.size} títulos" else ""
        statusText?.text = "$now  ·  $connectionLabel$libraryLabel"
    }

    /**
     * Mostra a última varredura bem-sucedida ([LibraryCacheStore]) na hora, sem spinner, enquanto
     * uma nova varredura SMB roda em segundo plano — só quando não há nada em cache (primeira
     * abertura do app, ou servidor recém-configurado) é que a grade espera pelo scan com spinner,
     * como antes.
     */
    private fun loadLibrary() {
        val config = ServerConfigStore.load(requireContext())
        if (config == null) {
            startActivity(Intent(requireContext(), ServerSetupActivity::class.java))
            requireActivity().finish()
            return
        }

        val cached = LibraryCacheStore.load(requireContext(), config)
        if (cached != null) {
            renderLibrary(cached)
        } else {
            progressBarManager.show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                MediaScanner(SmbClientProvider.instance).scanRoot(config)
            }
            progressBarManager.hide()

            when (result) {
                is SmbResult.Success -> {
                    LibraryCacheStore.save(requireContext(), config, result.value)
                    // Já exibido a partir do cache e nada mudou: não reconstrói a grade à toa
                    // (evitaria perder o foco/posição em que o usuário está navegando).
                    if (result.value != localItems) {
                        renderLibrary(result.value)
                    }
                }
                is SmbResult.Failure -> {
                    if (cached == null) {
                        renderLibrary(emptyList())
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                    }
                    // Com cache já na tela, uma varredura que falhou (ex: NAS temporariamente fora
                    // do ar) não precisa incomodar o usuário — a biblioteca em cache segue navegável.
                }
            }
        }
    }

    private fun renderLibrary(items: List<MediaItem>) {
        localItems = items
        val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        itemAdapters.clear()

        loadContinueWatching(rowsAdapter)
        val categoryRows = listOf(
            MediaCategory.FILME to R.string.row_movies,
            MediaCategory.SERIE to R.string.row_series,
            MediaCategory.DOCUMENTARIO to R.string.row_documentaries,
            MediaCategory.MUSICAL to R.string.row_musicals,
            MediaCategory.PESSOAL to R.string.row_personal,
            MediaCategory.OUTRO to R.string.row_other
        )
        for ((category, titleRes) in categoryRows) {
            val categoryItems = items.filter { it.category == category }
            if (categoryItems.isEmpty()) continue
            val itemsAdapter = ArrayObjectAdapter(CardPresenter())
            itemsAdapter.addAll(0, categoryItems)
            itemAdapters += itemsAdapter
            rowsAdapter.add(ListRow(HeaderItem(getString(titleRes)), itemsAdapter))
        }

        rowsAdapter.add(settingsRow())

        adapter = rowsAdapter
        loadCloudLibrary(rowsAdapter)
        updateStatus()

        val context = requireContext().applicationContext
        SucaAuthStore.load(context)?.let { session -> enrichPosters(context, session) }
    }

    /**
     * Linha "Continuar assistindo" com os vídeos que têm progresso salvo (qualquer filme ou
     * episódio, mesmo que a série/filme já não apareça mais nas outras linhas). Clicar num card
     * aqui vai direto pro player retomando de onde parou, sem passar pela tela de detalhe.
     */
    private fun loadContinueWatching(rowsAdapter: ArrayObjectAdapter) {
        val inProgress = WatchProgressStore.listInProgress(requireContext())
        if (inProgress.isEmpty()) return

        val items = inProgress.map { ip ->
            MediaItem(
                id = "$CONTINUE_WATCHING_ID_PREFIX${ip.path}",
                title = ip.title,
                kind = if (ip.mediaType == "tv") MediaKind.SERIES else MediaKind.MOVIE,
                path = ip.path,
                isDirectory = false,
                category = MediaCategory.FILME,
                tmdbId = ip.tmdbId
            )
        }
        val continueAdapter = ArrayObjectAdapter(CardPresenter())
        continueAdapter.addAll(0, items)
        itemAdapters += continueAdapter
        rowsAdapter.add(ListRow(HeaderItem(getString(R.string.row_continue_watching)), continueAdapter))
    }

    private fun resumePlayback(item: MediaItem) {
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

    /**
     * Busca pôsteres reais (TMDB, via Suca Media) para os itens locais em segundo plano, sem
     * atrasar a exibição inicial da grade. Usa [PosterLookup] (com cache persistido) para não
     * repetir buscas de rede a cada nova varredura — inclusive buscas sem resultado.
     */
    private fun enrichPosters(context: android.content.Context, session: SucaSession) {
        if (!session.capabilities.search) return
        viewLifecycleOwner.lifecycleScope.launch {
            for (itemsAdapter in itemAdapters.toList()) {
                for (index in 0 until itemsAdapter.size()) {
                    val media = itemsAdapter.get(index) as? MediaItem ?: continue
                    if (media.remotePosterUrl != null || media.category !in POSTER_ELIGIBLE_CATEGORIES) continue

                    // Episódio em "Continuar assistindo": se a tela de episódios já extraiu um
                    // frame dele, reaproveita (consulta só o disco, não a rede).
                    val cachedThumb = if (media.kind == MediaKind.SERIES) {
                        EpisodeThumbnailStore.cachedUri(context, media.path)
                    } else null
                    if (cachedThumb != null) {
                        itemsAdapter.replace(index, media.copy(remotePosterUrl = cachedThumb))
                        continue
                    }

                    // Sem frame: cai no pôster da SÉRIE. Buscar pelo título do episódio não casa
                    // com nada no TMDB, que não indexa episódios.
                    //
                    // O nome vem da PASTA, não do título do episódio: a pasta carrega o ano
                    // ("taken 2002") e é dele que o PosterLookup precisa para não confundir a
                    // minissérie de 2002 com a série homônima de 2017. É também a mesma pasta que
                    // a lista de séries usa — as duas telas chegam ao mesmo pôster por construção.
                    val query = if (media.kind == MediaKind.SERIES) {
                        TitleCleaner.seriesTitleFromPath(media.path)
                            ?: TitleCleaner.seriesName(media.title)
                    } else media.title

                    val match = withContext(Dispatchers.IO) {
                        PosterLookup.resolve(context, session, media.kind, query)
                    }

                    if (match?.posterUrl != null) {
                        itemsAdapter.replace(index, media.copy(remotePosterUrl = match.posterUrl, tmdbId = match.tmdbId))
                    }
                }
            }
        }
    }

    /**
     * Prepends a "Minha lista (Suca Media)" row sourced from the paired account's cloud
     * watchlist/liked/watched items (real TMDB posters). Runs after local rows are already
     * showing so a slow/unreachable pairing never delays the SMB browse experience.
     */
    private fun loadCloudLibrary(rowsAdapter: ArrayObjectAdapter) {
        val context = requireContext().applicationContext
        val session = SucaAuthStore.load(context) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val client = SucaApiClient(context)
            runCatching {
                withContext(Dispatchers.IO) { client.heartbeat(session) }
            }
            val result = withContext(Dispatchers.IO) { client.getLibrary(session) }
            if (result !is SucaResult.Success || result.value.isEmpty()) return@launch

            val cloudItems = result.value.map { cloud ->
                val match = localItems.firstOrNull { local ->
                    cloud.titleSnapshot != null &&
                        local.title.contains(cloud.titleSnapshot, ignoreCase = true)
                }
                MediaItem(
                    id = "cloud-${cloud.id}",
                    title = cloud.titleSnapshot ?: "#${cloud.tmdbId}",
                    kind = match?.kind ?: MediaKind.MOVIE,
                    path = match?.path ?: "",
                    isDirectory = match?.isDirectory ?: false,
                    category = MediaCategory.MINHA_LISTA,
                    remotePosterUrl = cloud.posterUrl,
                    tmdbId = cloud.tmdbId
                )
            }
            val cloudAdapter = ArrayObjectAdapter(CardPresenter())
            cloudAdapter.addAll(0, cloudItems)
            itemAdapters += cloudAdapter
            rowsAdapter.add(0, ListRow(HeaderItem(getString(R.string.row_my_list)), cloudAdapter))
        }
    }

    private fun openDetail(item: MediaItem) {
        if (item.category == MediaCategory.MINHA_LISTA && item.path.isBlank()) {
            Toast.makeText(requireContext(), R.string.cloud_item_not_found_locally, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(requireContext(), DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_ID, item.id)
            putExtra(DetailActivity.EXTRA_TITLE, item.title)
            putExtra(DetailActivity.EXTRA_KIND, item.kind.name)
            putExtra(DetailActivity.EXTRA_PATH, item.path)
            putExtra(DetailActivity.EXTRA_IS_DIRECTORY, item.isDirectory)
            putExtra(DetailActivity.EXTRA_EPISODE_COUNT, item.episodeCount)
            putExtra(DetailActivity.EXTRA_SEASON_COUNT, item.seasonCount)
            item.tmdbId?.let { putExtra(DetailActivity.EXTRA_TMDB_ID, it) }
        }
        startActivity(intent)
    }

    /**
     * Linha "Configurações" no menu vertical. É o único caminho de teclado para o pareamento: o
     * status no canto superior direito também abre os ajustes, mas o D-pad não chega nele — de
     * "Filmes" para cima o foco não sai do menu, e num Fire TV sem toque isso deixava o pareamento
     * com o Suca Media inalcançável num aparelho novo.
     */
    private fun settingsRow(): ListRow {
        val paired = SucaAuthStore.load(requireContext()) != null
        val actions = ArrayObjectAdapter(SettingsActionPresenter()).apply {
            add(SettingsAction(
                SettingsActionIds.SUCA_PAIRING,
                getString(if (paired) R.string.browse_suca_connected else R.string.settings_suca_pairing)
            ))
            add(SettingsAction(SettingsActionIds.OPEN_SETTINGS, getString(R.string.browse_open_settings)))
        }
        return ListRow(HeaderItem(getString(R.string.row_settings)), actions)
    }

    private fun handleSettingsAction(action: SettingsAction) {
        when (action.id) {
            SettingsActionIds.SUCA_PAIRING -> startActivity(Intent(requireContext(), PairingActivity::class.java))
            SettingsActionIds.OPEN_SETTINGS -> startActivity(Intent(requireContext(), SettingsActivity::class.java))
            SettingsActionIds.CHANGE_SERVER -> {
                ServerConfigStore.clear(requireContext())
                startActivity(Intent(requireContext(), ServerSetupActivity::class.java))
                requireActivity().finish()
            }
        }
    }
}
