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

### Stage S1a — Which tier actually runs (2026-11-27)

| Field | Value |
|---|---|
| Branch | `fix/s1-tier-truth-001` |
| PR | #9 (draft) |
| Base | `feature/sonata-tts-app` @ `7c4e6a64e39e34685b06e2e1698cf9eb896b9720` |
| Tests executed — tracked harness (`ci/run-ci.sh`) | 447 (was 441; **6 new**) |
| Tests executed — engine suite (`TtsEngine*Test`) | 70 (was 65; **5 new**) |
| Tests failing | 0 in both |
| Negative control — policy gate removed | `SynthesisTierPolicyTest` **2 failed / 6** |
| Negative control — both call sites reverted | `TtsEngineOnlineTest` **4 failed / 46** |
| CI | not run at the time of writing; this entry is updated only from a real run |

**What was broken.** The engine chose its tier with `preferOnline && url.isNotEmpty()`.
Both inputs were true in the shipped configuration — `Prefs.preferEdgeTts` defaults
to `true` and `BackendConfig.DEFAULT_URL` is a live address — so **every read took
the online tier first**, in a build whose manifest deliberately declares no
`INTERNET` permission. The consequence was not a theory:

- the first audio of every read waited for three failed attempts and 1.2 s of
  exponential backoff (`DEFAULT_MAX_RETRIES = 2`, `DEFAULT_RETRY_BASE_DELAY_MS = 400`,
  both added in PR #7, which made this worse rather than better);
- the user saw an `UnknownHostException`, which reads as a network fault and is not;
- the screen reported "Fetching the next part of the passage…" for a fetch that
  could never succeed;
- the More row said "Voice: Arkadia neural (…)" while the user was hearing the
  device's engine.

The three facts — what the user asked for, what address is configured, and whether
the build can open a socket — were collapsed into two, and the missing one was the
only one that was not a request.

**What was built.**

| File | Role |
|---|---|
| `SynthesisTierPolicy.kt` | The decision, pure JVM: preference + gateway + capability → tier |
| `TtsSettings.kt` | New `networkReachable`, documented as a capability rather than a preference |
| `Prefs.kt` | Derives it from the manifest (`checkSelfPermission(INTERNET)`), never hardcoded |
| `TtsEngine.kt` | `start` and `preview` both route through the policy |
| `MainActivity.kt` | The More-row label reports the effective tier, not the stored preference |
| `SynthesisTierPolicyTest.kt` | 6 tests, in the tracked JVM scope |

**Why the capability is read from the manifest and not written as `false`.** A
hardcoded `false` would be correct today and silently wrong the day the permission
returns. Asking the manifest means the gate flips by itself, and regression R11 —
which already fails the build if `INTERNET` comes back — keeps the two from
disagreeing.

**Why the offline tier reports no error.** A build that cannot reach the network is
not degrading from a tier it could have used; the device voice *is* its tier.
Reporting a failure there would be the same untruth in a different place.

**Verification, and its boundary.** The decision is executed by the tracked harness
(job 1). Its two call sites are not: `TtsEngine.kt` is `FORCED_OUT` in `ci/scope.py`,
and the Activity needs the Android runtime. The engine's behaviour was therefore
measured against a scratch harness that stubs `android.util.Log` and
`AndroidOfflineVoice` — **70 tests, 0 failures**, with the base tree at 65 and the
same 0 failures, and reverting both call sites reddening exactly the four tests that
assert the gate. That harness is not tracked, so it is a measurement and not CI
evidence; `TtsEngineOnlineTest` runs for real under `./gradlew testDebugUnitTest` in
`build-apk.yml`. Recorded in `SONATA_KNOWN_LIMITATIONS.md` §2 L5a rather than implied
to be stronger than it is.

**Not claimed.** No device validation. No neural voice. No UI change beyond the label.
`EngineFactory` is still not called from the UI, so a *saved* narration is still
unreachable by a user tapping a button — §8.4's next slice is unchanged by this one.

---

### Stage S1b — The progressive-read gate counted requests, not pieces (2026-11-27)

| Field | Value |
|---|---|
| Branch | `fix/s1-tier-truth-001` |
| PR | #9 (draft) |
| Base | `feature/sonata-tts-app` @ `7c4e6a64e39e34685b06e2e1698cf9eb896b9720` |
| Parent | `b66932fdd4d5b0728996b811c0fb1edd71903fab` |
| Candidate | `ba01087e9068a301dd4906a8621a7ce9565835ed` |
| Changed | 1 file, `app/src/androidTest/.../DeviceProgressiveReadProtocolTest.kt` (+50 / −6) |
| Instrumented suite | 25 tests, `BUILD SUCCESSFUL` on both runs at the candidate |

#### The defect

`p3_8_the_whole_passage_is_read_not_just_the_opening_piece` ended with

```kotlin
assertEquals("every piece must be requested exactly once ...", pieces.size, requests.size)
```

An exact one-to-one between HTTP requests and the passage's pieces. That equality
does not hold by design, and it failed on the branch's own head:

| Run | Event | Instrumented tests | Result |
|---|---|---|---|
| `45926912090` | `push`, `b66932fd` | `failure` | `p3_8 ... expected:<13> but was:<14>` |
| `45926917172` | `pull_request`, `b66932fd` | `success` | 25 tests, no failure |

Same commit, same workflow, opposite outcome. A gate that reports two verdicts for
one tree cannot be cited as evidence for either.

#### Why a 14th request is legitimate

`TtsEngine.fetchSegment` retries a piece whose synthesis failed *transiently*, up to
`DEFAULT_MAX_RETRIES` (2), with a 400 ms then 800 ms backoff.
`OkHttpTtsTransport` classifies a transport exception, a 5xx, a 429 and an empty
`200` as transient, and OkHttp retries a broken connection itself. A dropped socket
under emulator load therefore produces a second request for a piece that was already
requested — the engine recovering, not the engine misbehaving.

Three other explanations were ruled out against the source rather than assumed away:

- **Not a concurrent double-fetch.** `fetchSegment` re-checks and registers the
  in-flight index inside `synchronized(fetchLock)`, so two callers cannot both start
  the same piece.
- **Not a re-fetch of a played piece.** `schedulePrefetch` walks only forward from
  `playIndex + 1`, and `playIndex` advances monotonically.
- **Not a disagreement about the pieces.** The engine plans its segments with the
  same `SpeechChunker.splitForStreaming` the test calls, on the same text (the
  fixture has no leading or trailing whitespace for `TextLimits` to trim), so both
  sides see 13 pieces — which is the number the failure message reported.

That leaves the retry loop as the only source of a 14th request.

#### The repair

The assertion now states what the read must guarantee, and it counts pieces:

| Assertion | Catches |
|---|---|
| every piece requested at least once | stopping early, which drops the rest of the passage |
| no request body carries text outside the passage's pieces | a request the plan never contained |
| no piece requested more than `1 + TtsEngine.DEFAULT_MAX_RETRIES` times | a request loop, as opposed to a recovery |

A request is matched to a piece by the **longest** piece its body contains, because
a body carries exactly one piece and the pieces are contiguous slices of the passage.
Longest-match rather than first-match is what stops a short piece whose text happens
to recur elsewhere from claiming another piece's request.

#### Evidence

| Check | Where | Result |
|---|---|---|
| Assertion logic, positive: each piece once | Python replica of the fixture | pass |
| Assertion logic, positive: one piece retried (14 requests for 13 pieces) | Python replica | pass |
| Negative control: last three pieces dropped | Python replica | fails on *missing* |
| Negative control: one out-of-plan request | Python replica | fails on *unknown* |
| Negative control: one piece requested 4× | Python replica | fails on *over budget* |
| Compile of the instrumented sources | CI `compileDebugAndroidTestKotlin` | pass |
| Instrumented suite | CI run `45938561004` (push) | 25 tests, `BUILD SUCCESSFUL` |
| Instrumented suite | CI run `45938565202` (`pull_request`) | 25 tests, `BUILD SUCCESSFUL` |

#### Not claimed

- **The Kotlin was not compiled in the sandbox that wrote it.** There is no JDK and
  no Android SDK there, so `compileDebugAndroidTestKotlin` and the emulator run are
  the compile and the execution evidence — not this entry.
- **Two consecutive passes do not prove the flake is gone.** The original defect
  appeared on one of two runs at `b66932fd`; the candidate has now passed twice. What
  the change does establish is that the assertion no longer depends on a property the
  engine does not guarantee, which is the reason the flake existed.
- **This test can no longer detect an audible double-play.** It never measured that:
  a repeat *request* is not a repeat *playback*, and the engine plays staged files by
  index. Nothing is lost — the property it claimed was not the property it measured —
  but the claim is now stated correctly instead of being carried by a proxy.
- **No device validation, no neural voice, no product behaviour change.** One test
  assertion changed; `app/src/main` is untouched.

---

## 9. S1 — a finished read and a failed read are now distinguishable (append-only)

**Date:** 2026-11-27
**Base:** `7c4e6a64e39e34685b06e2e1698cf9eb896b9720` (branch `fix/s1-tier-truth-001`, PR #9)
**Parent of this change:** `ef6f979663a371123c194e7fbb917ef7a9c0b2f0`
**Code commit:** `f32b3b8e2e0cea227d975d4992a2a44dc3369e1c`

### 9.1 The defect

The reading screen decided its state line from the engine alone:

```kotlin
binding.tvState.text = when {
    playing && svc?.isBufferingNext == true -> getString(R.string.state_buffering)
    playing -> getString(R.string.state_speaking)
    paused -> getString(R.string.state_paused)
    bound -> getString(R.string.state_ready)
    else -> getString(R.string.state_starting)
}
```

A read that reached the end of the passage and a read that failed both fall
through to `bound -> "Ready"`, with the substate `"Paste text, or select some in
any app"`. That is the same screen as one that was never used, so **the user is
never told the read finished, and a failure is only ever a transient notice**.

Neither fact is recoverable from the engine: `TtsEngine` has no completion state
(it returns to idle), and `SpeechService` calls `stopSelf()` on both completion
and terminal error, so the service is gone by the time the screen re-renders.
`SpeechService.lastError` existed and was read by nobody.

Two further consequences of the same shape:

- **The synthesis wait was invisible.** Between `dispatch()` and the first audio
  the engine is neither playing nor paused, so `render()` showed `Ready` — the
  "inadequate visible synthesis/loading state" item in the S1 contract.
- **The two lines could disagree.** The state and the substate were decided by
  two independent `when` blocks, so any new state had to be added twice.

### 9.2 The change

| File | Role |
|---|---|
| `PlaybackPhase.kt` (new, pure JVM) | `PlaybackPhase`, `PlaybackObservation`, `PlaybackPhasePolicy.decide` — one decision over every input |
| `MainActivity.kt` | records the read's own facts (`readPreparing`, `readCompleted`, `readFailure`), renders both lines from the single phase |
| `values/strings.xml` | `state_completed`, `state_failed`, `substate_completed` |
| `PlaybackPhaseTest.kt` (new, JVM) | 14 tests over the decision table and its precedence |
| `narration-core/…/PlaybackPhaseWiringTest.kt` (new) | 2 wiring guards, including a negative control |

Precedence, highest first: buffering → playing → paused → failed → completed →
preparing → ready → starting. **Live playback outranks the terminal facts on
purpose**: an error that fell back to the offline voice leaves a message behind
while the read continues, and a stale `completed` must not hide a read that is
running now.

`onComplete` records completion *before* the service stops itself;
`onServiceDisconnected` deliberately leaves it alone, because that disconnect is
the normal consequence of `stopSelf()`. A deliberate `stopRead()` clears all
three flags: an abandoned read is neither finished nor failed.

### 9.3 Why the wiring guard is not beside the policy

`ci/scope.py` derives the app module's JVM scope by class reference and excludes
any file that names a class declared by an Android-bound file — **including
inside a string literal**. A guard that names the reading screen was therefore
`OUT` of the JVM job: measured, `python3 ci/scope.py | grep PlaybackPhaseTest`
returned `OUT`, and the 16 new tests counted **0** in `total_tests`. It lives in
`narration-core` instead, which `narration-core/build.sh` compiles in full and
whose working directory is the module root.

### 9.4 Evidence (run in this sandbox)

A JDK and the Kotlin compiler were provisioned here (Temurin `17.0.21.1`,
kotlinc `1.9.24`, jars from Maven Central and Google Maven), so the tracked
harness ran locally rather than only in GitHub Actions.

| Check | Command | Result |
|---|---|---|
| Baseline at `ef6f979`, clean tree | `bash ci/run-ci.sh --check` | `total_tests=447`, job1 exit 0, job2 exit 0 |
| With the change | `bash ci/run-ci.sh --check` | `total_tests=463`, job1 exit 0, job2 exit 0 |
| Attribution | `grep -c @Test` on the two new files | 14 + 2 = **16**, exactly the delta |
| **Live negative control** — wiring stripped from the real screen | `./narration-core/build.sh` | `Tests run: 171, Failures: 1`, exit **1**, naming all three missing properties |
| Restored, harness re-run | `bash ci/run-ci.sh --check` | `total_tests=463`, job1/job2 exit 0 |

`ci-evidence.log` was deliberately **not** rewritten: the other open PR on this
repository (#10) also rewrites it wholesale, so regenerating it here would make a
guaranteed textual conflict in a file that carries no reasoning. The numbers
above are the record; the log is regenerated by whoever merges last.

### 9.5 Not claimed

- **Not device-validated.** That a real screen paints these two lines is a device
  observation that was not made here; the phase decision and the wiring are
  verified as code and as source.
- **Completion is lost if it happens while the screen is away.** `onStop()`
  removes the client, so a read that finishes while the app is backgrounded
  returns to `Ready`. Carrying the outcome in the service is impossible while
  `onComplete` calls `stopSelf()`; changing that lifecycle is a separate slice,
  not part of this one.
- **No engine, model, or neural-tier change.** `EngineFactory` still has no
  caller on this branch — that is #10's slice, not this one.

---

## 10. S1 — a read that ends while the screen is away now reports how it ended (append-only)

**Date:** 2026-11-27. **Base:** `2276b9d`. This continues §9 and closes the first of
its two recorded gaps.

### 10.1 The defect

§9.5 recorded it: *"Completion is lost if it happens while the screen is away.
`onStop()` removes the client, so a read that finishes while the app is
backgrounded returns to `Ready`."* The same holds for a terminal failure.

`MainActivity.onStop()` removes `uiClient` and unbinds, and the service stops itself
the moment a read ends. So the two facts that make `PlaybackPhase.COMPLETED` and
`PlaybackPhase.FAILED` reachable existed only in the memory of a bound screen:

```
                    before                                    after
──────────────────────────────────────────────  ──────────────────────────────────────────────
read ends while away   service dies with the fact         the service records it before stopSelf()
screen returns         onServiceConnected → READY         adopts the record → COMPLETED / FAILED
read of another text   —                                    withheld (passages must match exactly)
live read on return    —                                    withheld (live state outranks a stored fact)
position after finish  last mid-read checkpoint survives    reset, so no "carry on" for a finished read
```

The stale-position half is the same defect seen from the resume side: the ticker
checkpoints at `CHECKPOINT_MS` and nothing wrote a final position, so a completed
passage could still offer "Carry on from 80%" for text the user had heard to the end.

### 10.2 The change

| File | Role |
|---|---|
| `ReadOutcome.kt` (new, pure JVM) | `ReadOutcome`, `ReadOutcomePolicy.adopt` — whether a stored outcome may be presented |
| `Prefs.kt` | `rememberOutcome` / `consumeOutcome` / `clearOutcome` — the record survives the service |
| `SpeechService.kt` | records `COMPLETED` (and resets the resume position) or a terminal `FAILED`, before `stopSelf()`; a `reading` flag keeps a preview's failure out of the passage's record |
| `MainActivity.kt` | `adoptStoredOutcome()` on the bind path; `dispatch()` clears a superseded record |
| `ReadOutcomeTest.kt` (new, JVM) | 10 tests over the adoption rule |
| `narration-core/…/ReadOutcomeWiringTest.kt` (new) | 3 wiring guards with two negative controls |

The rule is deliberately conservative, in the same spirit as `ResumePoint`: an
outcome is presented only when it exists, no read is live, the field is non-blank,
and the stored passage matches the current text **exactly**. Editing the passage —
even by appending — withholds it. A wrong "Completed" is worse than a missing one.

The record is presented **once** (`consumeOutcome`). Keeping it after it has been
shown would let the screen claim "Completed" indefinitely, long after the user
started and stopped other reads. Leaving and returning a second time therefore shows
`Ready`, which is truthful: nothing is being read.

### 10.3 Why the guard requires a call, not a definition

The first cut of the screen guard passed on a screen that *defined*
`adoptStoredOutcome()` but never called it — measured, `OK (174 tests)` with the call
line deleted. That is the same defect one level down: a correct adoption path that
nothing runs. The guard now requires the call on the bind path
(`addClient(uiClient)` followed by `adoptStoredOutcome()`), and the negative control
asserts exactly that finding.

### 10.4 Evidence (run in this sandbox)

Toolchain: Temurin `17.0.21.1`, kotlinc `1.9.24`, the classpath jars the tracked
harness names, all fetched from Maven Central / Google Maven into `/opt/tools`.

| Check | Command | Result |
|---|---|---|
| Baseline at `2276b9d`, clean tree | `bash ci/run-ci.sh --check` | `total_tests=463`, job1 0, job2 0 |
| With the change | `bash ci/run-ci.sh --check` | `total_tests=476`, job1 0, job2 0 |
| Attribution | `grep -c @Test` on the two new files | 10 + 3 = **13**, exactly the delta |
| **Live negative control** — the two `rememberOutcome` calls removed from the real service | `./narration-core/build.sh` | `Tests run: 174, Failures: 1`, naming *both* missing records |
| **Live negative control** — only the bind-path call removed from the real screen | `./narration-core/build.sh` | exit **1**, `Failures: 1`, naming *"the screen does not adopt an outcome when it binds"* |
| Both restored, harness re-run | `bash ci/run-ci.sh --check` | `total_tests=476`, job1 0, job2 0 |

`ci-evidence.log` is again left untouched: the other open PR (#10) rewrites it
wholesale, and the numbers above are the record.

### 10.5 Not claimed

- **Not device-validated.** That a real screen paints `Completed` after returning is
  a device observation that was not made here. What is verified is the decision
  table, the record's round trip through `Prefs`, and the wiring.
- **The service still stops itself on completion.** This slice carries the outcome
  around that lifecycle; it does not change it. A read that ends is still not
  resumable in place, and no attempt is made to keep the service alive.
- **A preview's failure is deliberately not recorded.** `reading` is false for a
  preview, so a sample that cannot be synthesised cannot claim the passage failed.
- **No engine, model, or neural-tier change.** `EngineFactory` still has no caller on
  this branch — that is #10's slice.

---

## 11. S1 — the preparing phase now keeps itself alive, and expires (append-only)

**Date:** 2026-11-28
**Base:** `3e034cae047366a72d3fd0d201036db2a111acc3` (branch `fix/s1-tier-truth-001`, PR #9)
**Continued, not duplicated:** an attempt to open a separate PR for this was abandoned
after `git merge` of this branch showed it already carried `PlaybackPhase.PREPARING`.

### 11.1 What §9 and §10 left open

§9 gave the screen a `PREPARING` phase, so the line stops reverting to "Ready". Two
things still did not follow from it:

1. **Nothing repainted.** The ticker's continue test was still the engine's own
   `isPlaying || isPaused`. During preparation the engine is neither, so the ticker
   ran exactly one pass, painted the phase, and stopped. The screen could say
   "Preparing…" and had no way to notice it ending — or not ending.
2. **The wait had no deadline.** `readPreparing` is a fact the *screen* holds; the
   engine will never correct it. A service that dies or never calls back left
   "Preparing…" on screen for ever, which is indistinguishable from a stuck screen
   and is a false claim that work is still in progress.

### 11.2 The change

`PlaybackPhasePolicy` gains the two facts the screen needs, both pure and both
tested here:

- `keepsTicking(phase)` — true for BUFFERING / PLAYING / PAUSED / **PREPARING**.
- `PREPARE_TIMEOUT_MS` (20 s) and `preparationExpired(waitedMs)`.

`MainActivity` records the dispatch instant, settles an over-long wait before each
paint and on `onResume`, and uses one `surfacePhase()` for both the painter and the
ticker. An expired wait is recorded as **FAILED** with a reason — the existing phase
— never as a silent reset to "Ready": a read nobody heard must not look like one
that was never asked for.

### 11.3 Evidence (run in this sandbox)

| Check | Command | Result |
|---|---|---|
| This branch at `3e034ca`, clean tree | `./ci/run-ci.sh --check` | `total_tests=476`, job1/job2 exit 0 |
| With this change | `./ci/run-ci.sh --check` | `total_tests=481`, job1/job2 exit 0 |
| `PlaybackPhaseTest` alone | `JUnitCore …PlaybackPhaseTest` | `OK (19 tests)` |
| **Negative control** — `PREPARING` moved to the `false` arm of `keepsTicking` | `JUnitCore …PlaybackPhaseTest` | `Tests run: 19, Failures: 2` — `a preparation keeps the ticker running` and its control |
| Fix restored, byte-identical (`cmp -s`) | — | restored |

476 → 481 = +5, exactly the new `PlaybackPhaseTest` methods.

### 11.4 Not claimed

- **Not device-validated.** `MainActivity` is outside the JVM scope (`ci/scope.py`),
  so the wiring is proven by compilation in the APK/emulator CI jobs, not here.
- **No engine, model, transport or playback change.** The wait is presented and
  bounded; how long synthesis actually takes is unchanged and unmeasured here.

## 12. S1 — a terminal event belongs to what is in flight, not to a boolean (append-only)

**Date:** 2026-11-28
**Base:** `b9bbf845394d7a7efbce49b0e0850a325f240363` (branch `fix/s1-tier-truth-001`, PR #9)
**Continued, not duplicated:** this is the invariant §10 states and does not hold.

### 12.1 The defect

§10 recorded the rule — "a preview's failure is not a read's, so it is not recorded"
— and implemented it with a boolean:

```kotlin
private var reading = false      // "true from the moment a read is dispatched"
...
if (reading) prefs.rememberOutcome(currentText, FAILED, msg)
```

A boolean cannot answer the question it is asked. Auditioning a voice is a
deliberate, supported path *during* a read: `MainActivity.previewVoice()` records the
reading position first, and its own comment says so — "auditioning a voice stops the
read". It dispatches `ACTION_PREVIEW`, which the service handles in a separate branch.
That branch never touches `reading`, so:

| step | `reading` | what the service believes |
|---|---|---|
| user reads a passage | `true` | a read is in progress |
| user auditions a voice → sample supersedes the read | `true` | a read is still in progress — **false** |
| the sample fails | `true` | "the read failed" |
| service stops; outcome stored | — | **passage recorded FAILED**, with the sample's message |

The user's passage was interrupted by their own action; it did not fail. The stored
outcome then survives the service, and `ReadOutcomePolicy.adopt` presents it on the
next bind, because the passage matches. So the claim outlives the session: a passage
the user only *interrupted* is reported as having failed.

The completion path was already correct — it tests `tts.isPreviewing` — but the error
path tests nothing, and `TtsEngine.preview` clears `isPreviewing` **before** it invokes
`onError`, so the engine's flag cannot be consulted there even in principle.

### 12.2 The change

One fact replaces the boolean: `InFlightUtterance { NONE, READ, PREVIEW }`, set on
dispatch by each of the three dispatch sites and cleared when an utterance ends.
`UtteranceAttributionPolicy` turns "what is in flight" plus "which terminal event" into
`TerminalRecord { COMPLETION, FAILURE, NONE }`, and only `READ` produces a record.

```
terminal event   in flight = READ          in flight = PREVIEW / NONE
───────────────  ────────────────────────  ───────────────────────────────
completion       record COMPLETED           record nothing (sample / unattributable)
terminal error   record FAILED              record nothing
```

Both terminal paths now ask the policy, so the rule lives in one place instead of
being re-derived at each site — which is how the two sites came to disagree.

`NONE` recording nothing is not a formality: an outcome arriving with nothing in
flight is unattributable, and an unattributable outcome is worse than none.

### 12.3 Evidence (run in this sandbox)

The JDK 17 / kotlinc 1.9.22 toolchain and the nine classpath jars were provisioned in
this sandbox, so the tracked harness ran here rather than only in Actions.

| Check | Command | Result |
|---|---|---|
| Baseline reproduced at `b9bbf84`, clean tree | `./ci/run-ci.sh --check` | `total_tests=481`, job1/job2 exit 0 — matches §11 |
| With this change | `./ci/run-ci.sh --check` | `total_tests=490`, job1/job2 exit 0 |
| `UtteranceAttributionTest` | job 1 (compiled in scope: pure JVM) | 7 tests, pass |
| `UtteranceAttributionWiringTest` | job 2 | 2 tests, pass |
| **Negative control A** — pre-repair `SpeechService.kt` restored from `HEAD`, everything else unchanged | `./ci/run-ci.sh --check` | `job 2: exit=1`; `There were 2 failures`, and the guard reported all five defects: *a dispatched passage is not recorded as the read in flight · a dispatched sample is not recorded as the sample in flight · completion is not decided by UtteranceAttributionPolicy.onComplete · a terminal error is not decided by UtteranceAttributionPolicy.onTerminalError · the service still holds a bare 'reading' flag* |
| **Negative control B** — in-suite, on the real file | `UtteranceAttributionWiringTest.the wiring guard detects the pre-repair service` | rewrites the live source back to the pre-repair shapes and asserts the same five defects, in order |
| Fix restored, byte-identical (`cmp -s`) | `./ci/run-ci.sh --check` | `total_tests=490`, job1/job2 exit 0 |

481 → 490 = **+9**, which is exactly the 7 new `UtteranceAttributionTest` methods plus
the 2 wiring nodes — no existing test changed outcome.

Control A is the one that matters: it is a *live* control on the real file, not a
fixture. It fails with the five-defect message above, which names the passage/sample
attribution explicitly, so neither predicate is vacuous.

### 12.4 Not claimed

- **Not device-validated.** `SpeechService.kt` is `OUT` of the JVM scope
  (`ci/scope.py`), so the wiring is proven by compilation in the APK/emulator jobs and
  by the source guard, not by execution on a device. No physical-device behaviour is
  claimed for this change.
- **The transient screen is unchanged.** `Client.onError` still delivers a sample's
  failure to the bound screen, which sets `readFailure` and can paint
  `PlaybackPhase.FAILED` while the sample is what failed. This change fixes the
  *stored* claim (which outlives the session); the *live* line during a failed sample
  is the next bounded task, and is recorded in `SONATA_KNOWN_LIMITATIONS.md` L24 rather
  than half-repaired here.
- **No engine, transport, model or playback change.** `TtsEngine.isPreviewing` is no
  longer read by the service; the property is left in place because the engine still
  uses it internally.
