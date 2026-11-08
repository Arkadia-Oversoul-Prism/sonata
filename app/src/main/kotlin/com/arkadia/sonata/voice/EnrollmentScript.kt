package com.arkadia.sonata.voice

/**
 * The guided enrollment script.
 *
 * The specification requires **guided** recording: the user is not left to record
 * "some audio", they are given passages chosen to exercise the parts of a voice a
 * model needs. The passages are fixed rather than free-form because a model trained
 * on a narrow sample (one sentence, one mood) reproduces a narrow voice.
 *
 * Pure data, so the script is testable and the UI has one source for it.
 */
object EnrollmentScript {

    /**
     * One guided prompt.
     *
     * [covers] names what the passage exercises, so the UI can tell the user why
     * they are reading it and the coverage check can be verified.
     */
    data class Prompt(
        val id: String,
        val instruction: String,
        val passage: String,
        val covers: String,
    )

    /**
     * The prompts, in order.
     *
     * Chosen to span the phonetic and prosodic range: sustained vowels and
     * consonants (articulation), a question (rising intonation), a statement
     * (falling intonation), and a longer passage (connected speech, where a voice's
     * rhythm actually lives).
     */
    val PROMPTS = listOf(
        Prompt(
            id = "articulation",
            instruction = "Read this slowly and clearly.",
            passage = "The morning light moved slowly across the quiet room, and the " +
                "river beyond the window carried its steady, unhurried sound.",
            covers = "articulation",
        ),
        Prompt(
            id = "question",
            instruction = "Read this as a question, letting your voice rise at the end.",
            passage = "Would you have believed it, if someone had told you the whole " +
                "story from the beginning?",
            covers = "rising intonation",
        ),
        Prompt(
            id = "statement",
            instruction = "Read this as a calm statement, letting your voice settle at the end.",
            passage = "There is a particular kind of silence that belongs to a house " +
                "at night, and it is not an empty one.",
            covers = "falling intonation",
        ),
        Prompt(
            id = "connected",
            instruction = "Read this at your natural pace, as if telling it to a friend.",
            passage = "She had kept the letter for years without ever deciding what to " +
                "do with it. It was not that she had forgotten; it was that the " +
                "decision itself had become a kind of company, and letting it go " +
                "would have meant being alone with the answer.",
            covers = "connected speech",
        ),
    )

    /** The total target duration across all prompts, in milliseconds. */
    const val TARGET_TOTAL_MS = 60_000L

    /**
     * Whether the recorded prompts cover enough of the script to train.
     *
     * Requires at least three of the four, so a user who cannot manage one prompt
     * is not blocked, but a user who recorded one sentence is.
     */
    fun hasEnoughCoverage(recordedIds: Set<String>): Boolean = recordedIds.size >= MIN_PROMPTS

    /** The prompts not yet recorded. */
    fun remaining(recordedIds: Set<String>): List<Prompt> =
        PROMPTS.filter { it.id !in recordedIds }

    const val MIN_PROMPTS = 3
}
