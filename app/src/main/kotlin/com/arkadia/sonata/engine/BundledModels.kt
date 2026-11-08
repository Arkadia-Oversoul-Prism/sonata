package com.arkadia.sonata.engine

/**
 * The bundled model catalogue.
 *
 * The figures here are the published ones for each model, recorded so the
 * compatibility gate has real numbers to compare against. The weights themselves
 * are a separate delivery; this is the catalogue, not the binary.
 *
 * The architecture is a **frozen base plus a trainable converter**. That is what
 * makes on-device training fit a phone's thermal budget: the base is never trained,
 * so an epoch touches only the converter's parameters.
 */
object BundledModels {

    /**
     * Kokoro-82M — the quality tier.
     *
     * The highest-quality small offline synthesizer available under a permissive
     * licence. Chosen first because the specification's priority is quality.
     */
    val KOKORO = ModelDescriptor(
        id = "kokoro-82m-v1.0",
        family = "kokoro",
        version = "1.0",
        sizeBytes = 86L * 1024 * 1024,
        minRamBytes = 220L * 1024 * 1024,
        sampleRate = 24_000,
        role = ModelDescriptor.Role.BASE,
    )

    /**
     * Piper/VITS — the speed tier.
     *
     * Much smaller and faster, at lower quality. The fallback for a device that
     * cannot hold Kokoro, so the product degrades in quality rather than in
     * availability.
     */
    val PIPER = ModelDescriptor(
        id = "piper-vits-v1.0",
        family = "piper",
        version = "1.0",
        sizeBytes = 22L * 1024 * 1024,
        minRamBytes = 90L * 1024 * 1024,
        sampleRate = 22_050,
        role = ModelDescriptor.Role.BASE,
    )

    /**
     * OpenVoice v2 tone-colour converter — the trainable part.
     *
     * MIT-licensed, so it can ship commercially. Its two-stage base → converter
     * split is exactly the architecture the specification requires: the base stays
     * frozen and only this compact adapter is trained on-device.
     */
    val OPENVOICE_CONVERTER = ModelDescriptor(
        id = "openvoice-v2-converter",
        family = "openvoice",
        version = "2.0",
        sizeBytes = 12L * 1024 * 1024,
        minRamBytes = 60L * 1024 * 1024,
        sampleRate = 22_050,
        role = ModelDescriptor.Role.TONE_CONVERTER,
    )

    /** Everything this build knows about. */
    val ALL = listOf(KOKORO, PIPER, OPENVOICE_CONVERTER)
}
