package com.arkadia.sonata.engine

/**
 * The offline tripwire.
 *
 * The engine has no network tier, so nothing should ever call this. It exists so
 * that if a network path is ever added it must route through here, and the test
 * that supplies a throwing probe fails the moment it is taken. The offline boundary
 * is asserted, not assumed.
 */
interface NetworkProbe {

    /** Called if — and only if — something attempts a network access. */
    fun onNetworkAccessAttempted(operation: String)
}

/**
 * The production probe: records nothing, because nothing should call it.
 *
 * A no-op rather than a throw, so a bug in a future network path degrades to a
 * silent no-op in production instead of crashing the app. The test suite supplies a
 * throwing probe, which is where the assertion belongs.
 */
object NoNetworkProbe : NetworkProbe {
    override fun onNetworkAccessAttempted(operation: String) = Unit
}

/**
 * A probe that fails loudly.
 *
 * Used by the tests to prove the engine never reaches a network path. If this is
 * ever called, the offline guarantee is broken and the test says so.
 */
class ThrowingNetworkProbe : NetworkProbe {
    override fun onNetworkAccessAttempted(operation: String) {
        throw IllegalStateException(
            "Sonata attempted a network access during '$operation'. " +
                "The app is offline-only; this is a defect.",
        )
    }
}
