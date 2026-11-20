# SONATA_ARCHITECTURE_DECISIONS

**Status:** Stage 7b production candidate
**Candidate commit:** `c6b7db5b355831d1da11c040f944349a465a9cf6`
**Date:** 2026-11-20

Architecture Decision Records for Sonata. Each record states the decision, the
alternatives, the consequence, and — where a decision was later reversed — the
supersession. Reversed decisions are kept, not deleted, because the reason a path
was abandoned is part of the design.

---

## ADR-001 — Offline-first, enforced structurally

**Status:** Accepted (unchanged since E7)

**Decision.** The shipped app must be incapable of network access. Enforced by
removing the `INTERNET` permission, by giving `ModelDescriptor` no URL field, and by
a regression test that fails the build if the permission returns.

**Alternatives.**
- *Offline-by-default with an online fallback.* Rejected: a fallback is a network
  path, and a network path is what the privacy promise forbids. It also makes the
  guarantee untestable, because "we only use it sometimes" cannot be asserted.
- *Runtime network blocking.* Rejected: a runtime guard is code that can be
  bypassed or forgotten. A missing permission cannot.

**Consequence.** No cloud TTS, no model download, no telemetry, no crash reporting.
The product must ship its models or accept side-loading.

**Verified by.** `RegressionTest.R11`, `ManifestAudit`, `PrivacyTest`.

---

## ADR-002 — Two-tier model strategy: quality and speed

**Status:** Accepted, **but neither tier is implemented** (see ADR-010)

**Decision.** Kokoro-82M for quality, Piper/VITS for speed, both behind one
`SpeechEngine` seam, chosen by device capability.

**Alternatives.**
- *One model.* Rejected: a single model cannot serve both a low-end device and the
  quality requirement.
- *Cloud model.* Rejected by ADR-001.

**Consequence.** The catalogue exists (`BundledModels`) with real published figures.
The runtime does not.

---

## ADR-003 — OpenVoice as a tone converter, not a generator

**Status:** Accepted

**Decision.** Voice adaptation is `base synthesizer → OpenVoice converter → user's
voice`. The base model is frozen; only the converter is trained on-device.

**Alternatives.**
- *Fine-tune the base model on-device.* Rejected: it needs far more data and compute
  than a phone has, and it would make every user's model a fork that cannot be
  updated.

**Consequence.** Adapter training is bounded (E11: under ten minutes for five
minutes of audio). The base model stays attributable and replaceable.

---

## ADR-004 — Per-segment engine invocation, not one document

**Status:** Accepted

**Decision.** The engine is invoked once per planned segment, not once per document.

**Alternatives.**
- *One SSML document for the whole passage.* Rejected: measurement showed that
  per-segment pitch offsets are flattened when rendered as one document, so every
  style collapses to the same contour.

**Consequence.** More process launches, higher latency, but the prosody the planner
asks for actually reaches the audio. `RegressionTest.R3` asserts the invocation
count by observing a recording engine.

**Superseded detail.** R3 previously asserted only that a pipeline object could be
constructed, which would have passed even if the engine rendered one document. See
§Supersessions.

---

## ADR-005 — `SonataStore` as the persistence seam

**Status:** Accepted

**Decision.** The library, read-along and export layers depend on a `SonataStore`
interface, not on Room. Room is one implementation.

**Alternatives.**
- *Depend on Room directly.* Rejected: it would make every test need an Android
  runtime, and it would couple the domain to a storage engine.

**Consequence.** The domain is testable on the JVM. The cost is that the Room
implementation itself is only testable with Robolectric, which is not available in
this environment — so `SonataRepositoryTest` is NOT RUN here.

---

## ADR-006 — Narration core as a separate, pure-JVM module

**Status:** Accepted (Stage 7b)

**Decision.** Narration planning, privacy auditing, reliability and validation live
in `narration-core/`, a module with no Android dependency.

**Alternatives.**
- *Keep it inside `app/`.* Rejected: it was previously delivered as a standalone
  harness that was explicitly "not a patch to the Stage 3 tree", which is why Stage
  7a could not review it as part of the product. A separate module that the app
  depends on makes it part of the product and keeps it executable without an SDK.

**Consequence.** 169 tests run anywhere, including CI, with no Android SDK. This is
the strongest test evidence the project has.

---

## ADR-007 — Declared test scope, not inferred

**Status:** Accepted (Stage 7b)

**Decision.** The JVM verification harness computes its scope from imports
(`build/scope.py`) and prints every excluded file. It does not silently skip.

**Alternatives.**
- *Hand-maintained include list.* Rejected: it drifts, and a drifted list either
  hides a file or fails confusingly.
- *Compile everything and ignore errors.* Rejected: that is how a build reports
  success while producing nothing.

**Consequence.** The report can state exactly which sources were not run and why.
The exclusion is a fact in the evidence, not an omission.

---

## ADR-008 — Privacy audit is conditional on capability

**Status:** Accepted (Stage 7b) — **supersedes** the unconditional rule

**Decision.** `RECORD_AUDIO` is required only when voice enrolment is implemented,
and forbidden when it is not.

**Alternatives.**
- *Always require it.* Rejected: it forces a microphone permission into a build with
  no recording code, which is a privacy defect in the opposite direction.

**Consequence.** The audit now catches both errors: a build that records without the
permission, and a build that asks for a microphone it never uses.

---

## ADR-009 — The manifest is read, never assumed

**Status:** Accepted (Stage 7b) — **supersedes** the fixture fallback

**Decision.** `R11` reads the shipped `AndroidManifest.xml` and fails if it cannot.

**Alternatives.**
- *Fall back to a canonical literal.* Rejected: it made the test pass in exactly the
  situation it existed to catch. This is the defect Stage 7a found as blocker B4.

**Consequence.** The first real run failed, exposing `INTERNET` and `allowBackup`.
The gate is now falsifiable.

---

## ADR-010 — The neural tier is unsupported, not pending

**Status:** Accepted (Stage 7b) — **supersedes** the "implemented" claim

**Decision.** The neural TTS tier is classified **unsupported**. There is no JNI
shim, no bundled model, and no inference runtime in this tree.

**Alternatives.**
- *Ship the declared JNI symbols and call it implemented.* Rejected: that is a
  placeholder in the production runtime, which the mandate explicitly forbids.
- *Implement the shim now.* Not possible here: it requires the Android NDK, the
  model binaries, and a device to validate against.

**Consequence.** The product cannot speak. This is the single largest gap between
the documentation and the product, and it is now stated in the README, the
requirements, the limitations and the handoff.

---

## ADR-011 — No merge to the default branch in this stage

**Status:** Accepted (Stage 7b)

**Decision.** The candidate lives on `integration/stage7b-prod-candidate`. It is not
merged, not released, not deployed.

**Why.** The mandate requires a pull request for Founder review, and requires that
merging remain a human decision. A machine that merges its own candidate has removed
the review it was asked to enable.

---

## Supersessions summary

| ADR | Superseded what | Reason |
|---|---|---|
| ADR-004 | R3's constructor-only assertion | It could not fail |
| ADR-008 | Unconditional `RECORD_AUDIO` | Forced an unused permission |
| ADR-009 | Fixture fallback in R11 | Made the gate unfalsifiable |
| ADR-010 | "Neural tier implemented" | No shim, no model, no runtime |

Each superseded decision is preserved in `docs/evolution/` and in
`SONATA_MASTER_REQUIREMENTS.md` §7.
