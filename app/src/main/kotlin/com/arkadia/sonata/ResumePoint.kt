package com.arkadia.sonata

/**
 * Whether a stored reading position may be offered as "carry on".
 *
 * Extracted from the Activity because the rule is the whole safety property of the
 * feature and it is pure: applying a stored offset to text it does not describe
 * would resume the user into the wrong place, which is worse than restarting. That
 * decision deserves a test, and a test needs it to be reachable without an Activity.
 *
 * The rule is deliberately conservative — it returns 0 (no resume) whenever anything
 * is uncertain, because a missing "carry on" is a small annoyance and a wrong one is
 * a bug the user cannot explain.
 */
object ResumePoint {

    /**
     * Do not offer to resume within this many characters of the end. There is
     * nothing left worth carrying on to, and a control that reads a few remaining
     * characters looks broken.
     */
    const val MIN_REMAINING = 40

    /**
     * The offset to resume from, or 0 when the read should start at the beginning.
     *
     * [storedPassage] is the text the offset was recorded against; [currentPassage]
     * is what is on screen now. They must match exactly, so editing the passage —
     * even to append to it — stops offering a resume rather than applying an offset
     * to text it does not describe.
     */
    fun offset(
        storedPassage: String,
        storedOffset: Int,
        currentPassage: String,
    ): Int {
        if (currentPassage.isBlank()) return 0
        if (storedPassage != currentPassage) return 0
        if (storedOffset <= 0) return 0
        // A stored offset past the end cannot be resumed into; the engine would clamp
        // it to nothing to read, which is a dead control.
        if (storedOffset >= currentPassage.length) return 0
        return if (storedOffset >= currentPassage.length - MIN_REMAINING) 0 else storedOffset
    }
}
