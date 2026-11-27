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

---

## 9. S4a — the screen reaches the offline engine (append-only)

**Date:** 2026-11-27
**Base:** `7c4e6a64e39e34685b06e2e1698cf9eb896b9720` (default branch `feature/sonata-tts-app`)
**Branch:** `feat/save-narration-reachable-01`

### 9.1 The defect

§8.4 ended with the next slice named: *"`EngineFactory` is still not called from
the UI."* It was not a note about polish. `grep -rn EngineFactory app/src`
returned exactly one hit — the definition — so the production engine that saves
a narration was constructible and tested but **unreachable**. No user action
could produce a stored file. `SONATA_KNOWN_LIMITATIONS.md` L1 item 3 said the
same thing, and §7.3 had already named the class: *"A correct component that
nothing constructs is not a feature."*

### 9.2 The change

| File | Role |
|---|---|
| `NarrationPresenter.kt` (new, JVM) | Turns a `NarrationOutcome` into the one line the user reads, and the `saved` flag the library may build on |
| `NarrationPresenterTest.kt` (new, JVM) | 4 tests, including a negative control for a Success that names no file |
| `MainActivity.kt` | `saveNarration()`: builds the engine lazily, narrates off the main thread, reports through the presenter; `onDestroy` cancels the scope and shuts the engine down |
| `activity_main.xml` | One `Save narration` control and its state line, in the existing Storage section |
| `strings.xml` | Two strings |
| `RegressionTest.kt` | **R14** — a source guard that fails if the wiring is removed |

The save action reuses `TextLimits.validate` for the blank/oversized contract and
`DocumentTitle.fromText` for the title, so the screen adds no new text rules. The
engine is built on first use, not at startup, and released in `onDestroy`.

**Why the `saved` claim lives in the presenter, not the screen.** The engine only
returns `Success` after the WAV is written, but the report does not take that on
trust: a `Success` whose `audioPath` is blank is reported as a failure. "Saved" is
the claim that a file exists — the same predicate `LibraryRules.isPlayable`
applies to a row before it is offered for playback. Putting it in a pure function
is what makes it testable without a device.

### 9.3 Evidence (this sandbox: JDK 21 + kotlinc 1.9.24 provisioned here)

The tracked harness ran locally, not only in GitHub Actions.

| Check | Command | Result |
|---|---|---|
| Base, before any change | `bash ci/run-ci.sh --check` | `total_tests=441`, job1/job2 exit 0 |
| With the presenter + tests | `bash ci/run-ci.sh --check` | `total_tests=445`, job1/job2 exit 0 |
| With the R14 guard | `bash ci/run-ci.sh --check` | **`total_tests=446`**, job1/job2 exit 0 |

`445 - 441 = 4` is exactly the new `NarrationPresenterTest` nodes; `446 - 445 = 1`
is exactly `R14`. Neither is inferred from the delta — `ci/scope.py` classifies
both new files (`MAIN` / `TEST`).

**Negative controls, both live on the real files and both restored byte-identically.**

| Control | Edit | Result |
|---|---|---|
| The saved-claim guard is load-bearing | `if (outcome.audioPath.isBlank())` → `if (false)` | job 1 **exit 1**, and `JUnitCore` on the class names `1) a_success_with_no_path_is_not_reported_as_saved` |
| The wiring guard is load-bearing | `wired.engine.narrate(` → `wired.engine.speak(` | job 2 **exit 1**, `1) R14 the screen reaches the offline engine` at `RegressionTest.kt:300` |

Restored: `diff` against the backup was empty, `grep -c "wired.engine.narrate("`
returned 1, and the harness returned to `total_tests=446`.

### 9.4 Not claimed

- **The Android sources were not compiled here.** There is no Android SDK in this
  sandbox, so `MainActivity.kt`, `EngineFactory.kt` and the layout are `OUT` of the
  JVM scope. The compile and APK evidence is GitHub Actions
  (`ci.yml`, `build-apk.yml`), not this entry.
- **No device validation.** That a real device's `TextToSpeech` engine writes the
  file is **UNVERIFIED** here; the adapter is tested on the JVM against a double.
