package com.baita.renaplay.data

import android.content.Context
import com.baita.renaplay.BuildConfig

/**
 * URL do serviço HEVC264 da rede local, que recodifica HEVC para H.264.
 *
 * Só é usada como fallback: o caminho normal é a descoberta por broadcast UDP
 * (Hevc264Discovery). Vale configurar quando o broadcast não chega — redes que o bloqueiam,
 * ou serviço em outra sub-rede.
 *
 * O valor inicial vem de `renaplay.convert.url` no local.properties (gitignored) via BuildConfig;
 * sem ele, fica vazio e só a descoberta funciona. A tela de ajustes sobrescreve.
 */
object ConversionSettingsStore {
    private const val PREFS = "renaplay_conversion"
    private const val KEY_SERVICE_URL = "service_url"

    /** Vazio quando não configurado — a descoberta UDP é quem acha o serviço. */
    val DEFAULT_SERVICE_URL: String = BuildConfig.SEED_CONVERT_URL.trim().trimEnd('/')

    /**
     * URL que o usuário escolheu a dedo, ou null. Tem precedência sobre a descoberta: quando
     * alguém aponta o app para um serviço específico, é nele que se quer converter — a descoberta
     * responde quem gritar mais rápido na rede, e com dois serviços no ar isso vira sorteio.
     */
    fun getExplicitServiceUrl(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SERVICE_URL, null)
            ?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }

    fun getServiceUrl(context: Context): String {
        val url = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SERVICE_URL, DEFAULT_SERVICE_URL)
            ?.trim()?.trimEnd('/')
        return if (url.isNullOrBlank()) DEFAULT_SERVICE_URL else url
    }

    fun setServiceUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SERVICE_URL, url.trim()).apply()
    }
}
