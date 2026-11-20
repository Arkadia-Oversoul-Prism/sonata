# SONATA_MODEL_MANIFEST

**Status:** Stage 7b production candidate
**Candidate commit:** `a102db4f01b04f95214216c3118545d4030cbb02`
**Date:** 2026-11-20

---

## 1. Headline

**No model binaries are present in this repository or in any build produced from
it.** The catalogue below describes models the architecture is designed around. It
does not describe models that ship.

This is stated first because a manifest that lists checksums for files that do not
exist is worse than no manifest. Every checksum field below is marked
`NOT PRESENT` rather than filled with a plausible-looking value.

Verified in this run:

```
find . -type f \( -name "*.onnx" -o -name "*.bin" -o -name "*.tflite" \
                  -o -name "*.pt" -o -name "*.gguf" \) -not -path "./build/*"
→ (no results)

app/src/main/assets   → does not exist
app/src/main/jniLibs  → does not exist
```

---

## 2. Catalogue

The catalogue is `app/src/main/kotlin/com/arkadia/sonata/engine/BundledModels.kt`.
The figures are the models' published specifications, recorded so the compatibility
gate has real numbers. They are **not** measurements of files in this tree.

| Model | Role | Version | Declared size | Min RAM | Sample rate | Languages | Binary present | SHA-256 |
|---|---|---|---|---|---|---|---|---|
| `kokoro-82m` | Base synthesizer (quality tier) | 1.0 | 80 MiB | 300 MiB | 24 000 Hz | en-US, en-GB | **No** | NOT PRESENT |
| `piper-vits` | Base synthesizer (speed tier) | 1.0 | 20 MiB | 120 MiB | 22 050 Hz | en-US | **No** | NOT PRESENT |
| `openvoice-v2-converter` | Tone converter | 2.0 | 15 MiB | 150 MiB | 24 000 Hz | en-US, en-GB | **No** | NOT PRESENT |

### Why the sizes are declared but unverified

`ModelDescriptor.sizeBytes` is used by `ModelCompatibility` to decide whether a
device can hold a model. That decision needs a number. The number is the published
figure. When a binary is actually added, the field must be replaced with the real
byte count and the SHA-256 recorded here — a declared size that disagrees with the
file is a compatibility bug that only shows up on a device with too little RAM.

---

## 3. Licences

| Model | Upstream licence | Redistribution | Verified against upstream? |
|---|---|---|---|
| Kokoro-82M | Apache-2.0 | Permitted with attribution | **No** — not verified in this run |
| Piper / VITS | MIT | Permitted with attribution | **No** — not verified in this run |
| OpenVoice v2 | MIT | Permitted with attribution | **No** — not verified in this run |

**These licence attributions are recorded from the project's own prior documentation
(E10), not from a licence file in this repository.** No `LICENSE` file for any model
is present. Before any model is bundled, the licence must be read from the upstream
release and the attribution text added to the app's about screen and to this table.

This is a **release blocker for model bundling**, not for the current candidate,
because the current candidate bundles no models.

---

## 4. Runtime

| Component | Present | Notes |
|---|---|---|
| Inference runtime (ONNX Runtime / sherpa-onnx) | **No** | No native library, no `jniLibs` |
| JNI bridge | **No** | No `external fun`, no `System.loadLibrary` anywhere in the tree |
| `SpeechModel` production implementation | **No** | Interface only; `FakeSpeechModel` exists for tests |
| Model download path | **No, by design** | `ModelDescriptor` has no URL field (ADR-001) |

### The JNI finding

Stage 7a reported `SherpaOnnxSession` JNI symbols declared but unimplemented. In
this tree there is no such class and no `external fun` at all. The neural tier is
therefore classified **unsupported** (ADR-010), not "implemented but unvalidated".

---

## 5. Evaluation corpus

The corpus is not a model, but it is the fixed input every measurement is tied to,
so its hash belongs here.

| Field | Value |
|---|---|
| File | `narration-core/src/main/kotlin/ai/sonata/validation/Corpus.kt` |
| SHA-256 | `e1eafd06f5eef56cf39d13d76b2eb2e85b3c036f67e6e84e804800db177154de` |
| Passages | 8 (7 original categories + `longsentence_01`) |
| Change in Stage 7b | Added `longsentence_01`, an ~800-character sentence |

The corpus hash changed in Stage 7b because a passage was added. Any WER figure
quoted from an earlier run was measured against the **previous** corpus and is not
directly comparable. This is recorded rather than glossed: the Stage 4–6 report's
WER of 0.5247 was measured without the long-sentence passage.

---

## 6. What must happen before a model ships

1. Obtain the binary from the upstream release.
2. Record its real byte count and SHA-256 in this table.
3. Read the upstream licence and add the attribution text.
4. Implement the JNI bridge and the `SpeechModel` implementation.
5. Verify the model loads and synthesises on a physical device.
6. Re-run the corpus evaluation and record the WER against the new corpus hash.

Until all six are done, the model manifest describes an intention, not a shipment.
