package com.baita.renaplay.data

import android.content.Context

// Percentual de conclusão acima do qual um vídeo é tratado como "já assistido" e deixa de
// mostrar a barra de progresso (mesmo comportamento de apps como Prime Video).
private const val WATCHED_THRESHOLD = 0.95f

data class InProgressVideo(
    val path: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val fraction: Float,
    val tmdbId: Int? = null,
    val mediaType: String? = null
)

object WatchProgressStore {
    private const val PREFS = "watch_progress"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(
        context: Context,
        path: String,
        title: String,
        positionMs: Long,
        durationMs: Long,
        tmdbId: Int? = null,
        mediaType: String? = null
    ) {
        if (durationMs <= 0) return
        val fraction = positionMs.toFloat() / durationMs.toFloat()
        if (fraction >= WATCHED_THRESHOLD) {
            clear(context, path)
            return
        }
        prefs(context).edit().apply {
            putLong("${path}_pos", positionMs)
            putLong("${path}_dur", durationMs)
            putString("${path}_title", title)
            putLong("${path}_ts", System.currentTimeMillis())
            // "Continuar assistindo" resolve o pôster de novo via título (PosterLookup), mas
            // guardar o tmdbId/mediaType aqui evita perder o vínculo já feito ao retomar a
            // reprodução (telemetria de atividade / busca de legenda via Suca Media).
            if (tmdbId != null) putInt("${path}_tmdb", tmdbId) else remove("${path}_tmdb")
            if (mediaType != null) putString("${path}_media_type", mediaType) else remove("${path}_media_type")
        }.apply()
    }

    fun progress(context: Context, path: String): Float {
        val p = prefs(context)
        val pos = p.getLong("${path}_pos", 0L)
        val dur = p.getLong("${path}_dur", 0L)
        if (dur <= 0) return 0f
        return (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
    }

    fun lastPosition(context: Context, path: String): Long =
        prefs(context).getLong("${path}_pos", 0L)

    fun clear(context: Context, path: String) {
        prefs(context).edit()
            .remove("${path}_pos")
            .remove("${path}_dur")
            .remove("${path}_title")
            .remove("${path}_ts")
            .remove("${path}_tmdb")
            .remove("${path}_media_type")
            .apply()
    }

    /** Vídeos com progresso salvo, do mais recente para o mais antigo — para a linha "Continuar assistindo". */
    fun listInProgress(context: Context): List<InProgressVideo> {
        val p = prefs(context)
        val all = p.all
        val paths = all.keys.filter { it.endsWith("_pos") }.map { it.removeSuffix("_pos") }
        return paths.mapNotNull { path ->
            val pos = p.getLong("${path}_pos", 0L)
            val dur = p.getLong("${path}_dur", 0L)
            val title = p.getString("${path}_title", null) ?: return@mapNotNull null
            if (dur <= 0) return@mapNotNull null
            val fraction = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            val ts = p.getLong("${path}_ts", 0L)
            val tmdbId = if (p.contains("${path}_tmdb")) p.getInt("${path}_tmdb", 0) else null
            val mediaType = p.getString("${path}_media_type", null)
            ts to InProgressVideo(path, title, pos, dur, fraction, tmdbId, mediaType)
        }.sortedByDescending { it.first }.map { it.second }
    }
}
