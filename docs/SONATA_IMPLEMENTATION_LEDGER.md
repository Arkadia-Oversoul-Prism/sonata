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

---

## 6. Post-merge evolution — PR #7 (2026-11-26)

**Append-only.** Sections 1–5 above are unchanged. This section records what
happened after PR #5 was merged, and where it contradicts an earlier entry it
says so rather than editing it.

### 6.1 What changed in the repository before this entry

| Fact | Value |
|---|---|
| PR #5 merged into the default branch | `49691de5b57dc10b4434bd9795b04dddb96935fe` |
| Default branch | `feature/sonata-tts-app` |
| PR #6 merged (device capability contract) | `53a60c05e8ffd5f42dba24518a8a1e724df5bdd4` on `integration/stage7b-prod-candidate` |
| Open PRs at the start of this entry | none |
| Open issues | #1 only |

**This contradicts §2's Stage 7b row**, which records `a102db4` as the candidate.
`a102db4` was the candidate *at the time*; the tree that actually shipped is
`49691de`. The earlier row is left as written.

### 6.2 Baseline established for this entry

| Field | Value |
|---|---|
| Base commit | `49691de5b57dc10b4434bd9795b04dddb96935fe` |
| Working tree | clean |
| Tests at baseline | **281** (112 app core + 169 narration core) |
| Tests failing at baseline | 0 |
| Android SDK in this environment | absent — Gradle build not runnable locally |

### 6.3 What this entry changed

Branch `feat/playback-retry-and-truthful-progress`, candidate
**`3f712ff34795c11dcdd3e73219110ea2ab36c245`**, PR #7 (draft).

| # | Change | Defect it closes | Negative control |
|---|---|---|---|
| 1 | `SynthesisResult.Failure` carries a `transient` flag; engine retries twice with exponential backoff | **There was no retry at all.** One 502 / dropped socket / empty 200 mid-passage dropped the rest of the read to the offline voice | Disabling the retry fails 3 tests |
| 2 | Notification text extracted to `NotificationText`; `SpeechService` refreshes it on a 2 s ticker | **The progress line was frozen.** It was computed once at read start and never rewritten, so a long read showed `Part 1 of 12 · 0%` throughout | Freezing the percentage fails 4 tests |
| 3 | `$` added to the currency list; `normaliseNumbers` added | **`$1,250` lost its unit entirely** — `$` was absent while €/£/¥ were present. Thousands separators, small ordinals and years also read badly | Covered by 12 new tests |
| 4 | `ci/scope.py` strips comments before scanning for references | A KDoc link like `[SpeechService]` counted as a code dependency, excluding pure-JVM files from the JVM job — `NotificationText` was excluded from the job that should test it | 112 previously-excluded tests now run |

### 6.4 Two bugs in my own first cut, caught by the tests written for it

Recorded because the pattern matters: the tests were written before the code was
trusted, and they found real errors in it.

1. The thousands-separator regex only matched the **last** group of a
   multi-group number, so `1,250` became `12 50`.
2. The year rule was bounded 1000–2099, so a **price** of `1250` was split into
   `12 50`. Narrowed to 1900–2099: a bare four-digit number in the wider range
   is far more often a quantity than a year.

### 6.5 Test and CI evidence at `3f712ff`

| Evidence | Result |
|---|---|
| Local harness `./ci/run-ci.sh` | 405 tests, both jobs exit 0 |
| Job 1 (app core) | 236 tests |
| Job 2 (narration core) | 169 tests |
| GitHub Actions — Sonata CI (`45730577830`) | **success** |
| GitHub Actions — Build Sonata APK, Build Release APK (`45730640054`) | **success** |
| GitHub Actions — Build Sonata APK, Instrumented tests API 34 (`45730577849`) | **success** |

The instrumented suite passing matters: it is the only check that exercises the
Android framework, and it is where the `INTERNET`-in-debug-overlay defect was
caught in the previous entry.

### 6.6 What this entry does NOT change

- **The neural tier is still absent.** No model binary, no JNI bridge, no
  `assets/`, no `jniLibs/`. `UnavailableNativeSession` is still what a build
  without the native library gets. **A10 remains the blocking item.**
- **No device validation.** Gates G1–G6 and S0-1..S0-4: still 0 of 6 executed.
  The retry backoff and the notification ticker are both timing-dependent and are
  proven only on the JVM scheduler and the emulator, not on hardware.
- **No signing.** No keystore, so no publishable APK/AAB.
- **No merge, release or deploy.** PR #7 is a draft.

### 6.7 Next deterministic task

The engine. Everything else in this repository is supporting work for a product
that cannot yet perform its one function. Concretely: bundle a model, implement
the JNI bridge behind `NativeSession`, and verify inference on a device. Until
that exists, no amount of P0/P1 polish changes the verdict.

---

## 7. Tracked bytecode cache removed, and the harness now judges the tree

### 7.1 Defect

