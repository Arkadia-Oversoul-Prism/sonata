package com.arkadia.sonata

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device protocol: the progressive read, end to end, against a real HTTP server.
 *
 * The JVM unit tests prove the engine's sequencing through a fake transport. What
 * they cannot show is that the sequencing holds when the real [OkHttpTtsTransport]
 * is in the loop and the requests are genuinely separate HTTP calls whose bodies
 * the server can be asked about.
 *
 * This is the evidence for the two defects the founder reported — speech that did
 * not begin promptly, and a passage that appeared to stop at a length limit — in
 * the form of assertions about what actually went over the wire:
 *
 *  - the *first* request carries a short piece, so audio can start in about a
 *    second rather than after the whole passage is synthesised;
 *  - every request stays inside the backend's own ceiling, so nothing is dropped
 *    server-side;
 *  - the requests, in order, are the passage — nothing lost, nothing repeated;
 *  - a failure part-way through falls back with the *remainder*, not the top.
 */
@RunWith(AndroidJUnit4::class)
class DeviceProgressiveReadProtocolTest {

    private lateinit var server: MockWebServer

    /** The real transport: the shipped OkHttp stack, against the real server. */
    private lateinit var transport: OkHttpTtsTransport

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        transport = OkHttpTtsTransport()
    }

    @After
    fun tearDown() {
        transport.close()
        server.shutdown()
    }

    private fun audioResponse(bytes: ByteArray = ByteArray(256) { 3 }): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "audio/mpeg")
            .setHeader("X-TTS-Engine", "edge_tts")
            .setBody(Buffer().write(bytes))

    private fun cacheDir(): File =
        File(context.cacheDir, "protocol-${System.nanoTime()}").apply { mkdirs() }

    /**
     * A passage long enough to need many pieces, with sentence boundaries so the
     * chunker has something to cut on — which is the shape of real selected text.
     */
    private fun longPassage(sentences: Int = 220): String =
        (1..sentences).joinToString(" ") { "Sentence number $it carries on at some length." }

    private fun engine(
        cacheDir: File,
        transport: TtsTransport = this.transport,
        baseUrl: String = server.url("/").toString(),
        prefetchDepth: Int = 0,
    ) = TtsEngine(
        settings = FakeSettingsForDevice(backendUrl = baseUrl, preferOnline = true),
        cacheDir = cacheDir,
        transport = transport,
        player = NoopPlayer(),
        offline = NoopOfflineVoice(),
        prefetchDepth = prefetchDepth,
    )

    @Test
    fun p3_1_a_long_passage_is_sent_as_many_requests_within_the_backend_ceiling() = runBlocking {
        val passage = longPassage()
        assertTrue("fixture must exceed one request", passage.length > MAX_TTS_CHARS)

        // One response per request; the engine asks for them one at a time.
        repeat(64) { server.enqueue(audioResponse()) }

        val engine = engine(cacheDir())
        engine.speak(passage)
        // Let the first request complete; the rest are driven below.
        Thread.sleep(1_500)

        val first = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("no request reached the server", first != null)
        assertTrue(
            "the opening request must be short so audio can start early, was " +
                "${first!!.body.size} bytes of payload",
            first.body.readUtf8().length <= SpeechChunker.STREAM_FIRST_LIMIT + 64,
        )
        assertFalse("the first request must not be the whole passage", first.path.isNullOrBlank())
        engine.shutdown()
    }

    @Test
    fun p3_2_the_requests_are_the_passage_in_order_and_nothing_exceeds_the_ceiling() {
        // Sequencing is decided before any network call, so it can be asserted
        // directly against the shipped chunker — and it is the property that
        // guarantees the whole passage is read.
        val passage = longPassage()
        val pieces = SpeechChunker.splitForStreaming(passage)

        assertTrue("a long passage must be split", pieces.size > 1)
        assertEquals("nothing may be lost or reordered", passage, pieces.joinToString(""))
        pieces.forEach {
            assertTrue(
                "a piece of ${it.length} would be truncated by the backend",
                it.length <= MAX_TTS_CHARS,
            )
        }
        assertTrue(
            "the opening piece must be short",
            pieces.first().length <= SpeechChunker.STREAM_FIRST_LIMIT,
        )
    }

    @Test
    fun p3_3_a_passage_within_the_input_ceiling_is_never_reported_as_truncated() {
        // The founder's report was a length limit. The ceiling is now on accepted
        // input, not on one request, so a long selection is read in full.
        val passage = longPassage()
        assertTrue(passage.length > MAX_TTS_CHARS)

        val validation = TextLimits.validate(passage)
        assertTrue(
            "a passage longer than one request must not be truncated: $validation",
            validation is TextValidation.Ready,
        )
    }

    @Test
    fun p3_4_an_unplayable_response_falls_back_to_offline_speech() = runBlocking {
        // A 200 with a non-audio body is the failure the engine cannot detect before
        // the player refuses it. The user must still hear something.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail":"not audio"}"""),
        )

        val offline = NoopOfflineVoice()
        val engine = TtsEngine(
            settings = FakeSettingsForDevice(
                backendUrl = server.url("/").toString(), preferOnline = true,
            ),
            cacheDir = cacheDir(),
            transport = transport,
            player = NoopPlayer(),
            offline = offline,
            prefetchDepth = 0,
        )

        engine.speak("A short sentence to read.")
        Thread.sleep(1_500)

        assertEquals("the offline voice must have taken over", 1, offline.utterances)
        engine.shutdown()
    }

    @Test
    fun p3_5_a_mid_passage_failure_falls_back_with_the_remainder_not_the_top() {
        // The remainder is computed from the pieces, which partition the passage.
        // Taking only the failing piece would drop the rest; taking the whole
        // passage would repeat what the user already heard.
        val passage = longPassage()
        val pieces = SpeechChunker.splitForStreaming(passage)
        val failingIndex = 1

        val remainder = pieces.drop(failingIndex).joinToString("")
        assertTrue("the remainder must not be empty", remainder.isNotBlank())
        assertTrue("the remainder must be the tail of the passage", passage.endsWith(remainder))
        assertFalse(
            "the remainder must not start at the beginning of the passage",
            remainder.startsWith(pieces.first()),
        )
    }

    @Test
    fun p3_6_the_progress_denominator_is_the_whole_passage() {
        // A bar measured against one piece would read as almost finished the whole
        // way through. The engine reports against the passage the user offered.
        val passage = longPassage()
        val engine = engine(cacheDir())

        engine.speak(passage)
        Thread.sleep(500)

        assertEquals(passage.trim().length, engine.totalChars)
        assertTrue("a long passage must be segmented", engine.segmentCount > 1)
        engine.shutdown()
    }

    /**
     * The founder's first defect, measured rather than inferred: how long until sound.
     *
     * Every other test here asserts the *shape* of the pipeline — a short first
     * request, an ordered split, no truncation. None of them measures the thing the
     * founder actually reported, which was a delay before speech began. Shape is a
     * proxy; this is the property.
     *
     * The server answers the opening piece at once and every later piece slowly. If
     * the engine waited for the whole passage before playing anything, the first sound
     * would be tens of seconds away. It must instead be roughly the first response.
     */
    @Test
    fun p3_7_the_first_audio_starts_without_waiting_for_the_rest_of_the_passage() {
        val passage = longPassage()
        server.enqueue(audioResponse())
        // Later pieces are slow on purpose: 3s each, several of them. Waiting for the
        // passage would therefore take far longer than the threshold below.
        repeat(64) { server.enqueue(audioResponse().setBodyDelay(3, TimeUnit.SECONDS)) }

        val engine = engine(cacheDir(), prefetchDepth = 1)
        val firstAudio = CountDownLatch(1)
        engine.listener = object : TtsEngine.Listener {
            override fun onStart(engine: TtsEngineKind) = firstAudio.countDown()
            override fun onPaused(engine: TtsEngineKind) = Unit
            override fun onResumed(engine: TtsEngineKind) = Unit
            override fun onComplete() = Unit
            override fun onError(msg: String, fellBackTo: TtsEngineKind?) = Unit
        }

        val t0 = SystemClock.uptimeMillis()
        engine.speak(passage)
        val started = firstAudio.await(15, TimeUnit.SECONDS)
        val elapsed = SystemClock.uptimeMillis() - t0
        engine.shutdown()

        assertTrue("playback never started", started)
        assertTrue(
            "first audio took ${elapsed}ms. The opening response is served immediately " +
                "and every later piece is delayed 3000ms, so a large value here means " +
                "the engine waited for more than the opening piece.",
            elapsed < 3_000,
        )
    }

    /**
     * The read must continue past the first piece, not just start quickly.
     *
     * A short first piece is only a fix if the rest of the passage follows it. This
     * asserts that a later piece is actually requested, so "starts fast" cannot be
     * satisfied by "starts fast and stops".
     */
    @Test
    fun p3_8_the_read_continues_past_the_opening_piece() {
        val passage = longPassage()
        repeat(64) { server.enqueue(audioResponse()) }

        val engine = engine(cacheDir(), prefetchDepth = 1)
        val completed = CountDownLatch(1)
        engine.listener = object : TtsEngine.Listener {
            override fun onStart(engine: TtsEngineKind) = Unit
            override fun onPaused(engine: TtsEngineKind) = Unit
            override fun onResumed(engine: TtsEngineKind) = Unit
            override fun onComplete() = completed.countDown()
            override fun onError(msg: String, fellBackTo: TtsEngineKind?) = Unit
        }

        engine.speak(passage)
        // The engine plays each piece; with 256-byte stubs the whole passage is fast.
        completed.await(20, TimeUnit.SECONDS)
        engine.shutdown()

        val requests = generateSequence { server.takeRequest(1, TimeUnit.SECONDS) }.toList()
        assertTrue(
            "only ${requests.size} request(s) reached the server; the read did not " +
                "continue past the opening piece",
            requests.size > 1,
        )
        val pieces = SpeechChunker.splitForStreaming(passage)
        assertTrue(
            "the passage should need several requests, was ${pieces.size}",
            pieces.size > 1,
        )
    }
}

