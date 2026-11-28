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

    /**
     * How long a dispatched read may wait for its first sample before the screen
     * stops claiming one is coming.
     *
     * The online transport's read timeout exists to cover synthesis of a whole
     * passage, so the wait is real and is not a fault — but it is not unbounded
     * either. A service that never calls back must not leave "Preparing…" on
     * screen for ever, because a screen that says it is still working cannot be
     * distinguished from one that is stuck.
     */
    const val PREPARE_TIMEOUT_MS = 20_000L

    /**
     * Whether the progress ticker must keep running in [phase].
     *
     * [PlaybackPhase.PREPARING] is included, and that is the whole point: the
     * ticker is the only thing repainting the screen between the dispatch and the
     * engine's first callback. Gating it on `playing || paused` — the engine's own
     * live state — stopped it on its first pass during preparation, so the screen
     * had no way to notice either that the read had started or that it had waited
     * too long.
     */
    fun keepsTicking(phase: PlaybackPhase): Boolean = when (phase) {
        PlaybackPhase.BUFFERING,
        PlaybackPhase.PLAYING,
        PlaybackPhase.PAUSED,
        PlaybackPhase.PREPARING,
        -> true

        PlaybackPhase.STARTING,
        PlaybackPhase.READY,
        PlaybackPhase.COMPLETED,
        PlaybackPhase.FAILED,
        -> false
    }

    /** True once a preparation has waited [timeoutMs], measured from the dispatch. */
    fun preparationExpired(waitedMs: Long, timeoutMs: Long = PREPARE_TIMEOUT_MS): Boolean =
        waitedMs >= timeoutMs
}
