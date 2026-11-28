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

    /**
     * Whether this build can reach the network at all.
     *
     * A capability, not a preference. [preferOnline] says what the user asked for;
     * this says what the build permits. They are separate because treating them as
     * one is what made the app attempt a socket it cannot open: the manifest
     * declares no `INTERNET` permission, so every read started with a request that
     * could only fail. See [SynthesisTierPolicy].
     */
    val networkReachable: Boolean
}
