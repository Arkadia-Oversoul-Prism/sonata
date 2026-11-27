package com.arkadia.sonata

/**
 * How a read ended, carried separately from the engine.
 *
 * When a read finishes the engine is idle again and the service stops itself, so
 * "finished", "failed" and "never started" are the same engine observation. The
 * screen could tell them apart only while it was bound: [MainActivity.onStop]
 * removes its client, so a read that ended while the user was in another app came
 * back as `Ready` — the same screen as one that was never used.
 *
 * This record is the terminal fact that outlives the service instance. The service
 * writes it before it stops itself and the screen adopts it when it binds again.
 *
 * Pure JVM on purpose: the rule that decides whether a stored outcome may be
 * presented is the safety property of the feature, and it must be testable without
 * an Activity.
 */
data class ReadOutcome(
    /** The passage the outcome belongs to, exactly as it was dispatched. */
    val passage: String,
    val status: Status,
    /** The message a failed read stopped with; null for a completed read. */
    val message: String? = null,
) {
    enum class Status { COMPLETED, FAILED }
}

object ReadOutcomePolicy {

    /**
     * The stored outcome the screen may present, or null when it must not.
     *
     * Conservative in the same way as [ResumePoint], and for the same reason: a
     * wrong "Completed" is worse than a missing one. An outcome is withheld when
     *
     *  - there is none,
     *  - a read is live now (a stale terminal fact must not paint over it),
     *  - the field is empty (nothing for the outcome to describe), or
     *  - it describes different text. Editing the passage — even by appending to
     *    it — makes the old outcome inapplicable rather than approximately right.
     */
    fun adopt(
        stored: ReadOutcome?,
        currentPassage: String,
        live: Boolean,
    ): ReadOutcome? {
        if (stored == null) return null
        if (live) return null
        if (currentPassage.isBlank()) return null
        if (stored.passage != currentPassage) return null
        return stored
    }
}
