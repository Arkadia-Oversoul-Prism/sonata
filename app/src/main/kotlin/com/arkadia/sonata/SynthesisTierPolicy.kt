package com.arkadia.sonata

/**
 * Which tier a read will actually use.
 *
 * The engine used to answer this inline — `preferOnline && url.isNotEmpty()` —
 * which asks what the *user* wants and what address is configured, but never
 * whether the build can open a socket at all. This build cannot: `INTERNET` is
 * deliberately absent from the manifest, so every read took the online tier first
 * and spent its retry budget and backoff on requests that could only fail.
 *
 * The cost was not only latency. The user saw an `UnknownHostException`, and the
 * screen reported "Fetching the next part of the passage…" for a fetch that was
 * never possible. A network error the app can never avoid is not an error; it is
 * a decision the app got wrong before it started.
 *
 * The three inputs are kept separate on purpose. `preferOnline` is a preference,
 * `backendUrl` is configuration, and `networkReachable` is a capability of the
 * build. Conflating the first and last is what produced the defect.
 */
object SynthesisTierPolicy {

    /**
     * The tier to use for a read.
     *
     * @param preferOnline what the user asked for
     * @param backendUrl the configured gateway; blank means offline-only
     * @param networkReachable whether this build can reach the network at all
     */
    fun choose(
        preferOnline: Boolean,
        backendUrl: String,
        networkReachable: Boolean,
    ): TtsEngineKind = when {
        !preferOnline -> TtsEngineKind.OFFLINE
        backendUrl.isBlank() -> TtsEngineKind.OFFLINE
        !networkReachable -> TtsEngineKind.OFFLINE
        else -> TtsEngineKind.ONLINE
    }
}
