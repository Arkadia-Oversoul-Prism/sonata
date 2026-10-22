package com.arkadia.sonata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunkerTest {

    @Test
    fun `short text is returned unchanged as a single piece`() {
        val text = "Hello."
        assertEquals(listOf(text), SpeechChunker.split(text))
    }

    @Test
    fun `text exactly at the limit is not split`() {
        val text = "a".repeat(SpeechChunker.PLATFORM_LIMIT)
        assertEquals(listOf(text), SpeechChunker.split(text))
    }

    @Test
    fun `empty text yields no pieces`() {
        assertEquals(emptyList<String>(), SpeechChunker.split(""))
    }

    @Test
    fun `no piece exceeds the limit`() {
        val text = ("word ".repeat(3000)).trim()
        val pieces = SpeechChunker.split(text)
        assertTrue("expected more than one piece", pieces.size > 1)
        pieces.forEach {
            assertTrue("piece of ${it.length} exceeds limit", it.length <= SpeechChunker.PLATFORM_LIMIT)
        }
    }

    /**
     * The load-bearing invariant: splitting must not lose or reorder content.
     * Without this a chunked read-aloud would silently skip part of the passage.
     */
    @Test
    fun `concatenating the pieces reproduces the input exactly`() {
        val text = (1..400).joinToString(" ") { "Sentence number $it ends here." }
        assertEquals(text, SpeechChunker.split(text).joinToString(""))
    }

    @Test
    fun `splits at a sentence boundary when one is available`() {
        val sentence = "This is a sentence. "
        val text = sentence.repeat(400).trim()
        val pieces = SpeechChunker.split(text)
        assertTrue(pieces.size > 1)
        // Every piece but the last should end where a sentence ended.
        pieces.dropLast(1).forEach {
            assertTrue("piece does not end at a boundary: ...${it.takeLast(20)}", it.endsWith("."))
        }
    }

    @Test
    fun `prefers a paragraph break over a sentence break`() {
        val block = "Paragraph text here. ".repeat(50)
        val text = block + "\n\n" + block
        val pieces = SpeechChunker.split(text, limit = block.length + 5)
        assertTrue("expected a paragraph-boundary cut", pieces[0].endsWith("\n"))
    }

    /**
     * Negative control for the break-point search: with no boundary anywhere the
     * chunker must still terminate by cutting hard. If `breakPoint` could return
     * `start`, this would loop forever rather than fail an assertion.
     */
    @Test
    fun `a single unbroken run of characters is cut hard without looping`() {
        val text = "x".repeat(9000)
        val pieces = SpeechChunker.split(text)
        assertEquals(3, pieces.size)
        assertEquals(text, pieces.joinToString(""))
        // The first two fill the limit; the remainder is whatever is left.
        assertEquals(listOf(4000, 4000, 1000), pieces.map { it.length })
    }

    @Test
    fun `a limit below the floor is raised rather than producing tiny pieces`() {
        val text = "y".repeat(1000)
        val pieces = SpeechChunker.split(text, limit = 1)
        pieces.forEach { assertTrue(it.length <= 1000) }
        assertEquals(text, pieces.joinToString(""))
    }
}
