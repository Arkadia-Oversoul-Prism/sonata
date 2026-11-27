package com.arkadia.sonata

import com.arkadia.sonata.engine.NarrationOutcome

/**
 * What the screen says after a "Save narration" attempt.
 *
 * [saved] is the only claim the library is allowed to build on, so it is stated
 * separately from the [message] the user reads.
 */
data class NarrationReport(
    val message: String,
    val saved: Boolean,
    val narrationId: Long? = null,
)

/**
 * Turns an engine [NarrationOutcome] into the one line the user reads.
 *
 * The engine can only reach [NarrationOutcome.Success] after the WAV is on disk,
 * but this report does not take that on trust: a Success whose path is blank is
 * reported as a failure. "Saved" is the claim that a file exists, and a blank
 * path is the one case where the claim and the storage disagree — the same
 * predicate [com.arkadia.sonata.data.LibraryRules.isPlayable] applies to a row
 * before it is offered for playback.
 *
 * Pure, so the promise is testable without a device or a database.
 */
object NarrationPresenter {

    fun report(outcome: NarrationOutcome, title: String): NarrationReport = when (outcome) {
        is NarrationOutcome.Success ->
            if (outcome.audioPath.isBlank()) {
                NarrationReport(message = NOT_WRITTEN, saved = false)
            } else {
                NarrationReport(
                    message = "Saved \u201C$title\u201D (${formatDuration(outcome.durationMs)})",
                    saved = true,
                    narrationId = outcome.narrationId,
                )
            }

        is NarrationOutcome.Failure -> NarrationReport(message = outcome.reason, saved = false)
    }

    /**
     * A duration as `m:ss`.
     *
     * A zero or negative length is `0:00` rather than a negative time: a model that
     * reports nothing is a zero-length read, not an error to display.
     */
    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "0:00"
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }

    const val NOT_WRITTEN = "The narration was not written to storage"
}
