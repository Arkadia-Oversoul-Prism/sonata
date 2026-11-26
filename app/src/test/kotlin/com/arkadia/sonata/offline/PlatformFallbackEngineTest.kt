package com.arkadia.sonata.offline

import com.arkadia.sonata.data.NarrationStatus
import com.arkadia.sonata.engine.DeviceProfile
import com.arkadia.sonata.engine.InMemoryModelRegistry
import com.arkadia.sonata.engine.NarrationOutcome
import com.arkadia.sonata.engine.OfflineEngine
import com.arkadia.sonata.engine.PlatformModel
import com.arkadia.sonata.engine.PlatformSpeechModel
import com.arkadia.sonata.engine.PlatformSynthesis
import com.arkadia.sonata.engine.VoiceReference
import com.arkadia.sonata.export.DirectoryFileStore
import com.arkadia.sonata.export.WavReader
import com.arkadia.sonata.export.WavWriter
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine with the platform fallback wired in.
 *
 * This is the test that answers the question the whole sprint exists for: **can a
 * device with no neural model produce a saved narration?** Before the fallback,
 * `resolveModel` returned null and every narration failed with "no speech model is
 * installed". Now it returns the platform model, and the audio lands on disk.
 *
 * The synthesis double is named as a double. What is proven here is the engine's
 * wiring and its ordering rules with a fallback present — not that a device's
 * engine produces audio, which needs a device.
 */
class PlatformFallbackEngineTest {

    private fun tempDir(): File = Files.createTempDirectory("sonata-fallback").toFile()

    private fun device() = DeviceProfile(
        ramBytes = 4L * 1024 * 1024 * 1024,
        freeStorageBytes = 2L * 1024 * 1024 * 1024,
        thermalValidated = false,
        label = "test",
    )

    /** A synthesis double that returns a real WAV. */
    private class StubSynthesis(private val wav: ByteArray?) : PlatformSynthesis {
        var calls = 0
            private set

        override suspend fun synthesizeToWav(
            text: String,
            voiceId: String?,
            speed: Float,
            pitch: Float,
        ): ByteArray? {
            calls++
            return wav
        }

        override fun isAvailable(): Boolean = true
    }

    private fun wav(frames: Int = 4800, rate: Int = 24_000): ByteArray {
        val pcm = ByteArray(frames * 2) { (it % 200).toByte() }
        return WavWriter.wrap(pcm, rate)
    }

    private fun engine(
        repo: FakeSonataStore,
        store: DirectoryFileStore,
        synthesis: PlatformSynthesis,
    ) = OfflineEngine(
        repository = repo,
        fileStore = store,
        // An empty registry: no neural model is installed. This is the device the
        // fallback exists for.
        registry = InMemoryModelRegistry(emptyList()),
        device = device(),
        fallback = PlatformSpeechModel(PlatformModel.DESCRIPTOR, synthesis),
    )

    @Test
    fun `a device with no neural model still produces a saved narration`() = runBlocking {
        val repo = FakeSonataStore()
        val store = DirectoryFileStore(tempDir())
        val synthesis = StubSynthesis(wav(frames = 4800, rate = 24_000))

        val outcome = engine(repo, store, synthesis).narrate("Hello there.", "Greeting")

        assertTrue("expected success, got $outcome", outcome is NarrationOutcome.Success)
        val success = outcome as NarrationOutcome.Success
        assertEquals(1, synthesis.calls)

        // The audio is on disk and is a real WAV with the right payload.
        val file = File(success.audioPath)
        assertTrue("the audio file must exist", file.exists())
        val parsed = WavReader.parse(file.readBytes())
        assertNotNull("the saved file must be a readable WAV", parsed)
        assertEquals(4800 * 2, parsed!!.pcm.size)
        assertEquals(24_000, parsed.sampleRate)

        // The duration is derived from the audio, not invented.
        assertEquals(200L, success.durationMs)

        // The row is COMPLETE and carries the platform model's version.
        val row = repo.narrations().first { it.id == success.narrationId }
        assertEquals(NarrationStatus.COMPLETE, row.status)
        assertEquals(PlatformModel.DESCRIPTOR.recordedVersion, row.modelVersion)
    }

    @Test
    fun `the fallback is used only when no neural model is installed`() = runBlocking {
        // With a neural model present, the fallback must not be consulted: the
        // neural tier is preferred, and a fallback that shadowed it would silently
        // downgrade every narration.
        val repo = FakeSonataStore()
        val store = DirectoryFileStore(tempDir())
        val synthesis = StubSynthesis(wav())
        val neural = testModel(id = "kokoro-82m")

        val engine = OfflineEngine(
            repository = repo,
            fileStore = store,
            registry = InMemoryModelRegistry(listOf(neural)),
            device = device(),
            modelProvider = FakeModelProvider(FakeSpeechModel(neural)),
            fallback = PlatformSpeechModel(PlatformModel.DESCRIPTOR, synthesis),
        )

        val outcome = engine.narrate("Hello.", "Greeting")
        assertTrue(outcome is NarrationOutcome.Success)
        assertEquals("the fallback must not be called", 0, synthesis.calls)
        val row = repo.narrations().first { it.id == (outcome as NarrationOutcome.Success).narrationId }
        assertEquals(neural.recordedVersion, row.modelVersion)
    }

    @Test
    fun `a cloned voice is refused when only the fallback exists`() = runBlocking {
        // The platform engine cannot produce a cloned voice. Refusing is correct;
        // silently speaking in the stock voice would break the promise.
        val repo = FakeSonataStore()
        val store = DirectoryFileStore(tempDir())
        val synthesis = StubSynthesis(wav())

        val outcome = engine(repo, store, synthesis).narrate(
            text = "Hello.",
            title = "Greeting",
            voice = VoiceReference.Cloned(1, "default", "/adapter"),
        )

        assertTrue(outcome is NarrationOutcome.Failure)
        assertEquals("the engine must not be called", 0, synthesis.calls)
        // No row is created for a request that could never be satisfied.
        assertTrue(repo.narrations().isEmpty())
    }

    @Test
    fun `a fallback failure leaves the row FAILED, never COMPLETE`() = runBlocking {
        val repo = FakeSonataStore()
        val store = DirectoryFileStore(tempDir())
        val synthesis = StubSynthesis(null) // engine produced nothing

        val outcome = engine(repo, store, synthesis).narrate("Hello.", "Greeting")

        assertTrue(outcome is NarrationOutcome.Failure)
        val row = repo.narrations().single()
        assertEquals(NarrationStatus.FAILED, row.status)
        assertTrue("a failed narration must carry a reason", !row.failureReason.isNullOrBlank())
    }

    @Test
    fun `no fallback and no model still fails honestly`() = runBlocking {
        // The pre-sprint state, pinned so a regression to it is visible.
        val repo = FakeSonataStore()
        val store = DirectoryFileStore(tempDir())
        val engine = OfflineEngine(
            repository = repo,
            fileStore = store,
            registry = InMemoryModelRegistry(emptyList()),
            device = device(),
            fallback = null,
        )

        val outcome = engine.narrate("Hello.", "Greeting")
        assertTrue(outcome is NarrationOutcome.Failure)
        assertTrue(
            (outcome as NarrationOutcome.Failure).reason.contains("No speech engine"),
        )
    }
}
