package com.arkadia.sonata

/**
 * Text ceilings for a synthesis request.
 *
 * [MAX_TTS_CHARS] mirrors the backend's own per-request ceiling: `kernel/tts.py::synthesize`
 * does `text.strip()[:5000]`. Sending more than that in one request silently loses
 * the tail server-side, so a single request is bounded to the same value.
 *
 * That is a per-*request* ceiling, not a ceiling on what the user may read.
 * [MAX_INPUT_CHARS] is the ceiling on accepted input, and it is far higher: a long
 * passage is split across several requests ([SpeechChunker.splitForStreaming]) so
 * the whole of it is spoken. The previous implementation conflated the two and
 * truncated the user's text at 5000 characters, which is why a long selection was
 * cut short — the defect a user reported as "there appears to be a length limit".
 */
const val MAX_TTS_CHARS = 5000

/**
 * The most text the app will accept for one read.
 *
 * Generous on purpose: a long article or a chapter is the normal case for a
 * read-aloud app. The bound exists only so a pathological paste cannot grow the
 * queue without limit, and exceeding it is reported rather than silently cut.
 */
const val MAX_INPUT_CHARS = 100_000

/** Outcome of validating a request's text. */
sealed class TextValidation {
    /** Nothing to speak after normalisation. */
    object Empty : TextValidation()

    /** Speakable text, unchanged. */
    data class Ready(val text: String) : TextValidation()

    /** Speakable text that was cut to [MAX_INPUT_CHARS]; [originalLength] was offered. */
    data class Truncated(val text: String, val originalLength: Int) : TextValidation()
}

object TextLimits {

    /**
     * Normalise and bound [raw].
     *
     * Whitespace-only input is treated as empty rather than sent to the backend,
     * which would answer `400 {"detail":"text is required"}`.
     *
     * Text up to [MAX_INPUT_CHARS] is accepted whole. It is the *caller* that splits
     * it into request-sized pieces, so a long passage is read in full rather than
     * truncated at the first request's ceiling.
     */
    fun validate(raw: CharSequence?): TextValidation {
        val normalised = raw?.toString()?.trim().orEmpty()
        if (normalised.isEmpty()) return TextValidation.Empty
        if (normalised.length <= MAX_INPUT_CHARS) return TextValidation.Ready(normalised)
        return TextValidation.Truncated(
            text = normalised.substring(0, MAX_INPUT_CHARS),
            originalLength = normalised.length,
        )
    }
}
