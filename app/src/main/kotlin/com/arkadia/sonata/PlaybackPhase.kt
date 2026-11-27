package com.arkadia.sonata

/**
 * What the main screen should tell the user is happening.
 *
 * [PlaybackState] describes the engine's position. This is the presentation
 * contract for it: exactly one phase per situation, so the state line and its
 * substate cannot disagree, and a read that has ended is distinguishable from a
 * screen that was never used.
 *
 * The last two phases are not derivable from the engine. When a read finishes the
 * engine is simply idle again, and when it fails the service stops, so "idle" and
 * "finished" and "failed" are the same observation unless the outcome is carried
 * separately. That is why [PlaybackObservation] takes them as inputs.
 */
enum class PlaybackPhase {
    /** Not bound to the player yet. */
    STARTING,

    /** Bound and idle: nothing has been read. */
    READY,

    /** A read was dispatched and no audio has started yet. */
    PREPARING,

    /** Audio is playing and the next piece is being fetched. */
    BUFFERING,

    PLAYING,

    PAUSED,

    /** The read reached the end of the passage. */
    COMPLETED,

    /** The read stopped with an error and did not continue. */
    FAILED,
}

/**
 * Every input the phase depends on, gathered in one place.
 *
 * @property preparing set when a read is dispatched, cleared when audio starts or
 *   the read ends. This is the synthesis wait the user previously could not see:
 *   the engine is neither playing nor paused while it prepares the first piece.
 * @property completed the read reached the end of the passage.
 * @property failure the message a failed read stopped with, or null.
 */
data class PlaybackObservation(
    val bound: Boolean,
    val playing: Boolean,
    val paused: Boolean,
    val bufferingNext: Boolean,
    val preparing: Boolean,
    val completed: Boolean,
    val failure: String?,
)

object PlaybackPhasePolicy {

    /**
     * Decide the phase. Precedence, highest first:
     *
     *  1. live playback (buffering, playing, paused) — what is happening now
     *  2. a failure — the read stopped and will not continue
     *  3. completion — the read reached the end
     *  4. preparing — dispatched, no audio yet
     *  5. bound / starting — nothing in flight
     *
     * Live state outranks the terminal facts on purpose. An error that fell back
     * to the offline voice leaves a message behind while the read continues, so a
     * lingering failure must not paint a stopped screen over a read that is
     * running; and a stale "completed" must not hide a read that is playing now.
     */
    fun decide(o: PlaybackObservation): PlaybackPhase = when {
        o.playing && o.bufferingNext -> PlaybackPhase.BUFFERING
        o.playing -> PlaybackPhase.PLAYING
        o.paused -> PlaybackPhase.PAUSED
        o.failure != null -> PlaybackPhase.FAILED
        o.completed -> PlaybackPhase.COMPLETED
        o.preparing -> PlaybackPhase.PREPARING
        o.bound -> PlaybackPhase.READY
        else -> PlaybackPhase.STARTING
    }
}
