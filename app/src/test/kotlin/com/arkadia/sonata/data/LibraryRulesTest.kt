package com.arkadia.sonata.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library's rules, tested as pure functions.
 *
 * These tests exist because the rules they cover are the specification's safety
 * properties, and a safety property that can only be exercised through a database
 * is one that will not be exercised. Everything here runs on the JVM with no
 * Android framework and no database.
 */
class LibraryRulesTest {

    private fun document(
        id: Long,
        title: String = "Doc $id",
        source: DocumentSource = DocumentSource.TYPED,
        updatedAt: Long = id,
    ) = Document(
        id = id,
        title = title,
        originalText = "text $id",
        cleanedText = null,
        sourceType = source,
        importedAt = updatedAt,
        updatedAt = updatedAt,
    )

    private fun narration(
        id: Long,
        title: String = "Narration $id",
        documentId: Long? = null,
        status: NarrationStatus = NarrationStatus.COMPLETE,
        audioPath: String? = "audio/$id.wav",
        favorite: Boolean = false,
        createdAt: Long = id,
    ) = Narration(
        id = id,
        documentId = documentId,
        title = title,
        audioPath = audioPath,
        createdAt = createdAt,
        status = status,
        favorite = favorite,
    )

    // ── Filters ──────────────────────────────────────────────────────────────

    @Test
    fun `all returns documents and narrations`() {
        val entries = LibraryRules.apply(
            LibraryFilter.ALL,
            documents = listOf(document(1), document(2)),
            narrations = listOf(narration(10)),
        )
        assertEquals(3, entries.size)
    }

    @Test
    fun `narrations filter excludes documents`() {
        val entries = LibraryRules.apply(
            LibraryFilter.NARRATIONS,
            documents = listOf(document(1)),
            narrations = listOf(narration(10), narration(11)),
        )
        assertEquals(2, entries.size)
        assertTrue(entries.all { it is LibraryEntry.OfNarration })
    }

    @Test
    fun `imported filter keeps only imported documents`() {
        val entries = LibraryRules.apply(
            LibraryFilter.IMPORTED,
            documents = listOf(
                document(1, source = DocumentSource.TYPED),
                document(2, source = DocumentSource.IMPORTED),
                document(3, source = DocumentSource.RECORDING),
            ),
            narrations = emptyList(),
        )
        assertEquals(1, entries.size)
        assertEquals(2L, entries.single().id)
    }

    @Test
    fun `recordings filter keeps only recording documents`() {
        val entries = LibraryRules.apply(
            LibraryFilter.RECORDINGS,
            documents = listOf(
                document(1, source = DocumentSource.TYPED),
                document(2, source = DocumentSource.RECORDING),
            ),
            narrations = emptyList(),
        )
        assertEquals(1, entries.size)
        assertEquals(2L, entries.single().id)
    }

    @Test
    fun `favorites filter keeps only favorited narrations`() {
        val entries = LibraryRules.apply(
            LibraryFilter.FAVORITES,
            documents = emptyList(),
            narrations = listOf(narration(1, favorite = true), narration(2, favorite = false)),
        )
        assertEquals(1, entries.size)
        assertEquals(1L, entries.single().id)
    }

    @Test
    fun `collection filter keeps only member narrations`() {
        val entries = LibraryRules.apply(
            LibraryFilter.COLLECTION,
            documents = emptyList(),
            narrations = listOf(narration(1), narration(2), narration(3)),
            collectionNarrationIds = setOf(1L, 3L),
        )
        assertEquals(setOf(1L, 3L), entries.map { it.id }.toSet())
    }

    @Test
    fun `recent is newest first and bounded`() {
        val entries = LibraryRules.apply(
            LibraryFilter.RECENT,
            documents = (1L..5L).map { document(it, updatedAt = it) },
            narrations = (10L..14L).map { narration(it, createdAt = it) },
            recentLimit = 3,
        )
        assertEquals(3, entries.size)
        assertEquals(listOf(14L, 13L, 12L), entries.map { it.id })
    }

    // ── Status transitions ───────────────────────────────────────────────────

    @Test
    fun `a pending narration may start generating`() {
        assertTrue(LibraryRules.canTransition(NarrationStatus.PENDING, NarrationStatus.GENERATING))
    }

    @Test
    fun `a generating narration may complete`() {
        assertTrue(LibraryRules.canTransition(NarrationStatus.GENERATING, NarrationStatus.COMPLETE))
    }

    @Test
    fun `an interrupted narration may NOT be relabelled complete`() {
        // The interrupted-generation contract: an incomplete narration must never be
        // silently presented as finished. It must be retried, which is visible.
        assertFalse(LibraryRules.canTransition(NarrationStatus.INTERRUPTED, NarrationStatus.COMPLETE))
    }

    @Test
    fun `a pending narration may NOT jump straight to complete`() {
        assertFalse(LibraryRules.canTransition(NarrationStatus.PENDING, NarrationStatus.COMPLETE))
    }

    @Test
    fun `a completed narration is terminal`() {
        NarrationStatus.values().forEach { to ->
            assertFalse(
                "COMPLETE -> $to must be refused",
                LibraryRules.canTransition(NarrationStatus.COMPLETE, to),
            )
        }
    }

    @Test
    fun `failed and interrupted narrations may be retried`() {
        assertTrue(LibraryRules.canTransition(NarrationStatus.FAILED, NarrationStatus.GENERATING))
        assertTrue(LibraryRules.canTransition(NarrationStatus.INTERRUPTED, NarrationStatus.GENERATING))
    }

    // ── Playability and recovery ─────────────────────────────────────────────

    @Test
    fun `only a complete narration with audio is playable`() {
        assertTrue(LibraryRules.isPlayable(narration(1)))
        assertFalse(LibraryRules.isPlayable(narration(2, status = NarrationStatus.GENERATING)))
        assertFalse(LibraryRules.isPlayable(narration(3, status = NarrationStatus.INTERRUPTED)))
        assertFalse(LibraryRules.isPlayable(narration(4, audioPath = null)))
        assertFalse(LibraryRules.isPlayable(narration(5, audioPath = "")))
    }

    @Test
    fun `an interrupted narration is recoverable`() {
        assertTrue(LibraryRules.isRecoverable(narration(1, status = NarrationStatus.INTERRUPTED)))
        assertFalse(LibraryRules.isRecoverable(narration(2, status = NarrationStatus.FAILED)))
        assertFalse(LibraryRules.isRecoverable(narration(3, status = NarrationStatus.COMPLETE)))
    }

    @Test
    fun `a narration that outlived its document still has a title`() {
        val orphan = narration(1, title = "My chapter", documentId = null)
        assertEquals("My chapter", LibraryRules.displayTitle(orphan))
    }

    @Test
    fun `a blank narration title falls back rather than showing nothing`() {
        assertEquals(
            LibraryRules.UNTITLED_NARRATION,
            LibraryRules.displayTitle(narration(1, title = "   ")),
        )
    }
}
