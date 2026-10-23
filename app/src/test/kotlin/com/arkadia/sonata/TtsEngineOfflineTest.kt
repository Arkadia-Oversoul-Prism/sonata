package com.arkadia.sonata

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Engine behaviour on the offline (platform TextToSpeech) path, where the
 * pause/resume semantics must be honest rather than implied.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsEngineOfflineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Harness(
        val engine: TtsEngine,
        val offline: FakeOfflineVoice,
        val listener: RecordingListener,
        val settings: FakeSettings,
    )

    private fun harness(
        scope: TestScope,
        ready: Boolean = true,
        result: SynthesisResult = SynthesisResult.Success(
            bytes = ByteArray(64) { 1 },
            contentType = "audio/mpeg",
            engineLabel = "edge_tts",
        ),
        backendUrl: String = "",
        preferOnline: Boolean = false,
    ): Harness {
        val offline = FakeOfflineVoice(ready = ready)
        val listener = RecordingListener()
        val settings = FakeSettings(backendUrl = backendUrl, preferOnline = preferOnline)
        val engine = TtsEngine(
            settings = settings,
            cacheDir = temp.newFolder("cache"),
            transport = FakeTransport(result = result),
            player = FakeAudioPlayer(),
            offline = offline,
            scope = scope,
            mainDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
        engine.listener = listener
        return Harness(engine, offline, listener, settings)
    }

    @Test
    fun `offline speech speaks the whole text from the start`() = runTest {
        val h = harness(this)
        h.engine.speak("Hello world")
        h.offline.reportRange(0)

        assertEquals(1, h.offline.utterances.size)
        assertEquals("Hello world", h.offline.utterances[0].text)
        assertEquals(0, h.offline.utterances[0].fromChar)
        assertEquals(listOf(TtsEngineKind.OFFLINE), h.listener.starts.map { it.engine })
    }

    @Test
    fun `offline speed and pitch preferences are applied`() = runTest {
        val h = harness(this)
        h.settings.speed = 1.5f
        h.settings.pitch = 0.75f
        h.engine.speak("Hello")

        assertEquals(1.5f, h.offline.utterances[0].speed, 0.001f)
        assertEquals(0.75f, h.offline.utterances[0].pitch, 0.001f)
    }

    @Test
    fun `pause stops the utterance and records the character reached`() = runTest {
        val h = harness(this)
        h.engine.speak("Hello world, this is a longer sentence.")
        h.offline.reportRange(12)

        val stopsBefore = h.offline.stopCalls
        h.engine.pause()

        // pause() must abort the platform utterance, not merely stop tracking it.
        assertTrue(h.offline.stopCalls > stopsBefore)
        val paused = h.engine.playbackState as PlaybackState.Offline
        assertTrue(paused.paused)
        assertEquals(12, paused.charOffset)
        assertTrue(h.listener.events.any { it is RecordingListener.Event.Paused })
    }

    @Test
    fun `resume re-speaks the remainder rather than restarting the utterance`() = runTest {
        val h = harness(this)
        h.engine.speak("Hello world, this is a longer sentence.")
        h.offline.reportRange(12)
        h.engine.pause()

        h.engine.resume()

        // Two utterances: the original, then the remainder from character 12.
        assertEquals(2, h.offline.utterances.size)
        val resumed = h.offline.utterances[1]
        assertEquals(12, resumed.fromChar)
        assertTrue(h.listener.events.any { it is RecordingListener.Event.Resumed })
    }

    @Test
    fun `resume does not speak a remainder that is only whitespace`() = runTest {
        val h = harness(this)
        h.engine.speak("Hello   ")
        // Progress reaches the end of the visible text.
        h.offline.reportRange(5)
        h.engine.pause()

        h.engine.resume()

        // Nothing left to speak, so the utterance is completed, not re-spoken.
        assertEquals(1, h.offline.utterances.size)
        assertTrue(h.listener.completed)
    }

    @Test
    fun `resume while idle reports an error rather than a false resume`() = runTest {
        val h = harness(this)
        h.engine.resume()

        assertEquals(1, h.listener.failures.size)
        assertEquals("Nothing to resume", h.listener.failures[0].msg)
    }

    @Test
    fun `pause while idle does not fabricate a paused state`() = runTest {
        val h = harness(this)
        h.engine.pause()

        assertEquals(PlaybackState.Idle, h.engine.playbackState)
        assertTrue(h.listener.events.isEmpty())
    }

    @Test
    fun `progress is not recorded while paused`() = runTest {
        val h = harness(this)
        h.engine.speak("Hello world, this is a longer sentence.")
        h.offline.reportRange(5)
        h.engine.pause()

        // A late progress callback from the stopped utterance must not move the
        // resume point backwards or forwards.
        h.offline.reportRange(40)

        val paused = h.engine.playbackState as PlaybackState.Offline
        assertEquals(5, paused.charOffset)
    }

    @Test
    fun `offline completion reports completion and clears the state`() = runTest {
        val h = harness(this)
        h.engine.speak("Hello")
        h.offline.complete()

        assertTrue(h.listener.completed)
        assertEquals(PlaybackState.Idle, h.engine.playbackState)
    }

    @Test
    fun `offline failure is reported and clears the state`() = runTest {
        val h = harness(this)
        h.engine.speak("Hello")
        h.offline.fail("Offline speech failed (code -1)")

        val failure = h.listener.failures.first()
        assertTrue(failure.msg.contains("code -1"))
        assertNull(failure.fellBackTo)
        assertEquals(PlaybackState.Idle, h.engine.playbackState)
    }

    @Test
    fun `stop aborts the offline utterance`() = runTest {
        val h = harness(this)
        h.engine.speak("Hello world")
        val before = h.offline.stopCalls

        h.engine.stop()

        assertTrue(h.offline.stopCalls > before)
        assertEquals(PlaybackState.Idle, h.engine.playbackState)
    }

    @Test
    fun `an unavailable offline engine reports a clear failure`() = runTest {
        val h = harness(this, ready = false)

        h.engine.speak("Hello")
        // Readiness is resolved asynchronously now: let the wait begin, then deny it.
        runCurrent()
        h.offline.denyReadiness()
        advanceUntilIdle()

        assertTrue(h.offline.utterances.isEmpty())
        assertTrue(h.listener.failures.first().msg.contains("Offline voice is unavailable"))
        assertFalse(h.engine.isPlaying)
    }

    @Test
    fun `a cold start waits for the engine to become ready and then speaks`() = runTest {
        // Not ready at construction: the real cold-start condition, since the
        // platform engine completes initialisation in a callback.
        val h = harness(this, ready = false)

        h.engine.speak("Cold start utterance")
        runCurrent()
        // Nothing spoken yet: the engine is still initialising.
        assertTrue("must not speak before the engine is ready", h.offline.utterances.isEmpty())

        // The platform callback lands.
        h.offline.becomeReady()
        advanceUntilIdle()

        assertEquals(1, h.offline.utterances.size)
        assertEquals("Cold start utterance", h.offline.utterances[0].text)
        assertTrue(h.listener.starts.any { it.engine == TtsEngineKind.OFFLINE })
    }

    @Test
    fun `a cold start fallback waits for readiness and reports the degradation`() = runTest {
        val h = harness(
            this,
            ready = false,
            result = SynthesisResult.Failure("Backend returned HTTP 502"),
            backendUrl = "https://example.invalid",
            preferOnline = true,
        )

        h.engine.speak("Falling back on a cold start")
        // runCurrent, not advanceUntilIdle: advancing virtual time here would fire
        // the readiness bound before the engine ever had a chance to initialise.
        runCurrent()
        // The online failure is known, but there is nothing to fall back to yet.
        assertTrue(h.offline.utterances.isEmpty())

        h.offline.becomeReady()
        advanceUntilIdle()

        // The degradation is still reported, and speech continues.
        assertEquals(1, h.offline.utterances.size)
        assertEquals(TtsEngineKind.OFFLINE, h.listener.failures.first().fellBackTo)
    }

    @Test
    fun `a superseded request does not speak once readiness arrives`() = runTest {
        val h = harness(this, ready = false)

        h.engine.speak("Superseded while waiting")
        runCurrent()
        h.engine.stop()
        h.offline.becomeReady()
        advanceUntilIdle()

        assertTrue("a superseded utterance must not start", h.offline.utterances.isEmpty())
    }
}

