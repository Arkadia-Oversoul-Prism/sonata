package com.arkadia.sonata

/** Which tier is (or was) speaking. */
enum class TtsEngineKind { ONLINE, OFFLINE }

/**
 * Honest playback state.
 *
 * The two tiers resume in genuinely different ways, so the state keeps the two
 * positions distinct rather than pretending one number means both:
 *
 *  - [Online] — `MediaPlayer` on a downloaded file. Position is an audio offset
 *    in milliseconds and `pause()`/`resume()` are real: the same decoded stream
 *    continues from where it stopped.
 *  - [Offline] — Android `TextToSpeech`. It has no pause primitive, so pausing
 *    stops the utterance and records the character offset last reached (from
 *    `UtteranceProgressListener.onRangeStart`, API 26+; `minSdk` is 29).
 *    Resuming re-synthesises the remainder from that character. That is a real
 *    content resume, and it is a re-synthesis seam — it is not sample-accurate,
 *    and the UI must not imply that it is.
 */
sealed class PlaybackState {

    abstract val engine: TtsEngineKind?

    object Idle : PlaybackState() {
        override val engine: TtsEngineKind? = null
    }

    data class Online(
        val text: String,
        val positionMs: Int,
        val paused: Boolean,
    ) : PlaybackState() {
        override val engine: TtsEngineKind = TtsEngineKind.ONLINE
    }

    data class Offline(
        val text: String,
        val charOffset: Int,
        val paused: Boolean,
    ) : PlaybackState() {
        override val engine: TtsEngineKind = TtsEngineKind.OFFLINE
    }

    val isPlaying: Boolean
        get() = when (this) {
            is Online -> !paused
            is Offline -> !paused
            Idle -> false
        }

    val isPaused: Boolean
        get() = when (this) {
            is Online -> paused
            is Offline -> paused
            Idle -> false
        }

    /**
     * True only when resuming continues the utterance rather than restarting it.
     * A paused [Offline] utterance is resumable because the character offset is
     * known; an utterance that was stopped is not paused and cannot resume.
     */
    val isResumable: Boolean
        get() = isPaused

    /** The still-unspoken remainder of an [Offline] utterance, or null if none. */
    fun remainingText(): String? {
        val offline = this as? Offline ?: return null
        val from = offline.charOffset.coerceIn(0, offline.text.length)
        return offline.text.substring(from).ifBlank { null }
    }
}
