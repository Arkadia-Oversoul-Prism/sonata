package com.arkadia.sonata

import com.arkadia.sonata.data.Narration
import com.arkadia.sonata.data.NarrationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library's offer rules.
 *
 * The property under test is not "the list renders" — it is **the app never offers
 * to play audio it does not have**. A row that is complete in the database but
 * whose file is gone must read as missing, not as ready.
 */
class LibraryPresenterTest {

    private fun narration(
        id: Long,
        status: NarrationStatus = NarrationStatus.COMPLETE,
        audioPath: String? = "/audio/$id.wav",
        durationMs: Long = 0,
        createdAt: Long = id * 1_000L,
        title: String = "Narration $id",
    ) = Narration(
        id = id,
        documentId = null,
        title = title,
        audioPath = audioPath,
        durationMs = durationMs,
        createdAt = createdAt,
        status = status,
    )

    @Test
    fun `a complete narration with its file on disk is offered for replay`() {
        val rows = LibraryPresenter.rows(listOf(narration(1))) { true }
        assertEquals(1, rows.size)
        assertEquals(LibraryOffer.REPLAY, rows[0].offer)
        assertEquals("Ready", rows[0].subtitle)
    }

    @Test
    fun `a complete narration with no audio path is not offered`() {
        val rows = LibraryPresenter.rows(listOf(narration(1, audioPath = null))) { true }
        assertEquals(
            "a COMPLETE row that names no file must not be presented as playable",
            LibraryOffer.NONE,
            rows[0].offer,
        )
        assertEquals("Audio file missing", rows[0].subtitle)
    }

    @Test
    fun `a complete narration whose file is gone is not offered`() {
        // The row says COMPLETE and names a path; the disk disagrees. This is the
        // negative control for the file check: without it the row would be
        // offered for replay and the tap would fail.
        val rows = LibraryPresenter.rows(listOf(narration(1))) { false }
        assertEquals(LibraryOffer.NONE, rows[0].offer)
        assertEquals("Audio file missing", rows[0].subtitle)
    }

    @Test
    fun `an interrupted narration is not offered for replay`() {
        val rows = LibraryPresenter.rows(
            listOf(narration(1, status = NarrationStatus.INTERRUPTED, audioPath = null)),
        ) { true }
        assertEquals(LibraryOffer.NONE, rows[0].offer)
        assertEquals("Interrupted", rows[0].subtitle)
    }

    @Test
    fun `a generating narration is not offered for replay`() {
        val rows = LibraryPresenter.rows(
            listOf(narration(1, status = NarrationStatus.GENERATING, audioPath = null)),
        ) { true }
        assertEquals(LibraryOffer.NONE, rows[0].offer)
        assertEquals("Generating\u2026", rows[0].subtitle)
    }

    @Test
    fun `rows are newest first`() {
        val rows = LibraryPresenter.rows(
            listOf(
                narration(1, createdAt = 1_000),
                narration(3, createdAt = 3_000),
                narration(2, createdAt = 2_000),
            ),
        ) { true }
        assertEquals(listOf(3L, 2L, 1L), rows.map { it.id })
    }

    @Test
    fun `a playable row carries its duration, an unplayable one does not`() {
        val playable = LibraryPresenter.rows(listOf(narration(1, durationMs = 151_000))) { true }
        assertEquals("Ready \u00b7 2:31", playable[0].subtitle)

        val missing = LibraryPresenter.rows(listOf(narration(1, durationMs = 151_000))) { false }
        assertEquals(
            "a duration beside a missing file would read as an offer",
            "Audio file missing",
            missing[0].subtitle,
        )
    }

    @Test
    fun `the summary counts what can be played, not what is stored`() {
        val narrations = listOf(
            narration(1),
            narration(2),
            narration(3, audioPath = null),
        )
        // 1 has its file, 2's file is gone, 3 never had a path.
        val rows = LibraryPresenter.rows(narrations) { it == "/audio/1.wav" }
        assertEquals("1 of 3 ready to replay", LibraryPresenter.summary(rows))
    }

    @Test
    fun `an empty library says so`() {
        val rows = LibraryPresenter.rows(emptyList()) { true }
        assertTrue(rows.isEmpty())
        assertEquals(LibraryPresenter.NO_NARRATIONS, LibraryPresenter.summary(rows))
    }

    @Test
    fun `durations format as minutes, and as hours past an hour`() {
        assertEquals("", LibraryPresenter.formatDuration(0))
        assertEquals("0:07", LibraryPresenter.formatDuration(7_400))
        assertEquals("2:31", LibraryPresenter.formatDuration(151_000))
        assertEquals("1:01:01", LibraryPresenter.formatDuration(3_661_000))
    }
}
