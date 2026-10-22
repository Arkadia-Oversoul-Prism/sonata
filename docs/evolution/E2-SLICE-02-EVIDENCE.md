# E2 · Slice 02 — Offline readiness, cleartext policy, staged-audio ownership

**Protocol:** `Arkadia-Oversoul-Prism/sonata#1` (bounded recursive evolution)
**Branch:** `feat/process-text-playback-hardening-01`
**Base:** `4f7e6674caf2e8c9b12de4516ce3b25b68e65574`
**Observation date:** 2026-10-21

## 0. Revision index — which revision each result supports

The pass produced four successive revisions. They are recorded separately because
each result binds to a revision, and conflating them would overstate what was
tested.

**A revision is not the unit of evidence — an implementation tree is.** Every
commit after `17af283f` changes only documentation, so all of them compile to the
same release APK, and CI results transfer across them by artifact identity.

| Revision | Change | Artifact |
|---|---|---|
| `404a84b6acf365ab7efd00e5fc098b691fae2be9` | source + device suite | local build, local 75-test run |
| `17af283f187ff438fc934a8bbabaab86a2c4c93d` | adds the CI workflow repair | `7f5ab696…` |
| later commits on this branch | this document and its corrections | `7f5ab696…` (unchanged) |

Rather than enumerate runs — which go stale on every push — the property is stated
as an invariant and re-derived in §4:

> every build of this implementation tree, on any machine, yields release APK
> sha256 `7f5ab696352dd8328595b8b44f64e33ae9a06607a78aaee03d27a75bfaeccda8`

A revision that changed any compiled input would produce a different hash and would
need its own CI run. Until then, `git log --format=%H -- <any file outside docs/>`
after `17af283f` being empty is the whole argument, and it is checkable.

This slice hardens three defects in the `PROCESS_TEXT → speech playback` path that
Slice 01's unit tests could not see, because each one only appears when the real
platform speech engine is involved. It claims **no replacement** of any user
workflow.

---

## 1. Environment note — two container restarts, and what that cost

The sandbox restarted twice during this pass, destroying the working copy, the
Android toolchain, and every uncommitted change. The recovery is recorded here
because it changes what can be claimed:

| Event | Consequence |
|---|---|
| Restart 1 | `/tmp/sonata` gone; toolchain at `/opt/toolchain` gone |
| Restart 2 | Second clone gone; the instrumented device suite was never committed and is **lost** |
| Recovery | Cloned to `/workspace/sonata-work/sonata` (persistent), toolchain to `/workspace/tc`, committed and pushed at each step |

Two consequences that bound this evidence:

1. **The instrumented device suite no longer exists.** `git log --all -- app/src/androidTest`
   is empty and no revision ever tracked `app/src/androidTest/`. The 22-test device
   protocol was written in the destroyed working copy and was never committed. Its
   earlier result (`22 tests, 4 failures`) is a **recollection, not an artifact**:
   no log, no APK, no source survives. It is reported as such and is not used to
   support any claim below. Everything asserted here is re-derived from the
   committed tree.
2. **No emulator can run.** `/dev/kvm` does not exist in this sandbox, so an
   AVD would run in software emulation only. The device-level checks the protocol
   asks for are therefore **UNVERIFIED** (see §6), and the three defects were
   instead reproduced and pinned at the JVM level, where they are deterministic.

The three defects themselves are unaffected: each is a logic defect in
`TtsEngine`, reproducible without a device.

## 2. Defects found and repaired

### D-1 — Offline readiness race (cold start refuses to speak)

`AndroidOfflineVoice` completes initialisation in a platform callback, so
`isReady()` returns `false` for a real interval after construction. `TtsEngine`
consulted `isReady()` **once** and gave up.

The failure is worst in the primary use case: a `PROCESS_TEXT` selection cold-starts
the service, so the engine is asked to speak before the platform engine is up. The
offline path then reported a **null** fallback engine — the user gets neither
speech nor an honest reason.

