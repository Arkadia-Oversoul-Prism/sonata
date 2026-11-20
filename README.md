# Sonata — Arkadia Voice App

Android 10+ offline text-to-speech reader for the Arkadia Intelligence System (ARKANA).

> ## ⚠️ Read this before anything else
>
> **Sonata cannot speak yet.** There is no bundled model, no inference runtime and
> no JNI bridge in this repository. The reading pipeline, narration planning,
> privacy enforcement and library logic are implemented and tested; the speech
> engine is not.
>
> This README previously described a working "offline neural TTS" and quoted
> performance figures ("cold start < 1 s", "speech start < 2 s") that were never
> measured. Both claims have been removed. See
> [`docs/SONATA_KNOWN_LIMITATIONS.md`](docs/SONATA_KNOWN_LIMITATIONS.md).

---

## Claim classification

Every capability below is classified into exactly one of five states. The
classification is the contract: if a feature is not in **Available now**, it does
not work today.

### ✅ Available now

Implemented, and verified by a test that runs in CI against the current commit.

| Capability | Verified by |
|---|---|
| Text chunking without loss or reordering | `SpeechChunkerTest` (17 tests) |
| Text length limits enforced before synthesis | `TextLimitsTest` |
| Discrete, bounded speed steps | `SpeedStepsTest` |
| Total playback state machine | `PlaybackStateTest` |
| Reading position representation and restore | `ResumePointTest` |
| Library ordering and display rules | `LibraryRulesTest` |
| Read-along coverage: every character exactly once | `ReadAlongTest` |
| Narration planning: 8 styles, measurable prosody | `StylePlannerTest` (28 tests) |
| Sentence splitting that survives initials and abbreviations | `RegressionTest.R4` |
| Long-sentence splitting under the 320-char cap | `StylePlannerTest` |
| WAV export | `ExportTest` |
| Resumable batch export | `ReliabilityTest` |
| Storage accounting | `ReliabilityTest` |
| Voice sample quality scoring | `VoiceStudioTest` |
| Bounded adapter training plan | `VoiceStudioTest` |
| **Offline enforcement: no `INTERNET`, no backup, no cleartext** | `RegressionTest.R11`, `ManifestAudit` |
| **No network attempt from the narration pipeline (JVM)** | `PrivacyTest` |

### 🟡 Implemented but not device-validated

The code exists and compiles. It has **never run on a physical device**. It may
work; nobody knows.

| Capability | Why unvalidated |
|---|---|
| PROCESS_TEXT handoff from other apps | Needs the Android runtime (Robolectric test not run here) |
| Floating overlay player | Needs a real window manager |
| Foreground playback service and notification | Needs the Android runtime |
| Library persistence (Room) | Needs the Android runtime |
| MediaStore / SAF export | Needs a device |
| Voice enrolment recording | Needs a microphone |

### 🟠 Experimental

Present, but not on the path to a working product and not covered by acceptance
criteria.

| Capability | Status |
|---|---|
| `OkHttpTtsTransport` | Dead code. With `INTERNET` removed it cannot work. Retained only until the offline engine replaces it. |
| Adapter training execution | The *plan* is implemented; the training loop is not. |
| Adapter application to audio | Not implemented. |

### 🔵 Planned

Designed and documented, not written.

| Capability | Reference |
|---|---|
| Bundled speech model (Kokoro-82M / Piper-VITS) | `docs/SONATA_MODEL_MANIFEST.md` |
| JNI inference bridge | `docs/SONATA_ARCHITECTURE_DECISIONS.md` ADR-010 |
| On-device adapter training loop | `docs/SONATA_TRAINING_ARCHITECTURE.md` §5 |
| Database migrations | Schema is at v1; registry empty |
| About screen with licence attributions | `docs/SONATA_PRODUCTION_HANDOFF.md` §5.3 |

### ⛔ Unsupported

Explicitly not supported. Do not expect these to work.

