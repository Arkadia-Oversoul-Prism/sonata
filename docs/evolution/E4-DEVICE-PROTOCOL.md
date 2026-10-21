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

## 2. What is ready to run

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
git checkout b55f6716d846dd78df1ce5744535c7b4583aecdf

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
| M-7 | Configure an **http://** backend, then speak | A message naming plain HTTP and recommending `https://` — not `UnknownServiceException` |
| M-8 | Repeat M-1 ten times in a row | No degradation; cache does not grow without bound (the D-3 leak) |
| M-9 | Deny the notification permission (Android 13+), then play | Playback works; no notification. Denial must not break playback |
| M-10 | Rotate the device / change system dark mode during playback | Playback is not interrupted |

M-2, M-7 and M-8 are the device-level expressions of the three repairs in this
slice. If M-2 fails, the readiness repair does not hold on that device and the
slice's central claim is false for it.

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
| Device suite executes | **BLOCKED** — no device, no KVM, no virtualisation flags |
| Primary user workflow on a device | **UNVERIFIED** |
| Replacement of any user workflow | **NOT CLAIMED** |

No merge, deploy, release, or production configuration change accompanies this
document. Human authorization remains required.