- **No catalogue UI.** A saved narration is written and recorded, but no screen
  lists the library yet, so "Save → Catalogue → Replay" is only half reached. This
  entry does not claim the library is browsable.
- **No neural voice.** The registry is still empty; this speaks in the platform
  voice, which is the honest current tier.

### 9.5 CI, observed on `29cb245a` (PR #10)

| Check | Run | Result |
|---|---|---|
| JVM verification (app core + narration core) | `45965117961` | **success** |
| Build Release APK + instrumented tests | `45965306042` (`pull_request`) | **success** |
| Build Release APK (first, `push`) | `45965117945` | failure — not attributable |

The first APK run failed in `Run unit tests` with `397 tests completed, 29 failed`,
and **all 29** carry `java.lang.AssertionError at MavenArtifactFetcher.java:129` →
`IOException at HttpURLConnection.java:2059`: Robolectric could not download its
`android-all` jar. The 29 are 23 `SonataRepositoryTest` + 6
`FloatingOverlayServiceTest`; `NarrationPresenterTest` is not among them, and the
base branch passed the same workflow twice earlier the same day. It is runner
infrastructure, not this branch.

**That red still settled the compile question.** In the same run
`> Task :app:compileDebugKotlin` and `> Task :app:compileDebugUnitTestKotlin`
both succeeded, and the retry passed the whole job including instrumented tests.
So `MainActivity.kt`, the layout and the new test compile — which the local JVM
harness cannot show, because the Android sources are `OUT` of its scope.

Recorded as **both a red and a green at one SHA**: `gh pr checks` reports only
the latest run, so reading it alone would have hidden the transient failure (and
reading only the red would have wrongly blamed the branch).

### 9.6 Composition with the open PR #9

Measured in a detached worktree by `git merge --no-edit origin/fix/s1-tier-truth-001`:

- `MainActivity.kt`, `activity_main.xml`, `strings.xml`, `RegressionTest.kt`
  **auto-merged**; no conflicted source file.
- Only the two append-only logs conflicted (`ci-evidence.log`,
  `docs/SONATA_IMPLEMENTATION_LEDGER.md`). Resolved by keeping #9's
  `### Stage S1a` under section 8, then this branch's `## 9.` after it.
- Composed tree: **`total_tests=452`, job1/job2 exit 0** —
  `441 + 6 (#9) + 5 (this branch) = 452`, zero failures.

Merge order is the human's. Whichever lands second re-runs the harness and
re-appends both logs; the source itself composes cleanly.

### 9.7 The push-run/PR-run split reproduced (head `921cf8e`)

The pattern in §9.5 is not a one-off. At the docs-only head `921cf8e`:

| Run | Event | Build Release APK | Instrumented tests |
|---|---|---|---|
| `45965932325` | `pull_request` | **success** | **success** |
| `45965940901` | `push` | **failure** | success |

The failing job carries **29** `MavenArtifactFetcher` occurrences again — the same
count, the same `SonataRepositoryTest` / `FloatingOverlayServiceTest` failures,
the same `compileDebugKotlin` success. So on this repository the `push`-event
build is red on a Robolectric artifact download while the `pull_request`-event
build of the identical tree is green, twice over.

**Consequence for reading CI here:** the `pull_request` run is the one that gates
the PR, and it is green. A red `push` run at the same SHA is not evidence about
the tree. Do not "fix" it in a feature branch — it is runner infrastructure, and
the fix (a pre-warmed Robolectric cache or a pinned `android-all` jar) is a
separate bounded change.

---

## 10. S4b — the library could not be read, so a saved narration could not be replayed (append-only)

### 10.1 The defect

S4a made `EngineFactory` reachable, so a user could save a narration. Nothing
listed what had been saved:

```
$ grep -rn "all()\|recent(\|narrations()" app/src/main/kotlin/com/arkadia/sonata/MainActivity.kt
(no match)
```

The `NarrationDao` had `all(): Flow<List<Narration>>` and the repository had no
read path on the `SonataStore` seam at all, so the screen could write audio and
never show it. "Generate → Save → Catalogue → Replay" stopped at the second step,
and `SONATA_KNOWN_LIMITATIONS.md` L1 item 3 said so.

### 10.2 The change

