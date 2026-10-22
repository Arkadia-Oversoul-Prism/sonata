package com.arkadia.sonata

/**
 * Splits text into pieces the platform `TextToSpeech` engine will accept.
 *
 * `TextToSpeech.speak` refuses input longer than
 * `TextToSpeech.getMaxSpeechInputLength()` (4000 on every shipping Android
 * version) and reports `ERROR`. The client allowed [MAX_TTS_CHARS] = 5000, so a
 * long passage — a paragraph, which is the normal case for a read-aloud app —
 * silently failed to start while a short word worked. That asymmetry is exactly
 * what a user reported: "hello" played, a paragraph did not.
 *
 * Splitting happens on the strongest boundary available so the seams fall at
 * paragraph and sentence ends rather than mid-word. A hard cut is the last
 * resort, and it is still a cut the caller can report honestly.
 */
object SpeechChunker {

    /** Used when the platform limit cannot be read. Matches the documented value. */
    const val PLATFORM_LIMIT = 4000

    /** Never split below this, whatever the platform reports. */
    const val MIN_LIMIT = 200

    private val STRONG = charArrayOf('\n', '.', '!', '?', '\u2026')
    private val WEAK = charArrayOf(';', ':', ',', ' ', '\t')

    /**
     * Split [text] into consecutive pieces each at most [limit] characters.
     *
     * Concatenating the result reproduces [text] exactly — no character is
     * dropped, added or reordered. That invariant is what lets a caller speak the
     * pieces in order and still have read the whole input.
     */
    fun split(text: String, limit: Int = PLATFORM_LIMIT): List<String> {
        val bound = limit.coerceAtLeast(MIN_LIMIT)
        if (text.isEmpty()) return emptyList()
        if (text.length <= bound) return listOf(text)

        val pieces = ArrayList<String>(text.length / bound + 1)
        var start = 0
        while (start < text.length) {
            val end = start + bound
            if (end >= text.length) {
                pieces += text.substring(start)
                break
            }
            val cut = breakPoint(text, start, end)
            pieces += text.substring(start, cut)
            start = cut
        }
        return pieces
    }

    /**
     * The index to cut at, strictly greater than [start] and at most [end], so
     * every iteration makes progress and the loop cannot spin.
     */
    private fun breakPoint(text: String, start: Int, end: Int): Int {
        for (i in end downTo start + 1) {
            if (text[i - 1] in STRONG) return i
        }
        for (i in end downTo start + 1) {
            if (text[i - 1] in WEAK) return i
        }
        return end
    }
}
