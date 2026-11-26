package com.arkadia.sonata

import android.content.Context
import com.arkadia.sonata.data.SonataDatabase
import com.arkadia.sonata.data.SonataRepository
import com.arkadia.sonata.engine.DeviceProfile
import com.arkadia.sonata.engine.InMemoryModelRegistry
import com.arkadia.sonata.engine.OfflineEngine
import com.arkadia.sonata.engine.PlatformModel
import com.arkadia.sonata.engine.PlatformSpeechModel
import com.arkadia.sonata.export.AppPrivateFileStore

/**
 * Builds the production [OfflineEngine].
 *
 * This class exists because of a gap that made the whole engine unreachable: before
 * it, `OfflineEngine` was constructed **only in tests**. The app's playback path
 * went through [TtsEngine], which streams to the speaker and saves nothing, so a
 * narration could never be produced on a device no matter how correct the engine
 * was. A correct component that nothing constructs is not a feature.
 *
 * The wiring here is deliberately minimal and honest:
 *
 *  - The registry is **empty**, because no neural model ships yet. That is the
 *    true state, and it is what makes the platform fallback the active tier.
 *  - The fallback is [PlatformSpeechModel] over [AndroidPlatformSynthesis], which
 *    renders through the device's own engine. It is offline and needs no model
 *    file, so a device can produce a saved narration today.
 *  - The device profile is [DeviceProfile.UNMEASURED], because no measurement
 *    harness has run. It is pessimistic on purpose: it refuses a neural model it
 *    cannot prove the device can hold, and it does not block the fallback, whose
 *    descriptor requires nothing.
 *
 * When a neural model is bundled, it is added to the registry here and the engine
 * prefers it automatically — [OfflineEngine.resolveModel] already implements that
 * order. Nothing else in this class changes.
 */
object EngineFactory {

    /**
     * The production engine for [context].
     *
     * The caller owns the returned engine's lifetime; [AndroidPlatformSynthesis]
     * holds a platform engine that should be shut down when the app is done with
     * it. [platformSynthesis] is exposed so that shutdown is possible.
     */
    fun create(context: Context): Wired {
        val synthesis = AndroidPlatformSynthesis(context)
        val engine = OfflineEngine(
            repository = SonataRepository(SonataDatabase.get(context)),
            fileStore = AppPrivateFileStore(context),
            // No neural model ships yet. The fallback is the active tier.
            registry = InMemoryModelRegistry(emptyList()),
            device = DeviceProfile.UNMEASURED,
            fallback = PlatformSpeechModel(PlatformModel.DESCRIPTOR, synthesis),
        )
        return Wired(engine, synthesis)
    }

    /** The engine and the platform engine it owns. */
    class Wired(
        val engine: OfflineEngine,
        private val synthesis: AndroidPlatformSynthesis,
    ) {
        /** Release the platform engine. */
        fun shutdown() = synthesis.shutdown()
    }
}
