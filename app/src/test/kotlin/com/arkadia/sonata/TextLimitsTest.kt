package com.arkadia.sonata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text validation and bounding. */
class TextLimitsTest {

    @Test
    fun `null input is empty`() {
        assertEquals(TextValidation.Empty, TextLimits.validate(null))
    }

    @Test
    fun `blank input is empty`() {
        assertEquals(TextValidation.Empty, TextLimits.validate(""))
        assertEquals(TextValidation.Empty, TextLimits.validate("   "))
        assertEquals(TextValidation.Empty, TextLimits.validate("\n\t  \n"))
    }

    @Test
    fun `normal text is ready and trimmed`() {
        val result = TextLimits.validate("  Hello there.  ")
        assertTrue(result is TextValidation.Ready)
        assertEquals("Hello there.", (result as TextValidation.Ready).text)
    }

    @Test
    fun `text at exactly the ceiling is not truncated`() {
        val text = "a".repeat(MAX_TTS_CHARS)
        val result = TextLimits.validate(text)
        assertTrue(result is TextValidation.Ready)
        assertEquals(MAX_TTS_CHARS, (result as TextValidation.Ready).text.length)
    }

    @Test
    fun `text past the ceiling is truncated to the backend limit and reported`() {
        val text = "b".repeat(MAX_TTS_CHARS + 250)
        val result = TextLimits.validate(text)

        assertTrue(result is TextValidation.Truncated)
        result as TextValidation.Truncated
        assertEquals(MAX_TTS_CHARS, result.text.length)
        assertEquals(MAX_TTS_CHARS + 250, result.originalLength)
    }

    @Test
    fun `whitespace is trimmed before the ceiling is applied`() {
        // 5000 characters of content plus padding must truncate to the ceiling of
        // content, not to ceiling-minus-padding.
        val text = "   " + "c".repeat(MAX_TTS_CHARS + 10) + "   "
        val result = TextLimits.validate(text)
        assertTrue(result is TextValidation.Truncated)
        assertEquals(MAX_TTS_CHARS, (result as TextValidation.Truncated).text.length)
        assertEquals(MAX_TTS_CHARS + 10, result.originalLength)
    }

    @Test
    fun `ceiling matches the backend truncation bound`() {
        // kernel/tts.py::synthesize does text.strip()[:5000]; a different client
        // bound would either waste a round trip or silently lose the tail.
        assertEquals(5000, MAX_TTS_CHARS)
    }
}
