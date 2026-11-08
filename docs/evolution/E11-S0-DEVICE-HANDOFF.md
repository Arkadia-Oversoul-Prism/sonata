# E11 — S0 Device Validation Handoff

**Status:** READY TO RUN · NOT YET RUN
**Depends on:** E10 (phases 1–7 implemented)

## Why this document exists

E10 records an implementation that is verified on the JVM and **unverified on a
device**. The specification's S0 gate is the thing that decides whether on-device
voice adaptation ships at all, and it is a measurement, not a judgement. This
document is the handoff: what to run, on what, and what number decides.

Until it has run, `DeviceProfile.thermalValidated` is false and
`ModelCompatibility.canAdapt` returns false. That is the correct state — the feature
is refused rather than attempted and failed.

## D1 — Device matrix

Three frozen devices, spanning the tiers. Record RAM, SoC and Android version for
each; the numbers go in the D4 report.

| Tier | Role | What it decides |
|---|---|---|
| Low | The floor | Whether the product works at all on a cheap device |
| Mid | **The gate** | The RTF ≤ 0.5 threshold is measured here |
| Flagship | The ceiling | What the quality tier can do when unconstrained |

The mid-tier device is the one that matters: the gate is defined on it, because a
model that only runs on a flagship is not a product.

## D2 — Harness APK

A headless benchmark app. No UI, no navigation, no design system.

- **Corpus** — a fixed set of texts: short (a sentence), medium (a paragraph), long
  (a page), plus one text with numbers and one with punctuation the normaliser
  rewrites. Fixed, so runs are comparable.
- **Run protocol** — for each candidate model on each device: load, warm up, then
  synthesise the corpus N times; record every metric per run; discard the first run
  (cold start) and report the median.
- **Report schema** — machine-readable JSON, one object per (device, model) pair,
  with the fields of D4. The schema is the contract: the report is what
  `DeviceValidationHarness.Measurement` is populated from.

## D3 — ONNX export set

The export and numerical-parity procedure for the base synthesizer and the
tone-colour converter against the PyTorch reference.

- Export both models to ONNX with a fixed input signature.
- Run the same input through PyTorch and ONNX; assert the outputs agree within a
  stated tolerance (the parity assertion).
- Record the tolerance and the observed maximum deviation in the report. A model
  that exports but does not match is not a candidate.

## D4 — Measured report template

For every candidate on every device:

| Metric | Unit | Gate |
|---|---|---|
| Real-time factor | ratio | ≤ 0.5 on mid-tier |
| Peak RSS | MB | ≤ 700 |
| On-disk size | MB | — |
| First-chunk latency | ms | — |
| Twenty-minute thermal trace | °C over time | no abort |
| Battery drain per minute of audio | %/min | — |
| Adapter training wall-clock | min | < 10 for 5 min of audio |
| Adapter training peak memory | MB | — |

`DeviceValidationHarness.DeviceReport` formats exactly these, and
`DeviceValidationHarness.evaluate` turns them into the pass/fail checklist.

## D5 — Training prototype plan

On-device adapter training against a synthetic five-minute dataset.

- Checkpoint every epoch (`ThermalWatchdog.CHECKPOINT_EVERY_EPOCHS`).
- The watchdog pauses above 42 °C and resumes below 38 °C — the hysteresis band is
  what stops the run oscillating at the threshold.
- Report wall-clock and peak memory.
- A pause is not a failure: the run resumes from its checkpoint.

## D6 — Recommendation memo template

One page: which base model, which backend, which adapter size, and the numbers that
justify each. The numbers come from D4; the memo is the argument, not the data.

## The S0 exit gate, as a pass/fail checklist

- [ ] Real-time factor ≤ 0.5 on mid-tier
- [ ] Peak RSS ≤ 700 MB
- [ ] Adapter training under ten minutes for five minutes of audio
- [ ] No thermal abort during training

All four must pass. A device that fails any one does not get on-device adaptation,
and the enrollment-only path is the product — which the specification states is a
legitimate outcome, not a failure.

## The rules for S0

- No design system.
- No navigation.
- No production database schema.
- No hosted service dependency.
- Measurements only.

## Sequencing

- **S1 and S2 may run in parallel.** The persistent library and the offline engine
  do not depend on each other's internals; they meet at the `SonataStore` seam.
- **S3 must not start before S2's exit gate.** Voice Studio depends on the engine
  being able to synthesise offline at all; building enrollment and training on top
  of an unproven engine would mean debugging two things at once.

## Authority boundary

This is an engineering investigation plan. It authorises no repository mutations,
branches, commits, dependency upgrades or schema migrations.
