package com.arkadia.sonata.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The persistence invariants, against a real in-memory SQLite database.
 *
 * These are the tests that matter for Phase 1. Each one pins a rule the
 * specification calls non-negotiable, and each one is written so that removing the
 * rule from the code makes the test fail — a test that would pass either way would
 * be worse than no test, because it would look like a guarantee.
 *
 * Room is used rather than a fake on purpose: the deletion-independence invariant
 * is enforced by SQLite's `ON DELETE SET NULL`, so a fake would test the fake
 * instead of the guarantee.
 */
@RunWith(RobolectricTestRunner::class)
class SonataRepositoryTest {

    private lateinit var database: SonataDatabase
    private lateinit var repository: SonataRepository

    /** A clock the test controls, so ordering is deterministic. */
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SonataDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SonataRepository(database) { now }
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ── Invariant 1: deletion independence ───────────────────────────────────

    @Test
    fun `deleting a document keeps the narrations made from it`() = runTest {
        val documentId = repository.createDocument("A chapter of text")
        val narrationId = repository.beginNarration(documentId, "A chapter of text")
        repository.markGenerating(narrationId)
        repository.completeNarration(narrationId, "audio/1.wav", 12_000)

        val kept = repository.deleteDocument(documentId)

        assertEquals("the narration must survive its document", 1, kept)
        val narration = repository.narration(narrationId)
        assertNotNull("the narration must still exist", narration)
        assertNull("its source reference must be cleared", narration!!.documentId)
        assertTrue("and it must still be playable", LibraryRules.isPlayable(narration))
        assertEquals("audio/1.wav", narration.audioPath)
    }

    @Test
    fun `deleting a document does not touch narrations from other documents`() = runTest {
        val first = repository.createDocument("First")
        val second = repository.createDocument("Second")
        val fromFirst = repository.beginNarration(first, "First")
        val fromSecond = repository.beginNarration(second, "Second")

        repository.deleteDocument(first)

        assertNull(repository.narration(fromFirst)!!.documentId)
        assertEquals(
            "the other document's narration must be untouched",
            second,
            repository.narration(fromSecond)!!.documentId,
        )
    }

    @Test
    fun `deleting a narration does not delete its document`() = runTest {
        val documentId = repository.createDocument("Kept text")
        val narrationId = repository.beginNarration(documentId, "Kept text")

        repository.deleteNarration(narrationId)

        assertNotNull("the document must survive its narration", repository.document(documentId))
        assertNull(repository.narration(narrationId))
    }

    @Test
    fun `deleting a collection keeps the narrations in it`() = runTest {
        val narrationId = repository.beginNarration(null, "In a collection")
        val collectionId = repository.createCollection("Favourites")
        repository.addToCollection(collectionId, narrationId)

        repository.deleteCollection(collectionId)

        assertNotNull("the narration must survive its collection", repository.narration(narrationId))
    }

    @Test
    fun `deleting a voice profile keeps the narration that used it`() = runTest {
        val profileId = repository.createEnrolledProfile("My voice", listOf("s1.wav"))
        val narrationId = repository.beginNarration(null, "Spoken", voiceProfileId = profileId)
        repository.markGenerating(narrationId)
        repository.completeNarration(narrationId, "audio/2.wav", 5_000)

        repository.deleteVoiceProfile(profileId)

        val narration = repository.narration(narrationId)
        assertNotNull("the audio must survive the profile", narration)
        assertNull("the profile reference is cleared", narration!!.voiceProfileId)
        assertEquals("audio/2.wav", narration.audioPath)
    }

    // ── Invariant 2: every completed session is persisted ────────────────────

