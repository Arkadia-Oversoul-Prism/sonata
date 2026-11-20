package com.arkadia.sonata.reliability

import com.arkadia.sonata.data.Narration
import com.arkadia.sonata.data.NarrationStatus
import com.arkadia.sonata.export.AudioFormat
import com.arkadia.sonata.export.AudioExporter
import com.arkadia.sonata.export.ExportResult

/**
 * Exports many narrations at once.
 *
 * The specification requires **bulk export**, and the important property is that a
 * failure on one item does not abandon the rest. A batch that stopped at the first
 * failure would leave the user unsure which files exist; a batch that reports
 * per-item outcomes lets them retry exactly what failed.
 *
 * Pure orchestration over the [AudioExporter] seam, so the batch rules are testable
 * without a device.
 */
class BatchExporter(private val exporter: AudioExporter) {

    /**
     * Export every narration in [narrations] as [format].
     *
     * Returns one outcome per input, in the same order, so the caller can map a
     * failure back to the item the user selected. Items that are not playable are
     * reported as skipped rather than attempted, because attempting them would
     * produce a confusing "no audio" error for something the library already knows
     * has no audio.
     */
    suspend fun exportAll(
        narrations: List<Narration>,
        format: AudioFormat,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BatchExportReport {
        val outcomes = ArrayList<BatchItemOutcome>(narrations.size)
        narrations.forEachIndexed { index, narration ->
            val outcome = when {
                narration.status != NarrationStatus.COMPLETE ->
                    BatchItemOutcome.Skipped(narration.id, "Not finished generating")

                narration.audioPath.isNullOrBlank() ->
                    BatchItemOutcome.Skipped(narration.id, "No audio file")

                else -> when (val result = exporter.export(narration, format)) {
                    is ExportResult.Success ->
                        BatchItemOutcome.Exported(narration.id, result.destination, result.bytes)

                    is ExportResult.Failure ->
                        BatchItemOutcome.Failed(narration.id, result.reason)
                }
            }
            outcomes += outcome
            onProgress(index + 1, narrations.size)
        }
        return BatchExportReport(format, outcomes)
    }
}

/** One item's outcome in a batch. */
sealed class BatchItemOutcome {
    abstract val narrationId: Long

    data class Exported(override val narrationId: Long, val destination: String, val bytes: Long) :
        BatchItemOutcome()

    data class Skipped(override val narrationId: Long, val reason: String) : BatchItemOutcome()

    data class Failed(override val narrationId: Long, val reason: String) : BatchItemOutcome()
}

/** The result of a batch export. */
data class BatchExportReport(
    val format: AudioFormat,
    val outcomes: List<BatchItemOutcome>,
) {
    val exported: List<BatchItemOutcome.Exported>
        get() = outcomes.filterIsInstance<BatchItemOutcome.Exported>()

    val failed: List<BatchItemOutcome.Failed>
        get() = outcomes.filterIsInstance<BatchItemOutcome.Failed>()

    val skipped: List<BatchItemOutcome.Skipped>
        get() = outcomes.filterIsInstance<BatchItemOutcome.Skipped>()

    /** Total bytes written. */
    val totalBytes: Long get() = exported.sumOf { it.bytes }

    /** Whether every item was exported. */
    val allSucceeded: Boolean get() = failed.isEmpty() && skipped.isEmpty()

    /** A one-line summary for the user. */
    fun summary(): String = buildString {
        append("${exported.size} exported")
        if (skipped.isNotEmpty()) append(", ${skipped.size} skipped")
        if (failed.isNotEmpty()) append(", ${failed.size} failed")
    }
}
