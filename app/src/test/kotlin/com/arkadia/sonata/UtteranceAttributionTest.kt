package com.arkadia.sonata

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A terminal event belongs to what is in flight, not to "a read was started".
 *
 * The defect this pins: auditioning a voice supersedes the read (two audio streams
 * cannot play at once), and the service's "a read is in progress" flag stayed set.
 * The sample's failure was therefore recorded against the passage, so a read the
 * user had deliberately interrupted came back as **Failed**, carrying the sample's
 * error message.
 */
class UtteranceAttributionTest {

    @Test
    fun `a completed read is recorded as a completion`() {
        assertEquals(
            TerminalRecord.COMPLETION,
            UtteranceAttributionPolicy.onComplete(InFlightUtterance.READ),
        )
    }

    @Test
    fun `a failed read is recorded as a failure`() {
        assertEquals(
            TerminalRecord.FAILURE,
            UtteranceAttributionPolicy.onTerminalError(InFlightUtterance.READ),
        )
    }

    @Test
    fun `a completed sample records nothing`() {
        assertEquals(
            TerminalRecord.NONE,
            UtteranceAttributionPolicy.onComplete(InFlightUtterance.PREVIEW),
        )
    }

    /**
     * The defect case. A sample is in flight because it superseded a read; the read
     * was interrupted by the user, so the sample's failure must not be recorded as
     * the passage's.
     */
    @Test
    fun `a failed sample records nothing, even though it superseded a read`() {
        assertEquals(
            TerminalRecord.NONE,
            UtteranceAttributionPolicy.onTerminalError(InFlightUtterance.PREVIEW),
        )
    }

    @Test
    fun `an unattributable event records nothing`() {
        assertEquals(
            TerminalRecord.NONE,
            UtteranceAttributionPolicy.onComplete(InFlightUtterance.NONE),
        )
        assertEquals(
            TerminalRecord.NONE,
            UtteranceAttributionPolicy.onTerminalError(InFlightUtterance.NONE),
        )
    }

    /** A completion and a failure are never confused, in either direction. */
    @Test
    fun `the two terminal events never produce each other's record`() {
        for (inFlight in InFlightUtterance.values()) {
            assertEquals(
                UtteranceAttributionPolicy.onComplete(inFlight) == TerminalRecord.FAILURE,
                false,
            )
            assertEquals(
                UtteranceAttributionPolicy.onTerminalError(inFlight) == TerminalRecord.COMPLETION,
                false,
            )
        }
    }

    /**
     * Only a read records, at every event. Stated as one property so a future
     * utterance kind is judged by the same rule instead of silently recording.
     */
    @Test
    fun `only a read in flight produces a record`() {
        for (inFlight in InFlightUtterance.values()) {
            val expected = inFlight != InFlightUtterance.READ
            assertEquals(expected, UtteranceAttributionPolicy.onComplete(inFlight) == TerminalRecord.NONE)
            assertEquals(expected, UtteranceAttributionPolicy.onTerminalError(inFlight) == TerminalRecord.NONE)
        }
    }
}
