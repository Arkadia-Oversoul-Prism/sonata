package com.arkadia.sonata.offline

import com.arkadia.sonata.data.NarrationStatus
import com.arkadia.sonata.engine.BaseSpeechModel
import com.arkadia.sonata.engine.BundledModels
import com.arkadia.sonata.engine.DeviceProfile
import com.arkadia.sonata.engine.EnginePolicy
import com.arkadia.sonata.engine.InMemoryModelRegistry
import com.arkadia.sonata.engine.ModelCompatibility
import com.arkadia.sonata.engine.ModelRole
import com.arkadia.sonata.engine.NarrationOutcome
import com.arkadia.sonata.engine.OfflineEngine
import com.arkadia.sonata.engine.ThrowingNetworkProbe
import com.arkadia.sonata.engine.TtsTier
import com.arkadia.sonata.export.DirectoryFileStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3 — the offline neural TTS engine. */
class EngineTest {

    private fun tempDir(): File = Files.createTempDirectory("sonata-engine").toFile()

    private fun device(
        ram: Long = 4L * 1024 * 1024 * 1024,
        storage: Long = 2L * 1024 * 1024 * 1024,
        thermal: Boolean = true,
    ) = DeviceProfile(ram, storage, thermal, "test")

    private fun registry(vararg models: com.arkadia.sonata.engine.ModelDescriptor) =
        InMemoryModelRegistry(models.toList())

    // ── The offline boundary ─────────────────────────────────────────────────

    @Test
    fun `no tier requires the network`() {
        // The machine-checkable form of "no network dependency for core TTS".
        for (tier in TtsTier.entries) {
            assertFalse("$tier must not require the network", EnginePolicy.requiresNetwork(tier))
        }
    }

    @Test
    fun `the neural tier is preferred when a model is available`() {
        assertEquals(TtsTier.NEURAL_LOCAL, EnginePolicy.selectTier(neuralModelAvailable = true))
        assertEquals(TtsTier.PLATFORM_FALLBACK, EnginePolicy.selectTier(neuralModelAvailable = false))
    }

    @Test
    fun `a narration records the model version that made it`() = runBlocking {
        val repo = FakeSonataStore()
        val store = DirectoryFileStore(tempDir())
        val model = testModel(id = "kokoro-82m")
        val engine = OfflineEngine(
            repository = repo,
            fileStore = store,
            registry = registry(model),
            device = device(),
            modelProvider = FakeModelProvider(FakeSpeechModel(model)),
        )

        val outcome = engine.narrate("Hello there.", "Greeting")
        assertTrue(outcome is NarrationOutcome.Success)
        val narration = repo.narration((outcome as NarrationOutcome.Success).narrationId)
        assertEquals("kokoro-82m@1.0", narration?.modelVersion)
    }

    @Test
    fun `the engine makes no network call when offline`() = runBlocking {
        // The probe throws on any network attempt. The narration must still succeed,
        // which proves the core path never reaches for the network.
        val repo = FakeSonataStore()
        val store = DirectoryFileStore(tempDir())
        val model = testModel()
        val engine = OfflineEngine(
            repository = repo,
            fileStore = store,
            registry = registry(model),
            device = device(),
            networkProbe = ThrowingNetworkProbe(),
            modelProvider = FakeModelProvider(FakeSpeechModel(model)),
        )

        val outcome = engine.narrate("Offline text.", "Offline")
        assertTrue(outcome is NarrationOutcome.Success)
    }

    // ── Save-before-synthesise ordering ──────────────────────────────────────

    @Test
    fun `the row is written before synthesis and completed after`() = runBlocking {
        val repo = FakeSonataStore()
        val store = DirectoryFileStore(tempDir())
        val model = testModel()
        val engine = OfflineEngine(
            repository = repo,
            fileStore = store,
            registry = registry(model),
            device = device(),
            modelProvider = FakeModelProvider(FakeSpeechModel(model)),
        )

        val outcome = engine.narrate("Text.", "Title") as NarrationOutcome.Success
        val id = outcome.narrationId
        // PENDING, then GENERATING, then COMPLETE — in that order.
        assertEquals(
            listOf(
                NarrationStatus.PENDING,
                NarrationStatus.GENERATING,
                NarrationStatus.COMPLETE,
            ),
            repo.statusHistory.filter { it.first == id }.map { it.second },
        )
    }

