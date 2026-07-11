package com.baita.renaplay.suca

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Dedicated HTTP client for calls to Suca Media only (sucamedia.lovable.app). Some Fire OS
 * builds hang indefinitely — confirmed not just in Android's NetworkSecurityTrustManager
 * wrapper, but inside the "AndroidCAStore" KeyStore provider itself (`KeyStore.load()` on it
 * never returns) — while validating certificates against this host, even though the same
 * device's system browser loads the site fine. [DirectTrustManager] sidesteps the broken
 * system CA store entirely: trust anchors are loaded from a JDK cacerts bundle shipped as an
 * APK asset (assets/cacerts.p12, standard "changeit" password) and the chain is validated via
 * CertPathValidator — it still validates the real certificate chain against real, well-known
 * root CAs; it does not accept arbitrary or self-signed certificates. Revocation checking
 * (OCSP/CRL) is disabled since that also requires a network round-trip.
 *
 * This client is intentionally NOT shared with other networking in the app (SMB, subtitle
 * providers) — see [com.baita.renaplay.subtitles.HttpClientProvider] for those, which keep the
 * platform's full default trust validation, revocation checking included.
 */
private class DirectTrustManager(context: Context) : X509TrustManager {
    private val appContext = context.applicationContext

    private val trustAnchors: Set<TrustAnchor> by lazy {
        Log.e("SucaHttpClientProvider", "loading bundled cacerts.p12 trust anchors...")
        val ks = KeyStore.getInstance("PKCS12")
        appContext.assets.open("cacerts.p12").use { input ->
            ks.load(input, "changeit".toCharArray())
        }
        val anchors = ks.aliases().toList().mapNotNull { alias ->
            (ks.getCertificate(alias) as? X509Certificate)?.let { TrustAnchor(it, null) }
        }.toSet()
        Log.e("SucaHttpClientProvider", "loaded ${anchors.size} trust anchors from bundled cacerts")
        anchors
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        // Not used: this trust manager is only ever installed for outbound (server) connections.
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        Log.e("SucaHttpClientProvider", "checkServerTrusted: validating chain of ${chain.size} certs")
        val certPath = CertificateFactory.getInstance("X.509").generateCertPath(chain.toList())
        val params = PKIXParameters(trustAnchors).apply {
            isRevocationEnabled = false
        }
        val validator = CertPathValidator.getInstance("PKIX")
        validator.validate(certPath, params)
        Log.e("SucaHttpClientProvider", "checkServerTrusted: OK")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

object SucaHttpClientProvider {
    @Volatile private var cached: OkHttpClient? = null

    fun client(context: Context): OkHttpClient {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val builder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)

            runCatching {
                val trustManager = DirectTrustManager(context)
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf(trustManager), null)
                builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            }.onFailure {
                Log.e("SucaHttpClientProvider", "Direct trust manager setup failed", it)
            }

            val built = builder.build()
            cached = built
            return built
        }
    }
}
