package com.arkadia.sonata.engine

/**
 * The tiers Sonata can speak in.
 *
 * The specification's decision is that there is exactly **one** tier: offline
 * neural. This type exists to make that decision checkable rather than merely
 * stated — [requiresNetwork] is false for every value, and a test asserts it.
 *
 * The platform fallback is kept as a distinct value because a build with no native
 * runtime linked must still be able to speak, and pretending otherwise would mean
 * crashing instead of degrading. But it is a *fallback*, not a tier: it is chosen
 * only when the neural model is unavailable, never in preference to it.
 */
enum class EngineTier {
    /** On-device neural model. The only real tier. */
    NEURAL_OFFLINE,

    /**
     * The platform's built-in TTS.
     *
     * Used only when no neural model is available. Still offline, still no API key —
     * it is a degradation, not a different architecture.
     */
    PLATFORM_FALLBACK,
}

object EnginePolicy {

    /**
     * Whether [tier] needs a network connection.
     *
     * Always false. This is the offline boundary expressed as a function, so the
     * claim "Sonata never uses the network" is a property a test can check rather
     * than a sentence in a document.
     */
    fun requiresNetwork(tier: EngineTier): Boolean = when (tier) {
        EngineTier.NEURAL_OFFLINE -> false
        EngineTier.PLATFORM_FALLBACK -> false
    }

    /**
     * The tier to use.
     *
     * Neural when a model is available, the platform fallback otherwise. There is no
     * third case and no network case, which is the whole point.
     */
    fun selectTier(neuralModelAvailable: Boolean): EngineTier =
        if (neuralModelAvailable) EngineTier.NEURAL_OFFLINE else EngineTier.PLATFORM_FALLBACK

    /**
     * Whether the app may make a network request at all.
     *
     * False. Kept as a named function so the answer is greppable and so a future
     * change that wants to add a network path has to change this line and fail a
     * test, rather than quietly adding a client somewhere.
     */
    fun networkPermitted(): Boolean = false
}
