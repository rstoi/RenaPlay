package com.baita.renaplay.smb

import android.util.Log
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties

data class SmbEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long
)

sealed class SmbResult<out T> {
    data class Success<T>(val value: T) : SmbResult<T>()
    data class Failure(val message: String, val cause: Throwable? = null) : SmbResult<Nothing>()
}

class SmbClient {

    // Reaproveita a MESMA sessão SMB autenticada entre chamadas nesta instância, em vez de negociar
    // uma conexão nova a cada listFiles(). Descoberto na prática: pedir dezenas de pastas com uma
    // autenticação nova para cada uma sobrecarrega NAS caseiro (a conexão é resetada pelo servidor:
    // "SocketException: Connection reset"), mesmo limitando a concorrência. O CIFSContext do
    // jcifs-ng é seguro para uso concorrente — é assim que SMB2 já suporta múltiplas requisições
    // simultâneas numa única sessão.
    @Volatile private var cachedContext: CIFSContext? = null
    @Volatile private var cachedKey: String? = null

    private fun baseContext(): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.responseTimeout", "8000")
            setProperty("jcifs.smb.client.connTimeout", "6000")
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
        }
        return BaseContext(PropertyConfiguration(props))
    }

    @Synchronized
    private fun contextWithAuth(user: String, password: String, domain: String): CIFSContext {
        val key = "$domain\\$user"
        cachedContext?.let { if (cachedKey == key) return it }
        val auth = NtlmPasswordAuthenticator(domain.ifBlank { null }, user, password)
        val ctx = baseContext().withCredentials(auth)
        cachedContext = ctx
        cachedKey = key
        return ctx
    }

    /** Passo 1: confirma que existe um servidor SMB respondendo neste IP (porta 445). */
    fun testHost(ip: String): SmbResult<Unit> {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, 445), 5000)
            }
            SmbResult.Success(Unit)
        } catch (e: Exception) {
            SmbResult.Failure("Não foi possível conectar a $ip:445", e)
        }
    }

    /** Passo 2: valida usuário/senha listando as pastas compartilhadas na raiz. */
    fun listShares(ip: String, user: String, password: String, domain: String = ""): SmbResult<List<String>> {
        return try {
            val ctx = contextWithAuth(user, password, domain)
            val root = SmbFile("smb://$ip/", ctx)
            // root.list() traz os nomes brutos dos compartilhamentos; listFiles() não resolve
            // isDirectory() de forma confiável para entradas de compartilhamento (tipo SHARE),
            // o que fazia a lista sair vazia em vários servidores (Samba, NAS).
            val shares = root.list()
                .map { it.trimEnd('/') }
                .filter { it.isNotBlank() && !it.endsWith("$") }
            SmbResult.Success(shares)
        } catch (e: Exception) {
            SmbResult.Failure("Usuário ou senha inválidos, ou sem acesso às pastas.", e)
        }
    }

    fun listFiles(
        ip: String,
        share: String,
        path: String,
        user: String,
        password: String,
        domain: String = ""
    ): SmbResult<List<SmbEntry>> {
        val startedAt = System.currentTimeMillis()
        return try {
            val ctx = contextWithAuth(user, password, domain)
            // SmbFile precisa da barra final para tratar corretamente como diretório;
            // sem ela, o jcifs-ng resolve os nomes dos filhos errado (concatenando com
            // o último segmento do próprio diretório).
            val cleanPath = path.trim('/')
            val url = if (cleanPath.isEmpty()) "smb://$ip/$share/" else "smb://$ip/$share/$cleanPath/"
            val dir = SmbFile(url, ctx)
            val entries = dir.listFiles()
                .filter { !it.name.startsWith(".") }
                .map {
                    SmbEntry(
                        name = it.name.trimEnd('/'),
                        path = if (cleanPath.isEmpty()) it.name.trimEnd('/') else "$cleanPath/${it.name.trimEnd('/')}",
                        isDirectory = it.isDirectory,
                        size = if (it.isDirectory) 0L else it.length()
                    )
                }
            // Log.e (não Log.d) porque este device suprime logs de depuração de apps de terceiros.
            Log.e("RenaPlaySmb", "listFiles OK: url=$url count=${entries.size} tookMs=${System.currentTimeMillis() - startedAt}")
            SmbResult.Success(entries)
        } catch (e: Exception) {
            Log.e("RenaPlaySmb", "listFiles FAILED: path=$path share=$share tookMs=${System.currentTimeMillis() - startedAt}", e)
            SmbResult.Failure("Erro ao listar $path em $share", e)
        }
    }

    fun openInputStream(
        ip: String,
        share: String,
        path: String,
        user: String,
        password: String,
        domain: String = ""
    ): InputStream {
        val ctx = contextWithAuth(user, password, domain)
        val url = "smb://$ip/$share/${path.trimStart('/')}"
        return SmbFile(url, ctx).inputStream
    }

    fun fileLength(
        ip: String,
        share: String,
        path: String,
        user: String,
        password: String,
        domain: String = ""
    ): Long {
        val ctx = contextWithAuth(user, password, domain)
        val url = "smb://$ip/$share/${path.trimStart('/')}"
        return SmbFile(url, ctx).length()
    }
}
