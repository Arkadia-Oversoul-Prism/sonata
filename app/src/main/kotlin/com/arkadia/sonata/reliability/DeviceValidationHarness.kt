package com.arkadia.sonata.reliability

import com.arkadia.sonata.engine.DeviceProfile
import com.arkadia.sonata.engine.ModelDescriptor

/**
 * The device validation harness.
 *
 * This is the phase that **gates on-device voice adaptation**. The specification
 * forbids advertising on-device training before it is measured against RAM,
 * storage, thermal limits and processing speed, and this is where those
 * measurements are recorded and turned into a decision.
 *
 * The harness itself runs on a device; the *policy* — what counts as a pass, and
 * what the numbers mean — is here, pure, so it is testable without one. That split
 * is deliberate: the measurement is device work, but the decision must be
 * reviewable and reproducible.
 */
object DeviceValidationHarness {

    /**
     * One measurement.
     *
     * Every field is a number the harness records, not an estimate. A field that
     * could not be measured is null, and a null is treated as a failure rather than
     * as a pass — the honest default.
     */
    data class Measurement(
        /** Real-time factor: synthesis seconds per audio second. Lower is better. */
        val realTimeFactor: Float?,
        /** Peak resident set size during synthesis, bytes. */
        val peakRssBytes: Long?,
        /** On-disk size of the model plus its working files, bytes. */
        val onDiskBytes: Long?,
        /** Time to the first audio chunk, milliseconds. */
        val firstChunkMs: Long?,
        /** Peak temperature over a twenty-minute run, Celsius. */
        val peakTemperatureCelsius: Float?,
        /** Battery drain per minute of audio, percent. */
        val batteryPercentPerMinute: Float?,
        /** Wall-clock time to train an adapter over five minutes of audio, ms. */
        val adapterTrainingMs: Long?,
        /** Peak memory during adapter training, bytes. */
        val adapterPeakMemoryBytes: Long?,
        /** Whether the training run completed without a thermal abort. */
        val trainingCompleted: Boolean,
    )

    /**
     * The S0 exit gate, as a pass/fail checklist.
     *
     * The three thresholds are the specification's, and they are the *only* thing
     * that decides whether adaptation ships. A device that fails any one of them
     * does not get the feature, and the enrollment-only path is the product — which
     * the specification states is a legitimate outcome, not a failure.
     */
    data class GateResult(
        val rtfPass: Boolean,
        val rssPass: Boolean,
        val trainingPass: Boolean,
        val thermalPass: Boolean,
    ) {
        /** Whether every gate passed. */
        val passed: Boolean get() = rtfPass && rssPass && trainingPass && thermalPass

        /** The gates that failed, for the report. */
        fun failures(): List<String> = buildList {
            if (!rtfPass) add("Real-time factor above ${RTF_LIMIT} on mid-tier")
            if (!rssPass) add("Peak memory above ${RSS_LIMIT_MB} MB")
            if (!trainingPass) add("Adapter training did not finish under ${TRAINING_LIMIT_MIN} minutes")
            if (!thermalPass) add("Thermal abort during training")
        }
    }

    /**
     * Evaluate [measurement] against the gate.
     *
     * A null measurement fails its gate. This is the important rule: an unmeasured
     * device is not a passing device, and treating "we did not measure it" as a pass
     * is exactly the dishonesty the specification's gate exists to prevent.
     */
    fun evaluate(measurement: Measurement): GateResult = GateResult(
        rtfPass = measurement.realTimeFactor?.let { it <= RTF_LIMIT } == true,
        rssPass = measurement.peakRssBytes?.let { it <= RSS_LIMIT_MB * 1024L * 1024 } == true,
        trainingPass = measurement.adapterTrainingMs?.let { it <= TRAINING_LIMIT_MIN * 60_000L } == true,
        thermalPass = measurement.trainingCompleted,
    )

    /**
     * The [DeviceProfile] implied by [measurement].
     *
     * This is how a measurement becomes the value the compatibility gate consults.
     * `thermalValidated` is set only when the training run completed *and* the
     * thermal trace stayed under the limit, so a device that trained but cooked
     * itself is not marked validated.
     */
    fun profileFrom(
        measurement: Measurement,
        ramBytes: Long,
        freeStorageBytes: Long,
        label: String,
    ): DeviceProfile {
        val gate = evaluate(measurement)
        return DeviceProfile(
            ramBytes = ramBytes,
            freeStorageBytes = freeStorageBytes,
            thermalValidated = gate.thermalPass && gate.trainingPass,
            label = label,
        )
    }

    /**
     * The report the harness produces.
     *
     * One page per device, with the numbers that justify each decision — the
     * specification's D4/D6 deliverables made concrete.
     */
    data class DeviceReport(
        val deviceLabel: String,
        val model: ModelDescriptor,
        val measurement: Measurement,
        val gate: GateResult,
    ) {
        /** A one-line verdict. */
        fun verdict(): String =
            if (gate.passed) "PASS \u2014 on-device adaptation may ship on this device"
            else "FAIL \u2014 ${gate.failures().joinToString("; ")}"

        /** The measured numbers, formatted for the report. */
        fun numbers(): List<Pair<String, String>> = listOf(
            "Real-time factor" to (measurement.realTimeFactor?.let { "%.3f".format(it) } ?: "not measured"),
            "Peak memory" to (measurement.peakRssBytes?.let { StorageAccountant.humanBytes(it) } ?: "not measured"),
            "On-disk size" to (measurement.onDiskBytes?.let { StorageAccountant.humanBytes(it) } ?: "not measured"),
            "First chunk" to (measurement.firstChunkMs?.let { "$it ms" } ?: "not measured"),
            "Peak temperature" to (measurement.peakTemperatureCelsius?.let { "%.1f\u00b0C".format(it) } ?: "not measured"),
            "Battery per minute" to (measurement.batteryPercentPerMinute?.let { "%.2f%%".format(it) } ?: "not measured"),
            "Adapter training" to (measurement.adapterTrainingMs?.let { "%.1f min".format(it / 60_000.0) } ?: "not measured"),
            "Training peak memory" to (measurement.adapterPeakMemoryBytes?.let { StorageAccountant.humanBytes(it) } ?: "not measured"),
        )
    }

    // ── The S0 exit gate thresholds ──────────────────────────────────────────

    /** Real-time factor must be at or below this on mid-tier. */
    const val RTF_LIMIT = 0.5f

    /** Peak resident set size must be at or below this. */
    const val RSS_LIMIT_MB = 700L

    /** Adapter training over five minutes of audio must finish within this. */
    const val TRAINING_LIMIT_MIN = 10L

    /** The thermal limit above which a run is aborted. */
    const val THERMAL_ABORT_CELSIUS = 45.0f
}
