package com.arkadia.sonata.voice

/**
 * Trains a tone-colour adapter on-device.
 *
 * The specification requires **real local model training**, with checkpointing,
 * validation, rollback and activation. This interface is that contract; the
 * concrete trainer is a seam so the *policy* — when to checkpoint, when to roll
 * back, when to activate — is testable without a training loop.
 *
 * The base synthesizer is **frozen**. Only the adapter trains, which is what makes
 * the whole thing fit in a phone: the adapter is three orders of magnitude smaller
 * than the base model, so the memory and thermal cost are those of the adapter,
 * not of a full fine-tune.
 */
interface VoiceAdapterTrainer {

    /**
     * Train an adapter for [profileId] from [samplePaths].
     *
     * [onState] is called after every epoch with the current state, so the UI can
     * show progress and the watchdog can pause the run. Returns the final state.
     */
    suspend fun train(
        profileId: Long,
        samplePaths: List<String>,
        plan: TrainingPlan,
        onState: (TrainingState) -> Unit,
    ): TrainingOutcome

    /** Resume a paused run from its last checkpoint. */
    suspend fun resume(state: TrainingState, onState: (TrainingState) -> Unit): TrainingOutcome
}

/** Outcome of a training run. */
sealed class TrainingOutcome {

    /**
     * The adapter trained and passed validation.
     *
     * [adapterPath] is the trained adapter, [validationLoss] is the score that
     * justified activating it, and [wallClockMs] is the measured time — which is
     * what replaces the estimate in [TrainingPlan.estimatedMinutes].
     */
    data class Success(
        val adapterPath: String,
        val validationLoss: Float,
        val wallClockMs: Long,
        val peakMemoryBytes: Long,
    ) : TrainingOutcome()

    /**
     * The run paused for thermal reasons and can be resumed.
     *
     * Distinct from [Failure] on purpose: a pause is not a failure, and the work
     * done so far is preserved in the checkpoint.
     */
    data class Paused(val state: TrainingState) : TrainingOutcome()

    /**
     * The adapter trained but did not beat the base voice on validation.
     *
     * This is a real and important outcome: it means the samples did not
     * characterise a voice well enough to improve on the base. Activating it
     * anyway would produce a *worse* voice while claiming to be the user's, so the
     * run is reported as a failure with the reason.
     */
    data class NotBetterThanBase(val validationLoss: Float, val baseLoss: Float) : TrainingOutcome()

    /** [reason] is safe to surface to the user. */
    data class Failure(val reason: String) : TrainingOutcome()
}

/**
 * The validation gate.
 *
 * Pure, so the decision to activate is testable. The rule is that an adapter is
 * activated only when it measurably beats the base voice on held-out audio — the
 * specification's "validation before activation", and the reason a bad training run
 * cannot silently ship a bad voice.
 */
object AdapterValidation {

    /**
     * The improvement an adapter must show over the base voice to be activated.
     *
     * A small margin, because the loss is a proxy: requiring a large improvement
     * would reject adapters that are audibly better, and requiring none would
     * activate adapters that are noise.
     */
    const val MIN_IMPROVEMENT = 0.02f

    /**
     * Whether an adapter with [adapterLoss] should replace a base voice with
     * [baseLoss].
     *
     * Lower loss is better, so the adapter must be *below* the base by at least
     * [MIN_IMPROVEMENT].
     */
    fun shouldActivate(adapterLoss: Float, baseLoss: Float): Boolean =
        adapterLoss < baseLoss - MIN_IMPROVEMENT

    /** A user-presentable reason an adapter was not activated. */
    fun rejectionReason(adapterLoss: Float, baseLoss: Float): String =
        "The trained voice did not improve on the base voice " +
            "(score ${"%.3f".format(adapterLoss)} vs ${"%.3f".format(baseLoss)}). " +
            "Try recording in a quieter room, or record the passages again."
}
