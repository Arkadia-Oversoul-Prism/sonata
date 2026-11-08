package com.arkadia.sonata.engine

/**
 * A speech model Sonata can run.
 *
 * The specification requires model versions and compatibility to be recorded, and
 * this is the record. A narration stores the [version] of the model that made it,
 * so audio stays attributable after a model is replaced.
 */
data class ModelDescriptor(
    /** Stable id, e.g. `kokoro-82m-v1.0`. */
    val id: String,
    /** Model family: `kokoro`, `piper`, `openvoice`. */
    val family: String,
    /** Version string, recorded on every narration this model produces. */
    val version: String,
    /** On-disk size in bytes. */
    val sizeBytes: Long,
    /** Minimum RAM the runtime needs to load it, in bytes. */
    val minRamBytes: Long,
    /** Output sample rate in Hz. */
    val sampleRate: Int,
    /** What this model does. */
    val role: Role,
    /** Languages it can speak, BCP-47 prefixes. */
    val languages: List<String> = listOf("en"),
) {
    /**
     * What the model is for.
     *
     * The distinction matters because the architecture is a frozen base plus a
     * trainable converter: a [BASE] is never trained, and a [TONE_CONVERTER] is the
     * only thing that is.
     */
    enum class Role {
        /** A text-to-speech synthesizer. Frozen; never trained on-device. */
        BASE,
        /** A tone-colour converter that adapts a base voice to a target timbre. */
        TONE_CONVERTER,
    }

    /** Whether this is the trainable part of the architecture. */
    val isTrainable: Boolean get() = role == Role.TONE_CONVERTER
}
