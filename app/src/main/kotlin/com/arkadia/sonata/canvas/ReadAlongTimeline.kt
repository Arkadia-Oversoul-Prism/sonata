package com.arkadia.sonata.canvas

/**
 * A span of the cleaned text, with the audio time it is spoken over.
 *
 * The canvas highlights a span while the audio plays it. Sentence-level spans are
 * always available because a sentence boundary is a property of the text; word-level
 * spans are available only when the engine reports reliable alignment, which is why
 * [confidence] exists.
 */
data class TextSpan(
    /** Start offset in the cleaned text. */
    val start: Int,
    /** End offset (exclusive) in the cleaned text. */
    val end: Int,
    /** Start time in the audio, milliseconds. */
    val startMs: Long,
    /** End time in the audio, milliseconds. */
    val endMs: Long,
    /** Whether this span is a sentence or a word. */
    val granularity: Granularity,
    /**
     * How reliable the alignment is, 0..1.
     *
     * A sentence span is 1.0: the boundary is in the text. A word span is whatever
     * the engine reported, and the canvas only highlights words above
     * [ReadAlongTimeline.WORD_CONFIDENCE_FLOOR] — the specification's
     * "confidence-gated word highlighting". Highlighting a word the engine is not
     * sure about is worse than highlighting the sentence, because a wrong highlight
     * is a visible lie.
     */
    val confidence: Float = 1f,
) {
    enum class Granularity { SENTENCE, WORD }

    /** Whether [positionMs] falls inside this span. */
    fun contains(positionMs: Long): Boolean = positionMs in startMs until endMs
}

/**
 * Maps an audio position to a span of text.
 *
 * The specification requires **sentence-level sync with confidence-gated word
 * highlighting**, and this is that contract. It is pure, so the mapping is testable
 * exhaustively — every millisecond of a timeline can be checked to map to exactly
 * one span, which is the property that keeps the highlight from flickering or
 * sticking.
 */
class ReadAlongTimeline(
    /** The cleaned text the spans index into. */
    val text: String,
    /** Sentence spans, in order, covering the whole text. */
    val sentences: List<TextSpan>,
    /** Word spans, in order, where the engine reported reliable alignment. */
    val words: List<TextSpan> = emptyList(),
) {

    /**
     * The span to highlight at [positionMs].
     *
     * Prefers a word span when one is available **and** confident enough; otherwise
     * falls back to the sentence. The fallback is the point: a timeline with no
     * reliable word alignment still highlights correctly at sentence level, so the
     * feature degrades rather than disappearing.
     */
    fun spanAt(positionMs: Long): TextSpan? {
        val word = words.firstOrNull {
            it.confidence >= WORD_CONFIDENCE_FLOOR && it.contains(positionMs)
        }
        if (word != null) return word
        return sentences.firstOrNull { it.contains(positionMs) }
            ?: sentences.lastOrNull()?.takeIf { positionMs >= it.endMs }
    }

    /** The sentence containing [positionMs], regardless of word confidence. */
    fun sentenceAt(positionMs: Long): TextSpan? =
        sentences.firstOrNull { it.contains(positionMs) }
            ?: sentences.lastOrNull()?.takeIf { positionMs >= it.endMs }

    /** The text of the span at [positionMs], or null. */
    fun textAt(positionMs: Long): String? {
        val span = spanAt(positionMs) ?: return null
        return text.substring(span.start.coerceIn(0, text.length), span.end.coerceIn(0, text.length))
    }

    /** Total duration of the timeline, milliseconds. */
    val durationMs: Long get() = sentences.lastOrNull()?.endMs ?: 0L

    companion object {
        /**
         * The confidence below which a word span is not highlighted.
         *
         * 0.7 is high enough that a wrong highlight is unlikely and low enough that
         * a good engine's alignment is used. Below it, the sentence highlight is
         * shown instead.
         */
        const val WORD_CONFIDENCE_FLOOR = 0.7f
    }
}

/**
 * Builds a [ReadAlongTimeline] from cleaned text and a duration.
 *
 * The sentence spans are derived from the text itself, so they are always
 * available; the word spans are supplied by the engine and are optional. The
 * allocation of time across sentences is proportional to their length, which is a
 * good approximation for speech and is exact enough for a sentence highlight.
 */
object TimelineBuilder {

    /**
     * Build a timeline for [cleaned] over [durationMs].
     *
     * [wordSpans] are the engine's alignment, if it produced any. They are used
     * as-is; the builder does not invent word timings, because an invented timing
     * is a highlight that is confidently wrong.
     */
    fun build(
        cleaned: String,
        durationMs: Long,
        wordSpans: List<TextSpan> = emptyList(),
    ): ReadAlongTimeline {
        val sentences = sentenceSpans(cleaned, durationMs)
        return ReadAlongTimeline(cleaned, sentences, wordSpans)
    }

    /**
     * Sentence spans, with time allocated proportionally to length.
     *
     * The last sentence is given the remainder of the duration rather than its
     * proportional share, so rounding cannot leave a gap at the end of the audio
     * where no span is highlighted.
     */
    fun sentenceSpans(cleaned: String, durationMs: Long): List<TextSpan> {
        val ranges = sentenceRanges(cleaned)
        if (ranges.isEmpty()) return emptyList()
        val totalChars = ranges.sumOf { it.second - it.first }.coerceAtLeast(1)
        val spans = ArrayList<TextSpan>(ranges.size)
        var elapsed = 0L
        for ((index, range) in ranges.withIndex()) {
            val (start, end) = range
            val isLast = index == ranges.lastIndex
            val spanMs = if (isLast) {
                (durationMs - elapsed).coerceAtLeast(0)
            } else {
                ((end - start).toLong() * durationMs / totalChars)
            }
            val endMs = if (isLast) durationMs else elapsed + spanMs
            spans += TextSpan(
                start = start,
                end = end,
                startMs = elapsed,
                endMs = endMs,
                granularity = TextSpan.Granularity.SENTENCE,
            )
            elapsed = endMs
        }
        return spans
    }

    /**
     * The character ranges of the sentences in [text].
     *
     * A sentence ends at `.`, `!`, `?` or `…` followed by whitespace or the end of
     * the text. A single letter followed by a period is treated as an initial, not
     * a sentence end — the conservative rule that keeps "J. R. R. Tolkien" from
     * becoming three sentences.
     */
    fun sentenceRanges(text: String): List<Pair<Int, Int>> {
        val ranges = ArrayList<Pair<Int, Int>>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val isTerminator = ch == '.' || ch == '!' || ch == '?' || ch == '\u2026'
            if (isTerminator) {
                val next = text.getOrNull(i + 1)
                val atEnd = next == null
                val followedBySpace = next != null && next.isWhitespace()
                // A period after a single letter is an initial, not a sentence end.
                val isInitial = ch == '.' && isSingleLetterBefore(text, i)
                if ((atEnd || followedBySpace) && !isInitial) {
                    val end = i + 1
                    if (end > start) ranges += start to end
                    start = end
                }
            }
            i++
        }
        if (start < text.length) {
            val tail = text.substring(start)
            if (tail.isNotBlank()) ranges += start to text.length
        }
        return ranges
    }

    /** Whether the character before [periodIndex] is a lone letter. */
    private fun isSingleLetterBefore(text: String, periodIndex: Int): Boolean {
        if (periodIndex < 1) return false
        val before = text[periodIndex - 1]
        if (!before.isLetter()) return false
        val beforeThat = text.getOrNull(periodIndex - 2)
        return beforeThat == null || beforeThat.isWhitespace()
    }
}
