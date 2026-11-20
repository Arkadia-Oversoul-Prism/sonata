# SONATA_PRODUCTION_HANDOFF

**Status:** Stage 7b production candidate
**Candidate commit:** `a102db4f01b04f95214216c3118545d4030cbb02`
**Branch:** `integration/stage7b-prod-candidate`
**Base:** `235346bee72b9162935ef74e05c84caa91c7bd13` (default branch `feature/sonata-tts-app`)
**Merge commit:** `07c68fe`
**Date:** 2026-11-20

---

## 1. FINAL VERDICT

# ⚠️ NOT READY — IMPLEMENTATION OR EVIDENCE INCOMPLETE

The candidate is **not** ready for human production review, and the reason is not
documentation or process. It is that **the product cannot speak**.

### Why this verdict and not "conditionally ready"

The three permitted verdicts are:

| Verdict | Meaning |
|---|---|
| READY FOR HUMAN PRODUCTION REVIEW | Everything a human needs to decide is present and green |
| CONDITIONALLY READY — SPECIFIC EXTERNAL GATES REMAIN | The implementation is complete; only things outside this environment remain |
| **NOT READY — IMPLEMENTATION OR EVIDENCE INCOMPLETE** | Something in the implementation or the evidence is missing |

"Conditionally ready" would be correct if the only gaps were the APK build and the
device gates — both genuinely external. But there is a gap that is **not** external:

**There is no speech engine.** No model binary, no inference runtime, no JNI bridge,
no `SpeechModel` implementation. The core function of a text-to-speech reader does
not exist in this tree.

That is an implementation gap, not an external gate. No amount of device access or
SDK availability would close it. Therefore the third verdict applies.

### What *is* complete

This is not a failed stage. Substantial, verifiable work was done:

- The integration tree that Stage 7a said never existed now exists at a named commit.
- 281 tests pass, 0 fail, against that exact commit.
- The privacy policy is now genuinely enforced — and the enforcement **found a real
  violation** that had been shipping.
- Three tests that did not test what they claimed are fixed, and two of them failed
  when corrected, proving they now execute.
- The documentation now matches the implementation, including where the
  implementation is absent.

---

## 2. Evidence for the verdict

### 2.1 What is met

| # | Criterion | Evidence |
|---|---|---|
| A1 | Integration tree at a named commit | `a102db4f01b04f95214216c3118545d4030cbb02` |
| A2 | CI runs against that exact commit | `ci-evidence.log` line 2: `commit_sha=a102db4…` |
| A3 | All executable tests pass | 112 + 169 = 281, 0 failures |
| A4 | Privacy verified against the real manifest | `R11` — and it found a violation, now fixed |
| A5 | No placeholder presented as production | Neural tier reclassified unsupported |
| A6 | Documentation matches implementation | This document set |
| A7 | APK/AAB built | **Built on GitHub Actions** — see §2.3. Unsigned (no keystore) |
| A8 | Instrumented device suite | **Passes on an API-34 emulator** — see §2.3. Not a physical device |

### 2.2 What is not met

| # | Criterion | Why |
|---|---|---|
| A7b | APK/AAB **signed** | No keystore. Signing is a release-authority decision |
| A8b | Device gates G1–G6 on a **physical** device | No physical device; emulator is not a substitute for thermal/audio/battery behaviour |
| A9 | Performance S0-1..S0-4 | Needs a physical device |
| A10 | Model binaries with checksums | **No models exist** |

A10 is the one that forces the verdict. A7b–A9 are external. A10 is not.

### 2.3 Real CI evidence (GitHub Actions)

Earlier drafts of this document said the Android build could not run because the
preparing environment had no SDK. That was true of the sandbox and **wrong about
the project**: `.github/workflows/build-apk.yml` builds on a GitHub runner with a
full SDK. It was run for this candidate and it passes.

| Workflow | Job | Result |
|---|---|---|
| Build Sonata APK | Build Release APK | ✅ unit tests, lint, `assembleRelease`, `bundleRelease`, `assembleDebug`, compile androidTest |
| Build Sonata APK | Instrumented tests (API 34 emulator) | ✅ `connectedDebugAndroidTest` |
| Sonata CI | JVM verification | ✅ 281 tests |

