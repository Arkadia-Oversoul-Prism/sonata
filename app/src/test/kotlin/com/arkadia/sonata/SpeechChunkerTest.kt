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

    // ── Streaming split ───────────────────────────────────────────────────────

    /**
     * The load-bearing invariant for progressive playback: a passage read piece by
     * piece must still be read in full and in order. Without this a long selection
     * would silently lose or repeat content at the seams.
     */
    @Test
    fun `streaming split reproduces the input exactly`() {
        val text = (1..400).joinToString(" ") { "Sentence number $it ends here." }
        assertEquals(text, SpeechChunker.splitForStreaming(text).joinToString(""))
    }

    @Test
    fun `streaming split reproduces an unbroken run of characters exactly`() {
        // No boundary anywhere: every seam is a hard cut, which is where an
        // off-by-one would drop or duplicate a character.
        val text = "z".repeat(10_000)
        val pieces = SpeechChunker.splitForStreaming(text)
        assertEquals(text, pieces.joinToString(""))
        assertTrue("expected many pieces", pieces.size > 5)
    }

    @Test
    fun `the first streaming piece is short so audio can start early`() {
        val text = (1..400).joinToString(" ") { "Sentence number $it ends here." }
        val pieces = SpeechChunker.splitForStreaming(text)
        assertTrue(
            "first piece was ${pieces[0].length}, which would delay the first sound",
            pieces[0].length <= SpeechChunker.STREAM_FIRST_LIMIT,
        )
    }

    @Test
    fun `the first streaming piece is never a fragment`() {
        // A first piece of one word would be over before the next was ready.
        val text = ("x".repeat(200) + ". ") + ("y".repeat(5000))
        val pieces = SpeechChunker.splitForStreaming(text)
        assertTrue(
            "first piece was only ${pieces[0].length} characters",
            pieces[0].length >= SpeechChunker.STREAM_MIN_FIRST,
        )
    }

    @Test
    fun `text shorter than the first limit is a single piece`() {
        val text = "A short sentence."
        assertEquals(listOf(text), SpeechChunker.splitForStreaming(text))
    }

    @Test
    fun `streaming pieces after the first are larger than the first`() {
        // The later pieces are prefetched during playback, so they can be bigger;
        // that keeps the request count down on a long passage.
        val text = (1..400).joinToString(" ") { "Sentence number $it ends here." }
        val pieces = SpeechChunker.splitForStreaming(text)
        assertTrue(pieces.size > 2)
        assertTrue(
            "later pieces should exceed the first limit",
            pieces.drop(1).any { it.length > SpeechChunker.STREAM_FIRST_LIMIT },
        )
    }

    @Test
    fun `every streaming piece fits within one backend request`() {
        val text = (1..800).joinToString(" ") { "Sentence number $it ends here." }
        SpeechChunker.splitForStreaming(text).forEach {
            assertTrue(
                "a piece of ${it.length} would be truncated by the backend",
                it.length <= MAX_TTS_CHARS,
            )
        }
    }
}
