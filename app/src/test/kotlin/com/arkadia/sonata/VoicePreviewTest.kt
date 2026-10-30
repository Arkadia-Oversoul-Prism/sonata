package com.arkadia.sonata

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
 * Command mapping for a voice sample.
 *
 * A preview must never be mistaken for a read. It carries its own action, so a
 * sample cannot start a read, be resumed, or be carried on from — conflating the two
 * would overwrite the user's reading position with a sample.
 */
class SpeechCommandPreviewTest {

    @Test
    fun `a preview resolves to Preview, not Speak`() {
        val cmd = SpeechCommand.resolve(
            action = SpeechService.ACTION_PREVIEW,
            text = "Hello, I am Aria.",
            label = null,
            voiceId = "aria",
        )
        assertEquals(SpeechCommand.Preview("aria", "Hello, I am Aria."), cmd)
    }

    @Test
    fun `a preview with no text is refused rather than dispatched`() {
        listOf(null, "", "   ").forEach { text ->
            assertEquals(
                "preview text=$text",
                SpeechCommand.None,
                SpeechCommand.resolve(SpeechService.ACTION_PREVIEW, text, null, 0, "aria"),
            )
        }
    }

    @Test
    fun `a preview with no voice is refused rather than dispatched`() {
        // Without a voice the request would use whatever the backend defaults to,
        // which is not the voice the user asked to hear.
        listOf(null, "", "  ").forEach { voice ->
            assertEquals(
                "preview voice=$voice",
                SpeechCommand.None,
                SpeechCommand.resolve(SpeechService.ACTION_PREVIEW, "Hello", null, 0, voice),
            )
        }
    }

    @Test
    fun `a preview does not carry a resume offset`() {
        // A preview is a sample, not a position in a passage.
        val cmd = SpeechCommand.resolve(
            action = SpeechService.ACTION_PREVIEW,
            text = "Hello",
            label = null,
            fromChar = 500,
            voiceId = "sonia",
        )
        assertEquals(SpeechCommand.Preview("sonia", "Hello"), cmd)
    }
}

/**
 * The engine's preview path.
 *
 * These matter because a preview reuses the read machinery — the same transport, the
 * same player, the same temp-file handling — so it can easily corrupt read state if
 * it is not kept separate.
 */
class TtsEnginePreviewTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Harness(
        val engine: TtsEngine,
        val transport: FakeTransport,
        val player: FakeAudioPlayer,
        val listener: RecordingListener,
    )

    private fun harness(
        scope: TestScope,
        result: SynthesisResult = SynthesisResult.Success(
            bytes = ByteArray(64) { 1 },
            contentType = "audio/mpeg",
            engineLabel = "edge_tts",
        ),
    ): Harness {
        val transport = FakeTransport(result = result)
        val player = FakeAudioPlayer()
        val listener = RecordingListener()
        val engine = TtsEngine(
            settings = FakeSettings(backendUrl = "https://example.test", preferOnline = true),
            cacheDir = temp.newFolder("cache"),
            transport = transport,
            player = player,
            offline = FakeOfflineVoice(ready = true),
            scope = scope,
            mainDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
        engine.listener = listener
        return Harness(engine, transport, player, listener)
    }

    @Test
    fun `a preview requests the named voice and plays the sample`() = runTest {
        val h = harness(this)
        h.engine.preview("Hello, I am Sonia.", "sonia")
        advanceUntilIdle()

        assertEquals(1, h.transport.requests.size)
        assertEquals("sonia", h.transport.requests.first().second)
        assertEquals("Hello, I am Sonia.", h.transport.requests.first().first)
        assertTrue("the sample should be playing", h.player.playing)
        assertTrue("the engine should report it is previewing", h.engine.isPreviewing)
    }

    @Test
    fun `a finished preview reports completion and stops previewing`() = runTest {
        val h = harness(this)
        h.engine.preview("Hello.", "aria")
        advanceUntilIdle()

        h.player.complete()
        advanceUntilIdle()

        assertFalse("previewing must end when the sample ends", h.engine.isPreviewing)
        assertEquals(1, h.listener.events.filterIsInstance<RecordingListener.Event.Completed>().size)
    }

    @Test
    fun `a preview never presents the sample as the passage`() = runTest {
        val h = harness(this)
        val passage = "This is my actual passage, which is quite long and worth reading."
        h.engine.speak(passage)
        advanceUntilIdle()

        val sample = "Hello, I am Jenny."
        h.engine.preview(sample, "jenny")
        advanceUntilIdle()

        // A sample supersedes the read, because two audio streams cannot play at
        // once. What must never happen is the sample being reported as the passage:
        // that would make the progress bar and "carry on" describe a read that is not
        // happening. The activity records the position before dispatching a preview,
        // so the user's place survives; the engine must not claim the sample is it.
        assertTrue("the sample should be the thing playing", h.engine.isPreviewing)
        assertFalse(
            "the sample must not be reported as the passage",
            h.engine.totalChars == sample.length,
        )
    }

    @Test
    fun `a preview leaves nothing to resume`() = runTest {
        // The sample is not a resumable read, so resuming after one must not continue
        // the sample. The activity's "carry on" is driven by its own stored passage,
        // not by engine state, so this pins that the engine offers nothing.
        val h = harness(this)
        h.engine.preview("Hello, I am Aria.", "aria")
        advanceUntilIdle()
        h.player.complete()
        advanceUntilIdle()

        assertEquals(
            "a finished sample leaves no passage to resume",
            0,
            h.engine.totalChars,
        )
    }

    @Test
    fun `a preview failure is reported and does not look like a read failure`() = runTest {
        val h = harness(this, result = SynthesisResult.Failure("All TTS engines failed"))
        h.engine.preview("Hello.", "george")
        advanceUntilIdle()

        assertEquals(1, h.listener.failures.size)
        assertEquals("All TTS engines failed", h.listener.failures.first().msg)
        // No fallback: a sample is not worth degrading the offline voice for.
        assertEquals(null, h.listener.failures.first().fellBackTo)
        assertFalse(h.engine.isPreviewing)
    }

    @Test
    fun `an empty preview is refused before any request is made`() = runTest {
        val h = harness(this)
        h.engine.preview("   ", "aria")
        advanceUntilIdle()

        assertEquals(0, h.transport.requests.size)
        assertEquals(1, h.listener.failures.size)
    }

    @Test
    fun `a preview cannot start when the online voice is not configured`() = runTest {
        val transport = FakeTransport()
        val engine = TtsEngine(
            settings = FakeSettings(backendUrl = "", preferOnline = true),
            cacheDir = temp.newFolder("cache"),
            transport = transport,
            player = FakeAudioPlayer(),
            offline = FakeOfflineVoice(ready = true),
            scope = this,
            mainDispatcher = StandardTestDispatcher(testScheduler),
        )
        val listener = RecordingListener()
        engine.listener = listener

        engine.preview("Hello.", "aria")
        advanceUntilIdle()

        assertEquals("no request without a backend", 0, transport.requests.size)
        assertEquals(1, listener.failures.size)
    }
}