Artifacts produced for the candidate SHA: `Sonata-release-<sha>` (release APK,
1.5 MB), `Sonata-debug-installable-<sha>` (debug APK, 5.8 MB, installable),
`Sonata-unit-test-reports-<sha>`, `Sonata-instrumented-test-reports-<sha>`.

**This is what found the last real defect.** The first instrumented run failed 17
tests with `lateinit property transport has not been initialized`. The cause was
the privacy fix itself: removing `INTERNET` from the main manifest also blocks a
loopback socket, so the MockWebServer suites could not start. The permission now
lives in `app/src/debug/AndroidManifest.xml` only — the release APK still declares
no `INTERNET`, and `R11` still fails the build if it appears in the main manifest.

No JVM test could have found this. It is the concrete argument for running the
real Android pipeline rather than reasoning about it.

---

## 3. What was fixed in this stage

Every item traces to a Stage 7a finding.

| Finding | Fix | Verified by |
|---|---|---|
| B1 — integration commit never published | Merged offline branch into S0 baseline; added `narration-core/` as a real module | `07c68fe`, `a102db4` |
| B2 — placeholder production runtime | Neural tier reclassified **unsupported**; no JNI shim exists | `grep external fun` → none |
| B3 — no README classification | Five-way classification added | `README.md` |
| B4 — privacy gate tested against a fixture | `R11` reads the real manifest and fails if absent | It failed, exposing 4 violations |
| R3 does not test invocations | Recording engine observes invocations | Test failed on first run (real bug), then passed |
| Connectivity probe is a tautology | Timeout assertion added | Prints `live=true elapsedMs=99` |
| Long-sentence path never exercised | ~800-char corpus passage + 2 tests | `StylePlannerTest` |
| No performance criteria | NFR-10..13 adopted from E11 | `SONATA_MASTER_REQUIREMENTS.md` §4.5 |

### The privacy finding, in detail

When `R11` was pointed at the real manifest it failed with:

```
manifest policy failures: [NO_INTERNET, RECORD_AUDIO_PRESENT, BACKUP_DISABLED, BACKUP_EXCLUSIONS]
```

The shipped app declared `INTERNET`, had `allowBackup="true"`, and requested
`RECORD_AUDIO` with no recording code. All four are fixed. This is the concrete
value of the stage: a privacy promise that was being broken while the test reported
success.

---

## 4. Reproducible build information

### 4.1 Source

| Field | Value |
|---|---|
| Repository | `github.com/Arkadia-Oversoul-Prism/sonata` |
| Branch | `integration/stage7b-prod-candidate` |
| Commit | `a102db4f01b04f95214216c3118545d4030cbb02` |
| Base commit | `235346bee72b9162935ef74e05c84caa91c7bd13` |
| Merge commit | `07c68fe` |
| Tracked files | 316 |
| App Kotlin sources | 88 |
| Narration-core Kotlin sources | 21 |

### 4.2 Toolchain (pinned)

| Component | Version | Why pinned |
|---|---|---|
| Kotlin compiler | 1.9.22 | Matches the project's declared version |
| JDK | 17 | `jvm-target 17` in every build script |
| JUnit | 4.13.2 | Test framework |
| Hamcrest | 1.3 | JUnit dependency |
| kotlinx-coroutines | 1.7.3 | Core + test |
| androidx room-common | 2.6.1 | Annotations only (pure JVM) |
| androidx annotation-jvm | 1.7.1 | Annotations only |
| OkHttp | 4.12.0 | Legacy transport |
| Okio | 3.6.0 | OkHttp dependency |
| org.json | 20240303 | Legacy transport |

### 4.3 Reproduce the test result

```bash
git clone https://github.com/Arkadia-Oversoul-Prism/sonata.git
cd sonata
git checkout a102db4f01b04f95214216c3118545d4030cbb02

# Install Kotlin 1.9.22 and JDK 17, fetch the jars listed in §4.2,
# then:
export LIB=/path/to/jars
export STD=/path/to/kotlinc/lib
export KOTLINC=/path/to/kotlinc

./ci/run-ci.sh             # both jobs; expect 281 tests, VERDICT=PASS
```

### 4.4 Reproduce the Android build

**Not possible in this environment.** The workflow is written
(`.github/workflows/ci.yml`, job `android-build`) and will run where the SDK is
available. It is deliberately **not** marked `continue-on-error`: a red job is the
honest signal.

### 4.5 Determinism notes

