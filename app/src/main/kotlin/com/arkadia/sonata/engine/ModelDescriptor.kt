package com.arkadia.sonata.engine

/**
 * A speech model Sonata can run.
 *
 * The specification requires **model versions and compatibility recorded**, and
 * this is the record. A narration stores the [version] of the model that made it,
 * so audio stays attributable after a model is replaced — without that, "which
 * model made this?" is unanswerable after an update.
 *
 * Models are **bundled or side-loaded, never downloaded at runtime**. There is no
 * URL field here on purpose: the offline boundary is a property of the type, not a
 * rule a caller must remember.
 */
data class ModelDescriptor(
    /** Stable id, e.g. `kokoro-82m-v1.0`. */
    val id: String,
    /** Family, e.g. `kokoro`, `piper`, `openvoice`. */
    val family: String,
    /** Version string, recorded on every narration. */
    val version: String,
    /** On-disk size in bytes. */
    val sizeBytes: Long,
    /** Minimum RAM the runtime needs to load it. */
    val minRamBytes: Long,
    /** Output sample rate in Hz. */
    val sampleRate: Int,
    /** BCP-47 language tags the model speaks. */
    val languages: List<String>,
    /**
     * Whether this model is a *base synthesizer* or a *tone-colour converter*.
     *
     * The distinction is the architecture: a base synthesizer produces speech, a
     * converter changes its tone colour to the user's voice. OpenVoice is a
     * converter, not a generator, so a cloning pipeline is
     * `base → converter → user's voice`, and the two are never interchangeable.
     */
    val role: ModelRole,
) {
    /** The version string recorded on a narration made with this model. */
    val recordedVersion: String get() = "$id@$version"
}

/** What part a model plays in the pipeline. */
enum class ModelRole {
    /** Produces speech from text. Kokoro, Piper. */
    BASE_SYNTHESIZER,

    /** Converts the tone colour of speech to a target voice. OpenVoice. */
    TONE_CONVERTER,
}
