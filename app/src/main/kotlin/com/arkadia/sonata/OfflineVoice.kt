package com.arkadia.sonata

/**
 * Offline speech via the platform `TextToSpeech` engine.
 *
 * The platform engine has no pause primitive. Pausing therefore stops the
 * utterance and records the character offset last reached; resuming speaks the
 * remainder from that offset. That is a genuine content resume, and it is a
 * re-synthesis seam — it is not sample-accurate, and the UI says so.
 */
interface OfflineVoice {

    /** Whether the platform engine finished initialising successfully. */
    fun isReady(): Boolean

    /**
     * Suspend until the engine can speak, returning `false` if it still cannot
     * after [timeoutMs].
     *
     * [isReady] alone cannot decide whether speech is possible: the platform
     * engine completes initialisation in a callback, so it reports `false` for a
     * real interval after construction. A cold start — a PROCESS_TEXT selection
     * starting the service on demand — reaches the fallback path before the engine
     * is up, so consulting [isReady] there would refuse to speak at exactly the
     * moment the fallback exists to guarantee the user hears something.
     */
    suspend fun awaitReady(timeoutMs: Long): Boolean

    /**
     * Speak [text] from [fromChar], reporting the character offset reached as it
     * progresses so a later pause can resume from a known point.
     *
     * [onDone] fires when the utterance completes; [onError] with a
     * user-presentable reason otherwise.
     */
    fun speak(
        text: String,
        fromChar: Int,
        speed: Float,
        pitch: Float,
        onStart: () -> Unit,
        onRange: (Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    )

    /** Abort the current utterance. */
    fun stop()
}
