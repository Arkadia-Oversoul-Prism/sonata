package com.arkadia.sonata.narration

/**
 * The eight narration styles.
 *
 * The specification names them, and each is a *prosodic character* rather than a
 * label: a style is a rate multiplier, a pause scale and a pitch offset, because
 * those are the three things the engine can actually vary. A style that promised
 * an emotion the model cannot synthesise would be a lie, so the styles are defined
 * only in terms of what the pipeline can deliver.
 *
 * The values are relative, not absolute: [rateScale] multiplies the user's chosen
 * speed, so a user who reads at 1.5× still reads at 1.5× in every style.
 */
enum class NarrationStyle(
    /** Display name. */
    val displayName: String,
    /** One line describing the delivery, shown in the picker. */
    val description: String,
    /** Multiplier on the user's chosen speed. */
    val rateScale: Float,
    /** Multiplier on every pause the planner places. */
    val pauseScale: Float,
    /** Pitch offset in semitones, applied by the engine where it can. */
    val pitchSemitones: Float,
) {
    /** Even, unhurried, neutral. The default. */
    NATURAL(
        displayName = "Natural",
        description = "Even and unhurried, as if reading to yourself",
        rateScale = 1.0f,
        pauseScale = 1.0f,
        pitchSemitones = 0f,
    ),

    /** Lighter, faster, more casual. */
    CONVERSATIONAL(
        displayName = "Conversational",
        description = "Lighter and quicker, as if telling someone nearby",
        rateScale = 1.08f,
        pauseScale = 0.85f,
        pitchSemitones = 0.5f,
    ),

    /** Slower, with longer pauses. */
    REFLECTIVE(
        displayName = "Reflective",
        description = "Slower, with room to think between sentences",
        rateScale = 0.9f,
        pauseScale = 1.4f,
        pitchSemitones = -0.5f,
    ),

    /** Slightly slower, warmer. */
    WARM(
        displayName = "Warm",
        description = "Gentle and close, with soft sentence endings",
        rateScale = 0.95f,
        pauseScale = 1.15f,
        pitchSemitones = -0.25f,
    ),

    /** Steady, deliberate, minimal pause variation. */
    CONFIDENT(
        displayName = "Confident",
        description = "Steady and deliberate, with firm sentence endings",
        rateScale = 1.0f,
        pauseScale = 0.9f,
        pitchSemitones = -0.25f,
    ),

    /** Varied pace, longer paragraph pauses. */
    STORYTELLING(
        displayName = "Storytelling",
        description = "Varied pace, with a longer breath between paragraphs",
        rateScale = 0.98f,
        pauseScale = 1.3f,
        pitchSemitones = 0.25f,
    ),

    /** Slowest, longest pauses, widest pitch range. */
    DRAMATIC(
        displayName = "Dramatic",
        description = "Slow and weighted, with long pauses for effect",
        rateScale = 0.88f,
        pauseScale = 1.6f,
        pitchSemitones = 0.75f,
    ),

    /** Precise, even, minimal expression. */
    PROFESSIONAL(
        displayName = "Professional",
        description = "Precise and even, as if reading a report aloud",
        rateScale = 1.02f,
        pauseScale = 0.8f,
        pitchSemitones = 0f,
    );

    companion object {
        /** The style used when none is chosen. */
        val DEFAULT = NATURAL

        /** Resolve a stored name to a style, falling back to [DEFAULT]. */
        fun fromName(name: String?): NarrationStyle =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
