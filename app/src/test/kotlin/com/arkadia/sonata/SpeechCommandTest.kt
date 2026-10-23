package com.arkadia.sonata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intent-to-command mapping, including the empty-SPEAK refusal. */
class SpeechCommandTest {

    @Test
    fun `a speak action with text resolves to a speak command`() {
        val command = SpeechCommand.resolve(SpeechService.ACTION_SPEAK, "Hello", "TITLE")
        assertTrue(command is SpeechCommand.Speak)
        command as SpeechCommand.Speak
        assertEquals("Hello", command.text)
        assertEquals("TITLE", command.label)
    }

    @Test
    fun `a speak action without a label uses the default label`() {
        val command = SpeechCommand.resolve(SpeechService.ACTION_SPEAK, "Hello", null)
        assertEquals(SpeechCommand.DEFAULT_LABEL, (command as SpeechCommand.Speak).label)
    }

    @Test
    fun `a speak action with no text is refused`() {
        // Regression: the service used to promote a foreground notification and
        // then return early, leaving a live service with nothing to do.
        assertEquals(SpeechCommand.None, SpeechCommand.resolve(SpeechService.ACTION_SPEAK, null, null))
        assertEquals(SpeechCommand.None, SpeechCommand.resolve(SpeechService.ACTION_SPEAK, "", null))
        assertEquals(SpeechCommand.None, SpeechCommand.resolve(SpeechService.ACTION_SPEAK, "   ", null))
    }

    @Test
    fun `pause, resume and stop map to their commands`() {
        assertEquals(SpeechCommand.Pause, SpeechCommand.resolve(SpeechService.ACTION_PAUSE, null, null))
        assertEquals(SpeechCommand.Resume, SpeechCommand.resolve(SpeechService.ACTION_RESUME, null, null))
        assertEquals(SpeechCommand.Stop, SpeechCommand.resolve(SpeechService.ACTION_STOP, null, null))
    }

    @Test
    fun `an unknown action resolves to none`() {
        assertEquals(SpeechCommand.None, SpeechCommand.resolve("com.example.UNKNOWN", "text", null))
    }

    @Test
    fun `a missing action resolves to none`() {
        assertEquals(SpeechCommand.None, SpeechCommand.resolve(null, "text", null))
    }

    @Test
    fun `a null intent resolves to none`() {
        assertEquals(SpeechCommand.None, SpeechCommand.from(null))
    }

    @Test
    fun `the process text path and the service path agree on the action name`() {
        // ProcessTextActivity and MainActivity both send ACTION_SPEAK; the mapping
        // must accept the exact constant the producers use.
        assertEquals(
            SpeechCommand.Speak("Hi", SpeechCommand.DEFAULT_LABEL),
            SpeechCommand.resolve(SpeechService.ACTION_SPEAK, "Hi", null),
        )
    }
}

/**
 * The resume offset travels with the SPEAK command.
 *
 * A stored position that silently fails to reach the engine would look like the
 * feature not working, with no error anywhere — so the plumbing is pinned here
 * rather than only at the engine.
 */
class SpeechCommandResumeTest {

    @Test
    fun `a resolved speak carries the offset it was given`() {
        val command = SpeechCommand.resolve(
            action = SpeechService.ACTION_SPEAK,
            text = "A long passage",
            label = null,
            fromChar = 4200,
        )
        assertEquals(4200, (command as SpeechCommand.Speak).fromChar)
    }

    @Test
    fun `speak defaults to reading from the start`() {
        val command = SpeechCommand.resolve(SpeechService.ACTION_SPEAK, "Hello", null)
        assertEquals(0, (command as SpeechCommand.Speak).fromChar)
    }

    @Test
    fun `a negative offset is treated as the start`() {
        // A corrupt or stale stored value must not become a negative index.
        val command = SpeechCommand.resolve(
            action = SpeechService.ACTION_SPEAK,
            text = "Hello",
            label = null,
            fromChar = -10,
        )
        assertEquals(0, (command as SpeechCommand.Speak).fromChar)
    }
}
