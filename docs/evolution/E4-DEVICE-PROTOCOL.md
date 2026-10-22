# E4 — Device verification protocol (prepared, not executed)

**Protocol:** `Arkadia-Oversoul-Prism/sonata#1` (bounded recursive evolution)
**Branch:** `feat/process-text-playback-hardening-01`
**Prepared against:** `b55f6716d846dd78df1ce5744535c7b4583aecdf`
**Date prepared:** 2026-10-21

## Why this document exists

The primary user workflow — select text, have it read aloud — has **not** crossed
the device-verification boundary. The 75 JVM tests and the green CI run prove the
engine's logic and the build, and nothing about the real platform `TextToSpeech`
engine, `PROCESS_TEXT` integration, background playback, or the notification
controls.

This sandbox cannot cross that boundary. Rather than claim verification it cannot
support, this document is the protocol to run and the evidence to collect. The
correct outcome of the current pass is **a prepared protocol and an explicit
blocker**.

## 1. The blocker, measured

| Probe | Result |
|---|---|
| `/dev/kvm` | **absent** |
| `/proc/cpuinfo` virtualisation flags (`vmx`/`svm`) | **0 occurrences** |
| `$ANDROID_HOME/emulator/emulator` | **not installed** |
| `$ANDROID_HOME/system-images/` | **absent** |
| `adb devices` | **no devices** |

The CPU flags are decisive. With no virtualisation extensions exposed, no x86_64
system image can run at all — this is a host limitation, not a missing package.
Installing the emulator and a system image would not change the outcome, so it was
not attempted. An `arm64` image would require an arm64 host; this is x86_64.

**Classification: BLOCKED** on device availability. Not `UNVERIFIED` through
oversight, and not `PASS`.

## 2. What to install — the release artifact cannot be installed

**Measured, and it invalidates the obvious path.** The release APK that CI uploads
is **unsigned** and cannot be installed on any device:

```
$ apksigner verify app-release-unsigned.apk
DOES NOT VERIFY
ERROR: Missing META-INF/MANIFEST.MF
```

`app/build.gradle.kts` declares no `signingConfig` for the `release` build type,
`git log -S signingConfig` over the whole history is **empty**, and the workflow
references no keystore secret. The release APK has therefore never been signed, by
CI or locally. Downloading `Sonata-release-*.apk` from a run and installing it will
fail with `INSTALL_PARSE_FAILED_NO_CERTIFICATES`.

| Artifact | Signed | Installable | sha256 |
|---|---|---|---|
| `app-release-unsigned.apk` | **no** | **no** | `7f5ab696…` (reproducible) |
| `app-debug.apk` | yes, Android Debug key | **yes** | **not reproducible — see below** |

**The debug APK's hash is not stable across machines, and this corrects an earlier
claim.** The CI-built debug APK and the locally built one have *different* sha256
values (`d1856545…` vs `b4d9cf8b…`) because each machine signs with its own
generated debug keystore:

| Build | Debug certificate SHA-256 |
|---|---|
| GitHub runner | `1ac97a80…` |
| this sandbox | `b77ec912…` |

So the *unsigned release* APK is the byte-reproducible artifact, and the *signed
debug* APK is not. This does not weaken the release-artifact identity argument in
`E2-SLICE-02-EVIDENCE.md` — that argument was made only about the unsigned release
APK, and it still holds. It does mean a debug APK's hash **cannot** be used to prove
which revision produced it; use the certificate and the artifact's own name (which
embeds the SHA) for that instead.

**Use the debug APK for device testing.** Build it from the candidate revision:

```bash
./gradlew assembleDebug --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Its package id is `com.arkadia.sonata.debug`, **not** `com.arkadia.sonata`, so it
installs alongside a release build rather than replacing it. That also means the
cache path in §3.3 uses the `.debug` suffix.

Signing the release APK is a deliberate non-action: it needs a keystore, and
introducing one is a release-authority change, not a test step. A throwaway
signature was verified to make the artifact installable (`apksigner sign` with the
debug key → `VERIFIED`), which confirms the missing signature is the *only*
obstacle — but that was done on a copy in `/tmp`, and the repository is untouched.

### Two caveats that follow from testing the debug variant

1. **Debug is not minified.** `release` sets `isMinifyEnabled = true` and
   `isShrinkResources = true`; debug does neither. A debug build therefore does not
   exercise ProGuard/R8. Passing on debug does not prove the release build behaves
   identically — it proves the *source* does.
2. **Debug exempts loopback from the cleartext rule** (see §3.2 M-7).

## 2b. What is ready to run

The instrumented suite is **compiled and packaged** (`app-debug-androidTest.apk`),
so no test authoring remains — only execution on a device.

| Suite | Tests | Covers |
|---|---|---|
| `DeviceVoiceProtocolTest` | 5 | real platform `TextToSpeech`: initialisation within a budget, `awaitReady` observing the callback rather than a not-yet-set flag, a shut-down engine resolving instead of hanging, `speak()` reaching a terminal callback, `stop()` on an idle engine |
| `DeviceTransportProtocolTest` | 9 | shipped `OkHttpTtsTransport` against a real `MockWebServer`: loopback cleartext permitted by the debug policy, the payload matching the `/api/tts` contract, and HTTP failure / non-audio content type / empty body / unreachable host / malformed URL / empty base URL each producing a `Failure` rather than an exception |
| **Total** | **14** | |

These 14 are the durable suite. The earlier 22-test suite was lost with a destroyed
working copy and is **not** recreated; it is not evidence and its count is unrelated.

## 3. Protocol — operator steps

### 3.1 Automated suite

```bash
git fetch origin
# Resolve the candidate rather than trusting a SHA copied into this document,
# which goes stale on the next commit. Record what it prints.
git checkout feat/process-text-playback-hardening-01
git rev-parse HEAD               # <-- record this in the report

