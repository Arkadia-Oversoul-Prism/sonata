# SONATA_IMPLEMENTATION_LEDGER

**Status:** Stage 7b production candidate
**Candidate commit:** `a102db4f01b04f95214216c3118545d4030cbb02`
**Date:** 2026-11-20

The ledger records what was built, when, at which commit, and what evidence exists
for it. It is append-only: entries are never rewritten. Where a later stage
contradicts an earlier one, the later entry says so and the earlier entry stays.

---

## 1. Historical evidence — E5 to E11 (preserved, unmodified)

These documents remain in `docs/evolution/` exactly as written. They are the record
of what was believed at the time. **They are not corrected in place**, because a
record that is silently edited is not a record.

| Doc | Title | What it established | Still true? |
|---|---|---|---|
| **E5** | The Arkadia voice contract, as measured | The voice contract and its measured properties | Partly — the contract stands; the measurement was of a hosted voice, not the shipped app |
| **E6** | Canonical source, and a correction to the record | Which repository is canonical; a correction to an earlier claim | Yes |
| **E7** | Offline Sonata: specification reconciliation and implementation plan | The offline plan and its phases | Yes, as a plan |
| **E8** | (phase evidence) | Offline phases 1–2 | Yes |
| **E9** | (phase evidence) | Offline phases 3–5 | Yes |
| **E10** | Offline Sonata: Phases 1–7 Implementation | The implementation of phases 1–7 | **Partly superseded** — see §3 |
| **E11** | S0 Device Validation Handoff | The device gates and the S0 exit criteria | Yes, and **still unexecuted** |

### Why E5–E11 are preserved rather than updated

The mandate requires preserving them as historical evidence. The practical reason
matches: E10 states that the implementation is "verified on the JVM and unverified
on a device". That sentence was true when written and is still true. Editing it to
match later findings would destroy the only record of what the team believed at the
point the decision was made.

---

## 2. Stage ledger

### Stage 0 — Baseline

| Field | Value |
|---|---|
| Commit | `235346bee72b9162935ef74e05c84caa91c7bd13` |
| Branch | `feature/sonata-tts-app` |
| Evidence | `docs/evolution/E0-BASELINE.md` |
| State | Android app skeleton; online TTS via OkHttp; `INTERNET` declared |

### Stage 1 — Plan

| Field | Value |
|---|---|
| Evidence | `docs/evolution/E1-PLAN.md` |
| State | Plan only, no code |

### Stage 2 — Slices 01–02

| Field | Value |
|---|---|
| Evidence | `docs/evolution/E2-SLICE-01-EVIDENCE.md`, `E2-SLICE-02-EVIDENCE.md` |
| State | Reading pipeline, chunking, playback state |
| **Note** | **These commits were never pushed to the remote.** See §4.1 |

### Stage 3 — Audit 01

| Field | Value |
|---|---|
| Evidence | `docs/evolution/E3-AUDIT-01.md` |
| State | Audit of the reading path; the "Stage 3 anchor fix" referenced by R11 |
| **Note** | **Never pushed to the remote.** See §4.1 |

### Stage 4 — Device protocol

| Field | Value |
|---|---|
| Evidence | `docs/evolution/E4-DEVICE-PROTOCOL.md` |
| State | Protocol for device measurement; no measurements taken |

### Stages 4–6 — Narration, privacy, validation

| Field | Value |
|---|---|
| Delivered as | `sonata-stage4-6-src.tar.gz` (standalone harness) |
| Tests | 166 passing |
| **Problem** | The harness's own report states it is "a new, self-contained implementation of the Stage 4–6 logic… not a patch to the Stage 3 tree" |
| **Consequence** | Stage 7a could not review it as part of the product. This is blocker B1 |

### Stage 7a — Independent review

| Field | Value |
|---|---|
| Verdict | **NOT READY** for the production-review package |
| Blockers | B1 integration commit never published; B2 placeholder runtime; B3 no README classification; B4 privacy gate tested against a fixture |
| Also found | R3 does not test what its name claims; connectivity probe is a tautology; long-sentence path never exercised; no performance criteria; offline sentinel is self-referential |
| Report | `webpages/sonata-stage7a-review/index.html` |

### Stage 7b — Production candidate (this stage)

| Field | Value |
|---|---|
| Branch | `integration/stage7b-prod-candidate` |
| Merge commit | `07c68fe` — offline branch merged into S0 baseline |
| Candidate commit | `a102db4f01b04f95214216c3118545d4030cbb02` |
| Files in tree | 184 tracked |
| App sources | 88 Kotlin files |
| Narration-core sources | 21 Kotlin files |
| Tests executed | 112 (app core) + 169 (narration core) = **281** |
| Tests failing | 0 |

