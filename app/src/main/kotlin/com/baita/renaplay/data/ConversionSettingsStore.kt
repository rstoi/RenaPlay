package com.baita.renaplay.data

import android.content.Context

/**
 * URL do serviço web de conversão (transcode-smb) que roda no Mac da rede local.
 * O RenaPlay envia o caminho SMB do vídeo e o serviço converte o HEVC para H.264
 * e substitui o arquivo no share (guardando backup .hevcbak do original).
 */
object ConversionSettingsStore {
    private const val PREFS = "renaplay_conversion"
    private const val KEY_SERVICE_URL = "service_url"
    const val DEFAULT_SERVICE_URL = "http://192.168.1.102:8765"

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
