package com.arkadia.sonata

import android.content.Intent

/**
 * What a service intent means, decided without Android framework side effects so
 * the mapping is unit-testable.
 */
sealed class SpeechCommand {
    /** Speak [text]; an empty request is refused rather than dispatched. */
    data class Speak(val text: String, val label: String) : SpeechCommand()

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
        fun resolve(action: String?, text: String?, label: String?): SpeechCommand =
            when (action) {
                SpeechService.ACTION_SPEAK ->
                    if (text.isNullOrBlank()) None
                    else Speak(text = text, label = label ?: DEFAULT_LABEL)

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
        )
    }
}
