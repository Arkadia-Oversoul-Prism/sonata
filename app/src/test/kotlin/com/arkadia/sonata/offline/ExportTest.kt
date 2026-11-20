package com.arkadia.sonata.offline

import com.arkadia.sonata.data.DocumentSource
import com.arkadia.sonata.data.NarrationStatus
import com.arkadia.sonata.export.AudioFormat
import com.arkadia.sonata.export.CompositeEncoder
import com.arkadia.sonata.export.DirectoryFileStore
import com.arkadia.sonata.export.ExportNaming
import com.arkadia.sonata.export.ExportResult
import com.arkadia.sonata.export.FileAudioExporter
import com.arkadia.sonata.export.FileTextExporter
import com.arkadia.sonata.export.LibraryFileCoordinator
import com.arkadia.sonata.export.WavEncoder
import com.arkadia.sonata.export.WavWriter
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 2 — export, rename and delete. */
class ExportTest {

    private fun tempDir(): File = Files.createTempDirectory("sonata-export").toFile()

    // ── Naming ───────────────────────────────────────────────────────────────

    @Test
    fun `illegal characters are replaced not dropped`() {
        // Replaced, not dropped, so two different titles cannot collapse to one name.
        assertEquals("a_b", ExportNaming.baseName("a/b"))
        assertEquals("a_b", ExportNaming.baseName("a\\b"))
        assertEquals("a_b", ExportNaming.baseName("a:b"))
    }

    @Test
    fun `a NUL is replaced rather than silently removed`() {
        // The bug the previous implementation had: a NUL was dropped, so "a\u0000b"
        // became "ab" and collided with a genuine "ab".
        val name = ExportNaming.baseName("a\u0000b")
        assertEquals("a_b", name)
    }

    @Test
    fun `a reserved device name is escaped`() {
        // The escape is appended to the name as written, so the case is preserved.
        assertEquals("con_", ExportNaming.baseName("con"))
        assertEquals("NUL_", ExportNaming.baseName("NUL"))
    }

    @Test
    fun `a trailing dot is removed`() {
        assertEquals("hello", ExportNaming.baseName("hello."))
    }

    @Test
    fun `an empty title falls back`() {
        assertEquals(ExportNaming.FALLBACK, ExportNaming.baseName("   "))
        assertEquals(ExportNaming.FALLBACK, ExportNaming.baseName("///"))
    }

    @Test
    fun `a long title is bounded`() {
        val long = "x".repeat(200)
        assertTrue(ExportNaming.baseName(long).length <= ExportNaming.MAX_BASE_LENGTH)
    }

    @Test
    fun `a duplicate name is made unique`() {
        val taken = setOf("note.wav")
        assertEquals("note (2).wav", ExportNaming.uniqueName("note", AudioFormat.WAV, taken))
        assertEquals(
            "note (3).wav",
            ExportNaming.uniqueName("note", AudioFormat.WAV, taken + "note (2).wav"),
        )
    }

    // ── WAV ──────────────────────────────────────────────────────────────────