| Capability | Why |
|---|---|
| **Neural TTS of any kind** | No model, no runtime, no JNI |
| **Any network feature** | `INTERNET` permission is deliberately absent |
| **Cloud voice / cloud TTS** | Contradicts the offline guarantee |
| **Speech-to-text** | Not implemented |
| **Voice cloning end-to-end** | Converter exists in the catalogue only |
| **Non-English languages** | English only |
| **Tablets / foldables** | Not tested |
| **Android below API 29** | `minSdk` is 29 |

---

## Architecture

Sonata is offline-only by construction, not by configuration. Three independent
mechanisms enforce it:

1. **No `INTERNET` permission** — a socket cannot be opened.
2. **`ModelDescriptor` has no URL field** — a model cannot be described as
   downloadable.
3. **`R11` fails the build** if the permission returns.

```
app/                     Android application (Activities, Services, Room, MediaStore)
narration-core/          Pure-JVM module: narration, privacy, reliability, validation
docs/evolution/          E0–E11, the historical record (preserved, not edited)
docs/SONATA_*.md         The current authoritative documentation set
ci/                      CI harness: run-ci.sh + scope.py (tracked, reproducible)
```

`narration-core` has no Android dependency, so its 169 tests run anywhere,
including CI, with no SDK.

---

## Building

### Tests (works anywhere)

Requires JDK 17 and Kotlin 1.9.22.

```bash
export LIB=/path/to/jars        # see docs/SONATA_PRODUCTION_HANDOFF.md §4.2
export STD=/path/to/kotlinc/lib
export KOTLINC=/path/to/kotlinc

./ci/run-ci.sh                  # all jobs:  expect 281 tests, VERDICT=PASS
```

### Android build (requires the SDK)

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**This has not been run.** No Android SDK is available in the environment where
this candidate was prepared, so no APK exists. The CI workflow
(`.github/workflows/ci.yml`) runs this job where the SDK is present, and it is
expected to be the first place an APK is produced.

---

## Documentation

| Document | Purpose |
|---|---|
| [`SONATA_MASTER_REQUIREMENTS.md`](docs/SONATA_MASTER_REQUIREMENTS.md) | What the product must do, and how each requirement is verified |
| [`SONATA_ARCHITECTURE_DECISIONS.md`](docs/SONATA_ARCHITECTURE_DECISIONS.md) | ADRs, including reversed decisions and why |
| [`SONATA_IMPLEMENTATION_LEDGER.md`](docs/SONATA_IMPLEMENTATION_LEDGER.md) | What was built, when, at which commit |
| [`SONATA_MODEL_MANIFEST.md`](docs/SONATA_MODEL_MANIFEST.md) | Models, checksums, licences — and the fact that none are present |
| [`SONATA_TRAINING_ARCHITECTURE.md`](docs/SONATA_TRAINING_ARCHITECTURE.md) | Adapter training design and its bounds |
| [`SONATA_PRIVACY_AND_SECURITY.md`](docs/SONATA_PRIVACY_AND_SECURITY.md) | The privacy promise and what the checks found |
| [`SONATA_DEVICE_VALIDATION.md`](docs/SONATA_DEVICE_VALIDATION.md) | The device gates — none executed |
| [`SONATA_TEST_AND_ACCEPTANCE_REPORT.md`](docs/SONATA_TEST_AND_ACCEPTANCE_REPORT.md) | 281 tests, 0 failures, and what they do not cover |
| [`SONATA_KNOWN_LIMITATIONS.md`](docs/SONATA_KNOWN_LIMITATIONS.md) | Every limitation, blockers first |
| [`SONATA_PRODUCTION_HANDOFF.md`](docs/SONATA_PRODUCTION_HANDOFF.md) | The verdict and the evidence for it |

The historical record in `docs/evolution/` (E0–E11) is **preserved unmodified**.
Where it is now wrong, the current documents say so and explain why; the old
documents are not edited, because a record that is silently corrected is not a
record.

---

## Status

| Field | Value |
|---|---|
| Candidate commit | `c6b7db5b355831d1da11c040f944349a465a9cf6` |
| Tests | 281 passed, 0 failed |
| APK | Not built |
| Device validation | Not performed |
| Verdict | **NOT READY — IMPLEMENTATION OR EVIDENCE INCOMPLETE** |