# A real device (USB debugging on), or an emulator on a KVM-capable host:
adb devices                      # must list exactly one authorised device

./gradlew connectedDebugAndroidTest --no-daemon
```

Expected: **14 tests, 0 failures**. Collect the report:

```bash
ls app/build/reports/androidTests/connected/debug/
# and the machine-readable result:
ls app/build/outputs/androidTest-results/connected/debug/*.xml
```

Record the device model, API level, and the `TextToSpeech` engine that
`p1_1` reports. A software-emulated runtime inflates initialisation by orders of
magnitude, so note whether the run was on hardware or an emulator.

### 3.2 Manual acceptance — the primary user workflow

The automated suite cannot drive the Android text-selection UI. These steps must be
performed by hand, and each is a separate claim:

| # | Step | Expected |
|---|---|---|
| M-1 | Select a paragraph in any app (browser, notes, PDF reader) → overflow → **Read aloud / Sonata** | Playback starts; text is spoken from the beginning |
| M-2 | Select text **before** opening Sonata (cold start), so the service must initialise | Speech starts after a short pause — **not** silence, and **not** "offline voice unavailable". This is the D-1 readiness repair |
| M-3 | Press Home during playback | Audio continues; the notification appears with pause/resume/stop |
| M-4 | Tap pause, then resume, in the notification | Playback pauses and resumes from roughly where it stopped |
| M-5 | Tap stop | Playback ends; the notification clears; no sound continues |
| M-6 | Incoming call or another app taking audio focus during playback | Playback stops or ducks; it does not play over the call |
| M-7 | Configure an **http://** backend on a **non-loopback host** (e.g. `http://example.com`), then speak | A message naming plain HTTP and recommending `https://` — not `UnknownServiceException` |
| M-8 | Repeat M-1 ten times in a row | No degradation; cache does not grow without bound (the D-3 leak) |
| M-9 | Deny the notification permission (Android 13+), then play | Playback works; no notification. Denial must not break playback |
| M-10 | Rotate the device / change system dark mode during playback | Playback is not interrupted |

M-2, M-7 and M-8 are the device-level expressions of the three repairs in this
slice. If M-2 fails, the readiness repair does not hold on that device and the
slice's central claim is false for it.

**M-7 has a trap, and it is a false-negative trap.** The debug build's network
policy exempts `localhost` and `127.0.0.1` from the cleartext rule (that exemption
exists so the instrumented suite can reach `MockWebServer`). Testing M-7 with
`http://localhost:8080` will therefore **not** produce a cleartext error, and
concluding from that silence that the repair is broken would be wrong — the policy
is working exactly as configured. M-7 must use a non-loopback host. Verified in the
built artifact:

```
$ aapt2 dump xmltree --file res/xml/network_security_config.xml app-debug.apk
    E: domain-config
      A: cleartextTrafficPermitted=true
        T: 'localhost'        <-- exempt
        T: '127.0.0.1'        <-- exempt
    E: base-config
      A: cleartextTrafficPermitted=false   <-- everything else still refused
```

### 3.3 Cache-leak check (M-8)

The D-3 repair claims no orphaned audio. Verify on the device rather than trusting
the JVM test:

```bash
adb shell run-as com.arkadia.sonata.debug ls -la cache/
```

After ten speak/stop cycles, at most one `sonata_*.mp3` should remain (the one
playing, or none when idle). More than one means the leak is real on-device and the
repair does not hold.

## 4. Evidence to return

To convert any row above from `UNVERIFIED` to `VERIFIED`, return:

1. the exact commit SHA under test;
2. the device model and API level, and whether it was hardware or an emulator;
3. the `connectedDebugAndroidTest` result — the **report file**, not a summary;
4. for each manual step, the observed result, with a screenshot or screen recording
   for M-2, M-3, M-5 and M-7;
5. for M-8, the `ls` output above;
6. any failure, verbatim, including the full stack trace.

A step that was not performed is reported as not performed. A green automated run
does not convert any manual step to `VERIFIED`, and a single passing device does not
establish behaviour across API levels.

## 5. What this protocol does not cover

- **Long-form listening** — chaptered playback, position persistence, speed
  control over hours. No bounded candidate has been authorized for it.
- **Voice capture and STT** — not implemented, and no placeholder was added. The
  manifest declares no `RECORD_AUDIO`, so no STT capability can be claimed.
- **Arkadia voice interaction** — the `/api/tts` contract is taken from Arkadia
  source and has never been exercised live from this repository.
- **ElevenLabs / Eleven Reader migration** — out of scope until the bounded
  candidates above are authorized. No replacement claim is made anywhere in this
  slice.

## 6. Status

| Boundary | State |
|---|---|
| Source builds, tests pass, CI gates | **VERIFIED** |
| A CI artifact is installable as-is | **FAILED** — release is unsigned; use the debug APK |
| Device suite executes | **BLOCKED** — no device, no KVM, no virtualisation flags |
| Primary user workflow on a device | **UNVERIFIED** |
| Release-minified build behaves as debug | **UNVERIFIED** — debug skips R8 |
| Replacement of any user workflow | **NOT CLAIMED** |

The second row is a genuine defect, not a missing step: the artifact this project
publishes cannot be installed. It is recorded here rather than fixed, because
signing requires a keystore and that is a release-authority decision.

No merge, deploy, release, or production configuration change accompanies this
document. Human authorization remains required.
