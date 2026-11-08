package com.arkadia.sonata.engine

/**
 * The tiers Sonata can speak in.
 *
 * There is deliberately **no online tier**. The specification requires offline to
 * be the only tier, so the enum has no member that could reach a network, and
 * [EnginePolicy.requiresNetwork] is a total function that returns false for every
 * value. A future contributor who wants an online tier has to add an enum member
 * and change a test that asserts the property — which is the point.
 */
enum class TtsTier {
    /** The local neural model. The primary and preferred tier. */
    NEURAL_LOCAL,

    /**
     * The platform `TextToSpeech` engine.
     *
     * A fallback for a device where no neural model is installed or compatible. It
     * is still fully offline: the platform engine runs on-device.
     */
    PLATFORM_FALLBACK,
}

/**
 * The engine's policy, as pure functions.
 *
 * Separated from the engine so the offline boundary is testable without a device,
 * a model or a coroutine. The single most important function here is
 * [requiresNetwork]: it is the machine-checkable form of "no network dependency
 * for core TTS".
 */
object EnginePolicy {

    /**
     * Whether [tier] needs a network connection.
     *
     * Always false. This is not a configuration — it is the specification's offline
     * boundary expressed as a function, and a test asserts it for every tier so a
     * new tier cannot be added that quietly needs the network.
     */
    fun requiresNetwork(tier: TtsTier): Boolean = false

    /**
     * The tier to use, given what is available.
     *
     * Prefers the neural tier whenever a compatible model is installed, and falls
     * back to the platform engine otherwise. Never returns a tier that needs the
     * network, because none does.
     */
    fun selectTier(neuralModelAvailable: Boolean): TtsTier =
        if (neuralModelAvailable) TtsTier.NEURAL_LOCAL else TtsTier.PLATFORM_FALLBACK

    /**
     * Whether a narration's recorded model version is still installed.
     *
     * Used to tell the user that a narration was made with a model that has since
     * been removed, rather than silently re-synthesising it in a different voice.
     */
    fun isModelStillInstalled(recordedVersion: String?, installed: List<ModelDescriptor>): Boolean {
        if (recordedVersion.isNullOrBlank()) return false
        return installed.any { it.recordedVersion == recordedVersion }
    }
}
