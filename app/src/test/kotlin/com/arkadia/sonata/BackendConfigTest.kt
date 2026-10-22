package com.arkadia.sonata

import org.junit.Assert.assertEquals
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
