package com.arkadia.sonata.offline

import com.arkadia.sonata.data.VoiceProfileKind
import com.arkadia.sonata.engine.DeviceProfile
import com.arkadia.sonata.engine.InMemoryModelRegistry
import com.arkadia.sonata.engine.VoiceReference
import com.arkadia.sonata.voice.AcceptedSample
import com.arkadia.sonata.voice.AdapterValidation
import com.arkadia.sonata.voice.EnrollmentOutcome
import com.arkadia.sonata.voice.EnrollmentScript
import com.arkadia.sonata.voice.SampleQuality
import com.arkadia.sonata.voice.SampleQualityGate
import com.arkadia.sonata.voice.ThermalWatchdog
import com.arkadia.sonata.voice.TrainingOutcome
import com.arkadia.sonata.voice.TrainingPlan
import com.arkadia.sonata.voice.TrainingState
import com.arkadia.sonata.voice.VoiceAdapterTrainer
import com.arkadia.sonata.voice.VoiceStudio
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4 — the Voice Studio. */
class VoiceStudioTest {

    private fun device(thermal: Boolean = true) = DeviceProfile(
        ramBytes = 4L * 1024 * 1024 * 1024,
        freeStorageBytes = 2L * 1024 * 1024 * 1024,
        thermalValidated = thermal,
        label = "test",
    )

    private fun registry() = InMemoryModelRegistry(
        listOf(
            testModel(id = "base", minRamBytes = 100L * 1024 * 1024),
            testModel(
                id = "converter",
                role = com.arkadia.sonata.engine.ModelRole.TONE_CONVERTER,
                minRamBytes = 100L * 1024 * 1024,
            ),
        ),
    )

    /** A trainer that succeeds with a supplied loss. */
    private class FakeTrainer(
        private val loss: Float = 0.10f,
        private val outcome: TrainingOutcome? = null,
    ) : VoiceAdapterTrainer {
        var trained = false
        override suspend fun train(
            profileId: Long,
            samplePaths: List<String>,
            plan: TrainingPlan,
            onState: (TrainingState) -> Unit,
        ): TrainingOutcome {
            trained = true
            outcome?.let { return it }
            onState(TrainingState(profileId, plan.epochs, plan.epochs, "/tmp/a.bin", loss, false))
            return TrainingOutcome.Success("/tmp/adapter.bin", loss, 120_000, 300L * 1024 * 1024)
        }

        override suspend fun resume(
            state: TrainingState,
            onState: (TrainingState) -> Unit,
        ): TrainingOutcome = TrainingOutcome.Failure("not used")
    }

    // ── The acoustic gate ────────────────────────────────────────────────────

    @Test
    fun `a quiet recording is rejected with a reason`() {
        val quality = SampleQuality(peak = 0.05f, rms = 0.005f, speechRatio = 0.9f, durationMs = 10_000)
        assertFalse(SampleQualityGate.isAcceptable(quality))
        assertTrue(SampleQualityGate.rejectionReason(quality)!!.contains("too quiet"))
    }

    @Test
    fun `a clipping recording is rejected`() {
        val quality = SampleQuality(peak = 1.0f, rms = 0.3f, speechRatio = 0.9f, durationMs = 10_000)
        assertFalse(SampleQualityGate.isAcceptable(quality))
        assertTrue(SampleQualityGate.rejectionReason(quality)!!.contains("clipping"))
    }

    @Test
    fun `a mostly-silent recording is rejected`() {
        val quality = SampleQuality(peak = 0.5f, rms = 0.1f, speechRatio = 0.1f, durationMs = 10_000)
        assertFalse(SampleQualityGate.isAcceptable(quality))
        assertTrue(SampleQualityGate.rejectionReason(quality)!!.contains("silence"))
    }

    @Test
    fun `a short recording is rejected before its level is judged`() {
        // The most actionable problem is reported first: recording longer is the
        // first thing to try, so "too short" wins over "too quiet".
        val quality = SampleQuality(peak = 0.5f, rms = 0.001f, speechRatio = 0.1f, durationMs = 1_000)
        assertTrue(SampleQualityGate.rejectionReason(quality)!!.contains("too short"))
    }

    @Test
    fun `a good recording is accepted`() {
        val quality = SampleQuality(peak = 0.7f, rms = 0.12f, speechRatio = 0.8f, durationMs = 15_000)
        assertTrue(SampleQualityGate.isAcceptable(quality))
        assertNull(SampleQualityGate.rejectionReason(quality))
    }

