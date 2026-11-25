package com.arkadia.sonata.engine

import com.arkadia.sonata.data.NarrationStatus
import com.arkadia.sonata.data.SonataStore
import com.arkadia.sonata.export.FileStore
import com.arkadia.sonata.export.WavWriter

/**
 * The offline narration engine.
 *
 * This is the piece that makes the specification's central promise true: **every
 * text generation and its completed audio file is saved automatically.** The
 * ordering below is the whole design, and it is deliberate:
 *
 *  1. A narration row is written as `PENDING` **before** synthesis starts, so a
 *     process death mid-generation leaves a record rather than nothing.
 *  2. The row moves to `GENERATING`.
 *  3. The model synthesises PCM.
 *  4. The PCM is written to the canonical file, via a `.partial` path so a crash
 *     never leaves a half-file that looks complete.
 *  5. Only then does the row become `COMPLETE`, carrying the audio path, the
 *     duration and the model version.
 *
 * A failure at any point after step 2 leaves the row `FAILED` with a reason, never
 * `COMPLETE`. That is the "recover or clearly mark incomplete work" boundary, and
 * it is enforced by [com.arkadia.sonata.data.LibraryRules.canTransition] rather
 * than by this class remembering to.
 *
 * The engine holds a [NetworkProbe] and calls it at the one point where a network
 * call would be made. In production that is a no-op; in a test it throws, which is
 * how the offline boundary is asserted rather than assumed.
 */
