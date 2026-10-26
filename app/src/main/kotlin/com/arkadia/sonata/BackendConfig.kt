package com.arkadia.sonata

/**
 * The Arkadia voice gateway this build talks to.
 *
 * Shipped as a default so the user never has to enter a URL: the neural voice is
 * a property of the app, not a setting. [Prefs] still allows an override for
 * diagnostics, but nothing in the ordinary interface asks for one.
 *
 * This is a *client* default. Provider credentials are never shipped in the APK —
 * anything bundled in an Android app can be extracted — so synthesis keys live on
 * the backend, which is what this address reaches.
 */
object BackendConfig {

    /** The canonical Arkadia origin (ADR-016). */
    const val DEFAULT_URL = "https://arkadia-qzu4.onrender.com"

    /**
     * Voices offered in the picker, in display order.
     *
     * Every entry here was verified against the live gateway with a real synthesis
     * request, because the gateway advertises two voices it cannot synthesise.
     * `GET /api/tts/status` lists seven, and `GET /api/tts/voices` describes all
     * seven, but `george` and `ryan` both answer:
     *
     *     HTTP 502 {"detail":"TTS synthesis failed: All TTS engines failed.
     *               Edge: No audio was received."}
     *
     * reproducibly, on repeated attempts, while the other five return audio. A picker
     * that offers a voice the backend cannot speak produces a 502 the user reads as
     * "Sonata is broken", so the two are not offered. The note on each voice below
     * says what was verified rather than what the gateway claims.
     *
     * `aetheria` is deliberately absent: the gateway maps it to
     * `en-US-AriaNeural` — the same underlying voice as `aria` — so offering both
     * would be two labels for one voice. The interesting expressive behaviour belongs
     * in a style parameter, not in a duplicate name.
     */
    val VOICES = listOf(
        Voice("aria", "Aria", "Warm, expressive — US female"),
        Voice("jenny", "Jenny", "Natural, clear — US female"),
        Voice("sonia", "Sonia", "Eloquent, composed — British female"),
        Voice("christopher", "Christopher", "Rich, warm — US male"),
    )

    /**
     * The voice used when a stored preference names one that is no longer offered.
     *
     * A preference written by an earlier build can name `george`, `ryan`, or
     * `aetheria`; resolving it to the first entry keeps the app working instead of
     * sending a request the gateway will answer with 502.
     */
    val FALLBACK_VOICE_ID = VOICES.first().id

    data class Voice(val id: String, val name: String, val note: String) {
        /** Spinner label. */
        override fun toString(): String = "$name — $note"
    }

    fun indexOf(voiceId: String): Int =
        VOICES.indexOfFirst { it.id == voiceId }.coerceAtLeast(0)

    /**
     * Resolve a stored voice id to one this build offers.
     *
     * Returns [FALLBACK_VOICE_ID] for anything unknown, so a preference naming a
     * removed voice degrades to a working voice rather than a 502.
     */
    fun resolveVoiceId(stored: String?): String =
        VOICES.firstOrNull { it.id == stored }?.id ?: FALLBACK_VOICE_ID
}
