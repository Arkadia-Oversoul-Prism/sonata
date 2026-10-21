package com.arkadia.sonata

/**
 * Text ceilings for a synthesis request.
 *
 * [MAX_TTS_CHARS] mirrors the backend's own ceiling: `kernel/tts.py::synthesize`
 * does `text.strip()[:5000]`. Sending more than that silently loses the tail
 * server-side, so the client truncates to the same bound and tells the caller it
 * did — a silent server-side truncation is not an honest outcome.
 */
const val MAX_TTS_CHARS = 5000

/** Outcome of validating a request's text. */
sealed class TextValidation {
    /** Nothing to speak after normalisation. */
    object Empty : TextValidation()

    /** Speakable text, unchanged. */
    data class Ready(val text: String) : TextValidation()

    /** Speakable text that was cut to [MAX_TTS_CHARS]; [originalLength] was offered. */
    data class Truncated(val text: String, val originalLength: Int) : TextValidation()
}

object TextLimits {

    /**
     * Normalise and bound [raw].
     *
     * Whitespace-only input is treated as empty rather than sent to the backend,
     * which would answer `400 {"detail":"text is required"}`.
     */
    fun validate(raw: CharSequence?): TextValidation {
        val normalised = raw?.toString()?.trim().orEmpty()
        if (normalised.isEmpty()) return TextValidation.Empty
        if (normalised.length <= MAX_TTS_CHARS) return TextValidation.Ready(normalised)
        return TextValidation.Truncated(
            text = normalised.substring(0, MAX_TTS_CHARS),
            originalLength = normalised.length,
        )
    }
}
