package com.arkadia.sonata.offline

import com.arkadia.sonata.engine.ModelDescriptor
import com.arkadia.sonata.engine.ModelRole
import com.arkadia.sonata.engine.PlatformModel
import com.arkadia.sonata.engine.PlatformSpeechModel
import com.arkadia.sonata.engine.PlatformSynthesis
import com.arkadia.sonata.engine.SynthesisOutput
import com.arkadia.sonata.engine.VoiceReference
import com.arkadia.sonata.export.WavReader
import com.arkadia.sonata.export.WavWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The platform speech engine adapter.
 *
 * These tests are the difference between "the interface exists" and "the adapter
 * works". [PlatformSpeechModel] is pure JVM, so everything except the Android
 * `TextToSpeech` call itself is verified here: the WAV→PCM conversion, the sample
 * rate being taken from the file rather than the descriptor, the failure mapping,
 * and the refusal to fake a cloned voice.
 *
 * What is NOT verified here, and must not be claimed: that a real device's engine
 * produces audio. That needs a device. [FakePlatformSynthesis] is a test double and
 * is named as one.
 */
class PlatformSpeechModelTest {

    /** A [PlatformSynthesis] that returns a real WAV built by [WavWriter]. */
    private class FakePlatformSynthesis(
        private val wav: ByteArray?,
        private val available: Boolean = true,
        private val throwOnCall: Boolean = false,
    ) : PlatformSynthesis {

        var calls = 0
            private set
        var lastText: String? = null
            private set
        var lastVoiceId: String? = null
            private set
        var lastSpeed: Float = 0f
            private set

        override suspend fun synthesizeToWav(
            text: String,
            voiceId: String?,
            speed: Float,
            pitch: Float,
        ): ByteArray? {
            calls++
            lastText = text
            lastVoiceId = voiceId
            lastSpeed = speed
            if (throwOnCall) throw IllegalStateException("engine exploded")
            return wav
        }

        override fun isAvailable(): Boolean = available
    }

    /** A WAV with [frames] samples of a non-silent square wave at [rate]. */
    private fun wav(frames: Int = 2400, rate: Int = 24_000): ByteArray {
        val pcm = ByteArray(frames * 2)
        for (i in 0 until frames) {
            val v = if (i % 2 == 0) 8000 else -8000
            pcm[i * 2] = (v and 0xFF).toByte()
            pcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return WavWriter.wrap(pcm, rate)
    }

    private fun model(s: PlatformSynthesis) =
        PlatformSpeechModel(PlatformModel.DESCRIPTOR, s)

    // ── The happy path ────────────────────────────────────────────────────────

    @Test
    fun `a real WAV is converted to PCM and reported as success`() = runBlocking {
        val source = wav(frames = 2400, rate = 24_000)
        val fake = FakePlatformSynthesis(source)
        val out = model(fake).synthesize("Hello there.", null) {}

        assertTrue("expected success, got $out", out is SynthesisOutput.Success)
        val success = out as SynthesisOutput.Success
        // The payload is the WAV's data chunk, not the whole file: a 44-byte header
        // left in would be played as a click at the start of every narration.
        assertEquals(2400 * 2, success.pcm.size)
        assertEquals(24_000, success.sampleRate)
        assertEquals(1, fake.calls)
        assertEquals("Hello there.", fake.lastText)
    }

    @Test
    fun `the sample rate comes from the file, not the descriptor`() = runBlocking {
        // The platform engine picks its own rate. A descriptor that disagreed with
        // the audio would make every duration wrong, so the file must win.
        val fake = FakePlatformSynthesis(wav(frames = 1000, rate = 16_000))
        val out = model(fake).synthesize("Hi.", null) {} as SynthesisOutput.Success
        assertEquals(16_000, out.sampleRate)
        assertTrue(
            "descriptor rate must not be used",
            out.sampleRate != PlatformModel.DESCRIPTOR.sampleRate,
        )
    }

    @Test
    fun `progress is monotonic and ends at one`() = runBlocking {
        val seen = mutableListOf<Float>()
        model(FakePlatformSynthesis(wav())).synthesize("Hi.", null) { seen += it }
        assertTrue("progress must be reported", seen.isNotEmpty())
        assertEquals(1.0f, seen.last(), 0.0001f)
        for (i in 1 until seen.size) {
            assertTrue("progress must not go backwards: $seen", seen[i] >= seen[i - 1])
        }
    }

    @Test
    fun `a base voice id is passed through to the engine`() = runBlocking {
        val fake = FakePlatformSynthesis(wav())
        model(fake).synthesize("Hi.", VoiceReference.Base("en-gb-x-gba")) {}
        assertEquals("en-gb-x-gba", fake.lastVoiceId)
    }

    // ── Failure paths ─────────────────────────────────────────────────────────

    @Test
    fun `an unavailable engine fails rather than returning silence`() = runBlocking {
        val fake = FakePlatformSynthesis(wav(), available = false)
        val out = model(fake).synthesize("Hi.", null) {}
        assertTrue(out is SynthesisOutput.Failure)
        // It must not even call the engine: a call would block on a dead engine.
        assertEquals(0, fake.calls)
    }

    @Test
    fun `a null render is a failure, not an empty success`() = runBlocking {
        val out = model(FakePlatformSynthesis(null)).synthesize("Hi.", null) {}
        assertTrue(out is SynthesisOutput.Failure)
        assertTrue((out as SynthesisOutput.Failure).reason.contains("no audio"))
    }

    @Test
    fun `an empty render is a failure`() = runBlocking {
        val out = model(FakePlatformSynthesis(ByteArray(0))).synthesize("Hi.", null) {}
        assertTrue(out is SynthesisOutput.Failure)
    }

    @Test
    fun `unreadable audio is a failure, not a crash`() = runBlocking {
        // Bytes that are not a WAV at all: the engine wrote something, but not
        // something we can interpret. Reporting is the only honest outcome.
        val out = model(FakePlatformSynthesis(ByteArray(200) { 0x7F }))
            .synthesize("Hi.", null) {}
        assertTrue(out is SynthesisOutput.Failure)
        assertTrue((out as SynthesisOutput.Failure).reason.contains("unreadable"))
    }

    @Test
    fun `an engine that throws is reported, not propagated`() = runBlocking {
        val out = model(FakePlatformSynthesis(wav(), throwOnCall = true))
            .synthesize("Hi.", null) {}
        assertTrue(out is SynthesisOutput.Failure)
        assertTrue((out as SynthesisOutput.Failure).reason.contains("Speech failed"))
    }

    @Test
    fun `blank text is refused before the engine is called`() = runBlocking {
        val fake = FakePlatformSynthesis(wav())
        val out = model(fake).synthesize("   ", null) {}
        assertTrue(out is SynthesisOutput.Failure)
        assertEquals(0, fake.calls)
    }

    @Test
    fun `a cloned voice is refused rather than silently downgraded`() = runBlocking {
        // The platform engine has no adapter stage. Speaking in the stock voice
        // when the user asked for their own would be a broken promise.
        val fake = FakePlatformSynthesis(wav())
        val out = model(fake).synthesize(
            "Hi.",
            VoiceReference.Cloned(profileId = 1, baseVoiceId = "default", adapterPath = "/x"),
        ) {}
        assertTrue(out is SynthesisOutput.Failure)
        assertTrue((out as SynthesisOutput.Failure).reason.contains("cloned voice"))
        assertEquals(0, fake.calls)
    }

    // ── The descriptor ────────────────────────────────────────────────────────

    @Test
    fun `the platform descriptor cannot be refused by the compatibility gate`() {
        // A fallback that the gate can refuse is not a fallback. Zero size and zero
        // RAM mean it passes on any device, which is the point.
        assertEquals(0L, PlatformModel.DESCRIPTOR.sizeBytes)
        assertEquals(0L, PlatformModel.DESCRIPTOR.minRamBytes)
        assertEquals(ModelRole.BASE_SYNTHESIZER, PlatformModel.DESCRIPTOR.role)
    }

    @Test
    fun `the platform model reports readiness from the engine`() = runBlocking {
        assertTrue(model(FakePlatformSynthesis(wav(), available = true)).isReady())
        assertFalse(model(FakePlatformSynthesis(wav(), available = false)).isReady())
    }
}

/**
 * [WavReader] against files this code did not write.
 *
 * The platform engine is free to emit chunks in any order, so a fixed 44-byte
 * offset would read metadata as audio. These tests pin the chunk walk.
 */
class WavReaderTest {

