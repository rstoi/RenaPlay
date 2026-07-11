package com.baita.renaplay.subtitles

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import com.baita.renaplay.R
import com.baita.renaplay.data.ServerConfigStore
import com.baita.renaplay.data.SubtitleSettingsStore
import com.baita.renaplay.data.SucaAuthStore
import com.baita.renaplay.player.PlaybackActivity
import com.baita.renaplay.smb.SmbClientProvider
import com.baita.renaplay.suca.SucaApiClient
import com.baita.renaplay.suca.SucaResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SubtitleSearchFragment : GuidedStepSupportFragment() {

    private var videoTitle: String = ""
    private var videoPath: String = ""
    private var tmdbId: Int? = null
    private var mediaType: String? = null
    private var results: List<SubtitleResult> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = requireActivity().intent
        videoTitle = intent.getStringExtra(SubtitleSearchActivity.EXTRA_TITLE) ?: ""
        videoPath = intent.getStringExtra(SubtitleSearchActivity.EXTRA_PATH) ?: ""
        tmdbId = intent.getIntExtra(SubtitleSearchActivity.EXTRA_TMDB_ID, -1).takeIf { it != -1 }
        mediaType = intent.getStringExtra(SubtitleSearchActivity.EXTRA_MEDIA_TYPE)
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance {
        return Guidance(getString(R.string.subtitle_search_title), videoTitle, "", null)
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(
            GuidedAction.Builder(requireContext())
                .id(GuidedAction.NO_ID.toLong())
                .title(getString(R.string.subtitle_searching))
                .infoOnly(true)
                .build()
        )
        runSearch()
    }

    private fun runSearch() {
        val context = requireContext()
        val config = ServerConfigStore.load(context) ?: return

        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                val query = cleanQueryFromTitle(videoTitle)
                val http = HttpClientProvider.client
                val openSubtitles = OpenSubtitlesProvider(http) { SubtitleSettingsStore.getOpenSubtitlesKey(context) }

                val local = LocalSubtitleProvider(SmbClientProvider.instance).search(config, videoPath)

                val webResults = mutableListOf<SubtitleResult>()
                if (SubtitleSettingsStore.isSourceEnabled(context, SubtitleSettingsStore.SOURCE_SUBTITLECAT)) {
                    webResults += runCatching { SubtitleCatProvider(http).search(query) }.getOrDefault(emptyList())
                }
                if (SubtitleSettingsStore.isSourceEnabled(context, SubtitleSettingsStore.SOURCE_ADDIC7ED)) {
                    webResults += runCatching { Addic7edProvider(http).search(query) }.getOrDefault(emptyList())
                }
                if (SubtitleSettingsStore.isSourceEnabled(context, SubtitleSettingsStore.SOURCE_OPENSUBTITLES, default = false)) {
                    webResults += runCatching { openSubtitles.search(query) }.getOrDefault(emptyList())
                }
                SucaAuthStore.load(context)?.let { session ->
                    if (session.capabilities.subtitles) {
                        webResults += runCatching {
                            searchViaSucaMedia(context, session, query, tmdbId, mediaType)
                        }.getOrDefault(emptyList())
                    }
                }

                val videoName = videoPath.substringAfterLast('/')
                (local + webResults).sortedByDescending { subtitleMatchScore(videoName, it.label) }
            }

            results = found
            renderResults()
        }
    }

    private fun renderResults() {
        if (results.isEmpty()) {
            setActions(
                mutableListOf(
                    GuidedAction.Builder(requireContext())
                        .id(GuidedAction.NO_ID.toLong())
                        .title(getString(R.string.subtitle_none_found))
                        .infoOnly(true)
                        .build()
                )
            )
            return
        }

        val newActions = results.mapIndexed { index, result ->
            GuidedAction.Builder(requireContext())
                .id(index.toLong() + 1)
                .title(result.label)
                .description(sourceLabel(result) + if (result.language.isNotBlank()) " · ${result.language}" else "")
                .build()
        }
        setActions(newActions.toMutableList())
    }

    private fun sourceLabel(result: SubtitleResult): String = when (result.source) {
        SubtitleSource.LOCAL -> getString(R.string.subtitle_source_local)
        SubtitleSource.SUBTITLECAT -> getString(R.string.subtitle_source_subtitlecat)
        SubtitleSource.ADDIC7ED -> getString(R.string.subtitle_source_addic7ed)
        SubtitleSource.OPENSUBTITLES -> getString(R.string.subtitle_source_opensubtitles)
        SubtitleSource.SUCA_MEDIA -> getString(R.string.subtitle_source_suca_media)
    }

    /**
     * Queries OpenSubtitles through Suca Media's server-side proxy (the device never sees the
     * OpenSubtitles key). Passing tmdbId/mediaType (when known) matches OpenSubtitles by title id
     * instead of free-text query, same as the web app does. Reuses the
     * same file-id-prefixed [SubtitleResult.downloadUrl] scheme as [OpenSubtitlesProvider], so
     * [SubtitleDownloader] resolves it identically — download still needs a local OpenSubtitles
     * key configured in Ajustes, same as that source (see [onGuidedActionClicked]).
     */
    private fun searchViaSucaMedia(
        context: android.content.Context,
        session: com.baita.renaplay.data.SucaSession,
        query: String,
        tmdbId: Int?,
        mediaType: String?
    ): List<SubtitleResult> {
        val result = SucaApiClient(context).getSubtitles(
            session = session,
            tmdbId = tmdbId,
            mediaType = mediaType,
            language = "pt-br",
            query = query
        )
        if (result !is SucaResult.Success) return emptyList()
        return result.value.mapNotNull { s ->
            val fileId = s.fileId ?: return@mapNotNull null
            SubtitleResult(
                source = SubtitleSource.SUCA_MEDIA,
                label = s.release?.ifBlank { null } ?: query,
                language = s.language ?: "",
                downloadUrl = "$OPENSUBTITLES_FILE_ID_PREFIX$fileId"
            )
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val index = action.id.toInt() - 1
        val result = results.getOrNull(index) ?: return

        if (result.source == SubtitleSource.LOCAL) {
            startPlayback(subtitleSmbPath = result.smbPath)
            return
        }

        // Resultados vindos do proxy do Suca Media (SUCA_MEDIA) baixam via
        // POST /v1/subtitles/download quando há sessão pareada — o device nunca precisa da
        // própria chave OpenSubtitles nesse caso. Para as demais fontes web, o download sempre
        // usa a chave LOCAL configurada em Ajustes; sem ela, o clique falhava silenciosamente e
        // o usuário só via "nenhuma legenda encontrada", que é enganoso: a legenda foi
        // encontrada, só não pôde ser baixada.
        val session = SucaAuthStore.load(requireContext())
        val canUseSucaDownload = result.source == SubtitleSource.SUCA_MEDIA && session != null
        if (result.source != SubtitleSource.LOCAL && !canUseSucaDownload &&
            SubtitleSettingsStore.getOpenSubtitlesKey(requireContext()).isBlank()
        ) {
            Toast.makeText(requireContext(), R.string.subtitle_download_needs_key, Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            Toast.makeText(requireContext(), R.string.subtitle_searching, Toast.LENGTH_SHORT).show()
            val context = requireContext()
            val file = withContext(Dispatchers.IO) {
                val http = HttpClientProvider.client
                val openSubtitles = OpenSubtitlesProvider(http) { SubtitleSettingsStore.getOpenSubtitlesKey(context) }
                SubtitleDownloader(http, openSubtitles).download(context, result, session)
            }

            if (file == null) {
                Toast.makeText(requireContext(), R.string.subtitle_none_found, Toast.LENGTH_LONG).show()
            } else {
                startPlayback(subtitleLocalPath = file.absolutePath)
            }
        }
    }

    private fun startPlayback(subtitleLocalPath: String? = null, subtitleSmbPath: String? = null) {
        val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_TITLE, videoTitle)
            putExtra(PlaybackActivity.EXTRA_PATH, videoPath)
            subtitleLocalPath?.let { putExtra(PlaybackActivity.EXTRA_SUBTITLE_LOCAL_PATH, it) }
            subtitleSmbPath?.let { putExtra(PlaybackActivity.EXTRA_SUBTITLE_SMB_PATH, it) }
            tmdbId?.let {
                putExtra(PlaybackActivity.EXTRA_TMDB_ID, it)
                putExtra(PlaybackActivity.EXTRA_MEDIA_TYPE, mediaType)
            }
        }
        startActivity(intent)
        requireActivity().finish()
    }
}
