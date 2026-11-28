package com.arkadia.sonata.reading

import com.arkadia.sonata.ResumePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-document reading position, tested as a pure rule.
 *
 * S3 asks for a persistent reading position across a library of documents. The
 * defect this file exists to prevent is concrete: with one global offset, reading
 * document B discarded the user's place in document A, and returning to A offered
 * nothing. Every test below that concerns two documents is a test of that defect.
 */
class ReadingPositionsTest {

    /** A passage long enough that a mid-document offset is resumable. */
    private val passageA = "A".repeat(500) + "middle of A" + "A".repeat(500)
    private val passageB = "B".repeat(400) + "middle of B" + "B".repeat(400)

    // ── Recording and reading back ───────────────────────────────────────────

    @Test
    fun `a recorded position is offered back for the same document and text`() {
        val positions = ReadingPositions.remember(
            existing = emptyList(),
            documentId = 7,
            passage = passageA,
            offsetChars = 200,
            now = 1_000,
        )
        assertEquals(200, ReadingPositions.offsetFor(positions, documentId = 7, passage = passageA))
    }

    @Test
    fun `a second document does not overwrite the first`() {
        val afterA = ReadingPositions.remember(emptyList(), documentId = 7, passage = passageA, offsetChars = 200, now = 1_000)
        val afterB = ReadingPositions.remember(afterA, documentId = 8, passage = passageB, offsetChars = 100, now = 2_000)

        assertEquals(2, afterB.size)
        assertEquals(200, ReadingPositions.offsetFor(afterB, documentId = 7, passage = passageA))
        assertEquals(100, ReadingPositions.offsetFor(afterB, documentId = 8, passage = passageB))
    }

    @Test
    fun `a document with no recorded position starts at the beginning`() {
        val positions = ReadingPositions.remember(emptyList(), documentId = 7, passage = passageA, offsetChars = 200, now = 1_000)
        assertEquals(0, ReadingPositions.offsetFor(positions, documentId = 99, passage = passageB))
    }

    @Test
    fun `recording the same document twice keeps one position, the later one`() {
        val first = ReadingPositions.remember(emptyList(), documentId = 7, passage = passageA, offsetChars = 100, now = 1_000)
        val second = ReadingPositions.remember(first, documentId = 7, passage = passageA, offsetChars = 300, now = 2_000)

        assertEquals(1, second.size)
        assertEquals(300, ReadingPositions.offsetFor(second, documentId = 7, passage = passageA))
        assertEquals(2_000, second.single().recordedAt)
    }

    @Test
    fun `a negative offset is clamped to zero rather than stored`() {
        val positions = ReadingPositions.remember(emptyList(), documentId = 7, passage = passageA, offsetChars = -50, now = 1_000)
        assertEquals(0, positions.single().offsetChars)
    }

    // ── The offset must describe the text it is applied to ───────────────────

    @Test
    fun `an edited document does not carry a stale offset into the new text`() {
        val positions = ReadingPositions.remember(emptyList(), documentId = 7, passage = passageA, offsetChars = 200, now = 1_000)
        val edited = passageA + " and a sentence appended"

        assertEquals(
            0,
            ReadingPositions.offsetFor(positions, documentId = 7, passage = edited),
        )
    }

    @Test
    fun `a passage of the same length but different text is still rejected`() {
        // Same length, different content: the length check alone would pass this
        // through, so the fingerprint is what stops it.
        val impostor = passageA.replaceRange(0, 1, "Z")
        val positions = ReadingPositions.remember(emptyList(), documentId = 7, passage = passageA, offsetChars = 200, now = 1_000)

        assertEquals(impostor.length, passageA.length)
        assertEquals(0, ReadingPositions.offsetFor(positions, documentId = 7, passage = impostor))
    }

    @Test
    fun `a blank passage has no position`() {
        val positions = ReadingPositions.remember(emptyList(), documentId = 7, passage = passageA, offsetChars = 200, now = 1_000)
        assertEquals(0, ReadingPositions.offsetFor(positions, documentId = 7, passage = ""))
    }

    // ── The rules ResumePoint already owns are not restated ──────────────────

    @Test
    fun `a position within the last few characters is not offered as carry on`() {
        val positions = ReadingPositions.remember(
            emptyList(),
            documentId = 7,
            passage = passageA,
            offsetChars = passageA.length - ResumePoint.MIN_REMAINING + 1,
            now = 1_000,
        )
        assertEquals(0, ReadingPositions.offsetFor(positions, documentId = 7, passage = passageA))
    }

    @Test
    fun `a position past the end of the passage is not offered`() {
        val positions = ReadingPositions.remember(
            emptyList(),
            documentId = 7,
            passage = passageA,
            offsetChars = passageA.length + 10,
            now = 1_000,
        )
        assertEquals(0, ReadingPositions.offsetFor(positions, documentId = 7, passage = passageA))
    }

    @Test
    fun `a position at the very start is not offered as carry on`() {
        val positions = ReadingPositions.remember(emptyList(), documentId = 7, passage = passageA, offsetChars = 0, now = 1_000)
        assertEquals(0, ReadingPositions.offsetFor(positions, documentId = 7, passage = passageA))
    }

