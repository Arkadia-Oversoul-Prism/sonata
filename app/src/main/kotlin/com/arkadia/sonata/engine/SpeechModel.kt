package com.arkadia.sonata.engine

/**
 * The swappable model runtime.
 *
 * This is the abstraction the specification calls for: **one runtime interface**
 * with the concrete backends behind it. ONNX Runtime Mobile, sherpa-onnx and a
 * CPU fallback all implement this, and nothing above it knows which is in use.
 * That is what makes the runtime decision reversible — a backend that fails the
 * device gate is replaced without touching the engine, the library or the UI.
 *
 * The interface has **no URL parameter and no network type anywhere in it**. The
 * offline boundary is expressed by the shape of the type rather than by a rule a
 * caller must remember, which is the only way it survives a future contributor.
 */
interface SpeechModel {

    /** The model this instance runs. */
    val descriptor: ModelDescriptor

    /** Whether the runtime loaded successfully and can synthesise. */
    suspend fun isReady(): Boolean

    /**
     * Synthesise [text] in [voice].
     *
     * [voice] is null for a bundled base voice, or a profile for a cloned voice.
     * [onProgress] reports 0..1 and must be monotonic.
     *
     * Implementations must not throw for an ordinary failure; they return
     * [SynthesisOutput.Failure] with a user-presentable reason, because every
     * failure path in the engine reports rather than crashes.
     */
    suspend fun synthesize(
        text: String,
        voice: VoiceReference?,
        onProgress: (Float) -> Unit,
    ): SynthesisOutput

    /** Release native resources. */
    fun close()
}

/**
 * Which voice to speak in.
 *
 * Either a bundled base voice or a user's adapted model. Kept as a sealed type so
 * "the user's own voice" and "a stock voice" cannot be confused: the specification
 * requires the user's cloned voice, and a type that can silently fall back to a
 * stock voice would make that requirement unenforceable.
 */
sealed class VoiceReference {

    /** A bundled base voice, identified by the model's own voice id. */
    data class Base(val voiceId: String) : VoiceReference()

    /**
     * A user's adapted voice.
     *
     * [adapterPath] is the locally trained tone-colour adapter and [baseVoiceId] is
     * the base voice it converts. Both are needed: the adapter cannot generate
     * speech, only recolour it.
     */
    data class Cloned(
        val profileId: Long,
        val baseVoiceId: String,
        val adapterPath: String,
    ) : VoiceReference()
}

/** Result of a synthesis. */
sealed class SynthesisOutput {

    /**
     * Raw PCM, 16-bit little-endian, at [sampleRate].
     *
     * PCM rather than an encoded container because the engine writes the canonical
     * WAV itself ([com.arkadia.sonata.export.WavWriter]) and export transcodes from
     * it; encoding here would mean decoding again to export.
     */
    class Success(
        val pcm: ByteArray,
        val sampleRate: Int,
        val durationMs: Long,
        /** The model version that produced this audio, recorded on the narration. */
        val modelVersion: String,
    ) : SynthesisOutput()

    /** [reason] is safe to surface to the user. */
    class Failure(val reason: String) : SynthesisOutput()
}