    @Test
    fun `the score is bounded and no single measure saturates it`() {
        val perfect = SampleQuality(peak = 0.7f, rms = 0.5f, speechRatio = 1.0f, durationMs = 60_000)
        assertTrue(perfect.score <= 1.0f)
        assertTrue(perfect.score > 0.9f)
        // Two different amplitudes must not both score 1.0 — the bug the clamp fixes.
        val a = SampleQuality(0.5f, 0.15f, 0.8f, 15_000).score
        val b = SampleQuality(0.5f, 0.30f, 0.8f, 15_000).score
        assertEquals(a, b, 0.0001f)
    }

    @Test
    fun `analysis of synthetic PCM finds the level and the speech ratio`() {
        // A loud half and a silent half: the ratio should be near a half.
        val pcm = ByteArray(48_000)
        for (i in 0 until 12_000) {
            val v = (8000).toShort()
            pcm[i * 2] = (v.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        val quality = SampleQualityGate.analyse(pcm, 24_000)
        assertTrue(quality.peak > 0.2f)
        assertTrue(quality.speechRatio in 0.3f..0.7f)
        assertEquals(1000L, quality.durationMs)
    }

    // ── Enrollment ───────────────────────────────────────────────────────────

    @Test
    fun `enrollment requires enough coverage`() = runBlocking {
        val studio = VoiceStudio(FakeSonataStore(), registry(), device(), FakeTrainer())
        val one = listOf(AcceptedSample("articulation", "/tmp/a.wav", goodQuality()))
        val outcome = studio.enroll("My voice", one)
        assertTrue(outcome is EnrollmentOutcome.Failure)
        assertTrue((outcome as EnrollmentOutcome.Failure).reason.contains("passages"))
    }

    @Test
    fun `enrollment creates a profile with the average quality`() = runBlocking {
        val repo = FakeSonataStore()
        val studio = VoiceStudio(repo, registry(), device(), FakeTrainer())
        val samples = listOf(
            AcceptedSample("articulation", "/tmp/a.wav", goodQuality()),
            AcceptedSample("question", "/tmp/b.wav", goodQuality()),
            AcceptedSample("statement", "/tmp/c.wav", goodQuality()),
        )
        val outcome = studio.enroll("My voice", samples)
        assertTrue(outcome is EnrollmentOutcome.Success)
        val profile = repo.voiceProfile((outcome as EnrollmentOutcome.Success).profileId)!!
        assertEquals(VoiceProfileKind.ENROLLED, profile.kind)
        assertTrue(profile.qualityScore!! > 0f)
    }

    @Test
    fun `enrollment alone does not produce a cloned voice`() = runBlocking {
        // The gate between stage 1 and stage 3: an enrolled-only profile is not the
        // user's voice yet, and must not be presented as one.
        val repo = FakeSonataStore()
        val studio = VoiceStudio(repo, registry(), device(), FakeTrainer())
        val samples = EnrollmentScript.PROMPTS.take(3).map {
            AcceptedSample(it.id, "/tmp/${it.id}.wav", goodQuality())
        }
        val outcome = studio.enroll("My voice", samples) as EnrollmentOutcome.Success
        assertNull(studio.voiceFor(outcome.profileId))
    }

    // ── Training ─────────────────────────────────────────────────────────────

    @Test
    fun `training is refused until the device is thermally validated`() = runBlocking {
        val repo = FakeSonataStore()
        val trainer = FakeTrainer()
        val studio = VoiceStudio(repo, registry(), device(thermal = false), trainer)
        val profileId = repo.createEnrolledProfile("My voice", listOf("/tmp/a.wav"))
        val outcome = studio.train(profileId)
        assertTrue(outcome is TrainingOutcome.Failure)
        assertFalse("the trainer must not have run", trainer.trained)
        assertNotNull(studio.trainingUnavailableReason())
    }

    @Test
    fun `a successful training activates the adapter`() = runBlocking {
        val repo = FakeSonataStore()
        val studio = VoiceStudio(repo, registry(), device(), FakeTrainer(loss = 0.10f))
        val profileId = repo.createEnrolledProfile("My voice", listOf("/tmp/a.wav"))
        val outcome = studio.train(profileId)
        assertTrue(outcome is TrainingOutcome.Success)
        val profile = repo.voiceProfile(profileId)!!
        assertEquals(VoiceProfileKind.ADAPTED, profile.kind)
        assertNotNull(profile.modelPath)
    }

    @Test
    fun `an adapter that does not beat the base voice is not activated`() = runBlocking {
        // Validation before activation: a bad run must not ship a bad voice.
        val repo = FakeSonataStore()
        val studio = VoiceStudio(repo, registry(), device(), FakeTrainer(loss = 0.30f))
        val profileId = repo.createEnrolledProfile("My voice", listOf("/tmp/a.wav"))
        val outcome = studio.train(profileId)
        assertTrue(outcome is TrainingOutcome.NotBetterThanBase)
        assertEquals(VoiceProfileKind.ENROLLED, repo.voiceProfile(profileId)!!.kind)
    }

    @Test
    fun `a paused run is not a failure`() = runBlocking {
        val repo = FakeSonataStore()
        val paused = TrainingOutcome.Paused(
            TrainingState(1L, 3, 10, "/tmp/ckpt.bin", 0.2f, paused = true, pauseReason = "hot"),
        )
        val studio = VoiceStudio(repo, registry(), device(), FakeTrainer(outcome = paused))
        val profileId = repo.createEnrolledProfile("My voice", listOf("/tmp/a.wav"))
        val outcome = studio.train(profileId)
        assertTrue(outcome is TrainingOutcome.Paused)
        // The profile is untouched, so the run can resume.
        assertEquals(VoiceProfileKind.ENROLLED, repo.voiceProfile(profileId)!!.kind)
    }

    @Test
    fun `an adapted profile yields a cloned voice reference`() = runBlocking {
        val repo = FakeSonataStore()
        val studio = VoiceStudio(repo, registry(), device(), FakeTrainer(loss = 0.10f))
        val profileId = repo.createEnrolledProfile("My voice", listOf("/tmp/a.wav"))
        studio.train(profileId)
        val voice = studio.voiceFor(profileId)
        assertTrue(voice is VoiceReference.Cloned)
        assertEquals(profileId, (voice as VoiceReference.Cloned).profileId)
    }

    @Test
    fun `the validation gate requires a real improvement`() {
        assertTrue(AdapterValidation.shouldActivate(0.10f, 0.18f))
        assertFalse(AdapterValidation.shouldActivate(0.17f, 0.18f))
        assertFalse(AdapterValidation.shouldActivate(0.20f, 0.18f))
    }

    // ── The thermal watchdog ───────────────────────────────────────────────────────────

    @Test
    fun `the watchdog pauses above the threshold`() {
        assertEquals(
            ThermalWatchdog.Action.PAUSE,
            ThermalWatchdog.decide(ThermalWatchdog.PAUSE_CELSIUS + 1f, currentlyPaused = false),
        )
    }

    @Test
    fun `the watchdog resumes only after cooling below the resume threshold`() {
        // The hysteresis band is the point: without it the run would oscillate.
        assertEquals(
            ThermalWatchdog.Action.CONTINUE,
            ThermalWatchdog.decide(ThermalWatchdog.PAUSE_CELSIUS - 1f, currentlyPaused = true),
        )
        assertEquals(
            ThermalWatchdog.Action.RESUME,
            ThermalWatchdog.decide(ThermalWatchdog.RESUME_CELSIUS - 1f, currentlyPaused = true),
        )
    }

    @Test
    fun `the default plan fits the ten-minute gate`() {
        assertTrue(TrainingPlan.DEFAULT.fitsGate)
        assertTrue(TrainingPlan.DEFAULT.estimatedMinutes <= TrainingPlan.GATE_MINUTES)
    }

    @Test
    fun `a plan that exceeds the gate is detected`() {
        val huge = TrainingPlan(epochs = 500, adapterParams = 1_000_000L, audioSeconds = 300)
        assertFalse(huge.fitsGate)
    }

    @Test
    fun `deleting a profile removes its samples`() = runBlocking {
        val repo = FakeSonataStore()
        val studio = VoiceStudio(repo, registry(), device(), FakeTrainer())
        val sample = java.io.File.createTempFile("sample", ".wav").apply { writeBytes(ByteArray(10)) }
        val profileId = repo.createEnrolledProfile("My voice", listOf(sample.absolutePath))
        studio.deleteProfile(profileId)
        assertFalse(sample.exists())
        assertNull(repo.voiceProfile(profileId))
    }

    private fun goodQuality() = SampleQuality(
        peak = 0.7f,
        rms = 0.12f,
        speechRatio = 0.8f,
        durationMs = 15_000,
    )
}
