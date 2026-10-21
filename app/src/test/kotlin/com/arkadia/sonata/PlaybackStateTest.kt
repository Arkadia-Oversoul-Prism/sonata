package com.arkadia.sonata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Playback state semantics, including the honest resume distinction. */
class PlaybackStateTest {

    @Test
    fun `idle is neither playing nor paused`() {
        val idle = PlaybackState.Idle
        assertFalse(idle.isPlaying)
        assertFalse(idle.isPaused)
        assertFalse(idle.isResumable)
        assertNull(idle.engine)
        assertNull(idle.remainingText())
    }

    @Test
    fun `online playing is playing but not paused`() {
        val state = PlaybackState.Online(text = "hello", positionMs = 0, paused = false)
        assertTrue(state.isPlaying)
        assertFalse(state.isPaused)
        assertEquals(TtsEngineKind.ONLINE, state.engine)
    }

    @Test
    fun `online paused is paused and resumable`() {
        val state = PlaybackState.Online(text = "hello", positionMs = 1200, paused = true)
        assertFalse(state.isPlaying)
        assertTrue(state.isPaused)
        assertTrue(state.isResumable)
    }

    @Test
    fun `offline paused is resumable`() {
        val state = PlaybackState.Offline(text = "hello world", charOffset = 6, paused = true)
        assertTrue(state.isResumable)
        assertEquals(TtsEngineKind.OFFLINE, state.engine)
    }

    @Test
    fun `offline remainder is the unspoken tail`() {
        val state = PlaybackState.Offline(text = "hello world", charOffset = 6, paused = true)
        assertEquals("world", state.remainingText())
    }

    @Test
    fun `offline remainder is null at the end of the text`() {
        val state = PlaybackState.Offline(text = "hello", charOffset = 5, paused = true)
        assertNull(state.remainingText())
    }

    @Test
    fun `offline remainder is null when only whitespace is left`() {
        val state = PlaybackState.Offline(text = "hello   ", charOffset = 5, paused = true)
        assertNull(state.remainingText())
    }

    @Test
    fun `offline remainder clamps an out-of-range offset instead of throwing`() {
        val state = PlaybackState.Offline(text = "hi", charOffset = 99, paused = true)
        assertNull(state.remainingText())
    }

    @Test
    fun `remaining text is only defined for offline playback`() {
        // Online resume uses a millisecond offset into decoded audio; there is no
        // character remainder to speak.
        assertNull(PlaybackState.Online("hello", 10, paused = true).remainingText())
    }

    @Test
    fun `a stopped utterance is not resumable`() {
        // stop() clears the state entirely: nothing can resume afterwards.
        assertFalse(PlaybackState.Idle.isResumable)
    }
}
