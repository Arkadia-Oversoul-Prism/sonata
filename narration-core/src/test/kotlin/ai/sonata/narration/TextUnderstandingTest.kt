package ai.sonata.narration

import org.junit.Assert.*
import org.junit.Test

/**
 * Stage 4, layer 1 — text understanding.
 *
 * These tests are pure string-in, structure-out. No audio, no engine, no clock. If one
 * of them fails, the fault is in how the text was read, and nothing downstream can be
 * blamed for it.
 */
class TextUnderstandingTest {

    // ---------- sentence splitting ----------

    @Test
    fun `splits plain sentences`() {
        val s = TextUnderstanding.splitSentences("One thing happened. Then another thing happened. That was all.")
        assertEquals(3, s.size)
        assertEquals("One thing happened.", s[0])
        assertEquals("That was all.", s[2])
    }

    @Test
    fun `does not split on an abbreviation`() {
        val s = TextUnderstanding.splitSentences("Dr. Smith arrived at 9 a.m. and waited. Nobody came.")
        assertEquals(2, s.size)
        assertTrue(s[0].contains("Dr. Smith"))
        assertTrue(s[0].contains("a.m."))
    }

    @Test
    fun `does not split on initials`() {
        val s = TextUnderstanding.splitSentences("J. R. R. Tolkien wrote books. He was patient.")
        assertEquals(2, s.size)
        assertTrue("initials must stay together, got $s", s[0].startsWith("J. R. R. Tolkien"))
    }

    @Test
    fun `keeps a run of terminators together`() {
        val s = TextUnderstanding.splitSentences("Was it worth it?! He said it was. Then nothing.")
        assertEquals(3, s.size)
        assertEquals("Was it worth it?!", s[0])
    }

    @Test
    fun `keeps a closing quote with its sentence`() {
        val s = TextUnderstanding.splitSentences("\"Fine,\" he wrote. \"Fine.\" Then nothing.")
        assertEquals(3, s.size)
        assertEquals("\"Fine,\" he wrote.", s[0])
    }

    @Test
    fun `does not split when the next word is lower case`() {
        val s = TextUnderstanding.splitSentences("The value was 3.5 units in total.")
        assertEquals(1, s.size)
    }

    @Test
    fun `handles a sentence with no terminator`() {
        val s = TextUnderstanding.splitSentences("A heading without a period")
        assertEquals(1, s.size)
        assertEquals("A heading without a period", s[0])
    }

    @Test
    fun `empty input yields no sentences`() {
        assertTrue(TextUnderstanding.splitSentences("").isEmpty())
        assertTrue(TextUnderstanding.splitSentences("   \n\n  ").isEmpty())
    }

    // ---------- paragraph structure ----------

    @Test
    fun `splits paragraphs on blank lines`() {
        val p = TextUnderstanding.splitParagraphs("First para here.\n\nSecond para here.\n\nThird.")
        assertEquals(3, p.size)
        assertEquals("First para here.", p[0].text)
    }

    @Test
    fun `single newline does not start a new paragraph`() {
        val p = TextUnderstanding.splitParagraphs("Line one\nline two continues.")
        assertEquals(1, p.size)
    }

    @Test
    fun `recognises a heading`() {
        val p = TextUnderstanding.splitParagraphs("# Chapter One\n\nSome prose follows here.")
        assertEquals(ParagraphKind.HEADING, p[0].kind)
        assertEquals(ParagraphKind.PROSE, p[1].kind)
    }

    @Test
    fun `recognises a numbered list`() {
        val p = TextUnderstanding.splitParagraphs("1. First item\n2. Second item\n3. Third item")
        assertEquals(ParagraphKind.LIST, p[0].kind)
    }

    @Test
    fun `recognises a bulleted list`() {
        val p = TextUnderstanding.splitParagraphs("- alpha\n- beta")
        assertEquals(ParagraphKind.LIST, p[0].kind)
    }

    @Test
    fun `strips list markers`() {
        assertEquals("First item", TextUnderstanding.stripListMarker("1. First item"))
        assertEquals("alpha", TextUnderstanding.stripListMarker("- alpha"))
        assertEquals("beta", TextUnderstanding.stripListMarker("a) beta"))
    }

    // ---------- classification ----------

    @Test
    fun `classifies a question`() {
        assertEquals(SegmentKind.QUESTION, TextUnderstanding.classifySentence("Was it worth it?"))
    }

    @Test
    fun `classifies an exclamation`() {
        assertEquals(SegmentKind.EXCLAMATION, TextUnderstanding.classifySentence("He said it was!"))
    }

    @Test
    fun `classifies technical text by hex literal`() {
        assertEquals(SegmentKind.TECHNICAL, TextUnderstanding.classifySentence("The manifest records 0x1F2A as the version."))
    }

