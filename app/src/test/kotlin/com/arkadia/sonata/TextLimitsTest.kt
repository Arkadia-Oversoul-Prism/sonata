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
    fun `text past the single-request ceiling is accepted whole, not truncated`() {
        // The ceiling is a *request* ceiling, not a ceiling on what the user may
        // read. A passage longer than one request is split across several, so the
        // validator must hand it on intact. Truncating here is the defect a user
        // reported as "there appears to be a length limit".
        val text = "b".repeat(MAX_TTS_CHARS + 250)
        val result = TextLimits.validate(text)

        assertTrue(result is TextValidation.Ready)
        assertEquals(MAX_TTS_CHARS + 250, (result as TextValidation.Ready).text.length)
    }

    @Test
    fun `whitespace is trimmed and a long passage survives intact`() {
        val text = "   " + "c".repeat(MAX_TTS_CHARS + 10) + "   "
        val result = TextLimits.validate(text)

        assertTrue(result is TextValidation.Ready)
        assertEquals(MAX_TTS_CHARS + 10, (result as TextValidation.Ready).text.length)
    }

    @Test
    fun `text past the input ceiling is truncated and reported`() {
        val text = "d".repeat(MAX_INPUT_CHARS + 500)
        val result = TextLimits.validate(text)

        assertTrue(result is TextValidation.Truncated)
        result as TextValidation.Truncated
        assertEquals(MAX_INPUT_CHARS, result.text.length)
        assertEquals(MAX_INPUT_CHARS + 500, result.originalLength)
    }

    @Test
    fun `ceiling matches the backend truncation bound`() {
        // kernel/tts.py::synthesize does text.strip()[:5000]; a different client
        // bound would either waste a round trip or silently lose the tail.
        assertEquals(5000, MAX_TTS_CHARS)
    }

    @Test
    fun `the accepted input ceiling is far above one request`() {
        // If these ever met, the app would be back to cutting a long passage off
        // at what one request can carry.
        assertTrue(
            "accepted input must exceed a single request by a wide margin",
            MAX_INPUT_CHARS > MAX_TTS_CHARS * 5,
        )
    }
}
