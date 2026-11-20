# SONATA_DEVICE_VALIDATION

**Status:** Stage 7b production candidate
**Candidate commit:** `a102db4f01b04f95214216c3118545d4030cbb02`
**Date:** 2026-11-20

---

## 1. Headline

**No device validation has been performed. Not one gate has been executed.**

This document exists to state that precisely, to define what would have to be run,
and to make the gap impossible to overlook. It is not a report of results.

| Gate | Description | Executed | Result |
|---|---|---|---|
| G1 | App installs and launches | **No** | — |
| G2 | Text selection reaches the reader | **No** | — |
| G3 | Speech is produced | **No** | — |
| G4 | Pause/resume preserves position | **No** | — |
| G5 | Floating overlay survives backgrounding | **No** | — |
| G6 | Export produces a playable file | **No** | — |

---

## 2. Why nothing was executed

Three independent blockers, any one of which is sufficient:

1. **No Android SDK in the build environment.** No APK can be produced, so there is
   nothing to install.
2. **No physical device.** The gates are defined on hardware; an emulator would not
   answer the questions G3 and G5 ask (real audio output, real window manager).
3. **No speech engine.** Even with a device and an APK, G3 would fail: there is no
   model and no inference runtime (ADR-010).

The third is the substantive one. G1, G2, G4 and G6 could in principle be exercised
on a device with a stub engine. G3 cannot, because the capability does not exist.

---

## 3. The S0 exit gate (from E11, unexecuted)

E11 defines the S0 exit gate as four measurements on a mid-tier device:

| # | Criterion | Threshold | Measured |
|---|---|---|---|
| S0-1 | Real-time factor | ≤ 0.5 | **No** |
| S0-2 | Peak RSS | ≤ 700 MB | **No** |
| S0-3 | Adapter training time for 5 min audio | < 10 min | **No** |
| S0-4 | Thermal abort during training | none | **No** |

All four must pass. A device that fails any one does not get on-device adaptation,
and the enrolment-only path is the product — which E11 correctly notes is a
legitimate outcome, not a failure.

**None of these can be measured without a device and a working engine.**

---

## 4. What the JVM tests do and do not prove

The 281 passing tests prove that the platform-independent logic is correct. They do
**not** prove that the app works.

| Question | JVM tests answer it? |
|---|---|
| Does chunking preserve the text? | **Yes** |
| Does the reading position survive a process restart? | **No** — the persistence layer is Room, untested here |
| Does the overlay stay on screen? | **No** — needs a window manager |
| Does speech come out of the speaker? | **No** — no engine |
| Does the notification appear? | **No** — needs the Android runtime |
| Does export write a playable file? | **Partly** — the WAV writer is tested; the MediaStore path is not |

This table is the honest boundary of the evidence. A green test run is not a working
app, and the documentation must not let a reader conclude otherwise.

---

## 5. What a device validation run would require

### 5.1 Prerequisites

1. An Android SDK to build the APK.
2. A mid-tier physical device (E11 defines the tier).
3. A bundled model and a working inference runtime.
4. A signed debug build.

### 5.2 Procedure per gate

**G1 — installs and launches.** `adb install`, launch, confirm the reader screen
appears within 3 s, confirm no crash in `logcat`.

**G2 — text selection reaches the reader.** Select text in a third-party app, choose
Sonata from the selection menu, confirm the text appears in the reader and the
selection is not truncated.

**G3 — speech is produced.** Press play, confirm audio from the speaker, confirm the
first audio arrives within 2 s, confirm the words match the text.

**G4 — pause/resume preserves position.** Pause mid-sentence, force-stop the app,
relaunch, resume, confirm playback continues from the paused word and not from the
start.

**G5 — overlay survives backgrounding.** Open the floating player, switch to another
app, confirm the overlay remains, confirm the controls work, confirm the notification
is present.

**G6 — export produces a playable file.** Export a narration, locate the file, play
it in a third-party player, confirm duration and content match.

### 5.3 Measurement procedure for S0

Real-time factor: synthesise a fixed 60-second passage, measure wall-clock time,
divide. Peak RSS: `adb shell dumpsys meminfo` at peak. Training time: run the
adapter training on 5 minutes of audio, measure. Thermal: monitor
`/sys/class/thermal` during training.

---

## 6. Risk of shipping without device validation

| Risk | Likelihood | Impact |
|---|---|---|
| The app crashes on launch | Medium | Total |
| Speech produces no audio | **Certain** — no engine | Total |
| The overlay is killed by the OS | Medium | Feature loss |
| Position is lost on process death | Low — logic is tested | Annoyance |
| Export writes an unplayable file | Low — WAV writer tested | Feature loss |
| Thermal throttling during training | Unknown | Feature loss |

The second row is why this candidate cannot be called ready for production. It is
not a risk; it is a certainty.

---

## 7. Acceptance criteria

| # | Criterion | Status |
|---|---|---|
| D1 | APK builds from the candidate commit | **Not met** — no SDK |
| D2 | APK installs on a mid-tier device | **Not met** — no device |
| D3 | G1–G6 executed and passed | **Not met** |
| D4 | S0-1..S0-4 measured and passed | **Not met** |
| D5 | No crash in a 30-minute reading session | **Not met** |

All five are external gates. None can be closed in this environment.
