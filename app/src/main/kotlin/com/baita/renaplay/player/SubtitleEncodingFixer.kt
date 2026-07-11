package com.baita.renaplay.player

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Os decodificadores de legenda do Media3 (SubRip/SSA) assumem UTF-8 e não fazem detecção de
 * charset. Legendas mais antigas/rips comuns em Windows-1252 (Latin-1 + aspas curvas etc.) viram
 * "n�s n�o sab�amos" em vez de "nós não sabíamos" quando lidas como se já fossem UTF-8. Detecta
 * esse caso e reconverte para UTF-8 de verdade antes de entregar ao player.
 */
object SubtitleEncodingFixer {
    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    fun normalizeToUtf8(rawBytes: ByteArray): ByteArray {
        if (isValidUtf8(rawBytes)) return rawBytes
        val text = String(rawBytes, WINDOWS_1252)
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (e: CharacterCodingException) {
            false
        }
    }
}
