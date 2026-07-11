package com.baita.renaplay.player

import java.nio.charset.Charset
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SubtitleEncodingFixerTest {

    @Test
    fun `valid utf8 bytes pass through unchanged`() {
        val bytes = "Olá, você não sabíamos".toByteArray(Charsets.UTF_8)
        assertArrayEquals(bytes, SubtitleEncodingFixer.normalizeToUtf8(bytes))
    }

    @Test
    fun `windows-1252 bytes are converted to real utf8`() {
        val windows1252 = Charset.forName("windows-1252")
        val original = "Olá, você não sabíamos"
        val legacyBytes = original.toByteArray(windows1252)

        val fixed = SubtitleEncodingFixer.normalizeToUtf8(legacyBytes)

        assertArrayEquals(original.toByteArray(Charsets.UTF_8), fixed)
    }
}
