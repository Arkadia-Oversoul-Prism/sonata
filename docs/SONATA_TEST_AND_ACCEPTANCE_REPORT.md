# SONATA_TEST_AND_ACCEPTANCE_REPORT

**Status:** Stage 7b production candidate
**Candidate commit:** `7d2a30139811c951bfc529fa2ea770f02601231b`
**Tree SHA:** recorded in `ci-evidence.log`
**Date:** 2026-11-20
**Evidence file:** `ci-evidence.log` (committed alongside this document)

---

## 1. Result

| Job | Scope | Tests | Failures | Exit |
|---|---|---|---|---|
| App core (JVM) | 38 main + 8 test sources | **112** | **0** | 0 |
| Narration core | 15 main + 6 test sources | **169** | **0** | 0 |
| **Total** | | **281** | **0** | **0** |

Both jobs ran against the exact candidate commit. The commit SHA is the first line
of `ci-evidence.log`, so the evidence cannot be detached from the tree it describes.

---

## 2. What was executed, and what was not

### 2.1 Executed

The JVM-verifiable subset of the application, plus the whole of `narration-core`.

`build/scope.py` computes the scope from imports and prints every excluded file. The
run reported:

```
jvm_main_sources=38
jvm_test_sources=8
excluded_sources=38
```

### 2.2 Not executed — and why

38 sources are excluded because they import `android.*`, `androidx.*` (beyond the
two pure-JVM annotation artifacts), or `org.robolectric.*`. They need the Android
SDK, which is not available in this environment.

The excluded set includes the tests that cover the most user-visible behaviour:

| Excluded test | What it would verify |
|---|---|
| `SonataRepositoryTest` | Library persistence across launches (Room) |
| `FloatingOverlayServiceTest` | The overlay stays alive over other apps |
| `ProcessTextHandoffTest` | Text selection reaches the reader |
| `VoicePreviewTest` | Voice auditioning |
| `TtsEngineOfflineTest` | The offline engine path |
| `TtsEngineOnlineTest` | The (now unreachable) online path |

**This is the most important limitation in this report.** The tests that pass are
real, but they are not the tests that would prove the app works.

### 2.3 Not run at all

- Android build (`assembleRelease`, `bundleRelease`) — no SDK
- Lint / static analysis — no Android toolchain
- Dependency vulnerability scan — no scanner
- Device gates G1–G6 — no device
- Performance measurements S0-1..S0-4 — no device

---

## 3. Test integrity

Stage 7a found three tests that did not test what their names claimed. All three are
fixed, and the fixes are verified by the fact that two of them **failed** when first
corrected.

### 3.1 R3 — engine invocation count

**Was:** `assertNotNull(NarrationPipeline(EspeakEngine()))`. This passes even if the
engine renders the whole plan as one document, which is the exact defect the test
was named for.

**Now:** a `RecordingEngine` observes every plan handed to the engine. The test
asserts the invocation count equals the segment count, that each invocation carries
one segment, and that the reported count matches what the engine saw.

**Evidence it is real:** the first corrected run failed with
`IllegalArgumentException: Prefix string "r3" too short` — a genuine bug in the new
test, which proves the new test actually executes its body.

### 3.2 Connectivity probe — tautology

**Was:** `assertTrue(live || !live)`. Cannot fail.

**Now:** asserts the probe returns a definite value and honours its timeout. The
observed value is printed, not asserted, because it depends on the environment.

**Evidence it is real:** the run printed `connectivityProbe: live=true elapsedMs=99`.

### 3.3 R11 — manifest fixture

**Was:** fell back to a manifest string literal embedded in the test when the real
file was absent. It passed in exactly the situation it existed to catch.

**Now:** fails if the shipped manifest cannot be read.

**Evidence it is real:** the first corrected run **failed** with
`manifest policy failures: [NO_INTERNET, RECORD_AUDIO_PRESENT, BACKUP_DISABLED, BACKUP_EXCLUSIONS]`.
The product was violating its own privacy policy. This is the single most valuable
result in the whole stage.