/**
 * Progress after a mid-passage fallback.
 *
 * When online synthesis fails part-way, the offline voice takes over the *remainder*
 * of the passage. The remainder is a different string from the passage, so measuring
 * progress against it makes the bar reset to zero and never reach the end — the user
 * sees the read restart even though the audio is continuous.
 */
class TtsEngineFallbackProgressTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Harness(val engine: TtsEngine, val player: FakeAudioPlayer)

    /** The first piece succeeds and plays; the second fails, so the fallback starts part-way in. */
    private fun engine(scope: TestScope, transport: FakeTransport): Harness {
        val settings = FakeSettings(backendUrl = "https://example.test", preferOnline = true)
        val player = FakeAudioPlayer()
        val engine = TtsEngine(
            settings = settings,
            cacheDir = temp.newFolder("cache"),
            transport = transport,
            player = player,
            offline = FakeOfflineVoice(ready = true),
            scope = scope,
            mainDispatcher = StandardTestDispatcher(scope.testScheduler),
            prefetchDepth = 0,
        )
        return Harness(engine, player)
    }

    @Test
    fun `progress after a mid-passage fallback continues from where the audio got to`() = runTest {
        val ok = SynthesisResult.Success(ByteArray(64) { 1 }, "audio/mpeg", "edge_tts")
        // Piece 1 succeeds, piece 2 fails, the rest would succeed. This is the shape
        // that exposes the bug: the offline voice takes over the remainder, which is a
        // different string from the passage.
        val transport = FakeTransport(
            sequence = listOf(ok, SynthesisResult.Failure("boom")),
        )
        val h = engine(this, transport)

        val passage = (1..400).joinToString(" ") { "sentence$it" }
        h.engine.speak(passage)
        advanceUntilIdle()

        // Piece 1 is playing. Finish it, exactly as the platform player would, so the
        // engine reaches for piece 2 — which fails and triggers the fallback.
        assertTrue("the first piece should be playing", h.player.playing)
        h.player.complete()
        advanceUntilIdle()

        // The offline voice has taken over the remainder, which begins part-way
        // through the passage.
        assertEquals(TtsEngineKind.OFFLINE, h.engine.currentEngine)
        assertEquals("progress is measured against the passage", passage.length, h.engine.totalChars)
        assertTrue(
            "progress must not jump back to zero when the offline voice takes over " +
                "(spokenChars=${h.engine.spokenChars}, first piece was " +
                "${transport.requests.first().first.length} chars)",
            h.engine.spokenChars > 0,
        )
        assertTrue(
            "progress must never exceed the passage",
            h.engine.spokenChars <= h.engine.totalChars,
        )
    }
}