- `build/scope.py` is deterministic: same tree in, same scope out.
- The corpus is fixed and hashed (`e1eafd06…`).
- No test depends on wall-clock time, network, or random ordering.
- `connectivityProbe` depends on the environment; its result is printed, not asserted.

---

## 5. Licence inventory

### 5.1 Application code

| Component | Licence | Status |
|---|---|---|
| Sonata application code | Proprietary (Arkadia) | — |
| Kotlin standard library | Apache-2.0 | Permitted |
| AndroidX (core, appcompat, activity) | Apache-2.0 | Permitted |
| Room | Apache-2.0 | Permitted |
| Media3 | Apache-2.0 | Permitted |
| OkHttp | Apache-2.0 | Permitted |
| kotlinx-coroutines | Apache-2.0 | Permitted |
| JUnit (test only) | EPL-1.0 | Test scope only, not shipped |
| Hamcrest (test only) | BSD-3-Clause | Test scope only, not shipped |

### 5.2 Models

| Model | Licence | Verified | Shipped |
|---|---|---|---|
| Kokoro-82M | Apache-2.0 | **No** | No |
| Piper / VITS | MIT | **No** | No |
| OpenVoice v2 | MIT | **No** | No |

**No model licence has been verified against an upstream licence file.** The
attributions above come from the project's own prior documentation. No model is
bundled, so no attribution obligation is currently triggered — but this must be
resolved before any model ships.

### 5.3 Attribution obligations

None currently triggered, because no third-party model or font is bundled. The
AndroidX/OkHttp/coroutines obligations are satisfied by the standard Apache-2.0
notice, which should be added to an about screen before release.

---

## 6. Migration plan

### 6.1 Database

| Field | Value |
|---|---|
| Current schema version | 1 |
| Migration registry | Empty |
| Migrations written | 0 |
| Migrations tested | 0 |

There is nothing to migrate: version 1 is the first schema. The first schema change
will be the first test of the migration path, and it must ship with a written
migration and a test that runs it against a populated v1 database.

### 6.2 From the previous candidate

The previous candidate was a standalone harness, not an installed app. There is no
user data to migrate, because no build was ever distributed.

### 6.3 From the S0 baseline

The S0 baseline (`235346b`) had `INTERNET` and an online transport. A user who
installed it would have a database at version 1 with the same schema. No migration
is required; the schema is unchanged.

---

## 7. Rollback plan

### 7.1 Rollback of this candidate

This candidate is **not merged and not released**. Rollback is therefore trivial:
do not merge it. The default branch is untouched at `235346b`.

### 7.2 If a future release must be rolled back

| Step | Action |
|---|---|
| 1 | Revert the merge commit on the release branch |
| 2 | Re-publish the previous APK/AAB |
| 3 | Confirm the database schema is unchanged (v1 → v1, no migration to undo) |
| 4 | Confirm no user data was written in a format the previous version cannot read |

Because the schema is at v1 and unchanged, a rollback carries no data risk. This
will stop being true at the first schema migration, at which point a down-migration
must be written and tested.

### 7.3 Rollback of the privacy fix

The privacy fix removes `INTERNET` and disables backup. Rolling it back would
re-enable network access and backup — which would break the product's core promise.
**This must not be rolled back.** If the offline engine cannot be delivered, the
correct action is to not ship, not to restore the network path.

---

## 8. What a human reviewer should do

1. **Read `SONATA_KNOWN_LIMITATIONS.md` first.** It is the shortest path to the
   truth about this candidate.
2. **Check the SHA.** `ci-evidence.log` line 2 must equal the PR head SHA.
3. **Re-run the two test commands** in §4.3. They take about three minutes.
4. **Decide the product question**, which is not a code question: is a
   source-complete, logic-tested, engine-less reader worth continuing, or should the
   engine be the next and only priority?

The engineering position is that the engine is the only thing that matters now.
Everything else in this candidate is supporting work for a product that cannot yet
perform its one function.

---

## 9. Sign-off

| Field | Value |
|---|---|
| Prepared by | DevOps Automator |
| Date | 2026-11-20 |
| Candidate commit | `a102db4f01b04f95214216c3118545d4030cbb02` |
| Tests | 281 passed, 0 failed |
| Merged | **No** |
| Released | **No** |
| Deployed | **No** |
| Verdict | **NOT READY — IMPLEMENTATION OR EVIDENCE INCOMPLETE** |
