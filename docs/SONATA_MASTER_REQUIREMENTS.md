# SONATA_MASTER_REQUIREMENTS

**Status:** Stage 7b production candidate
**Candidate commit:** `7d2a30139811c951bfc529fa2ea770f02601231b`
**Branch:** `integration/stage7b-prod-candidate`
**Date:** 2026-11-20
**Supersedes:** the requirement statements scattered across E5–E11 (preserved unchanged as historical evidence)

---

## 1. What this document is

This is the single authoritative statement of what Sonata must do, what it must not
do, and how each requirement is verified. It exists because the requirement set had
until now been distributed across eleven evolution documents, several of which
contradict each other because the product changed underneath them.

E5–E11 are **not** rewritten. They remain in `docs/evolution/` exactly as they were
written, because they are the record of what was believed at the time and why. Where
this document supersedes one of them, §7 records the supersession and the reason.

---

## 2. Product definition

Sonata is an **offline text-to-speech reader for Android**. A user selects text in
any app, or shares it to Sonata, and Sonata reads it aloud. The reading is
controllable (pause, resume, speed, position), the position survives process death,
and the audio can be exported.

The defining constraint is the **offline boundary**: the shipped application must
not be able to reach the network. This is not a performance preference. It is the
privacy promise the product is sold on, and it is enforced structurally (§4.3).

---

## 3. Functional requirements

Each requirement carries an ID, a statement, and its verification. **Verification
status is one of:**

- **EXECUTED** — a test ran in this environment and passed; the test is named.
- **COMPILED** — the code exists and compiles, but no test exercises the behaviour.
- **NOT RUN** — the code exists but requires the Android SDK or a physical device,
  neither of which is available here.
- **ABSENT** — the capability does not exist in this tree.

| ID | Requirement | Verification | Status |
|---|---|---|---|
| FR-01 | Text is split into speakable pieces without exceeding the backend limit | `SpeechChunkerTest` (17 tests) | EXECUTED |
| FR-02 | Splitting never loses or reorders a character | `SpeechChunkerTest.concatenating the pieces reproduces the input exactly` | EXECUTED |
| FR-03 | Playback state transitions are total and legal | `PlaybackStateTest` | EXECUTED |
| FR-04 | Reading position survives process death | `ResumePointTest` | EXECUTED |
| FR-05 | Speed changes are discrete and bounded | `SpeedStepsTest` | EXECUTED |
| FR-06 | Text length limits are enforced before synthesis | `TextLimitsTest` | EXECUTED |
| FR-07 | Library ordering and display rules are deterministic | `LibraryRulesTest` | EXECUTED |
| FR-08 | Read-along highlighting covers every character exactly once | `ReadAlongTest`, `StylePlannerTest.plan covers the whole document` | EXECUTED |
| FR-09 | Narration styles produce measurably distinct prosody | `StylePlannerTest` (28 tests) | EXECUTED |
| FR-10 | Sentence splitting does not cut at initials or abbreviations | `RegressionTest.R4` | EXECUTED |
| FR-11 | Long sentences are split at clause boundaries under the 320-char cap | `StylePlannerTest.a corpus sentence over the cap is actually split` | EXECUTED |
| FR-12 | Export produces a valid WAV | `ExportTest` | EXECUTED |
| FR-13 | Batch export is resumable and does not duplicate work | `ReliabilityTest` | EXECUTED |
| FR-14 | Storage accounting reports real usage | `ReliabilityTest` | EXECUTED |
| FR-15 | Voice sample quality is scored before training | `VoiceStudioTest` | EXECUTED |
| FR-16 | Adapter training plan is bounded and refuses bad input | `VoiceStudioTest` | EXECUTED |
| FR-17 | The app reads text shared from other apps | `ProcessTextHandoffTest` | NOT RUN (Robolectric) |
| FR-18 | The floating overlay stays alive over other apps | `FloatingOverlayServiceTest` | NOT RUN (Robolectric) |
| FR-19 | The library persists across launches | `SonataRepositoryTest` | NOT RUN (Room) |
| FR-20 | Speech is actually produced on a device | — | ABSENT (no engine) |

**FR-20 is the honest headline.** No speech engine is bundled or implemented in this
tree. See §5 and `SONATA_KNOWN_LIMITATIONS.md`.

---

## 4. Non-functional requirements

### 4.1 Offline boundary

