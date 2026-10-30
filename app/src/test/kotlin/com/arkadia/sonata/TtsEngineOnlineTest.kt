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
        prefetchDepth: Int = 0,
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
            // Deterministic fetch order: the engine fetches one piece at a time as
            // the previous one finishes, so a test can assert on the sequence.
            prefetchDepth = prefetchDepth,
        )
        engine.listener = listener
        return Harness(engine, transport, player, offline, listener, settings, cacheDir)
    }

    /**
     * Drive a segmented read to its end.
     *
     * `advanceUntilIdle()` alone cannot finish a multi-piece read: it is the real
     * `MediaPlayer`, not the engine, that reports a piece ended. This plays each
     * piece and completes it in turn, exactly as the platform player would, so the
     * assertions are about the engine's sequencing rather than about the fake's
     * bookkeeping.
     */
    private fun TestScope.playThrough(h: Harness, maxPieces: Int = 500) {
        repeat(maxPieces) {
            advanceUntilIdle()
            if (h.player.playing) h.player.complete() else return
        }
    }

    // ── Resuming where the user stopped ───────────────────────────────────────

    /**
     * A long passage must be resumable, and the resume must not skip or repeat
     * speech.
     *
     * The engine is the right place for the offset because it knows where each piece
     * begins. These tests pin the two ways that could go wrong: starting at the
     * wrong piece, and losing text at the seam.
     */
    @Test
    fun `speakFrom starts at the piece containing the offset`() = runTest {
        val h = harness(this)
        // Long enough to be split into several pieces.
        val text = (1..400).joinToString(" ") { "sentence$it" }
        val offset = text.length / 2

        h.engine.speakFrom(text, offset)
        advanceUntilIdle()

        val firstRequested = h.transport.requests.first().first
        assertTrue(
            "the read must not restart from the beginning (asked for: ${firstRequested.take(40)})",
            !firstRequested.startsWith("sentence1 "),
        )
        // The first piece is the one containing the offset, so it is a slice of the
        // passage from that piece's start — never a re-synthesis of the opening.
        assertTrue(
            "the first piece must come from the passage itself",
            text.contains(firstRequested),
        )
    }

    @Test
    fun `speakFrom covers the whole remainder of the passage`() = runTest {
        val h = harness(this, prefetchDepth = 1)
        val text = (1..400).joinToString(" ") { "sentence$it" }
        val offset = text.length / 3

        h.engine.speakFrom(text, offset)
        playThrough(h)

        // Concatenating everything actually sent must be the tail of the passage:
        // nothing before the resume point, nothing dropped after it, and nothing
        // duplicated at a seam.
        val spoken = h.transport.requests.joinToString("") { it.first }
        assertTrue("something must be read", spoken.isNotEmpty())
        assertTrue("the read must skip the part already heard", spoken.length < text.length)
        assertTrue(
            "the remainder must be read in order, with nothing lost at a seam",
            text.endsWith(spoken),
        )
    }

    @Test
    fun `speakFrom past the end does not crash and reads nothing`() = runTest {
        val h = harness(this)
        h.engine.speakFrom("A short passage.", fromChar = 9999)
        advanceUntilIdle()

        val spoken = h.transport.requests.joinToString("") { it.first }
        assertTrue("nothing before the clamp point may be spoken", spoken.isEmpty())
    }

    @Test
    fun `speakFrom with a negative offset reads from the start`() = runTest {
        val h = harness(this)
        h.engine.speakFrom("Hello there", fromChar = -50)
        advanceUntilIdle()

        assertEquals("Hello there", h.transport.requests.first().first)
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
    fun `input longer than one request is split, not truncated, and every piece is fetched`() = runTest {
        // The defect the founder reported as a length limit: the old path cut the
        // text at the backend's 5000-character request ceiling and spoke only that.
        // A longer passage must now be read in full, as several requests whose
        // concatenation is the original text.
        val h = harness(this, prefetchDepth = 0)
        val long = buildString { repeat(2000) { append("word$it ") } } // ~14k chars
        assertTrue("fixture must exceed one request", long.length > MAX_TTS_CHARS)

        h.engine.speak(long)
        playThrough(h)

        assertTrue("a long passage needs more than one request", h.transport.requests.size > 1)
        // No request may exceed the backend's own ceiling, or the tail of that
        // request would be silently dropped server-side.
        assertTrue(
            "no piece may exceed the backend ceiling: ${h.transport.requests.map { it.first.length }}",
            h.transport.requests.all { it.first.length <= MAX_TTS_CHARS },
        )
        // Nothing lost and nothing reordered: the requests, in order, are the text.
        // Compared against the trimmed input, which is what validation hands on.
        assertEquals(long.trim(), h.transport.requests.joinToString("") { it.first })
        // No truncation was reported, because none happened.
        assertTrue(
            "a passage that fits the input ceiling must not be reported as truncated",
            h.listener.failures.none { it.msg.contains("truncated") },
        )
    }

    @Test
    fun `the first request is short so speech can begin early`() = runTest {
        // Measured against the live gateway: a 340-character request returns in
        // ~1.8 s, a 5000-character request in ~29 s. Asking for the whole passage
        // first is what made speech feel like it never started.
        val h = harness(this, prefetchDepth = 0)
        val long = buildString { repeat(2000) { append("word$it ") } }
        h.engine.speak(long)
        advanceUntilIdle()

        val first = h.transport.requests.first().first.length
        assertTrue("the opening request must be short, was $first", first <= SpeechChunker.STREAM_FIRST_LIMIT)
    }

    @Test
    fun `the whole passage is played as consecutive pieces in order`() = runTest {
        val h = harness(this, prefetchDepth = 0)
        val long = buildString { repeat(2000) { append("word$it ") } }
        h.engine.speak(long)
        playThrough(h)

        // Each piece reaching the player in turn, and the last one completing the
        // read. Fewer plays than pieces would mean a piece was skipped.
        assertEquals(h.transport.requests.size, h.player.playCalls)
        assertTrue("the read must finish", h.listener.completed)
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
        // The leak lives in the window between writing the staged file and starting
        // playback. With a standard test dispatcher the main-thread hop completes
        // synchronously and that window never opens, so the guard would pass even
        // against the defect it exists to catch. A gated dispatcher holds the hop
        // open so a cancellation can land inside it, which is what the device
        // protocol observed.
        val gated = GatedDispatcher()
        val cacheDir = temp.newFolder("gated-cache")
        val engine = TtsEngine(
            settings = FakeSettings(backendUrl = "https://example.test", preferOnline = true),
            cacheDir = cacheDir,
            transport = FakeTransport(),
            player = FakeAudioPlayer(),
            offline = FakeOfflineVoice(),
            scope = this,
            mainDispatcher = gated,
        )

        engine.speak("First")
        runCurrent()
        // The file is written and the hop to the main dispatcher is queued.
        assertEquals("the hop must be held open by the gate", 1, gated.pending())
        assertTrue("the staged file must exist at this point",
            (cacheDir.listFiles()?.size ?: 0) == 1)

        // Cancel while the hop is pending: this is the cancellation that used to
        // orphan the file.
        engine.stop()
        advanceUntilIdle()
        gated.release()

        val leaked = cacheDir.listFiles()?.toList().orEmpty()
        assertTrue("staged files leaked: ${leaked.map { it.name }}", leaked.isEmpty())
    }

    @Test
    fun `rapid requests leave only the playing file staged`() = runTest {
        val h = harness(this)

        repeat(5) { h.engine.speak("Request number " + it) }
        advanceUntilIdle()

        // Four requests were superseded and must be gone; the fifth is playing, so
        // its file is in use. Asserting "empty" here would demand the engine delete
        // audio it is currently playing.
        val staged = h.cacheDir.listFiles()?.toList().orEmpty()
        assertEquals("only the playing request's file may remain: ${staged.map { it.name }}",
            1, staged.size)

        // Once playback is abandoned nothing may remain.
        h.engine.stop()
        advanceUntilIdle()
        val afterStop = h.cacheDir.listFiles()?.toList().orEmpty()
        assertTrue("stop must release every staged file: ${afterStop.map { it.name }}",
            afterStop.isEmpty())
    }

    @Test
    fun `a failure does not poison the request that follows it`() = runTest {
        // First request fails and falls back; the second must succeed normally on
        // the online engine. A state left behind by the failure (a stale file, a
        // stuck paused/generation flag) would surface here as a second offline
        // fallback or as an error.
        val transport = FakeTransport(
            sequence = listOf(
                SynthesisResult.Failure("Backend returned HTTP 502"),
                SynthesisResult.Success(ByteArray(64) { 1 }, "audio/mpeg", "edge_tts"),
            ),
        )
        val player = FakeAudioPlayer()
        val offline = FakeOfflineVoice()
        val listener = RecordingListener()
        val cacheDir = temp.newFolder("poison-cache")
        val engine = TtsEngine(
            settings = FakeSettings(backendUrl = "https://example.test", preferOnline = true),
            cacheDir = cacheDir,
            transport = transport,
            player = player,
            offline = offline,
            scope = this,
            mainDispatcher = StandardTestDispatcher(testScheduler),
        )
        engine.listener = listener

        engine.speak("First request")
        advanceUntilIdle()
        // The failure fell back to the offline voice.
        assertEquals(1, offline.utterances.size)
        assertEquals(TtsEngineKind.OFFLINE, listener.failures.first().fellBackTo)

        engine.speak("Second request")
        advanceUntilIdle()

        // The second request used the online engine: the failure did not poison it.
        // Both requests reached the backend, and the second is the only one that
        // played, because the first fell back to the offline voice.
        assertEquals(2, transport.requests.size)
        assertEquals(1, player.playCalls)
        assertEquals(1, offline.utterances.size)
        assertTrue(listener.starts.any { it.engine == TtsEngineKind.ONLINE })
        // Only the first failure was reported; the success added none.
        assertEquals(1, listener.failures.size)
        // Exactly one file is staged: the second request's, which is playing. The
        // failed request left nothing behind.
        assertEquals(1, cacheDir.listFiles()?.size ?: 0)
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

    // ── Progressive read: progress, buffering and mid-passage fallback ─────────

    @Test
    fun `progress reports the whole passage and the piece being played`() = runTest {
        val h = harness(this, prefetchDepth = 0)
        val long = buildString { repeat(2000) { append("word$it ") } }
        h.engine.speak(long)
        advanceUntilIdle()

        // The denominator is the passage the user offered, not the piece being
        // spoken: a progress read against a 900-character piece would always look
        // almost finished.
        assertEquals(long.trim().length, h.engine.totalChars)
        assertTrue("a long passage must be split", h.engine.segmentCount > 1)
        assertEquals("the first piece is piece 1", 1, h.engine.segmentIndex)
        assertEquals("nothing has been spoken past the first piece", 0, h.engine.spokenChars)
    }

    @Test
    fun `a paused read does not advance to the next piece`() = runTest {
        val h = harness(this, prefetchDepth = 0)
        val long = buildString { repeat(2000) { append("word$it ") } }
        h.engine.speak(long)
        advanceUntilIdle()

        h.engine.pause()
        val playsAtPause = h.player.playCalls

        // The first piece ends while paused; the engine must not roll on to the
        // second, or pausing would be indistinguishable from a no-op.
        h.player.complete()
        advanceUntilIdle()

        assertEquals("a paused read must not start another piece", playsAtPause, h.player.playCalls)
        assertTrue(h.engine.isPaused)
    }

    @Test
    fun `resuming a read paused between pieces continues it`() = runTest {
        val h = harness(this, prefetchDepth = 0)
        val long = buildString { repeat(2000) { append("word$it ") } }
        h.engine.speak(long)
        advanceUntilIdle()

        // Reach the gap between pieces: the first has ended and the next is not
        // staged yet.
        h.engine.pause()
        h.player.complete()
        advanceUntilIdle()
        assertTrue("expected the read to be paused", h.engine.isPaused)

        h.engine.resume()
        advanceUntilIdle()

        assertFalse(h.engine.isPaused)
        assertTrue("resuming must actually play the next piece", h.player.playCalls >= 2)
    }

    @Test
    fun `a failure on a later piece falls back with the unread remainder, not the whole passage`() = runTest {
        // The first piece succeeds; the second fails. The fallback must speak what
        // has not been read — re-reading the passage from the top would repeat
        // audio the user already heard, and speaking nothing would lose the rest.
        val long = buildString { repeat(2000) { append("word$it ") } }
        val transport = FakeTransport(
            sequence = listOf(
                SynthesisResult.Success(ByteArray(64) { 1 }, "audio/mpeg", "edge_tts"),
                SynthesisResult.Failure("Backend returned HTTP 502"),
            ),
        )
        val player = FakeAudioPlayer()
        val offline = FakeOfflineVoice()
        val listener = RecordingListener()
        val engine = TtsEngine(
            settings = FakeSettings(backendUrl = "https://example.test", preferOnline = true),
            cacheDir = temp.newFolder("fallback-cache"),
            transport = transport,
            player = player,
            offline = offline,
            scope = this,
            mainDispatcher = StandardTestDispatcher(testScheduler),
            prefetchDepth = 0,
        )
        engine.listener = listener

        engine.speak(long)
        advanceUntilIdle()
        val firstPiece = transport.requests.first().first
        assertTrue("the first piece must have been spoken", player.playCalls == 1)

        // Let the first piece finish so the read reaches the failing second piece.
        player.complete()
        advanceUntilIdle()

        assertEquals(1, offline.utterances.size)
        val spoken = offline.utterances[0].text
        assertTrue("the fallback must not be empty", spoken.isNotBlank())
        assertTrue(
            "the fallback must not re-read the passage from the top",
            !spoken.startsWith(firstPiece),
        )
        // And it must be the remainder, so nothing is lost.
        assertTrue("the remainder must be spoken", long.trim().endsWith(spoken))
    }

    @Test
    fun `the start is reported once for a whole segmented read`() = runTest {
        // A read is one utterance to the user. Reporting a start per piece would
        // make the UI flicker and the notification re-post on every piece.
        val h = harness(this, prefetchDepth = 0)
        val long = buildString { repeat(2000) { append("word$it ") } }
        h.engine.speak(long)
        playThrough(h)

        assertEquals(1, h.listener.starts.size)
    }

    // ── Prefetch must not cut off what is playing ────────────────────────────

    /**
     * The production prefetch depth.
     *
     * Every other test in this file runs at depth 0, which makes the read strictly
     * sequential and therefore *cannot* observe the failure this pins: at depth 1 the
     * next piece is synthesised while the current one plays, and an engine that
     * advances whenever a later piece becomes available cuts the current piece off
     * mid-sentence and hops forward.
     */
    @Test
    fun `a prefetched piece does not interrupt the piece playing`() = runTest {
        val h = harness(this, prefetchDepth = 1)
        val long = buildString { repeat(2000) { append("word$it ") } }
        h.engine.speak(long)
        advanceUntilIdle()

        // The first piece is playing, and the second was fetched during it.
        assertEquals("only the first piece may have started", 1, h.player.playCalls)
        assertTrue("the first piece must be playing", h.engine.isPlaying)
        assertTrue("a second piece must have been prefetched", h.transport.requests.size >= 2)

        // Let the first piece finish. Only now may the second start.
        h.player.complete()
        advanceUntilIdle()
        assertEquals("the second piece starts when the first ends", 2, h.player.playCalls)
    }

    @Test
    fun `every piece is heard in order at the production prefetch depth`() = runTest {
        val h = harness(this, prefetchDepth = 1)
        val long = buildString { repeat(2000) { append("word$it ") } }
        h.engine.speak(long)
        advanceUntilIdle()

        // Drive the read to completion by ending each piece in turn, and assert the
        // pieces were requested and played in the passage's order. A read that hops
        // forward plays fewer pieces than it requests.
        val played = mutableListOf<String>()
        var guard = 0
        while (h.engine.isPlaying && guard++ < 40) {
            val before = h.player.playCalls
            h.player.complete()
            advanceUntilIdle()
            if (h.player.playCalls > before) played += "piece${h.player.playCalls}"
        }

        assertEquals(
            "a prefetched read must play every piece it fetched, in order",
            h.transport.requests.size,
            h.player.playCalls,
        )
        // And the requests, in order, are the passage.
        assertEquals(long.trim(), h.transport.requests.joinToString("") { it.first })
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
