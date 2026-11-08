package com.arkadia.sonata.engine

/**
 * The swappable model runtime.
 *
 * This is the specification's "one swappable runtime abstraction". Everything above
 * it — the engine, the Voice Studio, the narration pipeline — talks to this
 * interface and never to ONNX Runtime, sherpa-onnx or any other binding. That is
 * what makes the runtime replaceable without touching the product logic, and it is
 * what makes the engine testable with a fake model instead of a 80 MB binary.
 */
interface SpeechModel {

    /** The model this instance runs. */
    val descriptor: ModelDescriptor

    /**
     * Whether the model is loaded and ready to synthesise.
     *
     * A model that is not ready must not be used; the engine checks this before
     * starting a narration so a failure is reported before a row is created rather
     * than after.
     */
    suspend fun isReady(): Boolean

    /**
     * Synthesise [text] in [voice].
     *
     * Returns raw PCM at [ModelDescriptor.sampleRate]. The caller owns the bytes and
     * is responsible for writing them; the model does not touch the filesystem,
     * which is what keeps it swappable and testable.
     *
     * [onProgress] is called with 0..1 as synthesis proceeds. It must be monotonic;
     * a progress bar that goes backwards is worse than no progress bar.
     */
    suspend fun synthesize(
        text: String,
        voice: VoiceReference?,
        onProgress: (Float) -> Unit = {},
    ): SynthesisResult

    /** Release native resources. Safe to call more than once. */
    fun close()
}

/**
 * Which voice to speak in.
 *
 * A sealed type rather than a nullable profile id, because the three cases are
 * genuinely different: a bundled voice needs no user data, an enrolled voice has
 * samples but no model, and a cloned voice has a trained adapter. Collapsing them
 * into one nullable field would make "enrolled but not trained" indistinguishable
 * from "no voice", which is exactly the state the UI must show differently.
 */
sealed class VoiceReference {

    /** A bundled base voice. No user data involved. */
    data class Bundled(val name: String) : VoiceReference()

    /**
     * An enrolled profile: samples captured, no model trained.
     *
     * Usable as a reference for training, not for generation in the user's voice.
     */
    data class Enrolled(val profileId: Long, val samplePaths: List<String>) : VoiceReference()

    /**
     * A cloned voice: an adapter was trained and activated.
     *
     * [adapterPath] is the trained tone-colour converter. This is the only reference
     * that produces audio in the user's own voice.
     */
    data class Cloned(
        val profileId: Long,
        val adapterPath: String,
        val adapterVersion: String,
    ) : VoiceReference()
}

/** The result of a synthesis. */
data class SynthesisResult(
    /** Raw PCM, 16-bit little-endian mono. */
    val pcm: ByteArray,
    /** Sample rate of [pcm]. */
    val sampleRate: Int,
    /** Duration in milliseconds. */
    val durationMs: Long,
    /** The model version that produced this audio. */
    val modelVersion: String,
) {
    // ByteArray needs explicit equals/hashCode; the data class default compares
    // references, which would make two identical results unequal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SynthesisResult) return false
        return pcm.contentEquals(other.pcm) &&
            sampleRate == other.sampleRate &&
            durationMs == other.durationMs &&
            modelVersion == other.modelVersion
    }

    override fun hashCode(): Int {
        var result = pcm.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + modelVersion.hashCode()
        return result
    }
}

/**
 * The native binding seam.
 *
 * A thin wrapper over whatever runtime is linked, so the model classes above can be
 * written once. [UnavailableNativeSession] is the default: a build with no runtime
 * linked reports "not loaded" and the engine degrades to the platform fallback
 * rather than crashing.
 */
interface NativeSession {

    /** Whether a native runtime is actually linked into this build. */
    fun isAvailable(): Boolean

    /** Load a model from [path]. Returns false if the runtime cannot load it. */
    fun load(path: String): Boolean

    /** Run inference on [input] and return the output bytes. */
    fun run(input: ByteArray): ByteArray

    /** Release the session. */
    fun close()
}

/**
 * The session used when no native runtime is linked.
 *
 * Reports unavailable rather than throwing, so a build without ONNX Runtime or
 * sherpa-onnx still starts and still runs its tests. The contract is present; the
 * binary is a separate delivery.
 */
object UnavailableNativeSession : NativeSession {
    override fun isAvailable(): Boolean = false
    override fun load(path: String): Boolean = false
    override fun run(input: ByteArray): ByteArray = ByteArray(0)
    override fun close() = Unit
}
