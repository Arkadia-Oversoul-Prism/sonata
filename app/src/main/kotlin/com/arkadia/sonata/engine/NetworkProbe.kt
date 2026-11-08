package com.arkadia.sonata.engine

/**
 * A seam that lets a test prove the engine makes no network call.
 *
 * The specification's offline boundary is the hardest property to keep true over
 * time, because it is a property of *absence*: nothing in the code says "do not
 * call the network", so nothing fails when someone adds a call. This interface
 * inverts that. The engine is constructed with a [NetworkProbe], and the test
 * supplies one that throws. If any code path reaches for the network, the test
 * fails loudly instead of the app quietly working only when online.
 *
 * The production implementation is [NoNetworkProbe], which is a no-op — the app
 * has no network client on the core path at all.
 */
interface NetworkProbe {

    /**
     * Called by the engine at the points where a network call *would* be made.
     *
     * A production build does nothing here. A test build throws.
     */
    fun onNetworkAccessAttempted(operation: String)
}

/** The production probe: the core path never attempts network access. */
object NoNetworkProbe : NetworkProbe {
    override fun onNetworkAccessAttempted(operation: String) = Unit
}

/**
 * A probe that fails the test if the engine reaches for the network.
 *
 * Used in `OfflineEngineTest` to assert the offline boundary. It is a real
 * assertion, not a comment: the test constructs the engine with this probe and
 * any network attempt throws.
 */
class ThrowingNetworkProbe : NetworkProbe {
    override fun onNetworkAccessAttempted(operation: String) {
        throw AssertionError("The engine attempted network access during: $operation")
    }
}