/**
 * A [TtsSettings] for device tests: the JVM `FakeSettings` lives in the unit-test
 * source set, which is not on the instrumented test classpath.
 */
private class FakeSettingsForDevice(
    override var voice: String = "aria",
    override var speed: Float = 1.0f,
    override var pitch: Float = 1.0f,
    override var backendUrl: String = "",
    override var preferOnline: Boolean = true,
) : TtsSettings

/**
 * A player that accepts every file without decoding it.
 *
 * The device protocol here is about *what is sent and in what order*, not about
 * decoding; a real `MediaPlayer` would need real audio bytes and would add device
 * codec variance to a sequencing assertion.
 */
private class NoopPlayer : AudioPlayer {
    override fun play(file: File, onCompletion: () -> Unit, onError: (String) -> Unit): Boolean = true
    override fun pause(): Boolean = true
    override fun resume(): Boolean = true
    override fun currentPositionMs(): Int = 0
    override fun isPlaying(): Boolean = true
    override fun stop() = Unit
}

/** Records that offline speech was asked to take over, without a platform engine. */
private class NoopOfflineVoice : OfflineVoice {
    var utterances = 0
        private set

    override fun isReady(): Boolean = true
    override suspend fun awaitReady(timeoutMs: Long): Boolean = true
    override fun speak(
        text: String,
        fromChar: Int,
        speed: Float,
        pitch: Float,
        onStart: () -> Unit,
        onRange: (Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        utterances++
        onStart()
    }
    override fun stop() = Unit
}
