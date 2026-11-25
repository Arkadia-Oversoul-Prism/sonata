# E10 — Offline Sonata: Phases 1–7 Implementation

**Status:** IMPLEMENTED · VERIFIED (JVM) · AWAITING DEVICE VALIDATION
**Branch:** `feat/sonata-offline-phases-1-7`
**Supersedes:** the online/offline two-tier architecture described in the original README.

## What this document is

The record of implementing the seven-phase roadmap from the canonical voice
architecture specification (v1.0) and the S0 engineering package (D1–D6). It states
what was built, what was verified, and — importantly — what was **not** verified,
because the honest boundary between the two is the most useful thing this document
can carry.

## The binding decisions, and where each one lives

| Decision | Where it is enforced |
|---|---|
| Fully offline; no STT, no cloud TTS, no API keys | `engine/EnginePolicy.requiresNetwork` returns false for every tier; `engine/NetworkProbe` is the tripwire; `OfflineEngineTest` asserts it |
| On-device enrollment **and** real local training | `voice/VoiceStudio` (three stages), `voice/VoiceAdapterTrainer` |
| Frozen base synthesizer + compact trainable adapter | `engine/SpeechModels.CloningSpeechModel`; `engine/BundledModels` marks the converter's role |
| One swappable runtime abstraction | `engine/SpeechModel` + `engine/NativeSession` |
| Automatic transactional persistence | `engine/OfflineEngine` (save-before-synthesise ordering) |
| Private working storage; export only via MediaStore/SAF | `export/AppPrivateFileStore`, `reliability/MediaStoreAudioExporter` |
| Text normalization before the model | `canvas/TextNormalizer` |

## Phase by phase

### Phase 1 — Persistent Library

Applied from the phase-1 patch (`0001-sonata-offline-phase1.patch`), which was
supplied as a reference artifact. It brings the Room schema, the DAOs, the
repository and `LibraryRules`.

Two invariants are expressed **in the schema**, not in a repository method:

- `Narration.documentId` is nullable with `ON DELETE SET NULL`, so deleting a
  document cannot delete a narration.
- `Document.originalText` and `Document.cleanedText` are separate columns, so
  normalisation never overwrites what the user supplied.

`LibraryRules.canTransition` is the transition table. It is the reason an
interrupted narration cannot be quietly relabelled complete: `COMPLETE` is
reachable only from `GENERATING`.

### Phase 2 — Export, rename, delete

`export/` — `AudioFormat`, `ExportNaming`, `WavWriter`, `FileStore`,
`AudioEncoder`, `AudioExporter`, `TextExporter`, `LibraryFileCoordinator`.

The design decision worth recording: **deletion independence is made structural at
the file level too.** Phase 1 guaranteed it at the row level; `LibraryFileCoordinator`
is the single place that removes a record and its file together, so "deleting a
document never touches a narration file" is a property of there being one deletion
path rather than a rule each caller must remember.

`WavWriter` is pure and dependency-free, which is why WAV is the guaranteed export
format: it needs no encoder, only a 44-byte header.

### Phase 3 — Offline neural TTS

`engine/` — `DeviceProfile`, `ModelDescriptor`, `ModelCompatibility`,
`SpeechModel`, `EnginePolicy`, `NetworkProbe`, `ModelRegistry`, `OfflineEngine`,
`SpeechModels`, `BundledModels`.

`OfflineEngine.narrate` is the ordering that makes the specification's central
promise true:

1. write the row `PENDING` **before** synthesis;
2. `GENERATING`;
3. synthesise;
4. write the audio via a `.partial` path and promote it;
5. only then `COMPLETE`, carrying the path, duration and model version.

A failure at any point after step 2 leaves the row `FAILED` with a reason, never
`COMPLETE`. That is enforced by the transition table, not by the engine remembering.

`ModelCompatibility.canAdapt` is the device gate: it returns false until
`DeviceProfile.thermalValidated` is true, which is the specification's rule that
on-device training is not advertised before it is measured.

### Phase 4 — Voice Studio

`voice/` — `SampleQuality`/`SampleQualityGate`, `EnrollmentScript`,
`TrainingPlan`, `ThermalWatchdog`, `VoiceAdapterTrainer`, `AdapterValidation`,
`VoiceStudio`.

Three stages with a hard gate between each:

- **Enrollment** — guided prompts, an acoustic gate (level, speech ratio, duration),
  a reusable profile. Always available.
