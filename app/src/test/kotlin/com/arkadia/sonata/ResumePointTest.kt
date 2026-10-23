package com.arkadia.sonata

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides whether a stored position may be offered.
 *
 * This is the safety property of resuming: applying an offset to text it does not
 * describe would drop the user into the wrong part of a passage. Every uncertain
 * case must resolve to "start from the beginning".
 */
class ResumePointTest {

    private val passage = "x".repeat(1000)

    @Test
    fun `a position inside the same passage is offered`() {
        assertEquals(500, ResumePoint.offset(passage, 500, passage))
    }

    @Test
    fun `a position in a different passage is refused`() {
        // The dangerous case: same length, different text. An offset here would be
        // meaningless, so it must not be applied.
        val other = "y".repeat(1000)
        assertEquals(0, ResumePoint.offset(passage, 500, other))
    }

    @Test
    fun `appending to a passage stops the offer`() {
        // Editing mid-read is common. The stored offset describes the old text.
        assertEquals(0, ResumePoint.offset(passage, 500, passage + " more text"))
    }

    @Test
    fun `no stored position offers nothing`() {
        assertEquals(0, ResumePoint.offset(passage, 0, passage))
        assertEquals(0, ResumePoint.offset("", 0, passage))
    }

    @Test
    fun `a negative stored position offers nothing`() {
        assertEquals(0, ResumePoint.offset(passage, -10, passage))
    }

    @Test
    fun `an empty current passage offers nothing`() {
        assertEquals(0, ResumePoint.offset(passage, 500, ""))
        assertEquals(0, ResumePoint.offset(passage, 500, "   "))
    }

    @Test
    fun `a position past the end offers nothing`() {
        // The passage was edited shorter, or the value is corrupt.
        assertEquals(0, ResumePoint.offset(passage, 1000, passage))
        assertEquals(0, ResumePoint.offset(passage, 99999, passage))
    }

    @Test
    fun `a position with too little left offers nothing`() {
        // 30 characters remain: nothing worth carrying on to.
        assertEquals(0, ResumePoint.offset(passage, 970, passage))
        // Exactly at the threshold is still not offered.
        assertEquals(0, ResumePoint.offset(passage, 960, passage))
        // One past it is.
        assertEquals(959, ResumePoint.offset(passage, 959, passage))
    }
}
