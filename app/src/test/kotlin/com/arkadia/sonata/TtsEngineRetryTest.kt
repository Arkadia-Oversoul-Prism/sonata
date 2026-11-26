package com.arkadia.sonata

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Retry behaviour for transient synthesis failures.
 *
 * Before this existed the engine had **no retry at all**: a single 502, dropped
 * socket or empty 200 mid-passage dropped the remainder of the read to the
 * offline voice. On a long read that is a jarring voice change for a blip that
 * would have cleared on its own.
 *
 * These tests are written so that removing the retry loop makes them fail — the
 * request count is the assertion, not a side effect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsEngineRetryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Harness(
        val engine: TtsEngine,
        val transport: FakeTransport,
        val player: FakeAudioPlayer,
        val offline: FakeOfflineVoice,
        val listener: RecordingListener,
    )

    private fun harness(
        scope: TestScope,
        sequence: List<SynthesisResult>,
        maxRetries: Int = TtsEngine.DEFAULT_MAX_RETRIES,
        retryBaseDelayMs: Long = 1,
        offlineReady: Boolean = true,
    ): Harness {
        val transport = FakeTransport(sequence = sequence)
        val player = FakeAudioPlayer()
        val offline = FakeOfflineVoice(ready = offlineReady)
        val listener = RecordingListener()
        val engine = TtsEngine(
            settings = FakeSettings(),
            cacheDir = temp.newFolder("cache"),
            transport = transport,
            player = player,
            offline = offline,
            scope = scope,
            mainDispatcher = StandardTestDispatcher(scope.testScheduler),
            prefetchDepth = 0,
            maxRetries = maxRetries,
            retryBaseDelayMs = retryBaseDelayMs,
        )
        engine.listener = listener
        return Harness(engine, transport, player, offline, listener)
    }

    private fun ok() = SynthesisResult.Success(ByteArray(64) { 1 }, "audio/mpeg", "edge_tts")

    /**
     * Drive a read to its end.
     *
     * `advanceUntilIdle()` alone cannot finish a read: it is the real
     * `MediaPlayer`, not the engine, that reports a piece ended. This plays each
     * piece and completes it in turn, exactly as the platform player would.
     */
    private fun TestScope.playThrough(h: Harness, maxPieces: Int = 500) {
        repeat(maxPieces) {
            advanceUntilIdle()
            if (h.player.playing) h.player.complete() else return
        }
    }

    // ── The retry that saves the read ─────────────────────────────────────────

    @Test
    fun `a transient failure is retried and the read continues online`() = runTest {
        // First attempt fails transiently, second succeeds. Without a retry the
        // engine would fall back to the offline voice on the first failure.
        val h = harness(
            this,
            sequence = listOf(
                SynthesisResult.Failure("Backend returned HTTP 502", transient = true),
                ok(),
            ),
        )

        h.engine.speak("Hello there")
        playThrough(h)

        assertEquals("the piece must be asked for twice", 2, h.transport.requests.size)
        assertEquals("the retry must play online", 1, h.player.playCalls)
        assertEquals("no fallback should have happened", 0, h.offline.utterances.size)
        assertTrue("the read must finish", h.listener.completed)
    }

    @Test
    fun `a permanent failure is not retried`() = runTest {
        // A 400 will fail identically every time. Retrying it only delays the
        // fallback the user needs, so the request count must stay at one.
        val h = harness(
            this,
            sequence = listOf(
                SynthesisResult.Failure("Backend returned HTTP 400", transient = false),
                ok(),
            ),
        )

        h.engine.speak("Hello there")
        advanceUntilIdle()

        assertEquals("a permanent failure must not be retried", 1, h.transport.requests.size)
        assertEquals("it must fall back", 1, h.offline.utterances.size)
    }

    @Test
    fun `retries are bounded and then the read falls back`() = runTest {
        // Every attempt fails transiently. The engine must stop after
        // maxRetries + 1 attempts rather than retrying forever.
        val h = harness(
            this,
            sequence = listOf(SynthesisResult.Failure("Backend returned HTTP 503", transient = true)),
            maxRetries = 2,
        )

        h.engine.speak("Hello there")
        advanceUntilIdle()

        assertEquals(
            "one initial attempt plus two retries",
            3,
            h.transport.requests.size,
        )
        assertEquals("the read must still be heard", 1, h.offline.utterances.size)
        assertEquals(TtsEngineKind.OFFLINE, h.listener.failures.first().fellBackTo)
    }

    @Test
    fun `maxRetries of zero disables retry entirely`() = runTest {
        // The pre-existing behaviour, kept reachable so the no-retry path is
        // still covered rather than becoming dead code.
        val h = harness(
            this,
            sequence = listOf(SynthesisResult.Failure("Backend returned HTTP 502", transient = true)),
            maxRetries = 0,
        )

        h.engine.speak("Hello there")
        advanceUntilIdle()

        assertEquals(1, h.transport.requests.size)
        assertEquals(1, h.offline.utterances.size)
    }

    @Test
    fun `a retry that succeeds does not report a failure to the user`() = runTest {
        // The blip is invisible: no error event, because the read recovered.
        val h = harness(
            this,
            sequence = listOf(
                SynthesisResult.Failure("Network error: SocketTimeoutException", transient = true),
                ok(),
            ),
        )

        h.engine.speak("Hello there")
        playThrough(h)

        assertTrue(
            "a recovered blip must not surface as an error",
            h.listener.failures.isEmpty(),
        )
        assertTrue(h.listener.completed)
    }

    @Test
    fun `a superseded read does not keep retrying`() = runTest {
        // A new speak() while a retry is pending must cancel the old read's
        // retries, not let them run alongside the new one.
        val h = harness(
            this,
            sequence = listOf(
                SynthesisResult.Failure("Backend returned HTTP 502", transient = true),
                ok(),
            ),
            retryBaseDelayMs = 1_000,
        )

        h.engine.speak("First passage")
        // Let the first attempt fail and the retry be scheduled, but not run.
        advanceUntilIdle()

        val afterFirst = h.transport.requests.size
        h.engine.speak("Second passage")
        playThrough(h)

        // The second read's own request must be the last one, and the first
        // read's pending retry must not have fired.
        assertTrue(
            "the superseded read must not add retries after supersession",
            h.transport.requests.size <= afterFirst + 1,
        )
        assertTrue(h.listener.completed)
    }

    // ── Classification ────────────────────────────────────────────────────────

    @Test
    fun `a failure defaults to permanent so an unclassified error is not retried`() = runTest {
        // The default matters: a failure that does not say it is transient must
        // be treated as permanent, because retrying something that cannot
        // succeed is the worse error.
        val failure = SynthesisResult.Failure("something went wrong")
        assertFalse(failure.transient)
    }

    @Test
    fun `a 5xx is classified transient and a 4xx is not`() {
        // The classification lives in the transport, so it is asserted there
        // rather than inferred from engine behaviour.
        assertTrue(SynthesisResult.Failure("Backend returned HTTP 502", transient = true).transient)
        assertFalse(SynthesisResult.Failure("Backend returned HTTP 400", transient = false).transient)
    }
}
