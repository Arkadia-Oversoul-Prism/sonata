package com.arkadia.sonata.narration

/**
 * A pause the planner places.
 *
 * [afterChar] is an offset into the cleaned text, so the pause is anchored to the
 * text rather than to a time — which means the plan survives a change of speed or
 * voice, and the canvas can show where the breath falls.
 */
data class Pause(
    val afterChar: Int,
    val durationMs: Long,
    val kind: PauseKind,
)

/** Why a pause is there. */
enum class PauseKind {
    /** After a sentence terminator. */
    SENTENCE,

    /** After a paragraph break. */
    PARAGRAPH,

    /** After a comma or other weak punctuation. */
    CLAUSE,

    /** After a colon or semicolon, which introduce something. */
    INTRODUCTORY,
}

/**
 * A segment of text with its delivery.
 *
 * The unit the engine speaks. Splitting at pauses rather than at a fixed length is
 * what makes the delivery expressive: the engine gets a phrase at a time, with the
 * pause between phrases explicit, instead of a wall of text.
 */
data class NarrationSegment(
    val start: Int,
    val end: Int,
    /** Rate multiplier for this segment, relative to the user's speed. */
    val rateScale: Float,
    /** Pitch offset in semitones for this segment. */
    val pitchSemitones: Float,
    /** The pause that follows this segment, if any. */
    val pauseAfter: Pause?,
)

/**
 * The complete delivery plan for a text.
 *
 * This is the output of the Narration Intelligence pipeline and the input to the
 * engine. It is a plain value, so it can be inspected, tested and shown to the user
 * — which matters, because the specification's requirement is that the difference
 * between treatments is *audible*, and a plan that cannot be inspected cannot be
 * verified to differ.
 */
data class ProsodyPlan(
    /** The cleaned text the plan indexes into. */
    val text: String,
    /** The style applied. */
    val style: NarrationStyle,
    /** The context detected. */
    val context: NarrationContext,
    /** The segments, in order, covering the whole text. */
    val segments: List<NarrationSegment>,
    /** Every pause, in order. */
    val pauses: List<Pause>,
) {
    /** Total pause time the plan adds, milliseconds. */
    val totalPauseMs: Long get() = pauses.sumOf { it.durationMs }

    /** The rate multiplier for the whole plan. */
    val rateScale: Float get() = style.rateScale

    /** Whether the plan is empty. */
    val isEmpty: Boolean get() = segments.isEmpty()
}