| File | Role |
|---|---|
| `LibraryPresenter.kt` (new, JVM) | Rows and their offer, from stored narrations |
| `LibraryPresenterTest.kt` (new, JVM) | 10 tests, including the two negative controls below |
| `SonataStore.kt` | `allNarrations()` — the read path |
| `SonataRepository.kt`, `RepositoryStore.kt` | The implementation and the seam |
| `FakeSonataStore.kt` | The same read path for the JVM tests |
| `EngineFactory.kt` | `store(context)`, and `Wired.store` so the screen reads the store the engine wrote to |
| `MainActivity.kt` | Renders the rows, replays a file, stops the passage read first |
| `activity_main.xml`, `item_library_row.xml`, `strings.xml` | The list and one row |
| `RegressionTest.kt` | **R15** — a source guard on the read, the presenter and the replay |

The rule the presenter exists to hold: **a row is offered for replay only when
`LibraryRules.isPlayable` holds AND the file is on disk.** A `COMPLETE` row is not
proof the audio survived — storage can be cleared, or a write interrupted after
the row was updated. `audioExists` is injected so the rule is a pure function and
the tests can disagree with the disk on purpose.

The screen does not decide any of this. It draws what the presenter says, so the
only rule that matters is in a function a test can reach, not in a view callback.

Two smaller decisions, both recorded because they are choices:

- **The Play control is removed, not disabled, on a row that cannot play.** A
  greyed button beside a row the app cannot play still reads as an offer.
- **A file that failed to decode is dropped from the offers for the session.** A
  file that failed once will fail again; the set is not persisted, because the
  cause may have been transient.

### 10.3 Evidence (this sandbox: JDK 21 + kotlinc 1.9.24 provisioned here)

Base `84aa83b` (PR #10's head) → head `1178132`.

| Check | Command | Base | Head |
|---|---|---|---|
| Tracked harness | `./ci/run-ci.sh --check` | `total_tests=446`, PASS | **`total_tests=457`, PASS** |
| Evidence log | `./ci/run-ci.sh` at `1178132` | — | `dirty_files=0`, `job1_exit=0`, `job2_exit=0` |
| Architecture / scope | `python3 ci/scope.py` | — | `LibraryPresenter.kt` MAIN, `LibraryPresenterTest.kt` TEST |

`457 - 446 = 11` is exactly the new nodes: `10` in `LibraryPresenterTest` (job 1)
and `1` for `R15` (job 2). Measured by running job 1 alone (`Tests run: 286`, of
which `LibraryPresenterTest` contributes 10) rather than inferred from the delta.

### 10.4 Negative controls (live on the real files, both restored byte-identically)

| Control | Edit | Result |
|---|---|---|
| The file check is load-bearing | drop `&& audioExists(...)` from `LibraryPresenter.rows` | job 1 **exit 1** — `3` failures: *a complete narration whose file is gone is not offered*, *a playable row carries its duration*, *the summary counts what can be played* |
| The wiring guard is load-bearing | `libraryPlayer.play(` → `libraryPlayer.pause(` | job 2 **exit 1**, `R15 the screen lists saved narrations and replays them` |

Restored: `sha256` of `LibraryPresenter.kt` `812dc43c…` and `MainActivity.kt`
`eff5ac4b…` identical before and after, harness back to `total_tests=457`.

The first control is the one that matters: without the file check the tests fail
*by name* on the property the presenter exists for, not on a count.

### 10.5 Not claimed

- **The Android sources are `OUT` of the JVM harness** (this sandbox has no
  Android SDK). `MainActivity.kt`, the two layouts and `strings.xml` are compiled
  by CI (`build-apk.yml`: `compileDebugKotlin`, `compileDebugUnitTestKotlin`,
  release APK), not here. `LibraryPresenter`, the store seam and the fake are
  compiled and run here.
- **No device validation.** That a device plays a saved file through
  `AndroidAudioPlayer` is **UNVERIFIED**; the player's behaviour is tested on the
  JVM against a double, and the file is played by `MediaPlayer` on a device.
- **No management actions.** Rename, delete, favourite, collections and export
  have no UI. The rules and the store operations exist and are tested; the screen
  exposes none of them.
- **No resume from a stored position.** `Narration.playbackPositionMs` is not yet
  consulted by the library row.
