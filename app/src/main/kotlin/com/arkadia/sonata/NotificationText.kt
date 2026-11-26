package com.arkadia.sonata

/**
 * The text of the playback notification.
 *
 * Extracted from [SpeechService] because it is the part that can be wrong in a
 * way the user notices, and because the service itself needs the Android
 * framework to exist. As a pure function it is testable on the JVM, which is
 * what lets the progress line be pinned by a test rather than by inspection.
 *
 * **Why this exists at all.** The notification used to be built only when
 * playback started, paused, resumed or finished. Its progress line was therefore
 * computed once, at the moment the read began, and then went stale: a listener
 * who opened the shade ten minutes into a long read saw "Part 1 of 12 · 0%" for
 * the whole read. The line was not wrong when it was written — it was simply
 * never rewritten. [SpeechService] now refreshes it on a ticker, and this
 * function is what the ticker calls.
 */
object NotificationText {

    /** How much of the passage preview to show before eliding. */
    const val PREVIEW_CHARS = 72

    /**
     * The notification body: preview, then position, then which voice is speaking.
     *
     * Each part is optional and blank parts are dropped rather than leaving
     * separators behind, so a paused read with no position does not render as
     * "Some text ·  · Offline voice".
     */
    fun body(
        text: String,
        spokenChars: Int,
        totalChars: Int,
        segmentIndex: Int,
        segmentCount: Int,
        buffering: Boolean,
        playing: Boolean,
        engine: TtsEngineKind?,
        fellBackOffline: Boolean,
    ): String {
        val preview = preview(text)
        val position = position(
            spokenChars = spokenChars,
            totalChars = totalChars,
            segmentIndex = segmentIndex,
            segmentCount = segmentCount,
            buffering = buffering,
            playing = playing,
        )
        val voice = voice(engine, fellBackOffline)
        return listOfNotNull(preview, position, voice).joinToString(" · ")
    }

    /** The passage preview, whitespace-collapsed and elided at [PREVIEW_CHARS]. */
    fun preview(text: String): String? {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return null
        return if (flat.length <= PREVIEW_CHARS) flat else flat.take(PREVIEW_CHARS) + "…"
    }

    /**
     * The position line, or null when there is nothing honest to say.
     *
     * "Buffering…" wins over a percentage because the percentage would be a
     * number that is not currently advancing — the read is waiting on the next
     * piece, and saying so is more useful than a frozen figure.
     *
     * A percentage is only shown while playing. A paused read has a position, but
     * presenting it as live progress would imply it is still moving.
     */
    fun position(
        spokenChars: Int,
        totalChars: Int,
        segmentIndex: Int,
        segmentCount: Int,
        buffering: Boolean,
        playing: Boolean,
    ): String? {
        if (buffering) return "Buffering…"
        if (!playing || totalChars <= 0) return null
        val percent = ((spokenChars.toFloat() / totalChars) * 100).toInt().coerceIn(0, 100)
        return if (segmentCount > 1) "Part $segmentIndex of $segmentCount · $percent%"
        else "$percent%"
    }

    /** Which voice is speaking, and whether it is a fallback. */
    fun voice(engine: TtsEngineKind?, fellBackOffline: Boolean): String? = when (engine) {
        TtsEngineKind.ONLINE -> "Neural voice"
        TtsEngineKind.OFFLINE ->
            if (fellBackOffline) "Offline voice (backend unavailable)" else "Offline voice"
        null -> null
    }
}