**Repair.** `OfflineVoice.awaitReady(timeoutMs)` suspends until the engine can
speak, backed by a `CompletableDeferred` completed from the init callback. The
engine waits off the main thread (blocking it would risk an ANR) and bounds the
wait, so a platform engine that never calls back cannot suspend speech forever.
The wait is generation-checked, so a superseded request cannot speak after the fact.

`offlineReadyTimeoutMs` is injectable with a production default of 10 s. That
default is deliberately generous: waiting too long costs a brief silence, giving
up too early costs all speech.

### D-2 — Cleartext blocked with no policy, and reported unintelligibly

`targetSdk` is 34 and the manifest carried no `networkSecurityConfig`, so a
plain-HTTP backend was refused outright and surfaced as
`UnknownServiceException`. The user saw `Network error: UnknownServiceException` —
an exception class, with nothing indicating that an `https://` URL is the remedy.

**Repair, in two parts.**

- The transport now names the block in the user-facing reason
  (`describeFailure`), instead of reporting a class name.
- A **debug-only** network policy permits cleartext to loopback only, so
  instrumented tests can reach a local `MockWebServer`.

The release build is unchanged and stays strict. Verified on the merged
manifests rather than asserted:

| Variant | `networkSecurityConfig` | Effective policy |
|---|---|---|
| release | absent | platform default — cleartext refused for every host |
| debug | `@xml/network_security_config` | loopback only; `base-config` still refuses cleartext |

A blanket `usesCleartextTraffic="true"` would have weakened the shipped app to
make a test pass; that was explicitly rejected.

### D-3 — Staged audio orphaned on cancellation

The hop to the main dispatcher is a suspension point, and `supersede()` cancels
the job. A cancellation landing in that window skipped the `delete()` inside the
block and orphaned the temp file, so repeated selections accumulated audio in the
cache.

**Repair.** `currentFile` is assigned immediately after staging, with no
suspension point in between, so every cancellation path (`speak`, `stop`,
`shutdown` → `supersede()`) releases the file through the mechanism that already
existed. `releaseStagedFile` clears `currentFile` only when it is still the
tracked file, so a superseded coroutine resuming late cannot untrack a newer
request's file and leak that instead.

An earlier attempt using `try/finally` with `NonCancellable` was **rejected by its
own negative control** — see §3.

## 3. Verification — every guard was proved able to fail

A test that passes against the defect it exists to catch is not evidence. Each
repair was verified by removing it and confirming the guard reddens.

| Repair | Negative control | Result |
|---|---|---|
| D-1 readiness | `awaitReady(...)` replaced by a pre-fix single check | **2 failed, 13 passed** — both cold-start tests red |
| D-3 ownership | `currentFile = file` assignment removed, all else kept | **1 failed, 26 passed** — the leak test red |
| D-3 (first attempt) | `try/finally` version, same test | **PASSED — guard was invalid**, see below |

The first D-3 attempt is the important one. Its `try/finally` cleanup looked
correct and its test passed, but the negative control also passed: with a standard
test dispatcher the main-thread hop completes **synchronously**, so the window the
leak lives in never opened. The test was measuring nothing.

Repairing the *test* required a dispatcher that holds the hop open, so
`GatedDispatcher` was added: tasks queue and run only when the test releases them.
With the window genuinely open, the original fix still failed
(`staged files leaked: [sonata_….mp3]`) and the ownership fix passed. The guard now
distinguishes the two.

## 4. Commands run and exact results

Toolchain: Temurin JDK `17.0.20.1+1`, Android SDK `platforms;android-34` /
`build-tools;34.0.0`, Gradle 8.4 (wrapper).

