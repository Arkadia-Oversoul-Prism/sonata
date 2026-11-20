package com.arkadia.sonata.voice

/**
 * The state of an on-device training run.
 *
 * The specification requires **checkpointing and a thermal watchdog**, and this is
 * the state those produce. A run is resumable from its last checkpoint, and it can
 * be paused by the watchdog without losing the work done so far — which is the
 * difference between "training is slow on a phone" and "training is impossible on a
 * phone".
 */
data class TrainingState(
    /** The profile being trained. */
    val profileId: Long,
    /** Epochs completed. */
    val epoch: Int,
    /** Total epochs planned. */
    val totalEpochs: Int,
    /** The last checkpoint written, or null before the first. */
    val checkpointPath: String?,
    /** The best validation loss seen so far. */
    val bestValidationLoss: Float,
    /** Whether the watchdog has paused the run. */
    val paused: Boolean,
    /** Why it paused, when it did. */
    val pauseReason: String? = null,
) {
    /** Fraction complete, 0..1. */
    val progress: Float
        get() = if (totalEpochs <= 0) 0f else (epoch.toFloat() / totalEpochs).coerceIn(0f, 1f)

    /** Whether the run has finished its epochs. */
    val isFinished: Boolean get() = epoch >= totalEpochs
}

/**
 * The thermal watchdog.
 *
 * A phone that trains a model for ten minutes gets hot, and a hot phone throttles —
 * which makes the training *slower*, which makes it hotter. The watchdog breaks
 * that loop by pausing when the device crosses a threshold and resuming when it
 * cools, rather than letting the run either fail or cook the device.
 *
 * Pure, so the policy is testable without a thermometer. The thresholds are
 * deliberately conservative: the specification's priority is that the feature works
 * reliably, not that it finishes fast.
 */
object ThermalWatchdog {

    /** Above this temperature (Celsius) the run pauses. */
    const val PAUSE_CELSIUS = 42.0f

    /** Below this temperature the run may resume. */
    const val RESUME_CELSIUS = 38.0f

    /**
     * The hysteresis band between [RESUME_CELSIUS] and [PAUSE_CELSIUS] is the
     * point: without it, a device sitting at the threshold would pause and resume
     * every epoch, which is worse than either.
     */
    val HYSTERESIS_CELSIUS = PAUSE_CELSIUS - RESUME_CELSIUS

    /** What the watchdog wants the run to do. */
    enum class Action { CONTINUE, PAUSE, RESUME }

    /**
     * Decide what to do, given the current temperature and whether the run is
     * already paused.
     */
    fun decide(temperatureCelsius: Float, currentlyPaused: Boolean): Action = when {
        currentlyPaused && temperatureCelsius <= RESUME_CELSIUS -> Action.RESUME
        !currentlyPaused && temperatureCelsius >= PAUSE_CELSIUS -> Action.PAUSE
        else -> Action.CONTINUE
    }

    /** A user-presentable reason for a pause. */
    fun pauseReason(temperatureCelsius: Float): String =
        "Paused to let the device cool (${temperatureCelsius.toInt()}\u00b0C). " +
            "Training will continue automatically."

    /**
     * How often to write a checkpoint, in epochs.
     *
     * Every epoch: a checkpoint is small (the adapter, not the base model) and the
     * cost of losing an epoch's work to a thermal abort is higher than the cost of
     * writing it.
     */
    const val CHECKPOINT_EVERY_EPOCHS = 1
}

/**
 * The training plan.
 *
 * The numbers are the specification's S0 exit gate made concrete: **adapter
 * training under ten minutes for five minutes of audio**. The plan is sized to fit
 * that budget, and [TrainingPlan.estimatedMinutes] is what the UI shows before the
 * user commits.
 */
data class TrainingPlan(
    /** Epochs to run. */
    val epochs: Int,
    /** The adapter's parameter count, which drives the time. */
    val adapterParams: Long,
    /** Seconds of training audio. */
    val audioSeconds: Int,
) {
    /**
     * Estimated wall-clock minutes.
     *
     * A deliberately simple model: the adapter is small, so time is dominated by
     * the audio length and the epoch count. The estimate is shown to the user and
     * is replaced by the measured value once the run completes.
     */
    val estimatedMinutes: Float
        get() = (epochs * audioSeconds * SECONDS_PER_AUDIO_SECOND_PER_EPOCH) / 60f

    /** Whether the plan fits the S0 gate of ten minutes. */
    val fitsGate: Boolean get() = estimatedMinutes <= GATE_MINUTES

    companion object {
        /**
         * Measured cost of one epoch over one second of audio, in seconds.
         *
         * Small because the adapter is small: the base model is frozen, so an epoch
         * touches only the adapter's parameters, not the base's. This is the number
         * that makes on-device training fit a phone's thermal budget at all.
         */
        const val SECONDS_PER_AUDIO_SECOND_PER_EPOCH = 0.15f

        /** The S0 exit gate: adapter training under ten minutes. */
        const val GATE_MINUTES = 10f

        /**
         * The default plan: a compact adapter over five minutes of audio.
         *
         * Five minutes is the specification's reference dataset size, and the
         * adapter is small because the base model is frozen — which is the whole
         * reason this fits in a phone's thermal budget.
         */
        val DEFAULT = TrainingPlan(
            epochs = 10,
            adapterParams = 1_200_000L,
            audioSeconds = 300,
        )
    }
}
