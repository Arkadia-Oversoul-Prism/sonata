package com.arkadia.sonata.offline

import com.arkadia.sonata.canvas.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shouted headings.
 *
 * A heading set in capitals is typography, not speech. Engines that treat an
 * all-caps token as an initialism read it letter by letter, so "INTRODUCTION"
 * is heard as "I N T R O D U C T I O N". Sentence-casing the line fixes the
 * reading; the controls below keep the rule from touching prose, acronyms or
 * inline emphasis.
 */
class TextNormalizerCaseTest {

    // ── The defect ────────────────────────────────────────────────────────────

    @Test
    fun `an all-caps heading is spoken as a word`() {
        assertEquals("Introduction", TextNormalizer.clean("INTRODUCTION"))
    }

    @Test
    fun `a shouted heading becomes a sentence`() {
        assertEquals("The end", TextNormalizer.clean("THE END"))
    }

    @Test
    fun `a markdown heading marker does not prevent the rewrite`() {
        // stripMarkdown removes "## " before this rule runs, which is why the
        // rule is stated over the line rather than over the raw source.
        assertEquals("Introduction", TextNormalizer.clean("## INTRODUCTION"))
    }

    // ── Acronyms survive ──────────────────────────────────────────────────────

    @Test
    fun `an acronym inside a shouted heading keeps its case`() {
        assertEquals("The NASA story", TextNormalizer.clean("THE NASA STORY"))
    }

    @Test
    fun `a line of acronyms only is left alone`() {
        // Negative control for the acronym list: a line that is entirely
        // acronyms is intentional typography, not a shouted sentence.
        assertEquals("NASA API", TextNormalizer.clean("NASA API"))
        assertFalse(TextNormalizer.isShoutedHeading("NASA API"))
    }

    @Test
    fun `a dashed acronym keeps its hyphen and case`() {
        assertEquals("N-ATLAS", TextNormalizer.clean("N-ATLAS"))
        assertFalse(TextNormalizer.isShoutedHeading("N-ATLAS"))
    }

    // ── Prose is untouched ────────────────────────────────────────────────────

    @Test
    fun `ordinary prose is left alone`() {
        assertEquals("This is Fine.", TextNormalizer.clean("This is Fine."))
    }

    @Test
    fun `inline capitals inside a sentence are left alone`() {
        // Emphasis mid-sentence is read with its surrounding words, so rewriting
        // it would change copy the author chose without fixing the defect.
        assertEquals("This is IMPORTANT.", TextNormalizer.clean("This is IMPORTANT."))
    }

    @Test
    fun `a single capital letter is not a heading`() {
        assertEquals("A", TextNormalizer.clean("A"))
    }

    @Test
    fun `punctuation alone is not a heading`() {
        assertEquals("!!!", TextNormalizer.clean("!!!"))
    }

    // ── Stability ─────────────────────────────────────────────────────────────

    @Test
    fun `the rewrite is idempotent`() {
        val once = TextNormalizer.clean("THE END\n\nINTRODUCTION\n\nNASA API")
        assertEquals(once, TextNormalizer.clean(once))
    }

    @Test
    fun `only the shouted line changes in a mixed passage`() {
        val out = TextNormalizer.clean("THE END\n\nThis is Fine.\n\nNASA API")
        assertEquals("The end\n\nThis is Fine.\n\nNASA API", out)
    }

    @Test
    fun `the detector agrees with the rewrite`() {
        assertTrue(TextNormalizer.isShoutedHeading("INTRODUCTION"))
        assertTrue(TextNormalizer.isShoutedHeading("THE NASA STORY"))
        assertFalse(TextNormalizer.isShoutedHeading("The end"))
    }
}
