package com.arkadia.sonata.narration

/**
 * What kind of text this is.
 *
 * The specification names seven context treatments, and the classification is what
 * selects one. It is a *heuristic* classification, not a model: the signals are
 * structural (a salutation, a date line, a list, quotation marks) and the result is
 * a suggestion the user can override. A classifier that guessed wrong and could not
 * be overridden would be worse than no classifier.
 */
enum class NarrationContext(
    val displayName: String,
    /** The style this context suggests. */
    val suggestedStyle: NarrationStyle,
) {
    /** A letter or email: salutation, sign-off, first person. */
    LETTER("Letter", NarrationStyle.WARM),

    /** A formal statement or announcement. */
    STATEMENT("Statement", NarrationStyle.CONFIDENT),

    /** Reflective prose: first person, abstract, unhurried. */
    REFLECTIVE("Reflective", NarrationStyle.REFLECTIVE),

    /** Narrative with characters and events. */
    STORYTELLING("Storytelling", NarrationStyle.STORYTELLING),

    /** Technical or reference material: lists, numbers, terms. */
    TECHNICAL("Technical", NarrationStyle.PROFESSIONAL),

    /** Dialogue-heavy text with quotation marks. */
    DIALOGUE("Dialogue", NarrationStyle.CONVERSATIONAL),

    /** Motivational or persuasive prose. */
    INSPIRATIONAL("Inspirational", NarrationStyle.DRAMATIC),

    /** Nothing distinctive was found. */
    NEUTRAL("Neutral", NarrationStyle.NATURAL),
}

/**
 * Classifies text into a [NarrationContext].
 *
 * Pure and total, so the classification is testable and never throws. The signals
 * are counted and the strongest wins, rather than the first match, so a letter that
 * happens to contain a list is still a letter.
 */
object ContextClassifier {

    /** Salutations that mark a letter. */
    private val SALUTATIONS = listOf(
        "dear ", "hello ", "hi ", "to whom it may concern", "good morning", "good evening",
    )

    /** Sign-offs that mark a letter. */
    private val SIGNOFFS = listOf(
        "sincerely", "regards", "yours truly", "yours faithfully", "best wishes",
        "warmly", "kind regards", "with love",
    )

    /** Words that mark reflective prose. */
    private val REFLECTIVE_MARKERS = listOf(
        "i remember", "i think", "i wonder", "perhaps", "maybe", "it seems",
        "looking back", "i have often", "i suppose",
    )

    /** Words that mark narrative. */
    private val STORY_MARKERS = listOf(
        "once upon", "one day", "she said", "he said", "they said", "suddenly",
        "and then", "long ago", "that night", "the next morning",
    )

    /** Words that mark technical material. */
    private val TECHNICAL_MARKERS = listOf(
        "figure", "table", "appendix", "section", "definition", "parameter",
        "configuration", "specification", "requirement", "algorithm",
    )

    /** Words that mark persuasive prose. */
    private val INSPIRATIONAL_MARKERS = listOf(
        "you can", "believe", "imagine", "together we", "the future", "possible",
        "dream", "courage", "change the world",
    )

    /**
     * Classify [text].
     *
     * Counts the signals for each context and returns the strongest. Ties resolve
     * toward [NarrationContext.NEUTRAL], because a weak signal is not a
     * classification.
     */
    fun classify(text: String): NarrationContext {
        if (text.isBlank()) return NarrationContext.NEUTRAL
        val lower = text.lowercase()

        val scores = mutableMapOf<NarrationContext, Int>()

        // A letter is the strongest structural signal: a salutation or a sign-off
        // is almost never present in other prose.
        var letter = 0
        if (SALUTATIONS.any { lower.trimStart().startsWith(it) }) letter += 3
        if (SIGNOFFS.any { lower.contains(it) }) letter += 2
        scores[NarrationContext.LETTER] = letter

        scores[NarrationContext.REFLECTIVE] = REFLECTIVE_MARKERS.count { lower.contains(it) }
        scores[NarrationContext.STORYTELLING] = STORY_MARKERS.count { lower.contains(it) }
        scores[NarrationContext.TECHNICAL] = TECHNICAL_MARKERS.count { lower.contains(it) } +
            listMarkerCount(text)
        scores[NarrationContext.INSPIRATIONAL] = INSPIRATIONAL_MARKERS.count { lower.contains(it) }

        // Dialogue: quotation marks around speech, at least twice.
        val quotePairs = lower.count { it == '"' } / 2
        scores[NarrationContext.DIALOGUE] = if (quotePairs >= 2) quotePairs else 0

        // A statement: formal, third person, no other signal.
        val statement = if (lower.contains("hereby") || lower.contains("announce") ||
            lower.contains("notice is") || lower.contains("be advised")
        ) 2 else 0
        scores[NarrationContext.STATEMENT] = statement

        val best = scores.maxByOrNull { it.value } ?: return NarrationContext.NEUTRAL
        // A single weak signal is not a classification.
        return if (best.value < MIN_SCORE) NarrationContext.NEUTRAL else best.key
    }

    /** How many list markers the text has. */
    private fun listMarkerCount(text: String): Int =
        text.lines().count { line ->
            val t = line.trimStart()
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") ||
                t.matches(Regex("\\d+[.)]\\s+.*"))
        }

    /** The score below which a classification is not made. */
    const val MIN_SCORE = 2
}
