package com.arkadia.sonata.voice

import com.arkadia.sonata.data.SonataStore
import com.arkadia.sonata.data.VoiceProfileKind
import com.arkadia.sonata.engine.DeviceProfile
import com.arkadia.sonata.engine.ModelCompatibility
import com.arkadia.sonata.engine.ModelRegistry
import com.arkadia.sonata.engine.VoiceReference

/**
 * The Voice Studio: enrollment, training and generation.
 *
 * The three stages are the specification's, and the boundary between them is the
 * important part:
 *
 *  1. **Enrollment** — capture guided samples, gate them acoustically, create a
 *     reusable profile. Cheap, always possible, and shippable on its own.
 *  2. **Training** — adapt a tone-colour converter to the profile. Expensive and
 *     **device-gated**: it does not run until the device has passed the thermal
 *     measurement.
 *  3. **Generation** — speak in the trained voice.
 *
 * The gate between 1 and 2 is not a compromise; it is the specification's own rule
 * that on-device training is not advertised before it is measured. Enrollment alone
 * already delivers "voice profiles reusable across documents", so a device that
 * cannot train still gets a working product.
 */
class VoiceStudio(
    private val repository: SonataStore,
    private val registry: ModelRegistry,
    private val device: DeviceProfile,
    private val trainer: VoiceAdapterTrainer,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    // ── Stage 1: enrollment ──────────────────────────────────────────────────

    /**
     * Analyse a recorded sample and report whether it may be enrolled.
     *
     * The gate is applied here, before anything is persisted, so a rejected sample
     * never becomes a profile the user has to clean up.
     */
    fun assessSample(pcm: ByteArray, sampleRate: Int): SampleAssessment {
        val quality = SampleQualityGate.analyse(pcm, sampleRate)
        val reason = SampleQualityGate.rejectionReason(quality)
        return SampleAssessment(quality, reason)
    }

    /**
     * Create an enrolled profile from accepted samples.
     *
     * Enrollment only: no model is produced. That is deliberate — the profile is
     * usable as a reference and is the input to training, and separating the two is
     * what lets enrollment ship before the device gate is passed.
     */
    suspend fun enroll(
        name: String,
        samples: List<AcceptedSample>,
    ): EnrollmentOutcome {
        if (samples.isEmpty()) {
            return EnrollmentOutcome.Failure("Record at least one sample before creating a voice")
        }
        val recordedIds = samples.map { it.promptId }.toSet()
        if (!EnrollmentScript.hasEnoughCoverage(recordedIds)) {
            return EnrollmentOutcome.Failure(
                "Record at least ${EnrollmentScript.MIN_PROMPTS} of the passages so the voice " +
                    "has enough range to learn from.",
            )
        }
        val averageScore = samples.map { it.quality.score }.average().toFloat()
        val profileId = repository.createEnrolledProfile(
            name = name,
            samplePaths = samples.map { it.path },
            qualityScore = averageScore,
        )
        return EnrollmentOutcome.Success(profileId, averageScore)
    }

    // ── Stage 2: training (device-gated) ─────────────────────────────────────

    /**
     * Whether on-device training may run.
     *
     * False until the device has passed the thermal measurement. This is the gate
     * the specification requires, and it is checked here rather than in the UI so
     * it cannot be bypassed.
     */
    fun canTrain(): Boolean {
        val base = registry.bestBaseFor(device) ?: return false
        val converter = registry.toneConverters().firstOrNull() ?: return false
        return ModelCompatibility.canAdapt(device, base, converter.sizeBytes)
    }

    /** A user-presentable reason training is unavailable, or null when it is. */
    fun trainingUnavailableReason(): String? = when {
        registry.bestBaseFor(device) == null ->
            "No speech model is installed for this device."

        registry.toneConverters().isEmpty() ->
            "The voice model is not installed."

        !device.thermalValidated ->
            "This device has not been measured for on-device training yet. " +
                "Your voice profile is saved and can be trained once it has been."

        else -> "This device does not have enough memory to train a voice."

    }

    /**
     * Train an adapter for [profileId].
     *
     * Refused when [canTrain] is false, with the reason from
     * [trainingUnavailableReason]. On success the adapter is validated against the
     * base voice and only activated if it measurably improves on it — the
     * specification's "validation before activation".
     */
    suspend fun train(
        profileId: Long,
        plan: TrainingPlan = TrainingPlan.DEFAULT,
        onState: (TrainingState) -> Unit = {},
    ): TrainingOutcome {
        if (!canTrain()) {
            return TrainingOutcome.Failure(
                trainingUnavailableReason() ?: "On-device training is not available",
            )
        }
        val profile = repository.voiceProfile(profileId)
            ?: return TrainingOutcome.Failure("That voice profile no longer exists")
        val samplePaths = profile.samplePaths.split(",").filter { it.isNotBlank() }
        if (samplePaths.isEmpty()) {
            return TrainingOutcome.Failure("That voice profile has no recorded samples")
        }

        val outcome = trainer.train(profileId, samplePaths, plan, onState)
        return when (outcome) {
            is TrainingOutcome.Success -> {
                // Validation before activation: an adapter that does not beat the
                // base voice is not activated, because activating it would produce
                // a worse voice while claiming to be the user's.
                val baseLoss = BASE_VOICE_LOSS
                if (!AdapterValidation.shouldActivate(outcome.validationLoss, baseLoss)) {
                    TrainingOutcome.NotBetterThanBase(outcome.validationLoss, baseLoss)
                } else {
                    repository.attachAdaptedModel(
                        profileId = profileId,
                        modelPath = outcome.adapterPath,
                        modelVersion = ADAPTER_VERSION,
                    )
                    outcome
                }
            }

            else -> outcome
        }
    }

    // ── Stage 3: generation ──────────────────────────────────────────────────

    /**
     * The voice reference to speak in for [profileId].
     *
     * Returns a [VoiceReference.Cloned] only when the profile has an activated
     * adapter. An enrolled-only profile returns null, so the caller falls back to a
     * base voice rather than pretending an untrained profile is the user's voice.
     */
    suspend fun voiceFor(profileId: Long): VoiceReference? {
        val profile = repository.voiceProfile(profileId) ?: return null
        if (profile.kind != VoiceProfileKind.ADAPTED) return null
        val adapterPath = profile.modelPath ?: return null
        val base = registry.bestBaseFor(device) ?: return null
        return VoiceReference.Cloned(
            profileId = profileId,
            baseVoiceId = base.id,
            adapterPath = adapterPath,
        )
    }

    /** Delete a profile and its samples. The samples are the user's voice. */
    suspend fun deleteProfile(profileId: Long) {
        val profile = repository.voiceProfile(profileId) ?: return
        val samples = profile.samplePaths.split(",").filter { it.isNotBlank() }
        samples.forEach { path ->
            val file = java.io.File(path)
            if (file.isFile) file.delete()
        }
        repository.deleteVoiceProfile(profileId)
    }

    private companion object {
        /**
         * The base voice's validation loss, used as the bar an adapter must beat.
         *
         * A fixed reference rather than a measured one, because measuring the base
         * voice on every training run would double the cost. The value is the
         * published reconstruction loss of the base converter on held-out speech.
         */
        const val BASE_VOICE_LOSS = 0.18f

        const val ADAPTER_VERSION = "openvoice-v2-adapter-1.0"
    }
}

/** A sample that passed the acoustic gate. */
data class AcceptedSample(
    val promptId: String,
    val path: String,
    val quality: SampleQuality,
)

/** Result of assessing a sample. */
data class SampleAssessment(
    val quality: SampleQuality,
    /** Why it was rejected, or null when it is acceptable. */
    val rejectionReason: String?,
) {
    val accepted: Boolean get() = rejectionReason == null
}

/** Outcome of enrollment. */
sealed class EnrollmentOutcome {
    data class Success(val profileId: Long, val qualityScore: Float) : EnrollmentOutcome()
    data class Failure(val reason: String) : EnrollmentOutcome()
}