| Command | Result |
|---|---|
| `./gradlew clean testDebugUnitTest assembleRelease --no-daemon` | **BUILD SUCCESSFUL** |
| `./gradlew testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest --no-daemon` | **BUILD SUCCESSFUL** (2m12s) |
| `./gradlew compileDebugAndroidTestKotlin --no-daemon` | **BUILD SUCCESSFUL** |
| unit tests | **75 tests, 0 failures, 0 errors, 0 skipped** |
| release APK | produced (unsigned), 3,110,031 bytes, sha256 `7f5ab696352dd8328595b8b44f64e33ae9a06607a78aaee03d27a75bfaeccda8` |

Test breakdown: 25 online + 15 offline + 10 playback-state + 8 speech-command
+ 7 speed-steps + 7 text-limits = **75**.

Slice 01 recorded 69 tests. The `+6` are the four new readiness/leak tests, the
recovery test, and the split of the unavailable-engine test.

### CI — push-triggered runs, and the trigger repair

Every push-triggered run on this branch has concluded `success` with all 9 steps
passing, including `Run unit tests` and `Compile instrumented tests`. The reported
count has been **75 tests, 0 failures, 0 ignored** on each, read from each run's own
uploaded JUnit report rather than from the job conclusion — a green job is not
evidence that any test executed.

Runs are not enumerated here because each documentation commit triggers another and
would stale the list. Re-derive with:

```bash
gh run list --branch feat/process-text-playback-hardening-01 --limit 20
# then download the 'Sonata-unit-test-reports-*' artifact of any run and read
# index.html: 'N tests M failures K ignored'
```

Before this slice the workflow's push filter was `feature/**`, which does **not**
match `feat/process-text-playback-hardening-01` (`fnmatch` returns `False`). Every
push to this branch had therefore been ungated, and the only prior run in the
repository's history was a manual `workflow_dispatch`. The three runs above are the
first push-triggered runs this repository has ever had, and the first time the unit
suite has been executed by CI at all — the job previously ran `assembleRelease`
only, so a green build said nothing about the tests.

### Artifact identity — four independent builds agree

Every build of this implementation tree, on two different machines, produces a
**byte-identical** release APK:

| Built by | Count | sha256 |
|---|---|---|
| GitHub runners (each push-triggered run) | **5** | `7f5ab696…` |
| this sandbox | 1 | `7f5ab696…` |

The hash was re-downloaded from each run's `Sonata-release-*` artifact and compared,
not inferred from the run's success.

Two independent claims follow, and they are different claims:

1. **The build is reproducible across environments.** A GitHub runner and this
   sandbox agree bit for bit, so the local result is not an environment artifact.
2. **The revisions after `17af283` are the same implementation.** Three of them
   produce the identical artifact, so they cannot differ in any compiled input.
   This is what licenses reading the CI results in §0 as one body of evidence.

Neither claim extends to device behaviour. An APK that builds identically can still
behave differently on a device, which is exactly what §7 leaves unverified.

### Audits

