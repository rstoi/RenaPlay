package com.baita.renaplay.player

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * O foco aqui é o SSE de /progress: o servidor tem duas formas de abandonar um job (emudecer, ou
 * fechar o stream sem dizer "done") e nenhuma das duas pode deixar o app esperando para sempre —
 * foi assim que uma conversão ficou 5h presa em "24%".
 */
class Hevc264ClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Prazo curto para o teste não demorar; em produção são 180s. */
    private fun client() = Hevc264Client(server.url("/").toString(), progressIdleTimeoutSeconds = 1)

    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" }

    @Test
    fun `progresso normal percorre os eventos ate done`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    sse(
                        """{"status":"running","progress":0.24,"eta":600,"error":null}""",
                        """{"status":"running","progress":0.479,"eta":7,"error":null}""",
                        """{"status":"done","progress":1.0,"eta":null,"error":null}"""
                    )
                )
        )

        val visto = mutableListOf<Int>()
        client().streamProgress("abc") { visto.add(it.progress) }

        // A fração do serviço (0.479) vira porcentagem (48), e o "done" fecha o stream.
        assertEquals(listOf(24, 48, 100), visto)
    }

    @Test
    fun `servidor que emudece no meio falha em vez de esperar para sempre`() {
        // Headers saem na hora e o corpo demora 3s: é o servidor calado com o job aberto. O delay
        // é só o suficiente para passar do prazo do cliente — mais que isso e o MockWebServer não
        // consegue desligar no fim do teste.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse("""{"status":"running","progress":0.24,"eta":null,"error":null}"""))
                .setBodyDelay(3, TimeUnit.SECONDS)
        )

        val client = client()
        val inicio = System.currentTimeMillis()
        var motivo: String? = null
        try {
            client.streamProgress("abc") { }
            fail("devia ter lançado Hevc264StalledException")
        } catch (e: Hevc264StalledException) {
            motivo = e.message
        }
        val decorrido = System.currentTimeMillis() - inicio

        // Tem de falhar POR SILÊNCIO, e não por o stream ter acabado depois que o corpo enfim veio.
        assertEquals(Hevc264StalledException.MUDO, motivo)
        // E antes dos 3s do corpo: quem cortou foi o prazo.
        assertTrue("demorou $decorrido ms — não falhou pelo prazo", decorrido < 2_500)
    }

    @Test
    fun `stream encerrado sem done nao passa por sucesso`() {
        // O servidor fecha o stream depois de um evento qualquer. Antes, o laço simplesmente
        // terminava e a conversão seguia para o download de um arquivo que não existe.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse("""{"status":"running","progress":0.24,"eta":null,"error":null}"""))
        )

        try {
            client().streamProgress("abc") { }
            fail("devia ter lançado Hevc264StalledException")
        } catch (e: Hevc264StalledException) {
            // esperado
        }
    }

    @Test
    fun `erro reportado pelo servidor chega a quem chamou`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse("""{"status":"error","progress":0.0,"eta":null,"error":"ffmpeg morreu"}"""))
        )

        var erro: String? = null
        client().streamProgress("abc") { erro = it.error }

        assertEquals("ffmpeg morreu", erro)
    }
}
