# SONATA_KNOWN_LIMITATIONS

**Status:** Stage 7b production candidate
**Candidate commit:** `a102db4f01b04f95214216c3118545d4030cbb02`
**Date:** 2026-11-20

Every limitation below is a fact about this candidate, not a plan. Where a
limitation is a release blocker it is marked **BLOCKER**.

---

## 1. Blocking limitations

### L1 — The app cannot speak **BLOCKER**

There is no speech engine. No model binary, no inference runtime, no JNI bridge, no
`SpeechModel` implementation. `SpeechModel` is an interface; the only implementation
is `FakeSpeechModel`, which is a test double.

**Consequence.** The core function of a text-to-speech reader does not work. This is
not a risk or a degradation; it is the absence of the product.

**What it would take.** Bundle a model, implement the JNI bridge, implement
`SpeechModel`, validate on a device.

### L2 — No APK or AAB can be produced here **BLOCKER**

The build environment has no Android SDK. `./gradlew assembleRelease` cannot run.

**Consequence.** There is no installable artifact. The candidate is source only.

**What it would take.** An environment with the Android SDK and AGP, or CI with
`android-actions/setup-android` (the workflow is written and will run there).

### L3 — No device validation **BLOCKER**

Gates G1–G6 and measurements S0-1..S0-4 are unexecuted. See
`SONATA_DEVICE_VALIDATION.md`.

**Consequence.** Nothing is known about behaviour on real hardware: launch, audio
output, overlay survival, thermal behaviour, memory.

### L4 — The neural tier is unsupported, not pending **BLOCKER**

Stage 7a found `SherpaOnnxSession` JNI symbols declared but unimplemented. In this
tree there is no such class and no `external fun` at all. The tier is classified
**unsupported** (ADR-010).

**Consequence.** Any documentation that described a two-tier engine with a working
neural tier was wrong. It is corrected here and in the README.

---

## 2. Significant limitations

### L5 — OkHttp is dead code that opens sockets

`OkHttpTtsTransport` remains in the tree. With `INTERNET` removed it cannot work.
It is a liability: dead code that opens sockets is exactly what a future change
might accidentally re-enable.

**Why it is not deleted now.** Removing the only transport before the offline engine
exists would leave no synthesis path at all. The removal should happen with the
replacement.

### L6 — The offline sentinel is partly self-referential

`NetworkSentinel.attempt` is called only from tests. "Zero attempts" is therefore
partly guaranteed by construction. The independent line is the `unshare -n`
namespace run, which removes the network at the OS level.

**Consequence.** The JVM offline evidence is weaker than it appears. Both lines are
reported; neither is overstated.

### L7 — Pause recall of 1.000 is partly a tolerance artefact

The Stage 4–6 measurement reported pause recall 1.000. The tolerance was 150 ms and
the smallest planned pause was 123 ms, so a pause could be detected slightly early
or late and still count.

**Consequence.** The figure is real but flattered by the tolerance. It should not be
quoted as "every pause was found exactly".

### L8 — WER figures predate the corpus change

The Stage 4–6 report's WER of 0.5247 was measured against a corpus whose longest
sentence was 239 characters. Stage 7b added an ~800-character sentence, changing the
corpus hash to `e1eafd06…`.

**Consequence.** The old WER is not comparable to any future run. It must be
re-measured.

### L9 — No dependency vulnerability scan

No scanner was run. The dependency set is small and conventional, but "small and
conventional" is not a scan.

### L10 — No lint or static analysis

The Android lint task cannot run without the SDK. Kotlin compiler warnings are
suppressed in the harness (`-nowarn`), so even compiler-level warnings are not
surfaced.

**Consequence.** There is no static-analysis evidence at all.

### L11 — Room migrations are untested

The database is at version 1 and the migration registry is empty. There is nothing
to migrate yet, so nothing is broken — but the migration path has never been
exercised, and the first schema change will be the first test of it.

### L12 — The long-sentence split is now tested but not measured

Stage 7b added a corpus passage that crosses the 320-character cap and two tests
that assert the split happens. The split is therefore **tested**. It has not been
**measured** — no WER or pause evaluation has been run on the new passage.

---

## 3. Minor limitations

| # | Limitation |
|---|---|
| L13 | `-nowarn` in the harness hides compiler warnings |
| L14 | The JVM harness excludes 38 sources; the exclusion is printed but the excluded behaviour is unverified |
| L15 | No signing configuration; no keystore in the repo (correct, but it means no signed build exists) |
| L16 | No ProGuard/R8 configuration verified |
| L17 | No accessibility audit (TalkBack, font scaling) |
| L18 | No localisation beyond English |
| L19 | No crash reporting, by design — which also means no field diagnostics |
| L20 | The floating overlay's behaviour under battery optimisation is unknown |

---

## 4. Limitations that are deliberate design choices

These are not defects. They are recorded so a reviewer does not mistake them for
gaps.

| Choice | Reason |
|---|---|
| No network permission | The privacy promise (ADR-001) |
| No model download | A download is a network path (ADR-001) |
| No analytics | Nothing to send, nowhere to send it |
| No account | Nothing to correlate |
| No cloud TTS | Contradicts the offline guarantee |
| No merge to default branch | Review is a human decision (ADR-011) |

---

## 5. Summary for a reviewer

The candidate is **source-complete for the platform-independent logic** and
**functionally incomplete as a product**. The logic is tested (281 tests, 0
failures) and the privacy policy is now genuinely enforced. The product cannot
speak, cannot be built into an APK here, and has never run on a device.

That is the honest position, and it is the position the verdict in
`SONATA_PRODUCTION_HANDOFF.md` is computed from.
