package com.arkadia.sonata.engine

/**
 * A [SpeechModel] that runs a base synthesizer.
 *
 * The concrete runtime is injected as a [NativeSession], so this class holds the
 * *logic* — progress reporting, failure mapping, version attribution — and the
 * native binding is a seam. That is what lets the engine be tested on the JVM
 * without ONNX Runtime or a model file, and it is why the runtime decision is
 * reversible: swapping sherpa-onnx for ONNX Runtime Mobile replaces the session,
 * not this class.
 */
class BaseSpeechModel(
    override val descriptor: ModelDescriptor,
    private val session: NativeSession = UnavailableNativeSession,
) : SpeechModel {

    override suspend fun isReady(): Boolean = session.isLoaded()

    override suspend fun synthesize(
        text: String,
        voice: VoiceReference?,
        onProgress: (Float) -> Unit,
    ): SynthesisOutput {
        if (!session.isLoaded()) {
            return SynthesisOutput.Failure("The speech model is not loaded")
        }
        val voiceId = when (voice) {
            is VoiceReference.Base -> voice.voiceId
            is VoiceReference.Cloned -> voice.baseVoiceId
            null -> DEFAULT_VOICE_ID
        }
        return try {
            val pcm = session.synthesize(text, voiceId, onProgress)
            if (pcm.isEmpty()) {
                SynthesisOutput.Failure("The model produced no audio")
            } else {
                SynthesisOutput.Success(
                    pcm = pcm,
                    sampleRate = descriptor.sampleRate,
                    durationMs = 0, // derived from the PCM by the engine
                    modelVersion = descriptor.recordedVersion,
                )
            }
        } catch (e: Exception) {
            SynthesisOutput.Failure("Synthesis failed: ${e.javaClass.simpleName}")
        }
    }

    override fun close() = session.close()

    private companion object {
        const val DEFAULT_VOICE_ID = "default"
    }
}

/**
 * A [SpeechModel] that runs a base synthesizer and then recolours its output to
 * the user's voice.
 *
 * This is the architecture the specification requires, made concrete: the base
 * synthesizer is **frozen** and the tone-colour converter is the only trainable
 * part. The converter cannot generate speech — it changes the tone colour of
 * speech the base produced — which is why both are needed and why the base is
 * never fine-tuned on-device.
 */
class CloningSpeechModel(
    private val base: ModelDescriptor,
    private val converter: ModelDescriptor,
    private val voice: VoiceReference.Cloned,
    private val baseSession: NativeSession = UnavailableNativeSession,
    private val converterSession: NativeSession = UnavailableNativeSession,
) : SpeechModel {

    override val descriptor: ModelDescriptor = base

    override suspend fun isReady(): Boolean =
        baseSession.isLoaded() && converterSession.isLoaded()

    override suspend fun synthesize(
        text: String,
        voice: VoiceReference?,
        onProgress: (Float) -> Unit,
    ): SynthesisOutput {
        if (!baseSession.isLoaded()) {
            return SynthesisOutput.Failure("The base speech model is not loaded")
        }
        if (!converterSession.isLoaded()) {
            return SynthesisOutput.Failure("The voice model is not loaded")
        }
        // The constructor's voice, not the parameter: this model was built for one
        // specific cloned voice, and speaking in a different one would be a bug.
        val target = this.voice
        return try {
            // Stage 1: the frozen base synthesizer produces speech in its own voice.
            val basePcm = baseSession.synthesize(text, target.baseVoiceId) { p ->
                // The base stage is the first 80% of the work; the conversion is
                // fast, so progress is weighted rather than split evenly.
                onProgress(p * 0.8f)
            }
            if (basePcm.isEmpty()) {
                return SynthesisOutput.Failure("The base model produced no audio")
            }
            // Stage 2: the trained adapter recolours it to the user's voice.
            val converted = converterSession.convert(basePcm, target.adapterPath) { p ->
                onProgress(0.8f + p * 0.2f)
            }
            if (converted.isEmpty()) {
                return SynthesisOutput.Failure("The voice model produced no audio")
            }
            SynthesisOutput.Success(
                pcm = converted,
                sampleRate = base.sampleRate,
                durationMs = 0,
                // Both versions are recorded, because a narration made with a
                // cloned voice depends on both and must stay attributable to both.
                modelVersion = "${base.recordedVersion}+${converter.recordedVersion}",
            )
        } catch (e: Exception) {
            SynthesisOutput.Failure("Voice conversion failed: ${e.javaClass.simpleName}")
        }
    }

    override fun close() {
        baseSession.close()
        converterSession.close()
    }
}

/**
 * The native binding seam.
 *
 * ONNX Runtime Mobile and sherpa-onnx both implement this. Keeping it this narrow
 * — load, synthesise, convert, close — is what makes the two interchangeable and
 * what makes the whole engine testable without either.
 */
interface NativeSession {

    /** Whether the model is loaded and ready. */
    fun isLoaded(): Boolean

    /** Synthesise [text] in [voiceId], reporting 0..1 progress. */
    fun synthesize(text: String, voiceId: String, onProgress: (Float) -> Unit = {}): ByteArray

    /**
     * Recolour [pcm] to the voice described by [adapterPath].
     *
     * Only a tone-colour converter implements this meaningfully; a base
     * synthesizer's session returns [pcm] unchanged.
     */
    fun convert(pcm: ByteArray, adapterPath: String, onProgress: (Float) -> Unit = {}): ByteArray

    /** Release native resources. */
    fun close()
}

/**
 * The session used when no native runtime is present.
 *
 * It reports "not loaded" rather than throwing, so a build without the native
 * library degrades to the platform fallback tier instead of crashing. This is the
 * honest state of a build that has the contract but not the binary.
 */
object UnavailableNativeSession : NativeSession {
    override fun isLoaded(): Boolean = false
    override fun synthesize(text: String, voiceId: String, onProgress: (Float) -> Unit): ByteArray =
        ByteArray(0)
    override fun convert(pcm: ByteArray, adapterPath: String, onProgress: (Float) -> Unit): ByteArray =
        pcm
    override fun close() = Unit
}
