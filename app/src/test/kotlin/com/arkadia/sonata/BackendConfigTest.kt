package com.arkadia.sonata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backend address is shipped, not configured.
 *
 * These pin the two properties that make the app usable on install: a default
 * address exists, and every advertised voice resolves to a spinner index. Without
 * the first, the neural voice is unreachable until the user finds a settings screen
 * and types a URL — which is exactly the burden this replaced.
 */
class BackendConfigTest {

    @Test
    fun `a default backend address is shipped`() {
        assertTrue(
            "default backend must be a non-empty http(s) origin",
            BackendConfig.DEFAULT_URL.startsWith("https://"),
        )
    }

    @Test
    fun `the default backend points at the canonical Arkadia origin`() {
        assertEquals("https://arkadia-qzu4.onrender.com", BackendConfig.DEFAULT_URL)
    }

    @Test
    fun `every voice resolves to a valid spinner index`() {
        BackendConfig.VOICES.forEach { voice ->
            val index = BackendConfig.indexOf(voice.id)
            assertTrue("${voice.id} resolved to $index", index in BackendConfig.VOICES.indices)
            assertEquals(voice.id, BackendConfig.VOICES[index].id)
        }
    }

    /**
     * Negative control: an unknown voice id must not resolve to an out-of-range
     * index, which would crash the spinner on a preference written by an older build.
     */
    @Test
    fun `an unknown stored voice falls back to the first entry`() {
        assertEquals(0, BackendConfig.indexOf("no-such-voice"))
    }

    @Test
    fun `voice ids are unique`() {
        val ids = BackendConfig.VOICES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}

/**
 * The picker only offers voices that actually synthesise.
 *
 * Verified against the live gateway: `george` and `ryan` answer 502 with
 * "All TTS engines failed. Edge: No audio was received." while the others return
 * audio. Offering a voice the backend cannot speak turns a working app into a
 * 502 for the user, so they are not offered.
 */
class VoiceOfferingTest {

    @Test
    fun `voices known to fail synthesis are not offered`() {
        val ids = BackendConfig.VOICES.map { it.id }
        assertFalse("george answers 502 on the live gateway", "george" in ids)
        assertFalse("ryan answers 502 on the live gateway", "ryan" in ids)
    }

    @Test
    fun `aetheria is not offered as a duplicate of aria`() {
        // The gateway maps aetheria to en-US-AriaNeural, the same voice as aria.
        val ids = BackendConfig.VOICES.map { it.id }
        assertFalse("aetheria is the same underlying voice as aria", "aetheria" in ids)
    }

    @Test
    fun `voices verified to synthesise are offered`() {
        val ids = BackendConfig.VOICES.map { it.id }
        listOf("aria", "jenny", "sonia", "christopher").forEach {
            assertTrue("$it returned audio from the live gateway", it in ids)
        }
    }

    @Test
    fun `a stored voice that is no longer offered resolves to one that is`() {
        // A preference written by an earlier build can name a removed voice.
        val resolved = BackendConfig.resolveVoiceId("george")
        assertTrue(
            "a removed voice must degrade to an offered one, not a 502",
            resolved in BackendConfig.VOICES.map { it.id },
        )
        assertEquals(BackendConfig.FALLBACK_VOICE_ID, resolved)
    }

    @Test
    fun `a stored voice that is still offered is kept`() {
        assertEquals("sonia", BackendConfig.resolveVoiceId("sonia"))
    }
}
