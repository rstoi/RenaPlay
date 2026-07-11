package com.baita.renaplay.data

import android.content.Context
import android.content.SharedPreferences

data class SucaCapabilities(
    val tmdb: Boolean,
    val subtitles: Boolean,
    val omdb: Boolean,
    val search: Boolean
)

data class SucaSession(
    val baseUrl: String,
    val deviceToken: String,
    val deviceId: String,
    val deviceName: String,
    val displayName: String?,
    val capabilities: SucaCapabilities
)

/** Persists the Suca Media device pairing (see docs/SKIN.md on the paired web project). */
object SucaAuthStore {
    private const val PREFS = "renaplay_suca"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_TOKEN = "device_token"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_CAP_TMDB = "cap_tmdb"
    private const val KEY_CAP_SUBTITLES = "cap_subtitles"
    private const val KEY_CAP_OMDB = "cap_omdb"
    private const val KEY_CAP_SEARCH = "cap_search"

    const val DEFAULT_BASE_URL = "https://sucamedia.lovable.app"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, session: SucaSession) {
        prefs(context).edit()
            .putString(KEY_BASE_URL, session.baseUrl)
            .putString(KEY_TOKEN, session.deviceToken)
            .putString(KEY_DEVICE_ID, session.deviceId)
            .putString(KEY_DEVICE_NAME, session.deviceName)
            .putString(KEY_DISPLAY_NAME, session.displayName)
            .putBoolean(KEY_CAP_TMDB, session.capabilities.tmdb)
            .putBoolean(KEY_CAP_SUBTITLES, session.capabilities.subtitles)
            .putBoolean(KEY_CAP_OMDB, session.capabilities.omdb)
            .putBoolean(KEY_CAP_SEARCH, session.capabilities.search)
            .apply()
    }

    fun load(context: Context): SucaSession? {
        val p = prefs(context)
        val token = p.getString(KEY_TOKEN, null) ?: return null
        val baseUrl = p.getString(KEY_BASE_URL, null) ?: DEFAULT_BASE_URL
        return SucaSession(
            baseUrl = baseUrl,
            deviceToken = token,
            deviceId = p.getString(KEY_DEVICE_ID, "") ?: "",
            deviceName = p.getString(KEY_DEVICE_NAME, "") ?: "",
            displayName = p.getString(KEY_DISPLAY_NAME, null),
            capabilities = SucaCapabilities(
                tmdb = p.getBoolean(KEY_CAP_TMDB, false),
                subtitles = p.getBoolean(KEY_CAP_SUBTITLES, false),
                omdb = p.getBoolean(KEY_CAP_OMDB, false),
                search = p.getBoolean(KEY_CAP_SEARCH, false)
            )
        )
    }

    fun isPaired(context: Context): Boolean = load(context) != null

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