---

## 4. Coverage by requirement

| Requirement group | Tests | Status |
|---|---|---|
| Chunking and limits | 47 | Pass |
| Library and read-along | 24 | Pass |
| Narration planning | 41 | Pass |
| Privacy and offline | 22 | Pass |
| Reliability and export | 30 | Pass |
| Voice studio | 17 | Pass |
| Regression suite | 20 | Pass |
| Integration | 8 | Pass |
| **Android-tier behaviour** | **0 executed** | **Not run** |

---

## 5. Functional acceptance

| # | Criterion | Status | Evidence |
|---|---|---|---|
| F1 | Text is chunked without loss | **Pass** | `SpeechChunkerTest` |
| F2 | Reading position is representable and restorable | **Pass** | `ResumePointTest` |
| F3 | Playback state machine is total | **Pass** | `PlaybackStateTest` |
| F4 | Narration styles are measurably distinct | **Pass** | `StylePlannerTest` |
| F5 | Long sentences split under the cap | **Pass** | `StylePlannerTest` (new) |
| F6 | Read-along covers every character once | **Pass** | `ReadAlongTest` |
| F7 | Export writes a valid WAV | **Pass** | `ExportTest` |
| F8 | Batch export is resumable | **Pass** | `ReliabilityTest` |
| F9 | The app reads shared text | **Not run** | Robolectric |
| F10 | The library persists | **Not run** | Room |
| F11 | Speech is produced | **Not run** | No engine |

---

## 6. Offline and privacy acceptance

| # | Criterion | Status | Evidence |
|---|---|---|---|
| P1 | No `INTERNET` permission | **Pass** | `R11` against the real manifest |
| P2 | No `ACCESS_NETWORK_STATE` | **Pass** | `ManifestAudit` |
| P3 | Backup disabled with exclusions | **Pass** | `ManifestAudit` |
| P4 | No cleartext traffic | **Pass** | `ManifestAudit` |
| P5 | No unused permission | **Pass** | `ManifestAudit` |
| P6 | Pipeline makes no network attempt (JVM) | **Pass** | `PrivacyTest` |
| P7 | No network attempt on a device | **Not run** | No device |

P1–P5 **failed** before this stage and pass now. That transition is the evidence.

---

## 7. Performance acceptance

| # | Criterion | Threshold | Measured |
|---|---|---|---|
| S0-1 | Real-time factor | ≤ 0.5 | **No** |
| S0-2 | Peak RSS | ≤ 700 MB | **No** |
| S0-3 | Training time, 5 min audio | < 10 min | **No** |
| S0-4 | Thermal abort | none | **No** |

No performance criterion was defined before Stage 7b. These four are adopted from
E11's S0 exit gate. None is measured.

---

## 8. Reproducing this result

```bash
git checkout 7d2a30139811c951bfc529fa2ea770f02601231b

# Toolchain: Kotlin 1.9.22, JDK 17, JUnit 4.13.2, coroutines 1.7.3,
#            room-common 2.6.1, annotation-jvm 1.7.1, okhttp 4.12.0,
#            okio 3.6.0, org.json 20240303

export LIB=/path/to/jars
export STD=/path/to/kotlinc/lib
export KOTLINC=/path/to/kotlinc

./ci/run-ci.sh             # both jobs; expect 281 tests, VERDICT=PASS
```

The CI workflow `.github/workflows/ci.yml` performs exactly these steps and uploads
the logs as artifacts keyed by commit SHA.

---

## 9. Verdict on test evidence

The test evidence is **strong for the platform-independent logic** and **absent for
the Android application**. 281 tests pass and none of them is a tautology or a
fixture test any more. But the tests that would prove the app works — persistence,
overlay, text handoff, speech — did not run, and one of them (speech) could not pass
if it did, because there is no engine.

A reviewer should read "281 tests pass" as "the logic is sound", not as "the app
works".