    @Test
    fun `a completed narration is persisted with its audio, duration and date`() = runBlocking {
        val repo = FakeSonataStore()
        val store = DirectoryFileStore(tempDir())
        val model = testModel()
        val engine = OfflineEngine(
            repository = repo,
            fileStore = store,
            registry = registry(model),
            device = device(),
            modelProvider = FakeModelProvider(FakeSpeechModel(model)),
        )

        val outcome = engine.narrate("Text.", "Title") as NarrationOutcome.Success
        val narration = repo.narration(outcome.narrationId)!!
        assertEquals(NarrationStatus.COMPLETE, narration.status)
        assertNotNull(narration.audioPath)
        assertTrue(File(narration.audioPath!!).isFile)
        assertTrue(narration.durationMs > 0)
        assertTrue(narration.createdAt > 0)
    }

    @Test
    fun `a failed synthesis leaves the narration FAILED, not COMPLETE`() = runBlocking {
        val repo = FakeSonataStore()
        val store = DirectoryFileStore(tempDir())
        val model = testModel()
        val engine = OfflineEngine(
            repository = repo,
            fileStore = store,
            registry = registry(model),
            device = device(),
            modelProvider = FakeModelProvider(FakeSpeechModel(model, failWith = "model exploded")),
        )

        val outcome = engine.narrate("Text.", "Title")
        assertTrue(outcome is NarrationOutcome.Failure)
        val narration = repo.narrations().single()
        assertEquals(NarrationStatus.FAILED, narration.status)
        assertEquals("model exploded", narration.errorReason)
        assertFalse(store.exists(narration.id))
    }

    @Test
    fun `a runtime that throws is reported, not crashed`() = runBlocking {
        val repo = FakeSonataStore()
        val store = DirectoryFileStore(tempDir())
        val model = testModel()
        val engine = OfflineEngine(
            repository = repo,
            fileStore = store,
            registry = registry(model),
            device = device(),
            modelProvider = FakeModelProvider(FakeSpeechModel(model, throwOnSynthesize = true)),
        )

        val outcome = engine.narrate("Text.", "Title")
        assertTrue(outcome is NarrationOutcome.Failure)
        assertEquals(NarrationStatus.FAILED, repo.narrations().single().status)
    }

    @Test
    fun `an interrupted narration cannot be completed without being retried`() = runBlocking {
        val repo = FakeSonataStore()
        val store = DirectoryFileStore(tempDir())
        val id = repo.beginNarration(null, "Interrupted")
        repo.markGenerating(id)
        // The process dies; startup marks the row.
        repo.recoverInterrupted()
        assertEquals(NarrationStatus.INTERRUPTED, repo.narration(id)?.status)

        // Completing it directly must be refused by the transition table.
        val completed = repo.completeNarration(id, "/tmp/x.wav", 1000)
        assertFalse(completed)
        assertEquals(NarrationStatus.INTERRUPTED, repo.narration(id)?.status)
    }

    @Test
    fun `retry reuses the interrupted narration row`() = runBlocking {
        val repo = FakeSonataStore()
        val store = DirectoryFileStore(tempDir())
        val model = testModel()
        val engine = OfflineEngine(
            repository = repo,
            fileStore = store,
            registry = registry(model),
            device = device(),
            modelProvider = FakeModelProvider(FakeSpeechModel(model)),
        )
        val id = repo.beginNarration(null, "Keep my title")
        repo.markGenerating(id)
        repo.recoverInterrupted()

        val outcome = engine.retry(id, "Text again.")
        assertTrue(outcome is NarrationOutcome.Success)
        // The same row, so the title and document link are preserved.
        assertEquals(1, repo.narrations().size)
        assertEquals("Keep my title", repo.narration(id)?.title)
        assertEquals(NarrationStatus.COMPLETE, repo.narration(id)?.status)
    }

