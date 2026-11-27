package com.arkadia.sonata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule that decides whether a stored read outcome may be presented.
 *
 * This is the safety property of the feature: a stale or inapplicable "Completed"
 * is a false claim about the user's passage, and the screen must withhold it rather
 * than approximate. Every test below is either a shape that must be withheld, or the
 * one shape that must be shown.
 */
class ReadOutcomeTest {

    private val passage = "The lake was still, and the morning had not yet reached it."

    private fun completed(p: String = passage) =
        ReadOutcome(passage = p, status = ReadOutcome.Status.COMPLETED)

    private fun failed(message: String, p: String = passage) =
        ReadOutcome(passage = p, status = ReadOutcome.Status.FAILED, message = message)

    @Test
    fun `no stored outcome presents nothing`() {
        assertNull(ReadOutcomePolicy.adopt(null, passage, live = false))
    }

    @Test
    fun `a completed outcome for this passage is presented`() {
        assertEquals(completed(), ReadOutcomePolicy.adopt(completed(), passage, live = false))
    }

    @Test
    fun `a failed outcome keeps its message`() {
        val stored = failed("The device voice is not installed")
        val adopted = ReadOutcomePolicy.adopt(stored, passage, live = false)
        assertEquals("The device voice is not installed", adopted?.message)
        assertEquals(ReadOutcome.Status.FAILED, adopted?.status)
    }

    @Test
    fun `a live read is never painted over by a stored outcome`() {
        assertNull(ReadOutcomePolicy.adopt(completed(), passage, live = true))
    }

    @Test
    fun `an empty field has no passage for the outcome to describe`() {
        assertNull(ReadOutcomePolicy.adopt(completed(), "", live = false))
        assertNull(ReadOutcomePolicy.adopt(completed(), "   \n\t ", live = false))
    }

    @Test
    fun `an outcome for different text is withheld`() {
        assertNull(
            ReadOutcomePolicy.adopt(completed("A different passage"), passage, live = false),
        )
    }

    @Test
    fun `appending to the passage makes the old outcome inapplicable`() {
        assertNull(ReadOutcomePolicy.adopt(completed(), "$passage And then it rained.", live = false))
    }

    @Test
    fun `a stored outcome is not matched by prefix or case folding`() {
        assertNull(ReadOutcomePolicy.adopt(completed(passage.take(20)), passage, live = false))
        assertNull(ReadOutcomePolicy.adopt(completed(passage.uppercase()), passage, live = false))
    }

    @Test
    fun `an outcome survives the screen being away and is presented on return`() {
        // The scenario this feature exists for: the read finished while the screen
        // was unbound, the field still holds the same passage, and the outcome is
        // the only evidence that it happened.
        val stored = completed()
        assertEquals(stored, ReadOutcomePolicy.adopt(stored, passage, live = false))
    }

    @Test
    fun `a failure is withheld from a passage it does not describe`() {
        assertNull(
            ReadOutcomePolicy.adopt(failed("boom", "some other text"), passage, live = false),
        )
    }
}
