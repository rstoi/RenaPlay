package com.baita.renaplay.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.baita.renaplay.data.ServerConfig

/**
 * Dispatches to SmbDataSource for renasmb:// URIs (video + legendas na rede),
 * e a DefaultDataSource para file:// (legendas baixadas localmente).
 */
class CompositeDataSource(context: Context, config: ServerConfig) : DataSource {

    private val smb = SmbDataSource(config)
    private val fallback = DefaultDataSource.Factory(context).createDataSource()
    private var active: DataSource = fallback

    override fun open(dataSpec: DataSpec): Long {
        active = if (dataSpec.uri.scheme == SMB_URI_SCHEME) smb else fallback
        return active.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active.read(buffer, offset, length)

    override fun getUri(): Uri? = active.uri

    override fun close() = active.close()

    override fun addTransferListener(transferListener: TransferListener) {
        smb.addTransferListener(transferListener)
        fallback.addTransferListener(transferListener)
    }
}

class CompositeDataSourceFactory(private val context: Context, private val config: ServerConfig) : DataSource.Factory {
    override fun createDataSource(): DataSource = CompositeDataSource(context, config)
}