    private fun pcm(frames: Int) = ByteArray(frames * 2) { (it % 251).toByte() }

    @Test
    fun `a canonical WAV round-trips through writer and reader`() {
        val payload = pcm(500)
        val parsed = WavReader.parse(WavWriter.wrap(payload, 24_000))
        assertNotNull(parsed)
        assertEquals(24_000, parsed!!.sampleRate)
        assertEquals(1, parsed.channels)
        assertEquals(16, parsed.bitsPerSample)
        assertTrue(payload.contentEquals(parsed.pcm))
    }

    @Test
    fun `a LIST chunk before data is skipped, not read as audio`() {
        // Build a WAV with an extra chunk between fmt and data, which is legal and
        // which a fixed-offset reader would misparse.
        val payload = pcm(100)
        val base = WavWriter.wrap(payload, 22_050)
        val listChunk = "LIST".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(4, 0, 0, 0) + "INFO".toByteArray(Charsets.US_ASCII)
        // Insert after the fmt chunk (offset 36) and fix the RIFF size.
        val out = base.copyOfRange(0, 36) + listChunk + base.copyOfRange(36, base.size)
        val fixed = out.copyOf()
        val newSize = out.size - 8
        fixed[4] = (newSize and 0xFF).toByte()
        fixed[5] = ((newSize ushr 8) and 0xFF).toByte()
        fixed[6] = ((newSize ushr 16) and 0xFF).toByte()
        fixed[7] = ((newSize ushr 24) and 0xFF).toByte()

        val parsed = WavReader.parse(fixed)
        assertNotNull("a LIST chunk must not defeat the parser", parsed)
        assertTrue(payload.contentEquals(parsed!!.pcm))
        assertEquals(22_050, parsed.sampleRate)
    }

    @Test
    fun `a truncated data chunk yields the audio that is present`() {
        val full = WavWriter.wrap(pcm(1000), 24_000)
        val truncated = full.copyOfRange(0, full.size - 500)
        val parsed = WavReader.parse(truncated)
        assertNotNull("a truncated file is still audio", parsed)
        assertEquals(1500, parsed!!.pcm.size)
    }

    @Test
    fun `a non-WAV file is rejected`() {
        assertNull(WavReader.parse(ByteArray(100) { 0x41 }))
        assertNull(WavReader.parse(ByteArray(0)))
        assertNull(WavReader.parse(ByteArray(8)))
    }

    @Test
    fun `a header-only file is rejected rather than returning empty audio`() {
        val headerOnly = WavWriter.wrap(ByteArray(0), 24_000)
        assertNull(WavReader.parse(headerOnly))
    }
}
