package com.arkadia.sonata.offline

import com.arkadia.sonata.narration.ContextClassifier
import com.arkadia.sonata.narration.NarrationContext
import com.arkadia.sonata.narration.NarrationIntelligence
import com.arkadia.sonata.narration.NarrationStyle
import com.arkadia.sonata.narration.PauseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 6 — Narration Intelligence. */
class NarrationIntelligenceTest {

    // ── The eight styles ─────────────────────────────────────────────────────

    @Test
    fun `there are exactly eight styles`() {
        assertEquals(8, NarrationStyle.entries.size)
    }

    @Test
    fun `every style is defined by rate, pause and pitch`() {
        // A style is only what the engine can vary. There is no "emotion" field,
        // because there is no emotion parameter to set.
        for (style in NarrationStyle.entries) {
            assertTrue("${style.name} rate", style.rateScale > 0f)
            assertTrue("${style.name} pause", style.pauseScale > 0f)
            assertTrue("${style.name} has a description", style.description.isNotBlank())
        }
    }

    @Test
    fun `the styles are audibly distinct`() {
        // The specification requires the difference between treatments to be
        // audible, so no two styles may share all three parameters.
        val signatures = NarrationStyle.entries.map {
            Triple(it.rateScale, it.pauseScale, it.pitchSemitones)
        }
        assertEquals(signatures.size, signatures.toSet().size)
    }

    @Test
    fun `a stored style name resolves, and an unknown one falls back`() {
        assertEquals(NarrationStyle.DRAMATIC, NarrationStyle.fromName("DRAMATIC"))
        assertEquals(NarrationStyle.DEFAULT, NarrationStyle.fromName("NONSENSE"))
        assertEquals(NarrationStyle.DEFAULT, NarrationStyle.fromName(null))
    }

    // ── Context classification ───────────────────────────────────────────────

    @Test
    fun `a letter is classified as a letter`() {
        val text = "Dear Sir,\n\nI am writing to confirm the arrangement.\n\nSincerely,\nAda"
        assertEquals(NarrationContext.LETTER, ContextClassifier.classify(text))
    }

    @Test
    fun `a story is classified as storytelling`() {
        val text = "Once upon a time there was a house. One day she said something. " +
            "Suddenly the door opened."
        assertEquals(NarrationContext.STORYTELLING, ContextClassifier.classify(text))
    }

    @Test
    fun `technical material is classified as technical`() {
        val text = "See figure 2 and table 3. The parameter is defined in section 4. " +
            "- first item\n- second item"
        assertEquals(NarrationContext.TECHNICAL, ContextClassifier.classify(text))
    }

    @Test
    fun `plain text is neutral`() {
        assertEquals(NarrationContext.NEUTRAL, ContextClassifier.classify("The cat sat on the mat."))
    }

    @Test
    fun `empty text is neutral`() {
        assertEquals(NarrationContext.NEUTRAL, ContextClassifier.classify(""))
    }

    @Test
    fun `each context suggests a style`() {
        for (context in NarrationContext.entries) {
            assertNotNull(context.suggestedStyle)
        }
    }

    // ── The pipeline ─────────────────────────────────────────────────────────

    @Test
    fun `the plan covers the whole text`() {
        val text = "First sentence. Second sentence. Third one."
        val plan = NarrationIntelligence.plan(text)
        assertFalse(plan.isEmpty)
        // The segments and pauses together cover the text exactly.
        assertEquals(0, plan.segments.first().start)
        assertEquals(text.length, plan.segments.last().end)
    }

    @Test
    fun `a sentence break is longer than a clause break`() {
        val plan = NarrationIntelligence.plan("Wait, then go. Now.")
        val clause = plan.pauses.first { it.kind == PauseKind.CLAUSE }
        val sentence = plan.pauses.first { it.kind == PauseKind.SENTENCE }
        assertTrue(sentence.durationMs > clause.durationMs)
    }

    @Test
    fun `a paragraph break is longer than a sentence break`() {
        val plan = NarrationIntelligence.plan("One.\n\nTwo.")
        val paragraph = plan.pauses.first { it.kind == PauseKind.PARAGRAPH }
        val sentence = plan.pauses.first { it.kind == PauseKind.SENTENCE }
        assertTrue(paragraph.durationMs > sentence.durationMs)
    }

    @Test
    fun `the style scales every pause`() {
        val text = "One. Two. Three."
        val natural = NarrationIntelligence.plan(text, NarrationStyle.NATURAL)
        val dramatic = NarrationIntelligence.plan(text, NarrationStyle.DRAMATIC)
        assertTrue(dramatic.totalPauseMs > natural.totalPauseMs)
    }

    @Test
    fun `the user's style overrides the context's suggestion`() {
        val letter = "Dear Sir,\n\nHello.\n\nSincerely, Ada"
        val plan = NarrationIntelligence.plan(letter, NarrationStyle.PROFESSIONAL)
        assertEquals(NarrationStyle.PROFESSIONAL, plan.style)
        // The context is still detected and reported, even though it was overridden.
        assertEquals(NarrationContext.LETTER, plan.context)
    }

    @Test
    fun `with no style the context decides`() {
        val letter = "Dear Sir,\n\nHello.\n\nSincerely, Ada"
        val plan = NarrationIntelligence.plan(letter)
        assertEquals(NarrationContext.LETTER.suggestedStyle, plan.style)
    }

    @Test
    fun `an initial does not create a sentence pause`() {
        val plan = NarrationIntelligence.plan("J. R. R. Tolkien wrote it.")
        val sentencePauses = plan.pauses.count { it.kind == PauseKind.SENTENCE }
        assertEquals(1, sentencePauses)
    }

    @Test
    fun `an empty text yields an empty plan`() {
        val plan = NarrationIntelligence.plan("")
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `the plan is inspectable`() {
        // The plan is a plain value, so the difference between treatments can be
        // verified rather than asserted.
        val plan = NarrationIntelligence.plan("One. Two.", NarrationStyle.WARM)
        assertTrue(plan.segments.isNotEmpty())
        assertTrue(plan.pauses.isNotEmpty())
        assertEquals(NarrationStyle.WARM.rateScale, plan.rateScale, 0.0001f)
    }

    @Test
    fun `a segment carries its style's pitch`() {
        val plan = NarrationIntelligence.plan("One. Two.", NarrationStyle.DRAMATIC)
        assertTrue(plan.segments.all { it.pitchSemitones == NarrationStyle.DRAMATIC.pitchSemitones })
    }
}