    @Test
    fun `a narration with no model installed fails with a reason`() = runBlocking {
        val repo = FakeSonataStore()
        val store = DirectoryFileStore(tempDir())
        val engine = OfflineEngine(
            repository = repo,
            fileStore = store,
            registry = registry(), // nothing installed
            device = device(),
        )
        val outcome = engine.narrate("Text.", "Title")
        assertTrue(outcome is NarrationOutcome.Failure)
        assertTrue((outcome as NarrationOutcome.Failure).reason.contains("No speech model"))
    }

    @Test
    fun `blank text is refused`() = runBlocking {
        val repo = FakeSonataStore()
        val store = DirectoryFileStore(tempDir())
        val model = testModel()
        val engine = OfflineEngine(
            repository = repo,
            fileStore = store,
            registry = registry(model),
            device = device(),
            modelProvider = FakeModelProvider(FakeSpeechModel(model)),
        )
        assertTrue(engine.narrate("   ", "Title") is NarrationOutcome.Failure)
        assertEquals(0, repo.narrations().size)
    }

    // ── The device gate ──────────────────────────────────────────────────────

    @Test
    fun `an incompatible model is refused before load`() {
        val big = testModel(minRamBytes = 8L * 1024 * 1024 * 1024)
        val small = device(ram = 2L * 1024 * 1024 * 1024)
        assertFalse(ModelCompatibility.canLoad(big, small))
        assertNotNull(ModelCompatibility.refusalReason(big, small))
    }

    @Test
    fun `a compatible model is allowed`() {
        val model = testModel(minRamBytes = 100L * 1024 * 1024)
        assertTrue(ModelCompatibility.canLoad(model, device()))
    }

    @Test
    fun `adaptation is refused until the device is thermally validated`() {
        val base = testModel(minRamBytes = 100L * 1024 * 1024)
        val unvalidated = device(thermal = false)
        assertFalse(ModelCompatibility.canAdapt(unvalidated, base, 15L * 1024 * 1024))
        // The same device, once measured, is allowed.
        assertTrue(ModelCompatibility.canAdapt(device(thermal = true), base, 15L * 1024 * 1024))
    }

    @Test
    fun `an unmeasured device is treated as unable to train`() {
        val base = testModel(minRamBytes = 100L * 1024 * 1024)
        assertFalse(ModelCompatibility.canAdapt(DeviceProfile.UNMEASURED, base, 15L * 1024 * 1024))
    }

    @Test
    fun `the best base model is the highest quality the device can run`() {
        val reg = registry(BundledModels.KOKORO_82M, BundledModels.PIPER_VITS)
        // A device that can hold Kokoro gets Kokoro.
        assertEquals(BundledModels.KOKORO_82M.id, reg.bestBaseFor(device())?.id)
        // A small device falls back to Piper rather than failing.
        val small = device(ram = 200L * 1024 * 1024, storage = 100L * 1024 * 1024)
        assertEquals(BundledModels.PIPER_VITS.id, reg.bestBaseFor(small)?.id)
    }

    @Test
    fun `a narration made with a removed model is detectable`() {
        val model = testModel(id = "gone")
        assertTrue(EnginePolicy.isModelStillInstalled("gone@1.0", listOf(model)))
        assertFalse(EnginePolicy.isModelStillInstalled("gone@1.0", emptyList()))
        assertFalse(EnginePolicy.isModelStillInstalled(null, listOf(model)))
    }

    @Test
    fun `a base model reports not ready when no native session is present`() = runBlocking {
        // The honest state of a build that has the contract but not the binary.
        val model = BaseSpeechModel(testModel())
        assertFalse(model.isReady())
    }

    @Test
    fun `the tone converter is a distinct role from the base synthesizer`() {
        assertEquals(ModelRole.BASE_SYNTHESIZER, BundledModels.KOKORO_82M.role)
        assertEquals(ModelRole.TONE_CONVERTER, BundledModels.OPENVOICE_V2_CONVERTER.role)
    }
}