### Stage L1 — Offline speech engine (2026-11-26)

| Field | Value |
|---|---|
| Branch | `feat/platform-speech-engine-001` |
| PR | #8 (draft, not merged) |
| Commit | `e1042065404e2a237aca127650875227638d22bf` |
| Base | `feature/sonata-tts-app` @ `49691de` |
| Tests executed | 299 (was 281; **18 new**) |
| Tests failing | 0 |
| Negative control | Fixed 44-byte WAV offset instead of a chunk walk → **suite fails** |
| CI — Sonata CI (JVM) | ✅ success, run `45787331162` |
| CI — Build Release APK (unit, lint, assembleRelease, bundleRelease) | ✅ success, run `45787331093` |
| CI — Instrumented tests (API 34 emulator) | run `45787339148` |

**What was actually broken.** `SpeechModel` was an interface whose only production
implementation was `UnavailableNativeSession`. `OfflineEngine.resolveModel`
therefore returned null and every narration failed with *"no speech model is
installed"*. Separately, `OfflineEngine` was constructed **only in tests** — the
app's playback path (`TtsEngine` → `AndroidOfflineVoice`) streams to the speaker
and saves nothing. Two independent gaps, either of which alone made a saved
narration impossible.

**What was built.**

| File | Role |
|---|---|
| `export/WavReader.kt` | Parses a WAV by walking its chunk list |
| `engine/PlatformSpeechModel.kt` | Pure-JVM adapter: WAV→PCM, failure mapping, version attribution |
| `AndroidPlatformSynthesis.kt` | The `synthesizeToFile` binding, with a bounded wait |
| `EngineFactory.kt` | Constructs the engine in production |
| `engine/OfflineEngine.kt` | Fallback tier; honest failure messages |

**Engine and model selected.** The Android platform `TextToSpeech` engine via
`synthesizeToFile`. **No model is bundled** — this is the fallback tier, and it
needs no model file. Licence: the platform engine is part of Android (Apache-2.0 /
device vendor). No third-party model licence applies because no third-party model
is present. Bundling Kokoro or Piper is a licensing and provenance decision for the
repository owner and was deliberately not taken here.

**Why `synthesizeToFile` and not `speak`.** `speak` streams to the speaker and
produces nothing that can be saved, exported or replayed. `synthesizeToFile` is the
only platform API that renders to a file, which is what the library path needs.
`AndroidOfflineVoice` already used `speak` for playback; both are needed and they
are different APIs.

**Why the WAV is parsed rather than assumed.** The platform engine may emit
`LIST`/`fact` chunks before `data`. A fixed 44-byte offset would read metadata as
audio — a click at the start of every narration. The negative control above is
exactly that bug, and it fails the suite.

**Why the sample rate comes from the file.** The engine picks its own rate. A
descriptor that disagreed with the audio would make every duration wrong, and
duration is what the library displays.

**What is NOT verified.** That a real device's engine produces audio. The adapter
is tested on the JVM against a double, which proves the wiring and the WAV handling,
not the platform call. Recorded as **UNVERIFIED**, not claimed.

**Four defects the JVM harness could not see.** All four were found only by running
the real Gradle pipeline on GitHub Actions, and all four would have shipped:

1. `EngineFactory` did not compile — `SonataRepository` passed where `SonataStore`
   was expected. The file imports `android.content.Context`, so `ci/scope.py`
   excludes it and never compiled it.
2. `PlatformFallbackEngineTest` did not compile — `failureReason` does not exist;
   the field is `errorReason`. The test references `OfflineEngine`, which is
   excluded, so the test was excluded too.
3. The JVM job compiled against **96-byte files**. `repo1.maven.org` rate-limits
   shared runner IPs and answers with a short error page under HTTP 200, which
   `curl -sSLO` writes to disk as if it were a jar. kotlinc then failed with
   unresolved references and the harness reported `COMPILE FAILED` — a code defect
   that did not exist. Fixed in two places: the workflow fetches from
   `repo.maven.apache.org` and validates every jar (non-empty, above a per-jar
   minimum, starting with `PK`), and `ci/run-ci.sh` preflights the classpath and
   exits 2 with `RESULT=TOOLCHAIN_BROKEN`.
4. A jar was saved under the wrong name — `annotation-jvm-1.7.1.jar` on disk while
   `CP_JARS` looks for `annotation-1.7.1.jar`. The new preflight reported
   `MISSING JAR` instead of letting kotlinc fail obscurely.

**The lesson, recorded because it is the point.** An excluded file is an unverified
file. The JVM harness verifies 299 tests but cannot see what it excludes, and two
compile errors lived in exactly that blind spot. The Gradle job is the only thing
that compiles the whole tree, which is why it must stay non-optional.

