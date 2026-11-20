package com.arkadia.sonata.offline

import com.arkadia.sonata.canvas.ReadAlongTimeline
import com.arkadia.sonata.canvas.TextNormalizer
import com.arkadia.sonata.canvas.TextSpan
import com.arkadia.sonata.canvas.TimelineBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 5 — the Read-Along Canvas. */
class ReadAlongTest {

    // ── The cleaning contract ────────────────────────────────────────────────

    @Test
    fun `emoji are removed`() {
        assertEquals("Hello world.", TextNormalizer.clean("Hello 😀 world. 🎉"))
    }

    @Test
    fun `an astral-plane emoji is removed`() {
        // The bug the previous implementation had: iterating Chars saw a surrogate
        // pair as two unpaired surrogates, so neither matched and the emoji survived.
        val cleaned = TextNormalizer.clean("Great work \uD83D\uDE80 done.")
        assertFalse(cleaned.contains("\uD83D\uDE80"))
        assertTrue(cleaned.contains("Great work"))
    }

    @Test
    fun `a flag emoji is removed`() {
        val cleaned = TextNormalizer.clean("From \uD83C\uDDEC\uD83C\uDDE7 today.")
        assertFalse(cleaned.contains("\uD83C\uDDEC"))
    }

    @Test
    fun `an em dash becomes a pause, not the word dash`() {
        val cleaned = TextNormalizer.clean("Wait \u2014 then go.")
        assertFalse(cleaned.contains("dash"))
        assertTrue(cleaned.contains(","))
    }

    @Test
    fun `a percent sign is spoken as a word`() {
        assertTrue(TextNormalizer.clean("50% of it").contains("percent"))
    }

    @Test
    fun `an ampersand is spoken as and`() {
        assertTrue(TextNormalizer.clean("salt & pepper").contains("and"))
    }

    @Test
    fun `html tags are removed`() {
        assertEquals("Hello world", TextNormalizer.clean("<p>Hello <b>world</b></p>"))
    }

    @Test
    fun `markdown syntax is removed but the words kept`() {
        val cleaned = TextNormalizer.clean("## Heading\n\n**bold** and *italic*")
        assertFalse(cleaned.contains("##"))
        assertFalse(cleaned.contains("**"))
        assertTrue(cleaned.contains("Heading"))
        assertTrue(cleaned.contains("bold"))
        assertTrue(cleaned.contains("italic"))
    }

    @Test
    fun `a markdown table becomes spoken sentences`() {
        val table = """
            | Name | Score |
            |------|-------|
            | Ada  | 95    |
            | Bob  | 88    |
        """.trimIndent()
        val cleaned = TextNormalizer.clean(table)
        assertTrue(cleaned.contains("Name: Ada"))
        assertTrue(cleaned.contains("Score: 95"))
        assertFalse(cleaned.contains("|"))
    }

    @Test
    fun `abbreviations are expanded`() {
        val cleaned = TextNormalizer.clean("Bring a coat, e.g. a warm one, etc.")
        assertTrue(cleaned.contains("for example"))
        assertTrue(cleaned.contains("and so on"))
        assertFalse(cleaned.contains("e.g."))
    }

    @Test
    fun `a title is expanded`() {
        assertTrue(TextNormalizer.clean("Dr. Smith arrived.").contains("Doctor Smith"))
    }

    @Test
    fun `paragraph breaks are preserved but runs of blank lines are collapsed`() {
        val cleaned = TextNormalizer.clean("One.\n\n\n\nTwo.")
        assertEquals("One.\n\nTwo.", cleaned)
    }

    @Test
    fun `paragraphs are split on blank lines`() {
        val paragraphs = TextNormalizer.paragraphs("One.\n\nTwo.\n\nThree.")
        assertEquals(listOf("One.", "Two.", "Three."), paragraphs)
    }

    @Test
    fun `the original text is never modified`() {
        // The cleaner is pure: it returns a new string and the input is untouched.
        val original = "Hello 😀 world \u2014 e.g. this."
        val cleaned = TextNormalizer.clean(original)
        assertEquals("Hello 😀 world \u2014 e.g. this.", original)
        assertFalse(cleaned.contains("😀"))
    }

    // ── Sentence segmentation ────────────────────────────────────────────────

    @Test
    fun `sentences are split on terminators`() {
        val ranges = TimelineBuilder.sentenceRanges("One. Two! Three?")
        assertEquals(3, ranges.size)
    }

    @Test
    fun `an initial is not a sentence end`() {
        // "J. R. R. Tolkien" must not become three sentences.
        val ranges = TimelineBuilder.sentenceRanges("J. R. R. Tolkien wrote it.")
        assertEquals(1, ranges.size)
    }

    // ── The timeline ─────────────────────────────────────────────────────────

    @Test
    fun `every millisecond maps to exactly one sentence`() {
        val text = "One. Two. Three."
        val timeline = TimelineBuilder.build(text, 3000)
        for (ms in 0 until 3000) {
            assertNotNull("no span at $ms", timeline.sentenceAt(ms.toLong()))
        }
    }

    @Test
    fun `the last sentence covers the end of the audio`() {
        // Rounding must not leave a gap where nothing is highlighted.
        val timeline = TimelineBuilder.build("One. Two. Three.", 3001)
        assertNotNull(timeline.sentenceAt(3000))
    }

    @Test
    fun `a confident word span is preferred over the sentence`() {
        val text = "Hello world."
        val word = TextSpan(0, 5, 0, 500, TextSpan.Granularity.WORD, confidence = 0.9f)
        val timeline = TimelineBuilder.build(text, 1000, listOf(word))
        assertEquals(TextSpan.Granularity.WORD, timeline.spanAt(100)?.granularity)
    }

    @Test
    fun `an unconfident word span falls back to the sentence`() {
        // Highlighting a word the engine is unsure about is a visible lie.
        val text = "Hello world."
        val word = TextSpan(0, 5, 0, 500, TextSpan.Granularity.WORD, confidence = 0.4f)
        val timeline = TimelineBuilder.build(text, 1000, listOf(word))
        assertEquals(TextSpan.Granularity.SENTENCE, timeline.spanAt(100)?.granularity)
    }

    @Test
    fun `a timeline with no word alignment still highlights sentences`() {
        val timeline = TimelineBuilder.build("One. Two.", 2000)
        assertTrue(timeline.words.isEmpty())
        assertNotNull(timeline.spanAt(500))
    }

    @Test
    fun `the text at a position is the sentence being spoken`() {
        val timeline = TimelineBuilder.build("First one. Second one.", 2000)
        val first = timeline.textAt(100)
        assertTrue(first!!.contains("First"))
    }

    @Test
    fun `an empty text yields an empty timeline`() {
        val timeline = TimelineBuilder.build("", 0)
        assertTrue(timeline.sentences.isEmpty())
        assertNull(timeline.spanAt(0))
    }

    @Test
    fun `the confidence floor is the documented value`() {
        assertEquals(0.7f, ReadAlongTimeline.WORD_CONFIDENCE_FLOOR, 0.0001f)
    }
}