| ID | Requirement | Verification | Status |
|---|---|---|---|
| NFR-01 | The shipped manifest does not request `INTERNET` | `RegressionTest.R11` against the real manifest | EXECUTED |
| NFR-02 | The shipped manifest does not request `ACCESS_NETWORK_STATE` | `ManifestAudit` check `NO_ACCESS_NETWORK_STATE` | EXECUTED |
| NFR-03 | Backup is disabled and private data is excluded | `ManifestAudit` checks `BACKUP_DISABLED`, `BACKUP_RULES_EXCLUDE_PRIVATE_DATA` | EXECUTED |
| NFR-04 | Cleartext traffic is not enabled | `ManifestAudit` check `NO_CLEARTEXT` | EXECUTED |
| NFR-05 | The narration pipeline makes no network attempt | `PrivacyTest.offline audit` | EXECUTED |
| NFR-06 | The app requests no permission it cannot use | `ManifestAudit` check `RECORD_AUDIO_MATCHES_CAPABILITY` | EXECUTED |

NFR-01 and NFR-03 **failed** when first run against the real manifest in this stage.
That is the single most important fact in this document: the privacy gate had been
passing against a fixture, and the product was violating its own policy. See §7.1.

### 4.2 Privacy

| ID | Requirement | Verification | Status |
|---|---|---|---|
| NFR-07 | No user text leaves the device | NFR-01 + NFR-05 | EXECUTED |
| NFR-08 | No voice sample leaves the device | NFR-03 (backup exclusions) | EXECUTED |
| NFR-09 | No analytics, telemetry or crash reporting SDK is present | dependency audit, §4.4 | EXECUTED |

### 4.3 Structural enforcement

The offline boundary is enforced in three independent places, so that no single
mistake removes it:

1. **The manifest** — no `INTERNET` permission. A socket cannot be opened.
2. **The type system** — `ModelDescriptor` has no URL field, by design. A model
   cannot be described as downloadable.
3. **The test suite** — `R11` fails the build if the permission returns, and
   `PrivacyTest` fails if the pipeline attempts a connection.

### 4.4 Dependency inventory

The application module declares: AndroidX core/appcompat/activity, Room, Media3,
OkHttp, kotlinx-coroutines. OkHttp is present for the legacy transport and is
**unreachable in the shipped build** because `INTERNET` is not declared. This is
recorded as a limitation, not presented as a feature.

### 4.5 Performance

| ID | Requirement | Verification | Status |
|---|---|---|---|
| NFR-10 | Real-time factor ≤ 0.5 on mid-tier device | E11 S0 gate | NOT RUN (no device) |
| NFR-11 | Peak RSS ≤ 700 MB | E11 S0 gate | NOT RUN (no device) |
| NFR-12 | Adapter training < 10 min for 5 min audio | E11 S0 gate | NOT RUN (no device) |
| NFR-13 | No thermal abort during training | E11 S0 gate | NOT RUN (no device) |

**No performance acceptance criteria were defined before Stage 7b.** Stage 7a
correctly flagged this. The four criteria above are adopted from E11's S0 exit gate,
which is the only place the project ever wrote down a performance threshold. They
remain unmeasured because they require hardware.

---

## 5. What the product is not

Stated plainly, because the previous documentation implied otherwise:

- **It is not a working speech application.** There is no bundled model, no
  inference runtime, and no JNI implementation. `SpeechModel` is an interface with
  no production implementation in this tree.
- **It has never run on a device.** Every device gate in E11 is untested.
- **It cannot produce an APK from this environment.** No Android SDK is available.
- **The neural tier is unsupported**, not "implemented but unvalidated".

---

## 6. Acceptance criteria for production review

The candidate is ready for human production review when **all** of the following
hold. This is the checklist the verdict in `SONATA_PRODUCTION_HANDOFF.md` is
computed from.

| # | Criterion | Met? |
|---|---|---|
| A1 | Integration tree exists at a named commit | **Yes** — `7d2a301` |
| A2 | CI runs against that exact commit | **Yes** — `ci-evidence.log`, SHA recorded |
| A3 | All executable tests pass | **Yes** — 112 + 169 |
| A4 | Privacy policy verified against the real manifest | **Yes** — and it found a real violation, now fixed |
| A5 | No placeholder presented as production | **Yes** — reclassified as unsupported |
| A6 | Documentation claims match implementation | **Yes** — this document |
| A7 | APK/AAB built and signed | **No** — no Android SDK |
| A8 | Device gates G1–G6 executed | **No** — no device |
| A9 | Performance criteria measured | **No** — no device |
| A10 | Model binaries present with checksums | **No** — no models bundled |

