package net.awkay.spanishreader.core.text

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals

class TextDecodingTest {
    private val sample = "¿Qué año? Señor, ¡sí!"

    @Test
    fun utf8() = assertEquals(sample, TextDecoding.decode(sample.toByteArray()))

    @Test
    fun utf8WithBom() = assertEquals(sample, TextDecoding.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sample.toByteArray()))

    @Test
    fun utf16Le() = assertEquals(sample, TextDecoding.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + sample.toByteArray(Charsets.UTF_16LE)))

    @Test
    fun windows1252Fallback() = assertEquals(sample, TextDecoding.decode(sample.toByteArray(Charset.forName("windows-1252"))))
}