    @Test
    fun `the WAV header is the canonical 44 bytes`() {
        val pcm = ByteArray(1000)
        val wav = WavWriter.wrap(pcm, 24_000)
        assertEquals(WavWriter.HEADER_BYTES + 1000, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals("data", String(wav, 36, 4))
    }

    @Test
    fun `the WAV payload round-trips`() {
        val pcm = ByteArray(500) { (it % 128).toByte() }
        val wav = WavWriter.wrap(pcm, 24_000)
        assertTrue(pcm.contentEquals(WavWriter.payload(wav)))
    }

    @Test
    fun `duration is derived from the byte count`() {
        // 24 000 frames of 16-bit mono at 24 kHz is exactly one second.
        assertEquals(1000L, WavWriter.durationMs(48_000, 24_000))
        assertEquals(500L, WavWriter.durationMs(24_000, 24_000))
    }

    @Test
    fun `a header-only file has an empty payload`() {
        val wav = WavWriter.wrap(ByteArray(0), 24_000)
        assertEquals(0, WavWriter.payload(wav).size)
    }

    // ── FileStore ────────────────────────────────────────────────────────────

    @Test
    fun `a write promotes the partial file`() {
        val store = DirectoryFileStore(tempDir())
        store.write(1L, ByteArray(100))
        assertTrue(store.exists(1L))
        assertFalse(store.partialPath(1L).exists())
    }

    @Test
    fun `a partial file is never left behind`() {
        val store = DirectoryFileStore(tempDir())
        store.write(7L, ByteArray(10))
        assertFalse(store.partialPath(7L).exists())
    }

    @Test
    fun `delete is idempotent`() {
        val store = DirectoryFileStore(tempDir())
        store.write(3L, ByteArray(10))
        assertTrue(store.delete(3L))
        // A second delete is success, not an error, so a retry converges.
        assertFalse(store.delete(3L))
    }

    @Test
    fun `allIds lists what is on disk`() {
        val store = DirectoryFileStore(tempDir())
        store.write(1L, ByteArray(10))
        store.write(5L, ByteArray(10))
        assertEquals(setOf(1L, 5L), store.allIds().toSet())
    }

    // ── Exporter ─────────────────────────────────────────────────────────────

    @Test
    fun `mp3 is not offered when no encoder is present`() {
        val store = DirectoryFileStore(tempDir())
        val exporter = FileAudioExporter(store, CompositeEncoder(listOf(WavEncoder())), tempDir())
        assertFalse(AudioFormat.MP3 in exporter.supportedFormats())
        assertTrue(AudioFormat.WAV in exporter.supportedFormats())
    }

    @Test
    fun `exporting a narration writes a readable file`() = runBlocking {
        val store = DirectoryFileStore(tempDir())
        val out = tempDir()
        val exporter = FileAudioExporter(store, CompositeEncoder(listOf(WavEncoder())), out)
        store.write(1L, WavWriter.wrap(ByteArray(4800), 24_000))
        val narration = narration(id = 1L, title = "My note", audioPath = store.canonicalPath(1L).absolutePath)

        val result = exporter.export(narration, AudioFormat.WAV)
        assertTrue(result is ExportResult.Success)
        val file = File((result as ExportResult.Success).destination)
        assertTrue(file.isFile)
        assertEquals("My note.wav", file.name)
        // The exported file is a real WAV, not a copy of the header alone.
        assertTrue(WavWriter.payload(file.readBytes()).isNotEmpty())
    }

    @Test
    fun `export is a copy and never a move`() = runBlocking {
        val store = DirectoryFileStore(tempDir())
        val exporter = FileAudioExporter(store, CompositeEncoder(listOf(WavEncoder())), tempDir())
        store.write(1L, WavWriter.wrap(ByteArray(4800), 24_000))
        val narration = narration(id = 1L, title = "Keep me", audioPath = store.canonicalPath(1L).absolutePath)

        exporter.export(narration, AudioFormat.WAV)
        // The canonical file must survive the export.
        assertTrue(store.exists(1L))
    }

    @Test
    fun `exporting a narration with no audio fails with a reason`() = runBlocking {
        val store = DirectoryFileStore(tempDir())
        val exporter = FileAudioExporter(store, CompositeEncoder(listOf(WavEncoder())), tempDir())
        val narration = narration(id = 99L, title = "Missing", audioPath = null)
        val result = exporter.export(narration, AudioFormat.WAV)
        assertTrue(result is ExportResult.Failure)
    }

    @Test
    fun `text export writes both representations`() = runBlocking {
        val out = tempDir()
        val exporter = FileTextExporter(out)
        val document = com.arkadia.sonata.data.Document(
            id = 1L,
            title = "Letter",
            originalText = "Dear Sir, 😀 hello.",
            cleanedText = "Dear Sir, hello.",
            sourceType = DocumentSource.TYPED,
            importedAt = 0,
            updatedAt = 0,
        )
        val original = exporter.exportOriginal(document)
        val cleaned = exporter.exportCleaned(document)
        assertTrue(original is ExportResult.Success)
        assertTrue(cleaned is ExportResult.Success)
        // The original keeps the emoji; the cleaned one does not.
        assertTrue(File((original as ExportResult.Success).destination).readText().contains("😀"))
        assertFalse(File((cleaned as ExportResult.Success).destination).readText().contains("😀"))
    }

    // ── Deletion independence, at the file level ─────────────────────────────

    @Test
    fun `deleting a document leaves every narration file on disk`() = runBlocking {
        val store = DirectoryFileStore(tempDir())
        val repo = FakeSonataStore()
        val coordinator = LibraryFileCoordinator(repo, store)
        val docId = repo.createDocument("Some text")
        val narrationId = repo.beginNarration(docId, "Narration")
        store.write(narrationId, ByteArray(100))

        coordinator.deleteDocument(docId)

        assertTrue("the narration file must survive", store.exists(narrationId))
        assertNotNull("the narration record must survive", repo.narration(narrationId))
    }

    @Test
    fun `deleting a narration removes both record and file`() = runBlocking {
        val store = DirectoryFileStore(tempDir())
        val repo = FakeSonataStore()
        val coordinator = LibraryFileCoordinator(repo, store)
        val narrationId = repo.beginNarration(null, "Narration")
        store.write(narrationId, ByteArray(100))

        coordinator.deleteNarration(narrationId)

        assertFalse(store.exists(narrationId))
        assertEquals(null, repo.narration(narrationId))
    }

    @Test
    fun `deleting a collection touches neither records nor files`() = runBlocking {
        val store = DirectoryFileStore(tempDir())
        val repo = FakeSonataStore()
        val coordinator = LibraryFileCoordinator(repo, store)
        val narrationId = repo.beginNarration(null, "Narration")
        store.write(narrationId, ByteArray(100))
        val collectionId = repo.createCollection("Favourites")
        repo.addToCollection(collectionId, narrationId)

        coordinator.deleteCollection(collectionId)

        assertTrue(store.exists(narrationId))
        assertNotNull(repo.narration(narrationId))
    }

    @Test
    fun `deleting a voice profile removes its samples but keeps the audio`() = runBlocking {
        val store = DirectoryFileStore(tempDir())
        val repo = FakeSonataStore()
        val coordinator = LibraryFileCoordinator(repo, store)
        val sample = File(tempDir(), "sample.wav").apply { writeBytes(ByteArray(10)) }
        val profileId = repo.createEnrolledProfile("My voice", listOf(sample.absolutePath))
        val narrationId = repo.beginNarration(null, "Narration", voiceProfileId = profileId)
        store.write(narrationId, ByteArray(100))

        coordinator.deleteVoiceProfile(profileId, listOf(sample.absolutePath))

        assertFalse("the sample must be gone", sample.exists())
        assertTrue("the narration audio must survive", store.exists(narrationId))
        assertEquals(null, repo.narration(narrationId)?.voiceProfileId)
    }

    private fun narration(id: Long, title: String, audioPath: String?) =
        com.arkadia.sonata.data.Narration(
            id = id,
            documentId = null,
            title = title,
            audioPath = audioPath,
            voiceProfileId = null,
            modelVersion = "test@1.0",
            durationMs = 1000,
            createdAt = 0,
            playbackPositionMs = 0,
            status = NarrationStatus.COMPLETE,
            errorReason = null,
            favorite = false,
        )
}
