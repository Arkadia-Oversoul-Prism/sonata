package com.arkadia.sonata.narration

import com.arkadia.sonata.canvas.TextNormalizer

/**
 * The Narration Intelligence pipeline.
 *
 * Six stages, in the specification's order, each a pure function so the whole
 * pipeline is testable without a device, a model or a coroutine:
 *
 *  - **N1 Text understanding** — split into paragraphs and sentences.
 *  - **N2 Context analysis** — classify the text into a [NarrationContext].
 *  - **N3 Emotional articulation** — choose the style (the user's, or the context's
 *    suggestion).
 *  - **N4 Prosody preparation** — place pauses and build the segments.
 *  - **N5 Speech generation** — the engine speaks the segments (not here; this
 *    stage is the plan's consumer).
 *  - **N6 Alignment** — the plan's character offsets are what the canvas aligns to.
 *
 * The pipeline **never claims expressiveness the model cannot produce**. Every
 * value it emits is a rate, a pause or a pitch offset — the three things the engine
 * can actually vary. There is no "emotion" field, because there is no emotion
 * parameter to set.
 */
object NarrationIntelligence {

    /**
     * Build a [ProsodyPlan] for [cleaned].
     *
     * [style] overrides the context's suggestion when the user has chosen one;
     * passing null lets the context decide. The user's choice always wins, because a
     * classification is a suggestion and the user is the authority on their own
     * text.
     */
    fun plan(cleaned: String, style: NarrationStyle? = null): ProsodyPlan {
        // N1 — text understanding.
        val paragraphs = TextNormalizer.paragraphs(cleaned)
        if (paragraphs.isEmpty()) {
            return ProsodyPlan(cleaned, style ?: NarrationStyle.DEFAULT, NarrationContext.NEUTRAL, emptyList(), emptyList())
        }

        // N2 — context analysis.
        val context = ContextClassifier.classify(cleaned)

        // N3 — emotional articulation: the user's style, or the context's suggestion.
        val chosen = style ?: context.suggestedStyle

        // N4 — prosody preparation.
        val pauses = placePauses(cleaned, chosen)
        val segments = buildSegments(cleaned, pauses, chosen)

        return ProsodyPlan(
            text = cleaned,
            style = chosen,
            context = context,
            segments = segments,
            pauses = pauses,
        )
    }

    /**
     * Place the pauses.
     *
     * The base durations are the ones a reader naturally takes, scaled by the
     * style's [NarrationStyle.pauseScale]. A paragraph break is always longer than a
     * sentence break, and a sentence break always longer than a clause break — the
     * hierarchy is what makes the delivery sound structured rather than merely
     * slowed.
     */
    fun placePauses(cleaned: String, style: NarrationStyle): List<Pause> {
        val pauses = ArrayList<Pause>()
        val scale = style.pauseScale

        var i = 0
        while (i < cleaned.length) {
            val ch = cleaned[i]
            when {
                // A paragraph break: two or more newlines.
                ch == '\n' && cleaned.getOrNull(i + 1) == '\n' -> {
                    pauses += Pause(
                        afterChar = i,
                        durationMs = (PARAGRAPH_MS * scale).toLong(),
                        kind = PauseKind.PARAGRAPH,
                    )
                    // Skip the run of newlines so one break is one pause.
                    while (i < cleaned.length && cleaned[i] == '\n') i++
                    continue
                }

                ch == '.' || ch == '!' || ch == '?' || ch == '\u2026' -> {
                    // A period after a single letter is an initial, not a sentence end.
                    if (!(ch == '.' && isInitial(cleaned, i))) {
                        pauses += Pause(
                            afterChar = i + 1,
                            durationMs = (SENTENCE_MS * scale).toLong(),
                            kind = PauseKind.SENTENCE,
                        )
                    }
                }

                ch == ':' || ch == ';' -> {
                    pauses += Pause(
                        afterChar = i + 1,
                        durationMs = (INTRODUCTORY_MS * scale).toLong(),
                        kind = PauseKind.INTRODUCTORY,
                    )
                }

                ch == ',' -> {
                    pauses += Pause(
                        afterChar = i + 1,
                        durationMs = (CLAUSE_MS * scale).toLong(),
                        kind = PauseKind.CLAUSE,
                    )
                }
            }
            i++
        }
        return pauses
    }

    /**
     * Build the segments.
     *
     * A segment runs from the end of one pause to the end of the next, so the
     * segments and the pauses together cover the text exactly. The rate varies
     * slightly across a paragraph — the first sentence a little slower, the last a
     * little slower still — which is what makes a paragraph sound like a paragraph
     * rather than a list of sentences.
     */
    fun buildSegments(
        cleaned: String,
        pauses: List<Pause>,
        style: NarrationStyle,
    ): List<NarrationSegment> {
        if (cleaned.isBlank()) return emptyList()
        val boundaries = pauses.map { it.afterChar }.filter { it in 1..cleaned.length }.distinct().sorted()
        val segments = ArrayList<NarrationSegment>(boundaries.size + 1)
        var start = 0
        for ((index, boundary) in boundaries.withIndex()) {
            if (boundary <= start) continue
            val pause = pauses.firstOrNull { it.afterChar == boundary }
            segments += NarrationSegment(
                start = start,
                end = boundary,
                rateScale = style.rateScale * segmentRateFactor(index, boundaries.size),
                pitchSemitones = style.pitchSemitones,
                pauseAfter = pause,
            )
            start = boundary
        }
        if (start < cleaned.length) {
            segments += NarrationSegment(
                start = start,
                end = cleaned.length,
                rateScale = style.rateScale,
                pitchSemitones = style.pitchSemitones,
                pauseAfter = null,
            )
        }
        return segments
    }

    /**
     * A small rate variation across a paragraph.
     *
     * The first and last segments of a paragraph are slightly slower, which is the
     * shape of natural speech: a reader eases in and eases out. The variation is
     * small (a few percent) because a large one sounds like a fault.
     */
    private fun segmentRateFactor(index: Int, total: Int): Float = when {
        total <= 2 -> 1.0f
        index == 0 -> 0.97f
        index == total - 1 -> 0.96f
        else -> 1.0f
    }

    /** Whether the character before [periodIndex] is a lone letter. */
    private fun isInitial(text: String, periodIndex: Int): Boolean {
        if (periodIndex < 1) return false
        val before = text[periodIndex - 1]
        if (!before.isLetter()) return false
        val beforeThat = text.getOrNull(periodIndex - 2)
        return beforeThat == null || beforeThat.isWhitespace()
    }

    // Base pause durations, in milliseconds, before the style's scale is applied.
    const val CLAUSE_MS = 180L
    const val INTRODUCTORY_MS = 260L
    const val SENTENCE_MS = 420L
    const val PARAGRAPH_MS = 800L
}
