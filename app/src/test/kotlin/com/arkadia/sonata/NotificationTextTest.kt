package com.arkadia.sonata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification's text contract.
 *
 * The defect this pins: the notification was rebuilt only on start/pause/resume/
 * complete, so its progress line was computed once and then frozen. A listener
 * opening the shade ten minutes into a long read saw the position from the
 * moment the read began. The service now refreshes it on a ticker; these tests
 * pin what the refreshed text must say.
 */
class NotificationTextTest {

    // ── Position ──────────────────────────────────────────────────────────────

    @Test
    fun `position advances as the read progresses`() {
        // The same read at two points must produce two different lines. This is
        // the assertion the old code could not satisfy, because it never
        // recomputed the line at all.
        val early = NotificationText.position(
            spokenChars = 100, totalChars = 1_000,
            segmentIndex = 1, segmentCount = 5,
            buffering = false, playing = true,
        )
        val later = NotificationText.position(
            spokenChars = 600, totalChars = 1_000,
            segmentIndex = 3, segmentCount = 5,
            buffering = false, playing = true,
        )

        assertEquals("Part 1 of 5 · 10%", early)
        assertEquals("Part 3 of 5 · 60%", later)
        assertTrue("the line must actually change", early != later)
    }

    @Test
    fun `a single-piece read shows a percentage without a part number`() {
        val line = NotificationText.position(
            spokenChars = 250, totalChars = 1_000,
            segmentIndex = 1, segmentCount = 1,
            buffering = false, playing = true,
        )
        assertEquals("25%", line)
    }

    @Test
    fun `buffering wins over a percentage`() {
        // A frozen percentage during a stall is worse than saying "Buffering…":
        // the number is not advancing, and presenting it as progress implies it
        // is about to.
        val line = NotificationText.position(
            spokenChars = 400, totalChars = 1_000,
            segmentIndex = 2, segmentCount = 5,
            buffering = true, playing = true,
        )
        assertEquals("Buffering…", line)
    }

    @Test
    fun `a paused read shows no live position`() {
        // A paused read has a position, but presenting it as live progress would
        // imply it is still moving.
        val line = NotificationText.position(
            spokenChars = 400, totalChars = 1_000,
            segmentIndex = 2, segmentCount = 5,
            buffering = false, playing = false,
        )
        assertNull(line)
    }

    @Test
    fun `a read with no known length shows no position`() {
        val line = NotificationText.position(
            spokenChars = 0, totalChars = 0,
            segmentIndex = 0, segmentCount = 0,
            buffering = false, playing = true,
        )
        assertNull(line)
    }

    @Test
    fun `a percentage is clamped to 0 to 100`() {
        // A spoken count past the total is possible at a chunk seam; it must not
        // render as "104%".
        val over = NotificationText.position(
            spokenChars = 1_040, totalChars = 1_000,
            segmentIndex = 5, segmentCount = 5,
            buffering = false, playing = true,
        )
        assertTrue(over!!.endsWith("100%"))
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    @Test
    fun `a long passage is elided at the preview limit`() {
        val long = "word ".repeat(100)
        val preview = NotificationText.preview(long)!!
        assertTrue(preview.endsWith("…"))
        assertEquals(NotificationText.PREVIEW_CHARS + 1, preview.length)
    }

    @Test
    fun `whitespace is collapsed in the preview`() {
        assertEquals("a b c", NotificationText.preview("a\n\n  b\t c"))
    }

    @Test
    fun `an empty passage has no preview`() {
        assertNull(NotificationText.preview("   \n  "))
    }

    // ── Voice ─────────────────────────────────────────────────────────────────

    @Test
    fun `the voice line distinguishes a fallback from a normal offline read`() {
        assertEquals("Neural voice", NotificationText.voice(TtsEngineKind.ONLINE, false))
        assertEquals("Offline voice", NotificationText.voice(TtsEngineKind.OFFLINE, false))
        assertEquals(
            "Offline voice (backend unavailable)",
            NotificationText.voice(TtsEngineKind.OFFLINE, true),
        )
        assertNull(NotificationText.voice(null, false))
    }

    // ── Composition ───────────────────────────────────────────────────────────

    @Test
    fun `the body joins the parts that exist and drops the ones that do not`() {
        val body = NotificationText.body(
            text = "A short passage",
            spokenChars = 500, totalChars = 1_000,
            segmentIndex = 2, segmentCount = 4,
            buffering = false, playing = true,
            engine = TtsEngineKind.OFFLINE, fellBackOffline = true,
        )
        assertEquals(
            "A short passage · Part 2 of 4 · 50% · Offline voice (backend unavailable)",
            body,
        )
    }

    @Test
    fun `a paused read with no preview does not leave stray separators`() {
        // The old code joined a blank preview with the other parts, which could
        // render as " ·  · Offline voice".
        val body = NotificationText.body(
            text = "   ",
            spokenChars = 0, totalChars = 0,
            segmentIndex = 0, segmentCount = 0,
            buffering = false, playing = false,
            engine = TtsEngineKind.OFFLINE, fellBackOffline = false,
        )
        assertEquals("Offline voice", body)
        assertTrue("no leading separator", !body.startsWith("·"))
        assertTrue("no doubled separator", !body.contains("·  ·"))
    }
}
