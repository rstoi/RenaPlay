package com.baita.renaplay.data

import android.content.Context
import android.content.SharedPreferences
import com.baita.renaplay.BuildConfig

data class ServerConfig(
    val ip: String,
    val user: String,
    val password: String,
    val share: String,
    val domain: String = ""
)

object ServerConfigStore {
    private const val PREFS = "renaplay_server"
    private const val KEY_IP = "ip"
    private const val KEY_USER = "user"
    private const val KEY_PASS = "pass"
    private const val KEY_SHARE = "share"
    private const val KEY_DOMAIN = "domain"
    private const val KEY_SEEDED = "seeded"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Configuração inicial vinda do local.properties (gitignored) via BuildConfig — evita digitar
     * IP, share e senha no controle remoto a cada instalação. Nula quando o build não traz semente,
     * e aí o app abre direto no assistente de setup.
     */
    private val seed: ServerConfig? =
        if (BuildConfig.SEED_SMB_IP.isNotBlank() && BuildConfig.SEED_SMB_SHARE.isNotBlank()) {
            ServerConfig(
                ip = BuildConfig.SEED_SMB_IP,
                user = BuildConfig.SEED_SMB_USER,
                password = BuildConfig.SEED_SMB_PASSWORD,
                share = BuildConfig.SEED_SMB_SHARE,
                domain = BuildConfig.SEED_SMB_DOMAIN
            )
        } else null

    fun save(context: Context, config: ServerConfig) {
        prefs(context).edit()
            .putString(KEY_IP, config.ip)
            .putString(KEY_USER, config.user)
            .putString(KEY_PASS, config.password)
            .putString(KEY_SHARE, config.share)
            .putString(KEY_DOMAIN, config.domain)
            .putBoolean(KEY_SEEDED, true)
            .apply()
    }

    fun load(context: Context): ServerConfig? {
        val p = prefs(context)
        val ip = p.getString(KEY_IP, null)
        val share = p.getString(KEY_SHARE, null)
        if (ip != null && share != null) {
            return ServerConfig(
                ip = ip,
                user = p.getString(KEY_USER, "") ?: "",
                password = p.getString(KEY_PASS, "") ?: "",
                share = share,
                domain = p.getString(KEY_DOMAIN, "") ?: ""
            )
        }
        // Primeira execução: grava a semente do build e segue como se o usuário tivesse
        // preenchido o setup. KEY_SEEDED impede que um "esquecer servidor" seja desfeito
        // pela semente no próximo load.
        val s = seed ?: return null
        if (p.getBoolean(KEY_SEEDED, false)) return null
        save(context, s)
        return s
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().putBoolean(KEY_SEEDED, true).apply()
    }
}

object SubtitleSettingsStore {
    private const val PREFS = "renaplay_subtitles"
    private const val KEY_OPENSUBTITLES_KEY = "opensubtitles_key"
    private const val KEY_SOURCE_SUBTITLECAT = "src_subtitlecat"
    private const val KEY_SOURCE_ADDIC7ED = "src_addic7ed"
    private const val KEY_SOURCE_OPENSUBTITLES = "src_opensubtitles"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getOpenSubtitlesKey(context: Context): String =
        prefs(context).getString(KEY_OPENSUBTITLES_KEY, "") ?: ""

    fun setOpenSubtitlesKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_OPENSUBTITLES_KEY, key).apply()
    }

    fun isSourceEnabled(context: Context, key: String, default: Boolean = true): Boolean =
        prefs(context).getBoolean(key, default)

    fun setSourceEnabled(context: Context, key: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(key, enabled).apply()
    }

    const val SOURCE_SUBTITLECAT = KEY_SOURCE_SUBTITLECAT
    const val SOURCE_ADDIC7ED = KEY_SOURCE_ADDIC7ED
    const val SOURCE_OPENSUBTITLES = KEY_SOURCE_OPENSUBTITLES
}
