package com.arkadia.sonata.offline

import com.arkadia.sonata.canvas.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Number and currency normalisation.
 *
 * Each rule here fixes something the engine gets audibly wrong. The dollar sign
 * was the worst of them: the euro, pound and yen were all handled while the
 * dollar was not, so "$1,250" reached the engine as a bare symbol and the amount
 * vanished from the narration entirely.
 */
class TextNormalizerNumbersTest {

    // ── Currency ──────────────────────────────────────────────────────────────

    @Test
    fun `a dollar amount is spoken rather than dropped`() {
        // The defect: '$' was absent from the currency list, so the symbol was
        // read as nothing and the listener heard "It costs 1250.50 today" with
        // no unit at all.
        val out = TextNormalizer.clean("It costs \$1,250.50 today.")
        assertTrue("the unit must be spoken", out.contains("dollars"))
        assertFalse("the symbol must be gone", out.contains("\$"))
    }

    @Test
    fun `the other currencies still work`() {
        assertTrue(TextNormalizer.clean("It costs \u20AC5.").contains("euros"))
        assertTrue(TextNormalizer.clean("It costs \u00A35.").contains("pounds"))
        assertTrue(TextNormalizer.clean("It costs \u00A55.").contains("yen"))
    }

    // ── Thousands separators ──────────────────────────────────────────────────

    @Test
    fun `a thousands separator is removed so the number is read as one`() {
        // "1,250" is read "one comma two five zero" by engines that treat the
        // comma as punctuation.
        assertEquals("1250", TextNormalizer.normaliseNumbers("1,250"))
        assertEquals("1250000", TextNormalizer.normaliseNumbers("1,250,000"))
    }

    @Test
    fun `a comma in a list is not treated as a separator`() {
        // Only a comma followed by exactly three digits is a separator. "3, 4"
        // is a list and must survive.
        assertEquals("3, 4 and 5", TextNormalizer.normaliseNumbers("3, 4 and 5"))
    }

    // ── Ordinals ──────────────────────────────────────────────────────────────

    @Test
    fun `small ordinals are spelled out`() {
        assertEquals("the first item", TextNormalizer.normaliseNumbers("the 1st item"))
        assertEquals("the second item", TextNormalizer.normaliseNumbers("the 2nd item"))
        assertEquals("the third item", TextNormalizer.normaliseNumbers("the 3rd item"))
        assertEquals("the tenth item", TextNormalizer.normaliseNumbers("the 10th item"))
    }

    @Test
    fun `an ordinal is not matched inside a larger number`() {
        // "21st" must not become "2first": the lookbehind rejects a preceding
        // digit, so the whole token is left for the engine to read.
        assertEquals("21st", TextNormalizer.normaliseNumbers("21st"))
    }

    // ── Years ─────────────────────────────────────────────────────────────────

    @Test
    fun `a year is split into two pairs`() {
        // "2026" is read "two thousand and twenty six" by some engines; a person
        // says "twenty twenty six".
        assertEquals("In 20 26 we shipped.", TextNormalizer.normaliseNumbers("In 2026 we shipped."))
        assertEquals("19 99", TextNormalizer.normaliseNumbers("1999"))
    }

    @Test
    fun `a year ending in double zero is said as hundred`() {
        assertEquals("19 hundred", TextNormalizer.normaliseNumbers("1900"))
        assertEquals("20 hundred", TextNormalizer.normaliseNumbers("2000"))
    }

    @Test
    fun `a decimal is not mistaken for a year`() {
        // "3.2026" is a version or a measurement, not a year.
        assertEquals("3.2026", TextNormalizer.normaliseNumbers("3.2026"))
    }

    @Test
    fun `a five digit number is not mistaken for a year`() {
        assertEquals("12026", TextNormalizer.normaliseNumbers("12026"))
    }

    // ── The pipeline ──────────────────────────────────────────────────────────

    @Test
    fun `the full pipeline handles a realistic sentence`() {
        val out = TextNormalizer.clean("In 2026 the 1st release cost \$1,250.")
        assertTrue("year split", out.contains("20 26"))
        assertTrue("ordinal spelled", out.contains("first"))
        assertTrue("currency spoken", out.contains("dollars"))
        assertTrue("separator removed", out.contains("1250"))
        assertFalse("no stray symbol", out.contains("\$"))
    }

    @Test
    fun `normalisation is idempotent on already-clean text`() {
        // Running the cleaner twice must not keep changing the text, or a
        // re-clean on load would drift.
        val once = TextNormalizer.clean("In 2026 the 1st release cost \$1,250.")
        val twice = TextNormalizer.clean(once)
        assertEquals(once, twice)
    }
}
