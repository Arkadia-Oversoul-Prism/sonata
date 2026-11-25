# E9 — Phase 3 groundwork: offline TTS engine

**Status:** design only — not implemented. Groundwork for the phase that follows
Phase 2.
**Depends on:** Phase 1 schema (`Narration.modelVersion`, `VoiceProfile`), the
existing `OfflineVoice` seam in `TtsEngine`.
**Specification clauses covered:** the offline-operation boundary, and the engine
half of B (native offline voice cloning).

---

## 1. Goal

Replace the online-first tier order with a **single offline tier**. After Phase 3,
Sonata synthesises speech with no network call, no API key and no cloud service.
The Arkadia backend path is removed, not merely demoted.

## 2. The decision (recorded, evidence-based)

| Layer | Choice | Why |
|---|---|---|
| **Runtime** | **sherpa-onnx** | Kotlin/Java API, ONNX Runtime under the hood, ships in production Android apps, no PyTorch |
| **Base voices** | **Kokoro-82M** (quality), **Piper/VITS** (speed) | Kokoro is the highest-quality small offline model; Piper is the fastest |
| **Cloning** | **OpenVoice v2 tone-colour converter** (ONNX) over the base voice | Piper alone does **not** clone arbitrary voices; OpenVoice is a converter, not a generator, so it needs a base voice to convert |

**The two-tier voice model** (from the plan, restated because Phase 3 depends on it):

- **Enrollment** — capture samples, analyse quality, create a reusable profile.
  Cheap, always possible, shippable without a device. Phase 4.
- **Adaptation** — adapt a neural model to the voice. Expensive, **device-gated**.
  Phase 4, behind the gate.

Phase 3 delivers the **engine** those tiers plug into; it does not itself clone.

## 3. What Phase 1 already provides

| Need | Phase 1 field / rule |
|---|---|
| Attribution of audio to a model | `Narration.modelVersion` |
| Voice identity | `VoiceProfile` (kind, samplePaths, modelPath, modelVersion) |
| Engine seam | `OfflineVoice` interface already injected into `TtsEngine` |
| Status lifecycle | `PENDING → GENERATING → COMPLETE / FAILED / INTERRUPTED` |

## 4. Model management

The specification requires **model versions and compatibility recorded**. Phase 3
adds a `ModelRegistry`:

```kotlin
data class ModelDescriptor(
    val id: String,              // e.g. "kokoro-82m-v1.0"
    val family: String,          // "kokoro" | "piper" | "openvoice"
    val version: String,
    val sizeBytes: Long,
    val minRamBytes: Long,
    val sampleRate: Int,
    val languages: List<String>,
)

interface ModelRegistry {
    fun installed(): List<ModelDescriptor>
    fun isCompatible(descriptor: ModelDescriptor, device: DeviceProfile): Boolean
    suspend fun install(descriptor: ModelDescriptor, source: File): Boolean
    suspend fun remove(descriptor: ModelDescriptor)
}
```

- Models are **bundled or side-loaded**, never downloaded at runtime (offline
  boundary). A first-run import from device storage is the only path in.
- A narration records the `modelVersion` that made it, so audio stays attributable
  after a model is replaced.
- `isCompatible` refuses to load a model the device cannot hold — the same gate the
  adaptation tier uses, applied to inference.

## 5. The engine seam

`TtsEngine` already injects an `OfflineVoice`. Phase 3 implements it:

```kotlin
interface OfflineVoice {
    suspend fun isReady(): Boolean
    suspend fun synthesize(
        text: String,
        voice: VoiceProfile?,
        model: ModelDescriptor,
        onProgress: (Float) -> Unit,
    ): SynthesisResult
}

data class SynthesisResult(
    val pcm: ByteArray,
    val sampleRate: Int,
    val durationMs: Long,
    val modelVersion: String,
)
```

The engine's existing discipline is preserved unchanged: single owner,
supersession of in-flight work, cleanup on every exit path, honest failure. Only
the tier order changes — offline becomes the only tier.

## 6. The device gate (non-negotiable)

The specification forbids advertising on-device training until it is measured.
Phase 3 ships **inference**; Phase 4's adaptation tier stays behind a harness that
measures, on the target device class:

- peak RSS during synthesis,
- model + profile storage footprint,
- sustained thermal behaviour over a long narration,
- tokens (or characters) per second.

If the numbers do not support adaptation, **enrollment-only is the product** — and
that is a legitimate outcome, not a failure.

## 7. Test plan

| Test | Type | What it pins |
|---|---|---|
| `the engine makes no network call when offline` | JVM (MockWebServer asserts zero requests) | the offline boundary |
| `a narration records the model version that made it` | Robolectric | attribution |
| `an incompatible model is refused before load` | JVM | the device gate |
| `superseding a synthesis cancels the previous one` | JVM | existing engine discipline preserved |
| `a failed synthesis leaves the narration FAILED, not COMPLETE` | Robolectric | honest failure |
| `synthesis progress is monotonic` | JVM | progress contract |

## 8. Risks

- **Model size**: Kokoro-82M is ~80 MB quantised; bundling it grows the APK. The
  side-load path avoids that but adds a first-run step.
- **sherpa-onnx ABI**: needs the right NDK/ABI splits; must be verified on arm64
  and armeabi-v7a.
- **Quality vs speed**: Kokoro is slower; the speed tier (Piper) must be selectable
  per narration, which the `VoiceProfile` already allows.
- **Licence**: Kokoro and Piper are permissively licensed; OpenVoice v2's licence
  must be re-checked before the cloning tier ships.

## 9. Authority

Design only. No merge, deploy or production change. Human authority remains final.
