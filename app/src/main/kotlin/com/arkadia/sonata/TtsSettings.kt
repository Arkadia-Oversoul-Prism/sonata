package com.arkadia.sonata

/**
 * The synthesis settings a request needs.
 *
 * A narrow interface rather than a concrete `Prefs` so the engine can be tested
 * without `SharedPreferences` or a `Context`; [Prefs] implements it.
 */
interface TtsSettings {
    /** Backend voice key; must be one the backend catalogue accepts. */
    val voice: String

    /** Speech rate, clamped to the backend's accepted range before sending. */
    val speed: Float

    /** Pitch; used by the offline engine only (the backend has no pitch parameter). */
    val pitch: Float

    /** Base URL of the Arkadia backend, or blank for offline-only operation. */
    val backendUrl: String

    /** Whether to attempt online synthesis when a backend URL is configured. */
    val preferOnline: Boolean
}