    @Test
    fun `classifies technical text by symbol density`() {
        assertEquals(SegmentKind.TECHNICAL, TextUnderstanding.classifySentence("Use a/b|c@d#e to reach the node."))
    }

    @Test
    fun `classifies dialogue by leading quote`() {
        assertEquals(SegmentKind.DIALOGUE_LINE, TextUnderstanding.classifySentence("\"You're late,\" she said."))
    }

    @Test
    fun `classifies dialogue by speech dash`() {
        assertEquals(SegmentKind.DIALOGUE_LINE, TextUnderstanding.classifySentence("— I never said that."))
    }

    @Test
    fun `plain prose is a sentence`() {
        assertEquals(SegmentKind.SENTENCE, TextUnderstanding.classifySentence("The morning arrived without ceremony."))
    }

    // ---------- emphasis ----------

    @Test
    fun `extracts bold emphasis`() {
        val (text, level) = TextUnderstanding.extractEmphasis("This is **very** important.")
        assertEquals(Emphasis.STRONG, level)
        assertEquals("This is very important.", text)
    }

    @Test
    fun `extracts italic emphasis`() {
        val (text, level) = TextUnderstanding.extractEmphasis("This is *quite* important.")
        assertEquals(Emphasis.LIGHT, level)
        assertEquals("This is quite important.", text)
    }

    @Test
    fun `extracts underscore emphasis`() {
        val (text, level) = TextUnderstanding.extractEmphasis("This is _quite_ important.")
        assertEquals(Emphasis.LIGHT, level)
        assertEquals("This is quite important.", text)
    }

    @Test
    fun `no emphasis leaves text untouched`() {
        val (text, level) = TextUnderstanding.extractEmphasis("Nothing special here.")
        assertEquals(Emphasis.NONE, level)
        assertEquals("Nothing special here.", text)
    }

    @Test
    fun `does not treat snake_case as emphasis`() {
        val (text, level) = TextUnderstanding.extractEmphasis("Call read_files then write_files.")
        assertEquals(Emphasis.NONE, level)
        assertEquals("Call read_files then write_files.", text)
    }

    // ---------- syllables ----------

    @Test
    fun `counts syllables plausibly`() {
        assertEquals(1, TextUnderstanding.countSyllables("cat"))
        assertEquals(2, TextUnderstanding.countSyllables("table"))
        assertEquals(3, TextUnderstanding.countSyllables("beautiful"))
        // "extraordinary" is 5 syllables (ex-tra-or-di-nar-y); the first version of this
        // test asserted 4, which was the test being wrong, not the counter.
        assertEquals(5, TextUnderstanding.countSyllables("extraordinary"))
    }

    @Test
    fun `syllable count never returns zero for a word`() {
        assertTrue(TextUnderstanding.countSyllables("rhythm") >= 1)
        assertTrue(TextUnderstanding.countSyllables("strengths") >= 1)
    }

    // ---------- full analysis ----------

    @Test
    fun `analysis preserves source offsets`() {
        val text = "First sentence here. Second sentence follows.\n\nA new paragraph starts."
        val d = TextUnderstanding.analyse(text)
        for (s in d.sentences) {
            assertTrue("offset out of range", s.sourceStart >= 0 && s.sourceEnd <= text.length)
            assertTrue("inverted range", s.sourceEnd >= s.sourceStart)
            val slice = text.substring(s.sourceStart, s.sourceEnd)
            assertEquals("offset does not point at the sentence text", s.text, slice)
        }
    }

    @Test
    fun `analysis counts sentences and words`() {
        val d = TextUnderstanding.analyse("One two three. Four five.")
        assertEquals(2, d.totalSentences)
        assertEquals(5, d.totalWords)
    }

    @Test
    fun `analysis of a list produces list items`() {
        val d = TextUnderstanding.analyse("1. First item\n2. Second item")
        assertTrue(d.sentences.all { it.kind == SegmentKind.LIST_ITEM })
    }

    @Test
    fun `analysis is deterministic`() {
        val text = "Some text. With two sentences.\n\nAnd a paragraph."
        val a = TextUnderstanding.analyse(text)
        val b = TextUnderstanding.analyse(text)
        assertEquals(a.totalSentences, b.totalSentences)
        assertEquals(a.sentences.map { it.text }, b.sentences.map { it.text })
        assertEquals(a.sentences.map { it.sourceStart }, b.sentences.map { it.sourceStart })
    }

    @Test
    fun `analysis handles a very long single sentence`() {
        val long = (1..400).joinToString(" ") { "word$it" } + "."
        val d = TextUnderstanding.analyse(long)
        assertEquals(1, d.totalSentences)
        assertEquals(400, d.totalWords)
    }
}