    // ── The bound and lifecycle ─────────────────────────────────────────────

    @Test
    fun `the store keeps at most its limit and evicts the oldest`() {
        var positions = emptyList<ReadingPosition>()
        for (i in 1..(ReadingPositions.MAX_ENTRIES + 3)) {
            positions = ReadingPositions.remember(
                existing = positions,
                documentId = i.toLong(),
                passage = passageA,
                offsetChars = 100,
                now = i.toLong() * 1_000,
            )
        }

        assertEquals(ReadingPositions.MAX_ENTRIES, positions.size)
        // The three oldest documents are gone; the newest is present.
        assertEquals(0, ReadingPositions.offsetFor(positions, documentId = 1, passage = passageA))
        assertEquals(100, ReadingPositions.offsetFor(positions, documentId = (ReadingPositions.MAX_ENTRIES + 3).toLong(), passage = passageA))
    }

    @Test
    fun `a zero limit keeps nothing rather than everything`() {
        val positions = ReadingPositions.remember(
            emptyList(), documentId = 7, passage = passageA, offsetChars = 100, now = 1_000, limit = 0,
        )
        assertTrue(positions.isEmpty())
    }

    @Test
    fun `forgetting a document removes only that document`() {
        val afterA = ReadingPositions.remember(emptyList(), documentId = 7, passage = passageA, offsetChars = 200, now = 1_000)
        val afterB = ReadingPositions.remember(afterA, documentId = 8, passage = passageB, offsetChars = 100, now = 2_000)

        val forgotten = ReadingPositions.forget(afterB, documentId = 7)

        assertEquals(1, forgotten.size)
        assertEquals(0, ReadingPositions.offsetFor(forgotten, documentId = 7, passage = passageA))
        assertEquals(100, ReadingPositions.offsetFor(forgotten, documentId = 8, passage = passageB))
    }

    // ── The stored format ────────────────────────────────────────────────────

    @Test
    fun `encode then decode reproduces the positions`() {
        val positions = ReadingPositions.remember(
            ReadingPositions.remember(emptyList(), documentId = 7, passage = passageA, offsetChars = 200, now = 1_000),
            documentId = 8,
            passage = passageB,
            offsetChars = 100,
            now = 2_000,
        )

        val roundTripped = ReadingPositions.decode(ReadingPositions.encode(positions))

        assertEquals(positions, roundTripped)
    }

    @Test
    fun `an unset preference decodes to no positions`() {
        assertTrue(ReadingPositions.decode("").isEmpty())
        assertTrue(ReadingPositions.decode("   ").isEmpty())
    }

    @Test
    fun `an unknown format version decodes to no positions rather than guessing`() {
        assertTrue(ReadingPositions.decode("v2;7:200:1010:12345:1000").isEmpty())
        assertTrue(ReadingPositions.decode("7:200:1010:12345:1000").isEmpty())
    }

    @Test
    fun `a malformed entry is dropped without losing the readable ones`() {
        val encoded = "v1;7:200:1010:12345:1000;not-an-entry;8:100:810:999:2000"

        val decoded = ReadingPositions.decode(encoded)

        assertEquals(2, decoded.size)
        assertEquals(listOf(7L, 8L), decoded.map { it.documentId })
    }

    @Test
    fun `an entry with unparseable fields is dropped`() {
        assertTrue(ReadingPositions.decode("v1;7:x:1010:12345:1000").isEmpty())
        assertTrue(ReadingPositions.decode("v1;7:200:1010:12345").isEmpty())
    }

    @Test
    fun `a negative offset in a stored value is not trusted`() {
        assertTrue(ReadingPositions.decode("v1;7:-1:1010:12345:1000").isEmpty())
    }

    @Test
    fun `an empty list encodes to the empty string`() {
        assertEquals("", ReadingPositions.encode(emptyList()))
    }

    @Test
    fun `the encoded form is stable for the same input`() {
        val positions = ReadingPositions.remember(emptyList(), documentId = 7, passage = passageA, offsetChars = 200, now = 1_000)
        assertEquals(ReadingPositions.encode(positions), ReadingPositions.encode(positions))
    }

    // ── The fingerprint ──────────────────────────────────────────────────────

    @Test
    fun `the fingerprint is stable and distinguishes different text`() {
        assertEquals(ReadingPositions.fingerprint(passageA), ReadingPositions.fingerprint(passageA))
        assertNotEquals(ReadingPositions.fingerprint(passageA), ReadingPositions.fingerprint(passageB))
    }

    @Test
    fun `an astral plane character is counted once`() {
        // A surrogate pair must not hash as its two halves; a paired and an
        // unpaired form of the same code point are different text.
        val withAstral = "a\uD83D\uDE00b"
        assertEquals(4, withAstral.length)
        assertNotEquals(
            ReadingPositions.fingerprint(withAstral),
            ReadingPositions.fingerprint("a\uD83Db"),
        )
    }
}
