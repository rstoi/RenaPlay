package com.baita.renaplay

import android.app.Application
import android.util.Log
import java.security.Security

class RenaPlayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Some Fire OS builds ship a system Conscrypt/BoringSSL that mis-negotiates TLS with
        // modern edge servers (observed: SSLHandshakeException / TLSV1_ALERT_PROTOCOL_VERSION).
        // Installing Google's standalone Conscrypt as the top security provider bypasses it.
        runCatching {
            Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
        }.onFailure {
            Log.e("RenaPlayApplication", "Failed to install Conscrypt provider", it)
        }
    }
}