**Still missing.** No neural model (the quality tier); no cloned voice (the platform
engine has no adapter stage, so a cloned request is refused rather than downgraded);
`EngineFactory` is not yet called from the UI, so no screen reaches `narrate()`.

---

## 3. What Stage 7b changed, and why

Every change below traces to a Stage 7a finding. Nothing was changed for taste.

| # | Change | Finding it closes |
|---|---|---|
| 1 | Offline branch merged into S0 baseline; combined tree published | B1 |
| 2 | Stage 4–6 module added as `narration-core/`, a real module the app depends on | B1 |
| 3 | Neural tier reclassified **unsupported**; no JNI shim exists | B2 |
| 4 | README five-way claim classification added | B3 |
| 5 | `R11` reads the real manifest and fails if absent | B4 |
| 6 | `INTERNET` removed from the manifest | B4 consequence |
| 7 | `allowBackup=false`, backup exclusions added, cleartext disabled | B4 consequence |
| 8 | `RECORD_AUDIO` rule made conditional on capability | B4 consequence |
| 9 | `R3` observes engine invocations via a recording engine | Test integrity |
| 10 | Connectivity-probe tautology replaced with a timeout assertion | Test integrity |
| 11 | Corpus gains an ~800-char sentence; two tests assert the split | Long-sentence gap |
| 12 | Performance criteria NFR-10..13 adopted from E11 | No performance criteria |
| 13 | CI workflow bound to the commit SHA | A2 |
| 14 | JVM harness with declared, printed scope | Reproducibility |
| 15 | Harness **tracked** under `ci/` instead of living in gitignored `build/` | Reproducibility — see below |

### The most important change

Item 5. When `R11` was finally pointed at the real manifest it **failed**, with
`NO_INTERNET`, `RECORD_AUDIO_PRESENT`, `BACKUP_DISABLED` and `BACKUP_EXCLUSIONS`.
The product had been violating its own privacy policy while the test reported
success. That is the concrete cost of a fixture-based gate, and it is why the
finding was a blocker rather than a nit.

### The second most important change

Item 15. The first Stage 7b cut cited `ci-evidence.log` as proof of its test
results, but the script that generated the log lived in `build/`, which
`.gitignore` excludes (`/build`, `*/build/`). **A reviewer who checked out the
candidate SHA could not regenerate the evidence.** This is Stage 7a's B1
defect — "the reviewed artifact is not the artifact that ships" — one level
down, and it was caught during the push for this stage rather than by a test.

The harness now lives in `ci/run-ci.sh` with `ci/scope.py`, both tracked and
executable (`100755`). `ci-evidence.log` is regenerable by running
`./ci/run-ci.sh` at the commit it names.

---

## 4. Repository state facts

### 4.1 Commits that exist only locally

The following commits were referenced by earlier stages but **do not exist on the
remote**:

- `c1c3b03` — Stage 2 slice 01
- `85e15aa` — Stage 2 slice 02
- `916d5c2` — Stage 3 audit
- `7fef1d2` — Stage 4 device protocol

Verified with `git cat-file -t` against a fresh clone: all four are `ABSENT`.

**Consequence.** The remote's `feature/sonata-tts-app` at `235346b` is the
**pre-integration baseline**. The Stage 2/3 work exists only in the Stage 4–6
tarball and in this candidate's merge. This is why the merged tree still contained
`TtsTransport.kt` and `INTERNET`: the commits that were supposed to remove them were
never published.

This is recorded as a fact, not a defect to be hidden. It is the root cause of B1.

### 4.2 Branches on the remote

| Branch | Head | Meaning |
|---|---|---|
| `feature/sonata-tts-app` | `235346b` | S0 baseline (default) |
| `feat/sonata-offline-phases-1-7` | `519552e` | Offline phases 1–7, 13 commits, 61 files, +9401/−5 |

### 4.3 What is not in the tree

- No `gradlew`-runnable Android build in this environment (no SDK)
- No model binaries
- No JNI implementation
- No signed APK or AAB

---

## 5. Evidence index

| Evidence | Location | Bound to |
|---|---|---|
| CI harness | `ci/run-ci.sh`, `ci/scope.py` | `a102db4` |
| CI run log | `ci-evidence.log` | `a102db4` (line 2) |
| App-core test result | `ci-evidence.log`, job 1 section | `a102db4` |
| Narration-core test result | `ci-evidence.log`, job 2 section | `a102db4` |
| Stage 7a review | `webpages/sonata-stage7a-review/index.html` | pre-merge tree |
| Historical record | `docs/evolution/E0..E11` | as written |
| Reproducible build info | `docs/SONATA_PRODUCTION_HANDOFF.md` §4 | `a102db4` |
