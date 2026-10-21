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
 * Engine behaviour on the online (backend) path, exercised through the seams.
 *
 * `Dispatchers.Main` is not available on the JVM, so the engine's coroutine scope
 * is supplied by the test; `Dispatchers.Main` hops inside the engine are stubbed
 * by `isReturnDefaultValues`. Everything asserted here is the engine's own logic:
 * validation, supersession, fallback and file cleanup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsEngineOnlineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Harness(
        val engine: TtsEngine,
        val transport: FakeTransport,
        val player: FakeAudioPlayer,
        val offline: FakeOfflineVoice,
        val listener: RecordingListener,
        val settings: FakeSettings,
        val cacheDir: java.io.File,
    )

    /**
     * Builds an engine whose coroutine scope is [scope] — the `runTest` scope.
     * Sharing the scheduler is required: a separate `TestScope` has its own
     * scheduler, so `advanceUntilIdle()` on it would never drive the engine's
     * launched work.
     */
    private fun harness(
        scope: TestScope,
        result: SynthesisResult = SynthesisResult.Success(ByteArray(64) { 1 }, "audio/mpeg", "edge_tts"),
        delayMs: Long = 0,
        offlineReady: Boolean = true,
        backendUrl: String = "https://example.test",
        preferOnline: Boolean = true,
    ): Harness {
        val transport = FakeTransport(result = result, delayMs = delayMs)
        val player = FakeAudioPlayer()
        val offline = FakeOfflineVoice(ready = offlineReady)
        val listener = RecordingListener()
        val settings = FakeSettings(backendUrl = backendUrl, preferOnline = preferOnline)
        val cacheDir = temp.newFolder("cache")
        val engine = TtsEngine(
            settings = settings,
            cacheDir = cacheDir,
            transport = transport,
            player = player,
            offline = offline,
            scope = scope,
            // Run state mutations on the test scheduler: Dispatchers.Main has no
            // JVM implementation, and the engine's main-thread hop must be
            // advanceable by advanceUntilIdle().
            mainDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
        engine.listener = listener
        return Harness(engine, transport, player, offline, listener, settings, cacheDir)
    }

    // ── Success path ──────────────────────────────────────────────────────────

    @Test
    fun `successful synthesis plays the staged file and reports the online engine`() = runTest {
        val h = harness(this)
        h.engine.speak("Hello there")
        advanceUntilIdle()

        assertEquals(1, h.transport.requests.size)
        assertEquals("Hello there", h.transport.requests[0].first)
        assertEquals(1, h.player.playCalls)
        assertEquals(listOf(TtsEngineKind.ONLINE), h.listener.starts.map { it.engine })
        assertEquals(TtsEngineKind.ONLINE, h.engine.currentEngine)
    }

    @Test
    fun `the configured voice and speed are sent`() = runTest {
        val h = harness(this)
        h.settings.voice = "sonia"
        h.settings.speed = 1.5f
        h.engine.speak("Hello")
        advanceUntilIdle()

        val (_, voice, speed) = h.transport.requests[0]
        assertEquals("sonia", voice)
        assertEquals(1.5f, speed, 0.001f)
    }

    @Test
    fun `speed is clamped to the backend range before sending`() = runTest {
        val h = harness(this)
        h.settings.speed = 9.0f
        h.engine.speak("Hello")
        advanceUntilIdle()

        assertEquals(2.0f, h.transport.requests[0].third, 0.001f)
    }

    @Test
    fun `completion deletes the staged file`() = runTest {
        val h = harness(this)
        h.engine.speak("Hello")
        advanceUntilIdle()

        val staged = h.player.lastFile
        assertTrue("staged file should exist", staged!!.exists())

        h.player.complete()

        assertFalse("completion must delete the temp file", staged.exists())
        assertTrue(h.listener.completed)
        assertEquals(PlaybackState.Idle, h.engine.playbackState)
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    fun `empty input is refused without contacting the backend`() = runTest {
        val h = harness(this)
        h.engine.speak("")
        advanceUntilIdle()

        assertTrue(h.transport.requests.isEmpty())
        assertEquals(0, h.player.playCalls)
        assertEquals(1, h.listener.failures.size)
        assertEquals("Nothing to read", h.listener.failures[0].msg)
    }

    @Test
    fun `whitespace-only input is refused without contacting the backend`() = runTest {
        val h = harness(this)
        h.engine.speak("   \n\t ")
        advanceUntilIdle()

        assertTrue(h.transport.requests.isEmpty())
        assertEquals(1, h.listener.failures.size)
    }

    @Test
    fun `null input is refused without contacting the backend`() = runTest {
        val h = harness(this)
        h.engine.speak(null)
        advanceUntilIdle()

        assertTrue(h.transport.requests.isEmpty())
        assertEquals(1, h.listener.failures.size)
    }

    @Test
    fun `oversized input is truncated before sending and the truncation is reported`() = runTest {
        val h = harness(this)
        val long = "x".repeat(MAX_TTS_CHARS + 500)
        h.engine.speak(long)
        advanceUntilIdle()

        assertEquals(1, h.transport.requests.size)
        assertEquals(MAX_TTS_CHARS, h.transport.requests[0].first.length)

        val truncationNotice = h.listener.failures.firstOrNull {
            it.msg.contains("truncated")
        }
        assertTrue("truncation must be reported to the user", truncationNotice != null)
    }

    // ── Failure and fallback ──────────────────────────────────────────────────

    @Test
    fun `a transport failure falls back to offline speech and reports why`() = runTest {
        val h = harness(this, result = SynthesisResult.Failure("Network error: SocketTimeoutException"))
        h.engine.speak("Hello")
        advanceUntilIdle()

        assertEquals(0, h.player.playCalls)
        assertEquals(1, h.offline.utterances.size)
        assertEquals("Hello", h.offline.utterances[0].text)

        val failure = h.listener.failures.first()
        assertTrue(failure.msg.contains("SocketTimeoutException"))
        assertEquals(TtsEngineKind.OFFLINE, failure.fellBackTo)
        assertEquals(listOf(TtsEngineKind.OFFLINE), h.listener.starts.map { it.engine })
    }

    @Test
    fun `an http failure falls back to offline speech`() = runTest {
        val h = harness(this, result = SynthesisResult.Failure("Backend returned HTTP 502"))
        h.engine.speak("Hello")
        advanceUntilIdle()

        assertEquals(1, h.offline.utterances.size)
        assertEquals(TtsEngineKind.OFFLINE, h.listener.failures.first().fellBackTo)
    }

    @Test
    fun `an empty audio body falls back to offline speech`() = runTest {
        val h = harness(this, result = SynthesisResult.Failure("Backend returned empty audio"))
        h.engine.speak("Hello")
        advanceUntilIdle()

        assertEquals(1, h.offline.utterances.size)
        assertEquals(0, h.player.playCalls)
    }

    @Test
    fun `audio that cannot be played falls back to offline speech`() = runTest {
        // The request succeeded and returned bytes, but they are not decodable
        // audio, so MediaPlayer refuses to start.
        val h = harness(this)
        h.player.startSucceeds = false
        h.engine.speak("Hello")
        advanceUntilIdle()

        assertEquals(1, h.offline.utterances.size)
        val failure = h.listener.failures.first()
        assertTrue(failure.msg.contains("could not be played"))
        assertEquals(TtsEngineKind.OFFLINE, failure.fellBackTo)
    }

    @Test
    fun `an undecodable download is deleted before falling back`() = runTest {
        val h = harness(this)
        h.player.startSucceeds = false
        h.engine.speak("Hello")
        advanceUntilIdle()

        val staged = h.player.lastFile!!
        assertFalse("an unplayable file must not be left in the cache", staged.exists())
    }

    @Test
    fun `when the offline engine is unavailable the failure is reported without a fallback`() = runTest {
        val h = harness(this, 
            result = SynthesisResult.Failure("Backend returned HTTP 500"),
            offlineReady = false,
        )
        h.engine.speak("Hello")
        advanceUntilIdle()

        assertTrue(h.offline.utterances.isEmpty())
        val failure = h.listener.failures.first()
        assertTrue(failure.msg.contains("Offline voice is unavailable"))
        assertNull(failure.fellBackTo)
        assertEquals(PlaybackState.Idle, h.engine.playbackState)
    }

    // ── Supersession and rapid requests ───────────────────────────────────────

    @Test
    fun `a rapid second request supersedes the first and only the second plays`() = runTest {
        val h = harness(this, delayMs = 100)

        h.engine.speak("First request")
        h.engine.speak("Second request")
        advanceUntilIdle()

        // The first request was cancelled mid-flight; only the second reached the
        // player, so no two utterances can overlap.
        assertEquals(1, h.player.playCalls)
        assertEquals(1, h.listener.starts.size)
    }

    @Test
    fun `supersession deletes the file staged by the superseded request`() = runTest {
        val h = harness(this)
        h.engine.speak("First")
        advanceUntilIdle()

        val first = h.player.lastFile!!
        assertTrue(first.exists())

        h.engine.speak("Second")
        advanceUntilIdle()

        assertFalse("the superseded request's file must be deleted", first.exists())
        assertEquals(2, h.player.playCalls)
    }

    @Test
    fun `cancelling while the staged file is being handed off does not orphan it`() = runTest {
        val h = harness(this)

        h.engine.speak("First")
        // Drive the request only as far as the main-dispatcher hop, so a
        // cancellation can land in the window where the file has been written but
        // playback has not started.
        runCurrent()
        h.engine.stop()
        advanceUntilIdle()

        val leaked = h.cacheDir.listFiles()?.toList().orEmpty()
        assertTrue("staged files leaked: ${leaked.map { it.name }}", leaked.isEmpty())
    }

    @Test
    fun `rapid requests do not accumulate staged files`() = runTest {
        val h = harness(this)

        repeat(5) { h.engine.speak("Request number " + it) }
        advanceUntilIdle()

        val leaked = h.cacheDir.listFiles()?.toList().orEmpty()
        assertTrue("staged files leaked: ${leaked.map { it.name }}", leaked.isEmpty())
    }

    @Test
    fun `a stale result arriving after supersession never starts playback`() = runTest {
        val h = harness(this, delayMs = 100)

        h.engine.speak("Stale")
        advanceUntilIdle()
        val playsAfterFirst = h.player.playCalls

        h.engine.speak("Fresh")
        advanceUntilIdle()

        // Exactly one additional start, from the fresh request only.
        assertEquals(playsAfterFirst + 1, h.player.playCalls)
        assertEquals(2, h.listener.starts.size)
    }

    // ── Stop and cleanup ──────────────────────────────────────────────────────

    @Test
    fun `stop deletes the staged file`() = runTest {
        val h = harness(this)
        h.engine.speak("Hello")
        advanceUntilIdle()

        val staged = h.player.lastFile!!
        assertTrue(staged.exists())

        h.engine.stop()

        assertFalse("stop must not leak temporary audio", staged.exists())
        assertEquals(PlaybackState.Idle, h.engine.playbackState)
    }

    @Test
    fun `stop releases the player`() = runTest {
        val h = harness(this)
        h.engine.speak("Hello")
        advanceUntilIdle()

        val before = h.player.stopCalls
        h.engine.stop()

        assertTrue(h.player.stopCalls > before)
        assertFalse(h.engine.isPlaying)
    }

    @Test
    fun `shutdown deletes the staged file and closes the transport`() = runTest {
        val h = harness(this)
        h.engine.speak("Hello")
        advanceUntilIdle()

        val staged = h.player.lastFile!!
        h.engine.shutdown()

        assertFalse(staged.exists())
        assertTrue(h.transport.closed)
    }

    // ── Pause / resume on the online path ─────────────────────────────────────

    @Test
    fun `online pause records the position and resume continues it`() = runTest {
        val h = harness(this)
        h.engine.speak("Hello")
        advanceUntilIdle()

        h.player.positionMs = 4200
        h.engine.pause()

        val paused = h.engine.playbackState as PlaybackState.Online
        assertTrue(paused.paused)
        assertEquals(4200, paused.positionMs)
        assertTrue(h.listener.events.any { it is RecordingListener.Event.Paused })

        h.engine.resume()

        val resumed = h.engine.playbackState as PlaybackState.Online
        assertFalse(resumed.paused)
        assertTrue(h.player.playing)
        assertTrue(h.listener.events.any { it is RecordingListener.Event.Resumed })
    }

    @Test
    fun `pause while idle is a no-op rather than a fabricated paused state`() = runTest {
        val h = harness(this)
        h.engine.pause()

        assertEquals(PlaybackState.Idle, h.engine.playbackState)
        assertTrue(h.listener.events.isEmpty())
    }

    @Test
    fun `resume while idle reports an error instead of claiming a resume`() = runTest {
        val h = harness(this)
        h.engine.resume()

        assertEquals(1, h.listener.failures.size)
        assertEquals("Nothing to resume", h.listener.failures[0].msg)
    }

    // ── Engine selection ──────────────────────────────────────────────────────

    @Test
    fun `a blank backend url uses offline speech without a network call`() = runTest {
        val h = harness(this, backendUrl = "")
        h.engine.speak("Hello")
        advanceUntilIdle()

        assertTrue(h.transport.requests.isEmpty())
        assertEquals(1, h.offline.utterances.size)
        assertEquals(listOf(TtsEngineKind.OFFLINE), h.listener.starts.map { it.engine })
    }

    @Test
    fun `disabling online preference uses offline speech`() = runTest {
        val h = harness(this, preferOnline = false)
        h.engine.speak("Hello")
        advanceUntilIdle()

        assertTrue(h.transport.requests.isEmpty())
        assertEquals(1, h.offline.utterances.size)
    }
}