- **Training** — device-gated; checkpointed; validated before activation.
- **Generation** — a `VoiceReference.Cloned` only when an adapter is activated.

`AdapterValidation.shouldActivate` is the rule that a bad training run cannot ship a
bad voice: an adapter is activated only when it measurably beats the base voice on
held-out audio.

### Phase 5 — Read-Along Canvas

`canvas/` — `TextNormalizer`, `ReadAlongTimeline`, `TimelineBuilder`.

`TextNormalizer` is the cleaning contract: markup, emoji, symbols, abbreviations,
whitespace. Two rules are worth naming because they were bugs first:

- Emoji are stripped by **code point**, not by `Char`. An astral-plane emoji is a
  surrogate pair; iterating `Char`s saw two unpaired surrogates, neither matched,
  and the emoji survived to be spoken as a replacement character.
- Abbreviations are matched with a **leading** word boundary only. A trailing `\b`
  never matches after a final period, because a period and a space are both
  non-word characters — so the rule silently did nothing.

`ReadAlongTimeline` is sentence-level sync with confidence-gated word highlighting:
a word span is used only above `WORD_CONFIDENCE_FLOOR`, otherwise the sentence is
highlighted. A wrong highlight is a visible lie, so the fallback is the feature.

### Phase 6 — Narration Intelligence

`narration/` — `NarrationStyle`, `NarrationContext`, `ProsodyPlan`,
`NarrationIntelligence`.

Six stages, each a pure function. The eight styles are defined **only** in terms of
what the engine can vary — rate, pause, pitch. There is no "emotion" field, because
there is no emotion parameter to set; a style that promised an emotion the model
cannot synthesise would be a lie.

### Phase 7 — Export and reliability

`reliability/` — `BatchExporter`, `StorageAccountant`, `CrashRecovery`,
`DeviceValidationHarness`, `MediaStoreAudioExporter`.

`DeviceValidationHarness` is the phase that gates on-device adaptation. Its
`evaluate` treats a **null measurement as a failure**, which is the important rule:
"we did not measure it" is not a pass.

## Verification

The pure-Kotlin logic was compiled and run on the JVM with a harness that stubs the
Room annotations and the Android framework seams. **134 tests, all passing.**

| Suite | Covers |
|---|---|
| `ExportTest` | naming, WAV, the file store, export, deletion independence |
| `EngineTest` | the offline boundary, save-before-synthesise ordering, the device gate |
| `VoiceStudioTest` | the acoustic gate, enrollment, training, validation, the watchdog |
| `ReadAlongTest` | the cleaning contract, sentence segmentation, the timeline |
| `NarrationIntelligenceTest` | the eight styles, context classification, the pipeline |
| `ReliabilityTest` | batch export, storage accounting, crash recovery, the S0 gate |

Five real defects were found and fixed by this run, each of which would have shipped
silently:

1. A class and an object shared the name `SampleQuality` — did not compile.
2. `CloningSpeechModel` referenced the `synthesize` parameter instead of the
   constructor's voice — would have spoken in the wrong voice.
3. `ExportNaming` returned `___` for a title of pure illegal characters instead of
   falling back.
4. The abbreviation rule's trailing `\b` made it a no-op.
5. Emoji stripping missed astral-plane emoji.

## What is NOT verified

This is the honest boundary, and it matters more than the list above.

- **No device has run this.** The S0 gate (RTF ≤ 0.5, peak RSS ≤ 700 MB, adapter
  training under ten minutes) is **unmeasured**. `DeviceProfile.UNMEASURED` is
  therefore the default, and `canAdapt` returns false — so on-device training is
  correctly refused rather than attempted and failed.
- **No native runtime is linked.** `UnavailableNativeSession` reports "not loaded",
  so a build without ONNX Runtime or sherpa-onnx degrades to the platform fallback
  tier rather than crashing. The contract is present; the binary is not.
- **No model weights are bundled.** `BundledModels` records the published figures
  for Kokoro-82M, Piper/VITS and the OpenVoice v2 converter so the compatibility
  gate has real numbers; the weights themselves are a separate delivery.
- **The Android-dependent classes are not compiled here** — `AppPrivateFileStore`,
  `MediaStoreAudioExporter`, the Room DAOs. They are thin bindings over the tested
  seams, but they are unverified until a Gradle build runs.

## Authority boundary

This document records an implementation. It authorises no further repository
mutations, branches, dependency upgrades or schema migrations. The next step is a
Gradle build and the S0 device harness, under a separate instruction.