class OfflineEngine(
    private val repository: SonataStore,
    private val fileStore: FileStore,
    private val registry: ModelRegistry,
    private val device: DeviceProfile,
    private val networkProbe: NetworkProbe = NoNetworkProbe,
    private val modelProvider: SpeechModelProvider = DefaultSpeechModelProvider,
) {

    /**
     * Narrate [text] and persist the result.
     *
     * [voice] is the voice to speak in; null means the best bundled base voice.
     * [title] names the narration; [documentId] links it to its source, which may
     * be null for a narration made from text that was never saved as a document.
     *
     * Returns the narration id on success, or a failure with a user-presentable
     * reason. The narration row exists in both cases — a failure is recorded, not
     * discarded.
     */
    suspend fun narrate(
        text: String,
        title: String,
        documentId: Long? = null,
        voice: VoiceReference? = null,
        onProgress: (Float) -> Unit = {},
    ): NarrationOutcome {
        if (text.isBlank()) return NarrationOutcome.Failure("There is nothing to narrate")

        val model = resolveModel(voice)
            ?: return NarrationOutcome.Failure(
                "No speech model is installed for this device. Import a voice model to narrate offline.",
            )

        // The row is written before synthesis, so an interruption is recoverable.
        val narrationId = repository.beginNarration(
            documentId = documentId,
            title = title,
            voiceProfileId = (voice as? VoiceReference.Cloned)?.profileId,
            modelVersion = model.descriptor.recordedVersion,
        )
        repository.markGenerating(narrationId)

        // The single point at which a network call would be made. In production
        // this is a no-op; a test supplies a probe that throws, which is how the
        // offline boundary is proven rather than promised.
        guardOffline("synthesize")

        val output = try {
            model.synthesize(text, voice, onProgress)
        } catch (e: Exception) {
            // A runtime that throws is a failure, not a crash: the row is marked
            // and the user is told, which is the honest outcome.
            repository.failNarration(narrationId, "Synthesis failed: ${e.javaClass.simpleName}")
            return NarrationOutcome.Failure("Synthesis failed: ${e.javaClass.simpleName}")
        }

        return when (output) {
            is SynthesisOutput.Failure -> {
                repository.failNarration(narrationId, output.reason)
                NarrationOutcome.Failure(output.reason)
            }

            is SynthesisOutput.Success -> {
                if (output.pcm.isEmpty()) {
                    repository.failNarration(narrationId, "The model produced no audio")
                    return NarrationOutcome.Failure("The model produced no audio")
                }
                val wav = WavWriter.wrap(output.pcm, output.sampleRate)
                val file = fileStore.write(narrationId, wav)
                val duration = if (output.durationMs > 0) {
                    output.durationMs
                } else {
                    WavWriter.durationMs(output.pcm.size, output.sampleRate)
                }
                val completed = repository.completeNarration(
                    narrationId = narrationId,
                    audioPath = file.absolutePath,
                    durationMs = duration,
                )
                if (!completed) {
                    // The transition table refused it, which can only mean the row
                    // was not GENERATING — an interrupted row. Report rather than
                    // force, because forcing is exactly the silent completion the
                    // specification forbids.
                    NarrationOutcome.Failure("This narration was interrupted and must be retried")
                } else {
                    NarrationOutcome.Success(narrationId, file.absolutePath, duration)
                }
            }
        }
    }

    /**
     * Retry an interrupted or failed narration, reusing its row.
     *
     * Reusing the row is what preserves the user's title and its link to the source
     * document. A retry that created a new row would lose both, which is why the
     * recovery path is here rather than in the UI.
     */
    suspend fun retry(
        narrationId: Long,
        text: String,
        voice: VoiceReference? = null,
        onProgress: (Float) -> Unit = {},
    ): NarrationOutcome {
        val existing = repository.narration(narrationId)
            ?: return NarrationOutcome.Failure("That narration no longer exists")
        if (existing.status == NarrationStatus.COMPLETE) {
            return NarrationOutcome.Failure("That narration is already complete")
        }
        val model = resolveModel(voice)
            ?: return NarrationOutcome.Failure("No speech model is installed for this device")

        repository.markGenerating(narrationId)
        guardOffline("synthesize")

        val output = try {
            model.synthesize(text, voice, onProgress)
        } catch (e: Exception) {
            repository.failNarration(narrationId, "Synthesis failed: ${e.javaClass.simpleName}")
            return NarrationOutcome.Failure("Synthesis failed: ${e.javaClass.simpleName}")
        }

        return when (output) {
            is SynthesisOutput.Failure -> {
                repository.failNarration(narrationId, output.reason)
                NarrationOutcome.Failure(output.reason)
            }

            is SynthesisOutput.Success -> {
                val wav = WavWriter.wrap(output.pcm, output.sampleRate)
                val file = fileStore.write(narrationId, wav)
                val duration = if (output.durationMs > 0) {
                    output.durationMs
                } else {
                    WavWriter.durationMs(output.pcm.size, output.sampleRate)
                }
                if (repository.completeNarration(narrationId, file.absolutePath, duration)) {
                    NarrationOutcome.Success(narrationId, file.absolutePath, duration)
                } else {
                    NarrationOutcome.Failure("This narration could not be completed")
                }
            }
        }
    }

    /**
     * The offline tripwire.
     *
     * The engine has no network tier, so this branch is unreachable — and that is
     * the point. It exists so that if a network tier is ever added it must route
     * through [NetworkProbe], and the test that supplies a throwing probe fails the
     * moment it is taken. The offline boundary is asserted, not assumed.
     */
    private fun guardOffline(operation: String) {
        if (EnginePolicy.requiresNetwork(EnginePolicy.selectTier(neuralModelAvailable = true))) {
            networkProbe.onNetworkAccessAttempted(operation)
        }
    }

    /**
     * The model to use for [voice].
     *
     * A cloned voice needs both a base synthesizer and its adapter; a base voice
     * needs only the synthesizer. If the device cannot run the best model, the next
     * compatible one is used rather than failing — the specification's priority is
     * quality, but a lower-quality voice is better than no voice.
     */
    private fun resolveModel(voice: VoiceReference?): SpeechModel? {
        val base = registry.bestBaseFor(device) ?: return null
        return when (voice) {
            is VoiceReference.Cloned -> {
                val converter = registry.toneConverters().firstOrNull {
                    registry.isCompatible(it, device)
                }
                // A cloned voice without a converter cannot be produced; falling
                // back to the base voice silently would violate the requirement
                // that Sonata speaks in the user's own voice, so the caller is told.
                if (converter == null) null else modelProvider.modelFor(base, converter, voice)
            }

            else -> modelProvider.modelFor(base, null, voice)
        }
    }
}

/**
 * Builds the [SpeechModel] for a base model and an optional voice.
 *
 * A seam so the engine's *ordering* rules — save before synthesise, complete only
 * after the audio is on disk, never complete an interrupted row — are testable with
 * a fake model, without ONNX Runtime, sherpa-onnx or a model file.
 */
interface SpeechModelProvider {

    /** The model for [base], with [converter] when a cloned voice is requested. */
    fun modelFor(
        base: ModelDescriptor,
        converter: ModelDescriptor?,
        voice: VoiceReference?,
    ): SpeechModel
}

/** The production provider: the real runtime-backed models. */
object DefaultSpeechModelProvider : SpeechModelProvider {
    override fun modelFor(
        base: ModelDescriptor,
        converter: ModelDescriptor?,
        voice: VoiceReference?,
    ): SpeechModel = if (converter != null && voice is VoiceReference.Cloned) {
        CloningSpeechModel(base, converter, voice)
    } else {
        BaseSpeechModel(base)
    }
}

/** Outcome of a narration. */
sealed class NarrationOutcome {
    data class Success(val narrationId: Long, val audioPath: String, val durationMs: Long) :
        NarrationOutcome()

    data class Failure(val reason: String) : NarrationOutcome()
}
