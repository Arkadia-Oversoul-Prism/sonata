# SONATA_KNOWN_LIMITATIONS

**Status:** Stage 7b production candidate
**Candidate commit:** `a102db4f01b04f95214216c3118545d4030cbb02`
**Date:** 2026-11-20

Every limitation below is a fact about this candidate, not a plan. Where a
limitation is a release blocker it is marked **BLOCKER**.

---

## 1. Blocking limitations

### L1 — The app can speak, but only in the platform voice **PARTIAL**

**Superseded 2026-11-26 by PR #8.** The previous text said there was no speech
engine at all. That is no longer true, and the correction matters in both
directions.

**What now works.** `PlatformSpeechModel` over `AndroidPlatformSynthesis` renders
speech through the device's own `TextToSpeech` engine via `synthesizeToFile`, and
`OfflineEngine` uses it as the fallback tier when no neural model is installed.
`EngineFactory` constructs the engine in production. A device with no model file
can now produce a saved narration.

**What still does not work.**

1. **No neural model.** Kokoro-82M and Piper are still absent. The app speaks in
   whatever voice the device's engine provides — which on many devices is a
   robotic formant synthesiser, not the natural voice the product promises. The
   quality tier is still missing.
2. **No cloned voice.** The platform engine has no adapter stage, so a
   cloned-voice request is refused rather than silently downgraded.
3. **`EngineFactory` is not called from the UI.** The engine is constructible in
   production, but no screen invokes `narrate()` yet. The library path exists and
   is tested; it is not yet reachable by a user tapping a button.
4. **No device validation.** That a real device's engine produces audio is
   **UNVERIFIED**. The adapter is tested on the JVM against a double, which proves
   the wiring and the WAV handling, not the platform call.

**Consequence.** The core function is no longer absent, but it is not yet the
product: the voice is the device's, not Sonata's, and no UI reaches it.

**What it would take.** Bundle a licensed neural model; call `EngineFactory` from
the library screen; validate on a device.

### L2 — No **signed** APK or AAB **BLOCKER for release**

**Corrected.** An earlier draft said no APK could be produced at all. That was
wrong: `.github/workflows/build-apk.yml` builds on a GitHub runner with a full
SDK, and it has produced both artifacts for this candidate.

| Artifact | Status |
|---|---|
| Release APK (unsigned) | ✅ built, 1.5 MB |
| Release AAB (unsigned) | ✅ built |
| Debug APK (installable) | ✅ built, 5.8 MB |

**Consequence.** The packages exist and can be inspected and installed (debug).
They are **unsigned**, so they cannot be published. Signing needs a keystore,
which is a release-authority decision, not a build decision.

**What it would take.** A keystore and a `signingConfig`, supplied by whoever
holds release authority. Nothing in the code blocks it.

### L3 — No **physical** device validation **BLOCKER**

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

**Corrected 2026-11-27 (PR #9).** The paragraph above said the transport "cannot
work" and stopped there, which hid a live defect: the *engine* still chose the
online tier on every read, so the dead transport was attempted, retried twice and
backed off before the offline voice took over. The user-visible result was an
`UnknownHostException` on every read and a "Fetching the next part of the passage…"
state for a fetch that was never possible. The tier decision is now
`SynthesisTierPolicy`, and it consults the build's own capability — the manifest
permission — rather than only the stored preference. See §2 L5a.

### L5a — The tier decision is verified in JVM; its two call sites are not

The decision itself — preference, gateway, capability in, tier out — is pure JVM and
is executed by the tracked harness (`SynthesisTierPolicyTest`, 6 tests, job 1).

The two places that *call* it are not executed there:

| Call site | Why it is out of the JVM harness |
|---|---|
| `TtsEngine.start` / `TtsEngine.preview` | `TtsEngine.kt` is `FORCED_OUT` in `ci/scope.py` |
| `MainActivity.render` (the More-row label) | The Activity needs the Android runtime |

`TtsEngine`'s behaviour is covered by five tests in `TtsEngineOnlineTest`, which run
under `./gradlew testDebugUnitTest` in `.github/workflows/build-apk.yml`. They were
executed locally for this change against a scratch harness that stubs
`android.util.Log` and `AndroidOfflineVoice`: **70 tests, 0 failures**, and removing
the gate reddens exactly the four that assert it. That scratch harness is not
tracked, so this is a measurement, not reproducible CI evidence. The Activity label
is verified by inspection only.

**Consequence.** Do not read "the gate is tested" as "the gate is wired". The wiring
is reviewed, and only the Android job can prove it.

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
| L21 | The retry backoff and the notification ticker are timing-dependent and are proven only on the JVM scheduler and the emulator, never on hardware |
| L22 | `normaliseNumbers` deliberately does not spell out arbitrary numbers as words; "21st" is left as digits because a full number-to-words implementation is locale-dependent and a wrong expansion is worse than the digits |
| L23 | The year rule is bounded 1900–2099, so a year like 1066 is read as digits. This is a deliberate trade: the wider range split prices like `1250` into `12 50` |

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
**functionally incomplete as a product**. The logic is tested (405 tests, 0
failures) and the privacy policy is genuinely enforced. The product cannot
speak, cannot be built into an APK here, and has never run on a device.

That is the honest position, and it is the position the verdict in
`SONATA_PRODUCTION_HANDOFF.md` is computed from.

**Updated 2026-11-26 (PR #7).** The test count above was 281 when this section
was written; it is now 405. The verdict is unchanged, because the count was never
what the verdict rested on — L1 and L4 are.
