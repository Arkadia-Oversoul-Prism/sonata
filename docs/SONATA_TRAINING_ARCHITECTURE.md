# SONATA_TRAINING_ARCHITECTURE

**Status:** Stage 7b production candidate
**Candidate commit:** `7d2a30139811c951bfc529fa2ea770f02601231b`
**Date:** 2026-11-20

---

## 1. Scope

Sonata's on-device training is **adapter training for voice tone conversion only**.
The base synthesizer is frozen. This document describes the architecture, the
bounds, and — importantly — what is implemented versus what is designed.

| Layer | Implemented | Tested | Runs on device |
|---|---|---|---|
| Sample quality scoring | Yes | Yes (`VoiceStudioTest`) | Not run |
| Training plan construction | Yes | Yes (`VoiceStudioTest`) | Not run |
| Adapter training execution | **No** | No | No |
| Adapter application to audio | **No** | No | No |

The planning layer is real and tested. The execution layer does not exist.

---

## 2. Why adapter training and not fine-tuning

Fine-tuning the base model on a phone is not viable: it needs hours of audio, far
more compute than a phone has, and it produces a per-user fork that can never be
updated. Adapter training changes only the tone-colour converter, which is small
(~15 MiB), fast to train, and discardable.

The pipeline is:

```
text → base synthesizer (frozen) → speech → tone converter (adapted) → user's voice
```

The base model stays attributable: every narration records
`ModelDescriptor.recordedVersion`, so "which model made this?" is answerable after
an update.

---

## 3. Sample quality gate

Before any training, recorded samples are scored. The gate exists because training
on bad audio produces a bad adapter, and a bad adapter is worse than no adapter —
the user hears their own voice rendered badly and concludes the feature is broken.

`SampleQuality` scores, per sample:

| Signal | Why it matters |
|---|---|
| Clipping | A clipped sample teaches the adapter to produce distortion |
| Signal-to-noise ratio | Noise is learned as part of the voice |
| Duration | Too short gives too few phonemes; too long wastes the user's time |
| Silence ratio | Long silences teach the adapter to produce silence |
| Level consistency | Inconsistent level teaches the adapter an unstable gain |

A sample that fails is rejected with a specific reason, not a generic "try again".

**Verified by** `VoiceStudioTest` — the scoring is pure arithmetic and runs on the
JVM.

---

## 4. Training plan bounds

`TrainingPlan` constructs a bounded plan from the accepted samples:

| Bound | Value | Rationale |
|---|---|---|
| Minimum accepted audio | 5 minutes | Below this the adapter overfits |
| Maximum training time | 10 minutes | E11 S0 gate; beyond this the phone thermally throttles |
| Adapter size | ~15 MiB | The converter, not the base model |
| Epochs | Bounded by the time budget, not fixed | A slow device does fewer epochs rather than running longer |

The time budget is the primary constraint, not the epoch count. A fixed epoch count
would either waste time on a fast device or overrun on a slow one.

**Verified by** `VoiceStudioTest` — the plan refuses insufficient audio and refuses
to construct a plan that exceeds the time budget.

---

## 5. What is not implemented

- **The training loop.** No gradient computation, no optimiser, no checkpointing.
- **The adapter format.** No serialisation, no versioning of a trained adapter.
- **Adapter application.** No code path that loads an adapter and applies it.
- **Thermal monitoring.** E11 requires "no thermal abort during training"; nothing
  reads the thermal state.

These are not oversights in this stage. They require the inference runtime that
ADR-010 records as unsupported. Building a training loop that cannot be run, on a
model that is not present, would be a placeholder — the exact thing the mandate
forbids.

---

## 6. Privacy of training data

Voice samples are the most sensitive data the product touches. The rules:

1. **Samples never leave the device.** Enforced by the absence of `INTERNET`
   (NFR-01) and by backup exclusion rules (NFR-03).
2. **Samples are excluded from backup.** `data_extraction_rules.xml` excludes
   `voice_samples` and `voice_adapters` from both cloud backup and device transfer.
3. **Samples are deletable.** The user can remove a voice and its samples; the
   adapter is deleted with them.
4. **No sample is used for anything but the user's own adapter.** There is no
   aggregation, no upload, no shared model.

Rule 1 is structural. Rules 2–4 are implemented in the storage layer and asserted
by the privacy audit where they are checkable from the manifest.

---

## 7. Acceptance criteria for the training feature

The feature is complete when:

| # | Criterion | Status |
|---|---|---|
| T1 | Sample quality gate rejects bad audio with a specific reason | **Met** (tested) |
| T2 | Training plan refuses insufficient audio | **Met** (tested) |
| T3 | Training plan respects the 10-minute budget | **Met** (tested) |
| T4 | Adapter trains in under 10 minutes for 5 minutes of audio | **Not met** — no training loop |
| T5 | No thermal abort during training | **Not met** — no thermal monitoring |
| T6 | Adapter measurably changes tone colour | **Not met** — no application path |
| T7 | Adapter is deleted with its samples | **Not met** — no adapter storage |

T1–T3 are the planning layer and are real. T4–T7 require the runtime and a device.