    @Test
    fun `a completed narration is persisted with its audio, voice, duration and date`() = runTest {
        val profileId = repository.createEnrolledProfile("Narrator", listOf("s.wav"))
        val documentId = repository.createDocument("Some text")
        val narrationId = repository.beginNarration(
            documentId = documentId,
            title = "Some text",
            voiceProfileId = profileId,
            modelVersion = "piper-en-lessac-high@1.0",
        )
        repository.markGenerating(narrationId)
        val completed = repository.completeNarration(narrationId, "audio/3.wav", 42_000)

        assertTrue(completed)
        val narration = repository.narration(narrationId)!!
        assertEquals(NarrationStatus.COMPLETE, narration.status)
        assertEquals("audio/3.wav", narration.audioPath)
        assertEquals(42_000L, narration.durationMs)
        assertEquals(profileId, narration.voiceProfileId)
        assertEquals("piper-en-lessac-high@1.0", narration.modelVersion)
        assertEquals(1_000L, narration.createdAt)
    }

    @Test
    fun `the playback position is saved and read back`() = runTest {
        val narrationId = repository.beginNarration(null, "Resumable")
        repository.markGenerating(narrationId)
        repository.completeNarration(narrationId, "audio/4.wav", 30_000)

        repository.savePosition(narrationId, 17_500)

        assertEquals(17_500L, repository.narration(narrationId)!!.playbackPositionMs)
    }

    @Test
    fun `a negative playback position is clamped rather than stored`() = runTest {
        val narrationId = repository.beginNarration(null, "Resumable")
        repository.savePosition(narrationId, -5)
        assertEquals(0L, repository.narration(narrationId)!!.playbackPositionMs)
    }

    // ── Invariant 3: interrupted generation is marked, never silently completed ──

    @Test
    fun `a narration left generating is marked interrupted on recovery`() = runTest {
        val narrationId = repository.beginNarration(null, "Interrupted")
        repository.markGenerating(narrationId)

        // Simulate a process death: the row is still GENERATING when the app restarts.
        val marked = repository.recoverInterrupted()

        assertEquals(1, marked)
        val narration = repository.narration(narrationId)!!
        assertEquals(NarrationStatus.INTERRUPTED, narration.status)
        assertFalse("an interrupted narration is not playable", LibraryRules.isPlayable(narration))
        assertTrue("but it is recoverable", LibraryRules.isRecoverable(narration))
    }

    @Test
    fun `recovery does not touch completed narrations`() = runTest {
        val narrationId = repository.beginNarration(null, "Finished")
        repository.markGenerating(narrationId)
        repository.completeNarration(narrationId, "audio/5.wav", 1_000)

        repository.recoverInterrupted()

        assertEquals(NarrationStatus.COMPLETE, repository.narration(narrationId)!!.status)
    }

    @Test
    fun `an interrupted narration cannot be completed without being retried`() = runTest {
        val narrationId = repository.beginNarration(null, "Interrupted")
        repository.markGenerating(narrationId)
        repository.recoverInterrupted()

        // The engine tries to complete it directly — this must be refused.
        val completed = repository.completeNarration(narrationId, "audio/6.wav", 1_000)

        assertFalse("an interrupted narration must not be silently completed", completed)
        assertEquals(NarrationStatus.INTERRUPTED, repository.narration(narrationId)!!.status)
    }

    @Test
    fun `an interrupted narration can be retried to completion`() = runTest {
        val narrationId = repository.beginNarration(null, "Retry me")
        repository.markGenerating(narrationId)
        repository.recoverInterrupted()

        repository.markGenerating(narrationId)
        val completed = repository.completeNarration(narrationId, "audio/7.wav", 2_000)

        assertTrue(completed)
        assertEquals(NarrationStatus.COMPLETE, repository.narration(narrationId)!!.status)
    }

    @Test
    fun `a failed narration records a reason`() = runTest {
        val narrationId = repository.beginNarration(null, "Fails")
        repository.markGenerating(narrationId)
        repository.failNarration(narrationId, "model not found")

        val narration = repository.narration(narrationId)!!
        assertEquals(NarrationStatus.FAILED, narration.status)
        assertEquals("model not found", narration.errorReason)
    }

    // ── Invariant 4: the original text is never overwritten ──────────────────

    @Test
    fun `cleaning the text leaves the original untouched`() = runTest {
        val original = "Hello 😀 **world**\n\n\n\nSee https://example.com"
        val documentId = repository.createDocument(original)

        repository.setCleanedText(documentId, "Hello world. See example dot com.")

        val document = repository.document(documentId)!!
        assertEquals("the original must be preserved verbatim", original, document.originalText)
        assertEquals("Hello world. See example dot com.", document.cleanedText)
    }

