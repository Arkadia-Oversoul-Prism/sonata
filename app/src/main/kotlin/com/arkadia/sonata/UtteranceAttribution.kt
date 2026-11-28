package com.arkadia.sonata

/**
 * What the service's current utterance is.
 *
 * The service used to record a terminal outcome against a boolean meaning "a read is
 * in progress". A voice sample breaks that boolean: auditioning a voice supersedes
 * whatever is playing (two audio streams cannot play at once), but it is dispatched by
 * [SpeechService.ACTION_PREVIEW] rather than by [SpeechService.ACTION_SPEAK], so the
 * flag stayed set. The sample's terminal event was then recorded as the **passage's**
 * outcome — a passage that had been interrupted by the user was later reported as
 * having *failed*, carrying the sample's error message.
 *
 * Naming what is in flight makes the question answerable: a terminal event belongs to
 * the read only when a read is what is in flight.
 */
enum class InFlightUtterance {
    /** Nothing dispatched, or the last utterance already ended. */
    NONE,

    /** A passage. Its terminal event is the passage's outcome. */
    READ,

    /**
     * A short sample. Its terminal event is about the sample, and must reach neither
     * the stored outcome nor the reading position.
     */
    PREVIEW,
}

/** What a terminal engine event may do to the stored read outcome. */
enum class TerminalRecord {
    COMPLETION,
    FAILURE,

    /** The event belongs to something that is not a read; record nothing. */
    NONE,
}

/**
 * Which terminal record a terminal event produces, given what is in flight.
 *
 * Pure and separate from the service because this is the safety property of the
 * feature — a wrong "Failed" is a claim about the user's passage that the
 * application is not entitled to make — and it must be testable without an Android
 * runtime.
 *
 * Only [InFlightUtterance.READ] records. That includes the `NONE` case, which is not
 * a formality: an event arriving with nothing in flight is unattributable, and an
 * unattributable outcome is worse than a missing one.
 */
object UtteranceAttributionPolicy {

    fun onComplete(inFlight: InFlightUtterance): TerminalRecord =
        if (inFlight == InFlightUtterance.READ) TerminalRecord.COMPLETION
        else TerminalRecord.NONE

    fun onTerminalError(inFlight: InFlightUtterance): TerminalRecord =
        if (inFlight == InFlightUtterance.READ) TerminalRecord.FAILURE
        else TerminalRecord.NONE
}