`ci/__pycache__/scope.cpython-311.pyc` (7,011 bytes) was committed on this
branch. `ci/scope.py` is imported by the harness, so running it writes
`__pycache__`; the cache produced by that import was staged along with the
source. Nothing in the tree judged it, so it would have shipped with the
candidate.

This is the repository's own defect class, one level down from Stage 7a's B1
finding: a tracked derived artifact is an artifact no reviewer reads and every
checkout rewrites, and a stale `.pyc` can shadow the source it claims to be
compiled from.

### 7.2 Repair and guard

| Change | Purpose |
|---|---|
| `.gitignore` — `__pycache__/`, `*.pyc`, `*.pyo` | the cache can no longer be staged by habit |
| `git rm --cached ci/__pycache__/scope.cpython-311.pyc` | the committed artifact leaves the tree |
| `ci/hygiene.py` (new, stdlib only) | fails when any **tracked** path is a bytecode cache; skips with a note outside a git checkout, so an exported tarball is not misjudged |
| `ci/run-ci.sh` preflight | runs the guard before job 1, so a red run is red for the tree and not only for the sources it compiles |

### 7.3 Evidence (this sandbox: `python3` only, no JVM)

| Check | Result |
|---|---|
| `python3 ci/hygiene.py` on the pre-repair tree | exit **1**, names `ci/__pycache__/scope.cpython-311.pyc` |
| same, after untracking | exit **0**, `no tracked bytecode cache` |
| negative control — `git add -f ci/__pycache__/probe.cpython-311.pyc` | exit **1**, names the probe |
| negative control through the harness | `SUMMARY preflight=FAIL`, harness exit **1** |
| harness after removing the probe | preflight passes, proceeds to job 1 |

The pre-repair run is a *live* control on the real file, not a fixture: the
guard was executed against the tree that carried the defect before it was
repaired.

### 7.4 Not claimed

- **Job 1 reports `COMPILE FAILED` in this sandbox** because `kotlinc` and `java`
  are absent here. That is the toolchain absence `ci/run-ci.sh` already documents
  (`android_sdk=ABSENT`), not a source defect. The authoritative JVM and APK
  evidence remains GitHub Actions.
- No source file, test assertion, or neural-tier claim changed. **A10 (the
  engine) remains the blocking item** and this entry does not touch it.

---

## 8. S1 — shouted headings, and a harness that ran here (append-only)

**Date:** 2026-11-26
**Base:** `49691de5b57dc10b4434bd9795b04dddb96935fe` (branch `feat/playback-retry-and-truthful-progress`, PR #7)

### 8.1 The defect

`TextNormalizer` had no case rule. A heading set in capitals reached the engine as
capitals, and an engine that treats an all-caps token as an initialism reads it
letter by letter — `INTRODUCTION` is heard as "I N T R O D U C T I O N". This is
the *"uppercase headings spoken unnaturally"* item in the S1 contract, and it was
the only cleaning rule in the S1 list with no implementation.

### 8.2 The rule

`normaliseCase` runs on each line after markup removal and before the symbol rules.
It rewrites a line only when the line is **entirely capitals** and is **not made
only of known acronyms**; the rewrite lowercases the line, capitalises its first
word, and restores any known acronym (`THE NASA STORY` -> `The NASA story`).
Prose, inline capitals, and acronym-only lines are untouched. The acronym list is
explicit because a heuristic that guesses "this looks like an acronym" turns `US`
into `us` and changes what a sentence means.

### 8.3 Evidence (run in this sandbox)

A JDK and the Kotlin compiler were provisioned here (Temurin `17.0.21.1`,
kotlinc `1.9.24`, jars from `repo.maven.apache.org` and Google Maven), so the
tracked harness ran locally rather than only in GitHub Actions. This supersedes the
"this sandbox: `python3` only, no JVM" note in §7.3 **for this entry**; §7.3 stays
as written.

| Check | Command | Result |
|---|---|---|
| Harness on PR #7 head before the change | `bash ci/run-ci.sh --check` | `total_tests=405`, job1/job2 exit 0 |
| Harness with the rule | `bash ci/run-ci.sh --check` | `total_tests=418`, job1/job2 exit 0 |
| **Negative control** — `normaliseCase` call removed | `bash ci/run-ci.sh` | `Tests run: 249, Failures: 5` |
| Rule restored, harness re-run | `bash ci/run-ci.sh --check` | `total_tests=418`, 0 failures |

The 418 - 405 = 13 delta is exactly the new `TextNormalizerCaseTest` nodes
(`ci/scope.py` on this branch includes the file; it is not in the excluded set).

### 8.4 Not claimed

- **Not device-validated.** Whether a particular vendor engine spelled the heading
  is a device observation that was not made here; the rule is verified as a text
  transformation only.
- **Inline capitals are deliberately left alone.** Emphasis inside a sentence is
  read with its surrounding words, and rewriting it would change authored copy
  without addressing the heading defect.
- No engine, model, playback, or UI change. `EngineFactory` is still not called
  from the UI, which remains the next slice.
