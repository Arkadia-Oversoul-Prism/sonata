package com.arkadia.sonata.engine

/**
 * The bundled model catalogue.
 *
 * These are the models Sonata ships or side-loads. The values are the published
 * figures for each model, recorded here so the compatibility gate has real numbers
 * to work with rather than guesses. Nothing here is downloaded at runtime: the
 * catalogue describes what *may* be installed, and [ModelRegistry.installed]
 * reports what *is*.
 *
 * The two-tier choice is the specification's: **Kokoro-82M for quality** (the
 * priority) and **Piper/VITS for speed** (the fallback for a device that cannot
 * hold Kokoro). Both run under the same runtime abstraction, so the choice is a
 * data decision, not an architectural one.
 */
object BundledModels {

    /**
     * Kokoro-82M, the quality tier.
     *
     * ~80 MB quantised. The highest-quality small offline model available, and the
     * one the specification's "highest natural quality while offline" points at.
     */
    val KOKORO_82M = ModelDescriptor(
        id = "kokoro-82m",
        family = "kokoro",
        version = "1.0",
        sizeBytes = 80L * 1024 * 1024,
        minRamBytes = 300L * 1024 * 1024,
        sampleRate = 24_000,
        languages = listOf("en-US", "en-GB"),
        role = ModelRole.BASE_SYNTHESIZER,
    )

    /**
     * Piper/VITS, the speed tier.
     *
     * ~20 MB. Lower quality than Kokoro but far faster and far smaller, which is
     * what makes it the right choice on a low-end device.
     */
    val PIPER_VITS = ModelDescriptor(
        id = "piper-vits",
        family = "piper",
        version = "1.0",
        sizeBytes = 20L * 1024 * 1024,
        minRamBytes = 120L * 1024 * 1024,
        sampleRate = 22_050,
        languages = listOf("en-US"),
        role = ModelRole.BASE_SYNTHESIZER,
    )

    /**
     * OpenVoice v2's tone-colour converter.
     *
     * ~15 MB. MIT-licensed, so it can ship. It is a **converter, not a
     * generator**: it recolours speech the base synthesizer produced, which is why
     * the base is frozen and only this is trained on-device.
     */
    val OPENVOICE_V2_CONVERTER = ModelDescriptor(
        id = "openvoice-v2-converter",
        family = "openvoice",
        version = "2.0",
        sizeBytes = 15L * 1024 * 1024,
        minRamBytes = 150L * 1024 * 1024,
        sampleRate = 24_000,
        languages = listOf("en-US", "en-GB"),
        role = ModelRole.TONE_CONVERTER,
    )

    /** Every model Sonata knows about. */
    val ALL = listOf(KOKORO_82M, PIPER_VITS, OPENVOICE_V2_CONVERTER)
}
