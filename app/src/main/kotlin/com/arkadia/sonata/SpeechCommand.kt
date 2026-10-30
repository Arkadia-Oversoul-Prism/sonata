package com.arkadia.sonata

import android.content.Intent

/**
 * What a service intent means, decided without Android framework side effects so
 * the mapping is unit-testable.
 */
sealed class SpeechCommand {
    /**
     * Speak [text]; an empty request is refused rather than dispatched.
     *
     * [fromChar] is where to start inside [text]. It exists so a passage can be
     * resumed where the user left it instead of restarting from the top.
     */
    data class Speak(
        val text: String,
        val label: String,
        val fromChar: Int = 0,
    ) : SpeechCommand()

    /**
     * Speak a short sample of [voiceId] and stop.
     *
     * Deliberately not a [Speak]: it must not start a read, must not be treated as
     * the passage, and must not be resumed or carried on. The user is choosing a
     * voice, not listening to their text, so conflating the two would overwrite the
     * reading position with a sample.
     */
    data class Preview(val voiceId: String, val text: String) : SpeechCommand()

    object Pause : SpeechCommand()
    object Resume : SpeechCommand()
    object Stop : SpeechCommand()

    /** No action, or a SPEAK carrying no speakable text. */
    object None : SpeechCommand()

    companion object {
        const val DEFAULT_LABEL = "SONATA"

        /**
         * Resolve raw intent parts to a command.
         *
         * Pure, so the mapping is unit-testable without constructing an
         * `android.content.Intent`. A SPEAK with missing, empty, or
         * whitespace-only text resolves to [None]: the original code returned
         * `START_NOT_STICKY` early, leaving the foreground notification already
         * promoted and the service alive with no work — a stale-service state.
         */
        fun resolve(
            action: String?,
            text: String?,
            label: String?,
            fromChar: Int = 0,
            voiceId: String? = null,
        ): SpeechCommand =
            when (action) {
                SpeechService.ACTION_SPEAK ->
                    if (text.isNullOrBlank()) None
                    else Speak(
                        text = text,
                        label = label ?: DEFAULT_LABEL,
                        // A negative offset is meaningless; treat it as the start.
                        fromChar = fromChar.coerceAtLeast(0),
                    )

                SpeechService.ACTION_PREVIEW ->
                    if (text.isNullOrBlank() || voiceId.isNullOrBlank()) None
                    else Preview(voiceId = voiceId, text = text)

                SpeechService.ACTION_PAUSE -> Pause
                SpeechService.ACTION_RESUME -> Resume
                SpeechService.ACTION_STOP -> Stop
                else -> None
            }

        /** Resolve [intent] to a command. */
        fun from(intent: Intent?): SpeechCommand = resolve(
            action = intent?.action,
            text = intent?.getStringExtra(SpeechService.EXTRA_TEXT),
            label = intent?.getStringExtra(SpeechService.EXTRA_LABEL),
            fromChar = intent?.getIntExtra(SpeechService.EXTRA_FROM_CHAR, 0) ?: 0,
            voiceId = intent?.getStringExtra(SpeechService.EXTRA_VOICE_ID),
        )
    }
}