A1–A6 are met. A7–A10 are external gates that cannot be closed in this environment.

---

## 7. Superseded decisions

Each entry records what was decided, what replaced it, and why. Historical
documents are not edited.

### 7.1 The privacy gate was verified against a fixture (supersedes E10 §privacy)

**Was:** `R11` fell back to a manifest string literal embedded in the test when the
real manifest was absent, and the Stage 4–6 report described this as "manifest
parsed as text".

**Now:** `R11` fails if the shipped manifest cannot be read. Running it against the
real manifest immediately failed with `NO_INTERNET`, `RECORD_AUDIO_PRESENT`,
`BACKUP_DISABLED`, `BACKUP_EXCLUSIONS`.

**Why it changed:** a test that passes when the artifact is missing is not a test of
the artifact. The fallback made the gate unfalsifiable in exactly the situation it
existed to catch.

**Consequence:** the shipped manifest was genuinely violating the policy. `INTERNET`
was removed, `allowBackup` set to `false`, backup exclusion rules added, and
`usesCleartextTraffic` set to `false`.

### 7.2 `RECORD_AUDIO` was required unconditionally (supersedes E10 §permissions)

**Was:** the audit failed any manifest without `RECORD_AUDIO`, on the grounds that
voice enrolment needs it.

**Now:** the audit requires `RECORD_AUDIO` **only if** voice enrolment is
implemented, and fails a manifest that requests it when it is not.

**Why it changed:** this tree contains no recording code — no `MediaRecorder`, no
`AudioRecord`. Demanding a microphone permission from a build that cannot record is
itself a privacy defect, and the old rule would have forced one.

### 7.3 The neural tier was described as implemented (supersedes E10 §engine)

**Was:** E10 and the Stage 4–6 report described a two-tier engine with a neural
tier, and Stage 7a found `SherpaOnnxSession` JNI symbols declared but unimplemented.

**Now:** the neural tier is **unsupported**. There is no JNI shim in this tree at
all — no `external fun`, no `System.loadLibrary`.

**Why it changed:** declared-but-unimplemented native symbols are a placeholder, and
a placeholder in the production runtime is the specific thing the mandate forbids.

### 7.4 Performance had no acceptance criteria (supersedes nothing — a gap)

**Was:** no performance threshold was ever written down outside E11's S0 gate.

**Now:** NFR-10..13 adopt the E11 thresholds as the project's performance criteria.

**Why:** Stage 7a flagged that "performance acceptance" was undefined, which made it
unmeasurable and therefore unclaimable.

### 7.5 The long-sentence path was never exercised (supersedes E10 §corpus)

**Was:** the corpus's longest sentence was 239 characters against a 320-character
cap, so `splitLongSentence` never ran during any evaluation.

**Now:** the corpus contains `longsentence_01`, an ~800-character sentence, and two
tests assert that the cap is crossed and that the split actually happens.

**Why:** a cap that is never reached is decorative, and the WER/pause measurements
were silently blind to the path that handles the hardest input.

---

## 8. Traceability

| Requirement group | Test file | Count |
|---|---|---|
| Chunking, limits, speed, state | `SpeechChunkerTest`, `TextLimitsTest`, `SpeedStepsTest`, `PlaybackStateTest`, `ResumePointTest` | 47 |
| Library and read-along | `LibraryRulesTest`, `ReadAlongTest` | 24 |
| Narration planning | `StylePlannerTest`, `TextUnderstandingTest` | 41 |
| Privacy and offline | `PrivacyTest`, `RegressionTest.R11` | 22 |
| Reliability and export | `ReliabilityTest`, `ExportTest` | 30 |
| Voice studio | `VoiceStudioTest` | 17 |
| Regression suite | `RegressionTest` | 20 |
| Integration | `IntegrationTest` | 8 |

Totals: **112** in the app core (JVM-verifiable subset) and **169** in
`narration-core`. The two suites are disjoint: the app core tests the Android
application's platform-independent logic, `narration-core` tests the narration,
privacy, reliability and validation module.