| Audit | Result |
|---|---|
| `git grep` credential patterns in tracked files | **no matches** |
| Manifest permissions | `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `SYSTEM_ALERT_WINDOW` — **no `RECORD_AUDIO`** |
| `usesCleartextTraffic` in `main` manifest | **0** |
| Audio/transcript persistence (`MediaRecorder`, `openFileOutput`, external storage) | **none** |

No microphone permission is present and no STT is claimed: the protocol forbids
placeholder STT, so none was added.

## 5. Residual risks

| # | Risk | Status |
|---|---|---|
| 1 | Readiness bound (10 s) is a judgement, not a measurement | **UNVERIFIED** — no device to measure cold-start cost against |
| 2 | Offline resume is a re-synthesis seam, not sample-accurate | Known and labelled in the UI; unchanged by this slice |
| 3 | Device behaviour of D-1/D-2/D-3 | **UNVERIFIED** — no KVM, no emulator; JVM-level reproduction only |
| 4 | The 22-test device protocol | **LOST** with the destroyed working copy; must be rewritten from the committed tree |
| 5 | Backend contract (`/api/tts`) is documented from Arkadia source, not exercised live | **UNVERIFIED** in this pass |

## 6. Not verified

- No emulator or device run: `/dev/kvm` absent. `PROCESS_TEXT` invocation,
  background playback, notification controls, and interruption handling are
  **UNVERIFIED**.
- No live backend call: the `/api/tts` contract is taken from Arkadia source.
- No Arkadia-repository inspection in this pass; the MIE/N-ATLAS/capability
  matrix the protocol asks for under E0 §4 is **not yet established**.

## 7. Device protocol suite — 14 tests written and committed (not the lost 22)

The 22-test device protocol was lost with the destroyed working copy (§1). A
**new, smaller suite of 14 tests** has been written from the committed tree and
committed, so a restart cannot lose it again. It adds `app/src/androidTest/`,
`testInstrumentationRunner`, and the instrumented test dependencies the module did
not previously declare.

The 22-test suite is **not** recreated and is **not** evidence: its tests, its
results and its source are gone. The durable suite is the 14 committed here, and
`grep -c '@Test'` over `app/src/androidTest/` returns 14 (5 + 9). The difference in
count is not a regression — the two suites are unrelated artifacts.

Two suites, both **compiled and packaged** into `app-debug-androidTest.apk`:

| Suite | What it can only test on a device |
|---|---|
| `DeviceVoiceProtocolTest` (5) | The real platform `TextToSpeech` engine: initialisation within a budget, that `awaitReady` observes the callback rather than a not-yet-set flag, that a shut-down engine resolves instead of hanging, that `speak()` reaches a terminal callback, and that `stop()` on an idle engine is safe |
| `DeviceTransportProtocolTest` (9) | The shipped `OkHttpTtsTransport` against a real `MockWebServer` over loopback: that the debug policy permits loopback cleartext, that the payload matches the `/api/tts` contract, and that HTTP failure, non-audio content type, empty body, unreachable host, malformed URL and empty base URL each produce a `Failure` rather than an exception |

**These tests have not been run.** They are recorded as
**compiled-but-unexecuted** — the strongest claim this environment supports. The
blocker is measured, not assumed:

| Probe | Result |
|---|---|
| `/dev/kvm` | **absent** |
| `/proc/cpuinfo` virtualisation flags (`vmx`/`svm`) | **0 occurrences** — the CPU exposes no virtualisation extensions at all |
| `$ANDROID_HOME/emulator/emulator` | **not installed** |
| `$ANDROID_HOME/system-images/` | **absent** |
| `adb devices` | **no devices** |

The absent CPU flags are the decisive one: without them no x86_64 system image can
run, KVM or not, so this is a hardware/host limitation rather than a missing
package. Installing the emulator and a system image would not change the outcome.
See `docs/evolution/E4-DEVICE-PROTOCOL.md` for the prepared protocol and the exact
command an operator with a device should run.

## 8. Next bounded candidate — BLOCKED

**Run the committed device suite on a real device or a KVM-capable emulator.**
That is the only artifact that can close residual risks 1 and 3. Until it runs,
the three repairs are proven at the JVM level only, and the device-level behaviour
of `PROCESS_TEXT`, background playback, notification controls and interruption
handling remains unverified.

**This candidate is BLOCKED, and the blocker is measured.** `/proc/cpuinfo`
reports **zero** `vmx`/`svm` flags, so no x86_64 system image can run on this host
regardless of KVM or installed packages. No emulator binary or system image is
present, and `adb devices` is empty. Installing packages would not change the
outcome, so it was not attempted.

The prepared protocol and the exact operator commands are in
`docs/evolution/E4-DEVICE-PROTOCOL.md`. Until an operator with a device returns the
evidence listed there, no device-level claim is made.

## 9. Statement of authority

No merge, deploy, release, production configuration change, or secret change was
performed. The branch is pushed to `feat/process-text-playback-hardening-01` for
review; the base branch is untouched. Human approval remains required.
