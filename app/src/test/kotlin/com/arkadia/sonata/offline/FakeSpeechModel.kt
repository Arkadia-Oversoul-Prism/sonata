package com.arkadia.sonata.offline

import com.arkadia.sonata.engine.ModelDescriptor
import com.arkadia.sonata.engine.ModelRole
import com.arkadia.sonata.engine.SpeechModel
import com.arkadia.sonata.engine.SpeechModelProvider
import com.arkadia.sonata.engine.SynthesisOutput
import com.arkadia.sonata.engine.VoiceReference

/**
 * A [SpeechModel] that returns fixed PCM.
 *
 * Lets the engine's ordering rules be tested without a runtime or a model file.
 * [failWith] makes it fail on demand, so the "a failure leaves the row FAILED, not
 * COMPLETE" rule can be pinned.
 */
class FakeSpeechModel(
    override val descriptor: ModelDescriptor,
    private val pcm: ByteArray = ByteArray(4800) { 0x11 },
    private val sampleRate: Int = 24_000,
    private val failWith: String? = null,
    private val throwOnSynthesize: Boolean = false,
) : SpeechModel {

    /** How many times synthesize was called. */
    var synthesizeCount = 0
        private set

    /** Whether close was called. */
    var closed = false
        private set

    override suspend fun isReady(): Boolean = true

    override suspend fun synthesize(
        text: String,
        voice: VoiceReference?,
        onProgress: (Float) -> Unit,
    ): SynthesisOutput {
        synthesizeCount++
        if (throwOnSynthesize) throw IllegalStateException("native failure")
        if (failWith != null) return SynthesisOutput.Failure(failWith)
        onProgress(0.5f)
        onProgress(1.0f)
        return SynthesisOutput.Success(
            pcm = pcm,
            sampleRate = sampleRate,
            durationMs = 0,
            modelVersion = descriptor.recordedVersion,
        )
    }

    override fun close() {
        closed = true
    }
}

/** A [SpeechModelProvider] that returns a supplied fake. */
class FakeModelProvider(private val model: SpeechModel) : SpeechModelProvider {
    override fun modelFor(
        base: ModelDescriptor,
        converter: ModelDescriptor?,
        voice: VoiceReference?,
    ): SpeechModel = model
}

/** A model descriptor for tests. */
fun testModel(
    id: String = "test-model",
    role: ModelRole = ModelRole.BASE_SYNTHESIZER,
    sizeBytes: Long = 10L * 1024 * 1024,
    minRamBytes: Long = 100L * 1024 * 1024,
    sampleRate: Int = 24_000,
): ModelDescriptor = ModelDescriptor(
    id = id,
    family = "test",
    version = "1.0",
    sizeBytes = sizeBytes,
    minRamBytes = minRamBytes,
    sampleRate = sampleRate,
    languages = listOf("en-US"),
    role = role,
)
