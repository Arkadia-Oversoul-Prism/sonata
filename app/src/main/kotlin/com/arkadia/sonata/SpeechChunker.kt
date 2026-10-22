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

    // ── Streaming split ───────────────────────────────────────────────────────

    /**
     * The first piece is deliberately short.
     *
     * Synthesis latency scales with the length of the request: measured against the
     * live gateway, a 340-character request returned in ~1.8 s while a single
     * 5000-character request took ~29 s. The user's complaint — "speech does not
     * begin immediately" — was that wait, because the old path asked for the whole
     * passage as one request and waited for all of it before a single sample played.
     *
     * A short first piece buys an early start; the longer pieces that follow are
     * fetched while the first is still playing, so the passage continues without a
     * gap.
     */
    const val STREAM_FIRST_LIMIT = 240

    /**
     * Later pieces are larger than the first because they are prefetched during
     * playback rather than waited on, and a larger request is a lower-overhead one.
     */
    const val STREAM_LATER_LIMIT = 900

    /**
     * Never cut the first piece below this, whatever boundary lies nearby: a
     * one-word opening utterance would be over almost before it began, and the
     * player would stall waiting for the piece after it.
     */
    const val STREAM_MIN_FIRST = 60

    /**
     * Split [text] into consecutive pieces for progressive playback: a short first
     * piece so audio can start early, then larger pieces.
     *
     * The same invariant as [split] holds and is the reason a caller may play the
     * pieces in order and claim the whole input was read: concatenating the result
     * reproduces [text] exactly, with no character dropped, added, duplicated or
     * reordered.
     */
    fun splitForStreaming(
        text: String,
        firstLimit: Int = STREAM_FIRST_LIMIT,
        laterLimit: Int = STREAM_LATER_LIMIT,
    ): List<String> {
        if (text.isEmpty()) return emptyList()

        val firstBound = firstLimit.coerceAtLeast(STREAM_MIN_FIRST)
        val laterBound = laterLimit.coerceAtLeast(MIN_LIMIT)
        if (text.length <= firstBound) return listOf(text)

        val pieces = ArrayList<String>(text.length / laterBound + 2)
        var start = 0

        // First piece: prefer a boundary, but do not fall below STREAM_MIN_FIRST, so
        // the opening utterance is a phrase rather than a fragment.
        val firstCut = breakPoint(text, start, start + firstBound)
            .coerceAtLeast(minOf(start + STREAM_MIN_FIRST, text.length))
        pieces += text.substring(start, firstCut)
        start = firstCut

        while (start < text.length) {
            val end = start + laterBound
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
}
