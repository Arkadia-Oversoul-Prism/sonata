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

    /** Voices the backend advertises, in display order. */
    val VOICES = listOf(
        Voice("aria",    "Aria",        "Warm US female"),
        Voice("aetheria","Aetheria",    "Oracle — deep, calm"),
        Voice("jenny",   "Jenny",       "Clear US female"),
        Voice("sonia",   "Sonia",       "British female"),
        Voice("christopher", "Christopher", "US male"),
        Voice("george",  "George",      "British male"),
        Voice("ryan",    "Ryan",        "Casual US male"),
    )

    data class Voice(val id: String, val name: String, val note: String) {
        /** Spinner label. */
        override fun toString(): String = "$name — $note"
    }

    fun indexOf(voiceId: String): Int =
        VOICES.indexOfFirst { it.id == voiceId }.coerceAtLeast(0)
}
