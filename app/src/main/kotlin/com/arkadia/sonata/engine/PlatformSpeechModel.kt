package com.arkadia.sonata.engine

import com.arkadia.sonata.export.WavReader

/**
 * The platform speech engine, behind a seam.
 *
 * `android.speech.tts.TextToSpeech` can only render to a **file**
 * (`synthesizeToFile`), never to a buffer, and it reports completion through a
 * callback. Both facts are Android-shaped, so they are confined to this interface
 * and its one Android implementation. Everything above it — the model, the engine,
 * the library — sees a suspend function that returns WAV bytes.
 *
 * That confinement is what makes the adapter testable: [PlatformSpeechModel] is
 * pure JVM and is exercised against a fake implementation of this interface, so
 * the WAV→PCM conversion, the failure mapping and the version attribution are all
 * verified without a device or an emulator.
 */
interface PlatformSynthesis {

    /**
     * Render [text] to a complete WAV file and return its bytes.
     *
     * Returns null when the engine is unavailable or produced nothing. Throws only
     * for a programming error; an ordinary engine failure is null, because every
     * failure path in the engine reports rather than crashes.
     *
     * [speed] is a multiplier (1.0 = normal) and [pitch] is a multiplier
     * (1.0 = unchanged), matching the platform engine's own units.
     */
    suspend fun synthesizeToWav(
        text: String,
        voiceId: String?,
        speed: Float,
        pitch: Float,
    ): ByteArray?

    /** Whether the underlying engine finished initialising. */
    fun isAvailable(): Boolean
}

/**
 * A [SpeechModel] backed by the platform speech engine.
 *
 * This is the implementation that makes Sonata able to speak on a device with no
 * neural model installed. It is the **platform fallback tier** of
 * [EnginePolicy.selectTier], and it is genuinely offline: the platform engine runs
 * on-device and this class has no network type anywhere in it.
 *
 * The audio is read back from the WAV the engine wrote rather than assumed. The
 * sample rate comes from the file, not from [descriptor], because the platform
 * engine chooses its own rate and a descriptor that disagreed with the audio would
 * make every duration wrong.
 */
class PlatformSpeechModel(
    override val descriptor: ModelDescriptor,
    private val synthesis: PlatformSynthesis,
    private val speed: Float = 1.0f,
    private val pitch: Float = 1.0f,
) : SpeechModel {

    override suspend fun isReady(): Boolean = synthesis.isAvailable()

    override suspend fun synthesize(
        text: String,
        voice: VoiceReference?,
        onProgress: (Float) -> Unit,
    ): SynthesisOutput {
        if (text.isBlank()) return SynthesisOutput.Failure("There is nothing to speak")
        if (!synthesis.isAvailable()) {
            return SynthesisOutput.Failure("The device speech engine is not available")
        }

        // A cloned voice cannot be produced by the platform engine: it has no
        // adapter stage. Saying so is required — silently speaking in the stock
        // voice would break the promise that Sonata speaks in the user's voice.
        if (voice is VoiceReference.Cloned) {
            return SynthesisOutput.Failure(
                "The device speech engine cannot speak in a cloned voice",
            )
        }

        val voiceId = (voice as? VoiceReference.Base)?.voiceId

        onProgress(0.05f)
        val wav = try {
            synthesis.synthesizeToWav(text, voiceId, speed, pitch)
        } catch (e: Exception) {
            return SynthesisOutput.Failure("Speech failed: ${e.javaClass.simpleName}")
        }
        if (wav == null || wav.isEmpty()) {
            return SynthesisOutput.Failure("The device speech engine produced no audio")
        }

        val parsed = WavReader.parse(wav)
            ?: return SynthesisOutput.Failure("The device speech engine produced unreadable audio")
        if (parsed.pcm.isEmpty()) {
            return SynthesisOutput.Failure("The device speech engine produced no audio")
        }

        onProgress(1.0f)
        return SynthesisOutput.Success(
            pcm = parsed.pcm,
            // From the file, not the descriptor: the engine picks its own rate.
            sampleRate = parsed.sampleRate,
            durationMs = 0, // derived from the PCM by the engine
            modelVersion = descriptor.recordedVersion,
        )
    }

    override fun close() = Unit
}

/**
 * The descriptor for the platform engine.
 *
 * Not a bundled model: it is the device's own engine, so its size and RAM
 * requirement are the platform's, not a file's. The values are deliberately
 * nominal — [ModelCompatibility] must never refuse the fallback tier, because a
 * device that cannot hold a neural model is exactly the device that needs it.
 */
object PlatformModel {

    const val ID = "platform-tts"

    /**
     * The platform engine's descriptor.
     *
     * `sizeBytes` and `minRamBytes` are zero because there is no model file to
     * hold: the engine is part of the OS. A non-zero value here would make the
     * compatibility gate refuse the fallback on a low-storage device, which is the
     * opposite of what a fallback is for.
     */
    val DESCRIPTOR = ModelDescriptor(
        id = ID,
        family = "platform",
        version = "1.0",
        sizeBytes = 0,
        minRamBytes = 0,
        // Nominal; the real rate is read from each rendered file.
        sampleRate = 22_050,
        languages = listOf("en-US", "en-GB"),
        role = ModelRole.BASE_SYNTHESIZER,
    )
}
