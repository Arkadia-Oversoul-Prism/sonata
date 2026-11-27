package com.arkadia.sonata

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tier decision, as a decision rather than as a side effect of a failed
 * request.
 *
 * The defect this pins: the engine chose the online tier whenever the preference
 * was on and an address was configured, without asking whether the build can open
 * a socket. In the shipped build it cannot — `INTERNET` is deliberately absent — so
 * every read spent its retries and backoff on requests that could only fail, then
 * fell back. The user saw an `UnknownHostException` and a "Fetching the next part"
 * state for a fetch that was never possible.
 *
 * These tests are pure JVM on purpose: the decision is the thing that was wrong,
 * and it can be stated without an engine, a device, or a socket.
 */
class SynthesisTierPolicyTest {

    @Test
    fun `the shipped build reads offline even though the preference asks for online`() {
        // The exact production configuration: preferEdgeTts defaults true, a backend
        // URL is shipped, and the manifest declares no INTERNET permission.
        val tier = SynthesisTierPolicy.choose(
            preferOnline = true,
            backendUrl = BackendConfig.DEFAULT_URL,
            networkReachable = false,
        )

        assertEquals(TtsEngineKind.OFFLINE, tier)
    }

    @Test
    fun `an unreachable build is not talked out of the offline tier by a preference`() {
        // Both settings say "go online". The capability says it cannot. The
        // capability wins, because it is a fact and the others are requests.
        val tier = SynthesisTierPolicy.choose(
            preferOnline = true,
            backendUrl = "https://arkadia-qzu4.onrender.com",
            networkReachable = false,
        )

        assertEquals(TtsEngineKind.OFFLINE, tier)
    }

    @Test
    fun `a reachable build with a configured gateway uses the online tier`() {
        // The negative control. Without this, a policy that always answered OFFLINE
        // would pass every test above while deleting the neural tier entirely.
        val tier = SynthesisTierPolicy.choose(
            preferOnline = true,
            backendUrl = "https://arkadia-qzu4.onrender.com",
            networkReachable = true,
        )

        assertEquals(TtsEngineKind.ONLINE, tier)
    }

    @Test
    fun `a user who turned the online voice off stays offline on a reachable build`() {
        val tier = SynthesisTierPolicy.choose(
            preferOnline = false,
            backendUrl = "https://arkadia-qzu4.onrender.com",
            networkReachable = true,
        )

        assertEquals(TtsEngineKind.OFFLINE, tier)
    }

    @Test
    fun `a blank gateway stays offline on a reachable build`() {
        val tier = SynthesisTierPolicy.choose(
            preferOnline = true,
            backendUrl = "",
            networkReachable = true,
        )

        assertEquals(TtsEngineKind.OFFLINE, tier)
    }

    @Test
    fun `a gateway of spaces is not a configured gateway`() {
        // `isBlank`, not `isEmpty`: an address the user cleared to spaces must not be
        // treated as configured and sent to a transport.
        val tier = SynthesisTierPolicy.choose(
            preferOnline = true,
            backendUrl = "   ",
            networkReachable = true,
        )

        assertEquals(TtsEngineKind.OFFLINE, tier)
    }
}