    @Test
    fun `a document starts with no cleaned text`() = runTest {
        val documentId = repository.createDocument("Raw text")
        assertNull(repository.document(documentId)!!.cleanedText)
    }

    // ── Invariant 5: model versions are recorded ─────────────────────────────

    @Test
    fun `an adapted profile records its model version`() = runTest {
        val profileId = repository.createEnrolledProfile("Cloned", listOf("s1.wav", "s2.wav"))
        repository.attachAdaptedModel(profileId, "models/cloned.onnx", "openvoice-v2@1.0")

        val profile = repository.voiceProfile(profileId)!!
        assertEquals(VoiceProfileKind.ADAPTED, profile.kind)
        assertEquals("models/cloned.onnx", profile.modelPath)
        assertEquals("openvoice-v2@1.0", profile.modelVersion)
    }

    @Test
    fun `an enrolled profile has no model`() = runTest {
        val profileId = repository.createEnrolledProfile("Enrolled only", listOf("s1.wav"))
        val profile = repository.voiceProfile(profileId)!!
        assertEquals(VoiceProfileKind.ENROLLED, profile.kind)
        assertNull(profile.modelPath)
        assertNull(profile.modelVersion)
    }

    // ── Titles ───────────────────────────────────────────────────────────────

    @Test
    fun `a document is titled from its first non-blank line`() = runTest {
        val documentId = repository.createDocument("\n\n   The Long Road Home   \nmore text")
        assertEquals("The Long Road Home", repository.document(documentId)!!.title)
    }

    @Test
    fun `renaming a document refuses to store an empty title`() = runTest {
        val documentId = repository.createDocument("Original")
        repository.renameDocument(documentId, "   ")
        assertEquals(DocumentTitle.UNTITLED, repository.document(documentId)!!.title)
    }

    @Test
    fun `renaming a narration is independent of its document title`() = runTest {
        val documentId = repository.createDocument("Document title")
        val narrationId = repository.beginNarration(documentId, "Document title")

        repository.renameNarration(narrationId, "My own name")

        assertEquals("My own name", repository.narration(narrationId)!!.title)
        assertEquals("Document title", repository.document(documentId)!!.title)
    }

    // ── Library view ─────────────────────────────────────────────────────────

    @Test
    fun `the library view reflects the filter`() = runTest {
        val documentId = repository.createDocument("Typed text")
        repository.createDocument("Imported text", source = DocumentSource.IMPORTED)
        val narrationId = repository.beginNarration(documentId, "Typed text")
        repository.markGenerating(narrationId)
        repository.completeNarration(narrationId, "audio/8.wav", 1_000)

        val narrations = repository.library(LibraryFilter.NARRATIONS).first()
        assertEquals(1, narrations.size)

        val imported = repository.library(LibraryFilter.IMPORTED).first()
        assertEquals(1, imported.size)
        assertEquals("Imported text", imported.single().title)
    }

    @Test
    fun `counts report playable and interrupted separately`() = runTest {
        val a = repository.beginNarration(null, "Done")
        repository.markGenerating(a)
        repository.completeNarration(a, "audio/9.wav", 1_000)

        val b = repository.beginNarration(null, "Stuck")
        repository.markGenerating(b)
        repository.recoverInterrupted()

        val counts = repository.counts().first()
        assertEquals(2, counts.narrations)
        assertEquals(1, counts.playable)
        assertEquals(1, counts.interrupted)
    }

    // ── Reading sessions ─────────────────────────────────────────────────────

    @Test
    fun `a reading session records where the user stopped`() = runTest {
        val narrationId = repository.beginNarration(null, "Session")
        val sessionId = repository.startSession(narrationId)
        now = 2_000L
        repository.endSession(sessionId, 9_000)

        val session = repository.recentSessions().first().single()
        assertEquals(narrationId, session.narrationId)
        assertEquals(2_000L, session.endedAt)
        assertEquals(9_000L, session.lastPositionMs)
    }
}
