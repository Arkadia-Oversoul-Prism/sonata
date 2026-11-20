# SONATA_PRIVACY_AND_SECURITY

**Status:** Stage 7b production candidate
**Candidate commit:** `a102db4f01b04f95214216c3118545d4030cbb02`
**Date:** 2026-11-20

---

## 1. The promise

Sonata reads the user's text aloud without that text, or the user's voice, ever
leaving the device. This is the product's entire privacy claim, and it is the reason
the app is offline.

The claim is only worth something if it is **checkable**. This document records what
is checked, how, and what the checks found.

---

## 2. What the checks found

**The privacy policy was being violated by the shipped manifest, and the test that
was supposed to catch it was passing.**

When `R11` was pointed at the real `AndroidManifest.xml` in Stage 7b, it failed:

```
manifest policy failures: [NO_INTERNET, RECORD_AUDIO_PRESENT, BACKUP_DISABLED, BACKUP_EXCLUSIONS]
```

| Finding | What it meant |
|---|---|
| `NO_INTERNET` | `android.permission.INTERNET` was declared — the app could reach the network |
| `RECORD_AUDIO_PRESENT` | `RECORD_AUDIO` was required by the audit but no recording code exists |
| `BACKUP_DISABLED` | `android:allowBackup="true"` — user documents and voice samples could leave via backup |
| `BACKUP_EXCLUSIONS` | backup was enabled with no exclusion rules |

All four are fixed in this candidate. The fact that they were live is recorded here
because a privacy report that only lists what is now correct is not a report.

---

## 3. Current state of the manifest

| Attribute | Value | Check |
|---|---|---|
| `android.permission.INTERNET` | **absent** | `NO_INTERNET` |
| `android.permission.ACCESS_NETWORK_STATE` | **absent** | `NO_ACCESS_NETWORK_STATE` |
| `android.permission.RECORD_AUDIO` | **absent** (no recording code) | `RECORD_AUDIO_MATCHES_CAPABILITY` |
| `android.permission.WRITE_EXTERNAL_STORAGE` | **absent** | `NO_BROAD_STORAGE` |
| `android.permission.READ_EXTERNAL_STORAGE` | **absent** | `NO_BROAD_STORAGE` |
| `android:allowBackup` | `false` | `BACKUP_DISABLED` |
| `android:fullBackupContent` | `false` | — |
| `android:dataExtractionRules` | `@xml/data_extraction_rules` | `BACKUP_RULES_EXCLUDE_PRIVATE_DATA` |
| `android:usesCleartextTraffic` | `false` | `NO_CLEARTEXT` |

### The debug overlay, and why it is not a loophole

`app/src/debug/AndroidManifest.xml` declares `android.permission.INTERNET`. This
is deliberate and it does not weaken the promise, but it needs stating plainly
rather than being discovered by a reviewer.

**Why it exists.** Android gates *every* socket on `INTERNET`, including a
loopback connection to `127.0.0.1`. The two MockWebServer device suites
(`DeviceProgressiveReadProtocolTest`, `DeviceTransportProtocolTest`) start a local
server and drive the real OkHttp stack against it. With `INTERNET` removed from
the main manifest, those suites failed in `setUp` — 17 tests, all with
`lateinit property transport has not been initialized`, because the socket could
not be opened. The failure had nothing to do with the behaviour under test.

**Why it is safe.**

| Fact | How it is enforced |
|---|---|
| The permission is **not** in the release APK | It is declared in the `debug` source set only; `app/src/main/AndroidManifest.xml` has no `INTERNET` |
| `R11` still guards the shipped manifest | It reads `app/src/main/AndroidManifest.xml` and fails the build if `INTERNET` appears there |
| The release build is what ships | `assembleRelease` / `bundleRelease` do not merge the `debug` source set |

**What a reviewer should check.** Unzip the release APK and confirm the manifest
declares no `INTERNET`. That is the artifact that matters; the debug overlay is
test scaffolding.

### Permissions that remain, and why

| Permission | Why it is needed |
|---|---|
| `FOREGROUND_SERVICE` | Playback must continue when the app is backgrounded |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | The declared type for the playback service |
| `FOREGROUND_SERVICE_SPECIAL_USE` | The floating overlay is a persistent control surface |
| `SYSTEM_ALERT_WINDOW` | The floating player draws over other apps |
| `POST_NOTIFICATIONS` | Without it the playback notification, and every transport control, is invisible on API 33+ |

None of these grants network access, storage access, or microphone access.

---

## 4. Backup exclusions

`app/src/main/res/xml/data_extraction_rules.xml` excludes, from both cloud backup
and device transfer:

- `sonata.db`, `sonata.db-shm`, `sonata.db-wal` — the document database
- `voice_samples` — recorded voice samples
- `voice_adapters` — trained adapters
- `exports` — exported audio
- `sonata_prefs.xml` — preferences

`allowBackup` is already `false`, so nothing is backed up at all. The rules are
defence in depth: if a future change re-enables backup, the private data still does
not leave.

---

## 5. Runtime offline evidence

A manifest claim is a claim about a file. The runtime claim is separate: does the
process actually attempt a connection?

`PrivacyTest` runs the narration pipeline with a socket sentinel armed and asserts
that no attempt is made. `OfflineAudit` records every attempt with host and port.

### The honest limitation of this evidence

Stage 7a correctly observed that `NetworkSentinel.attempt` is called only from
tests, so "zero attempts" is partly guaranteed by construction: if nothing calls the
sentinel, nothing is recorded. The genuinely independent line is the `unshare -n`
namespace run, which removes the network at the OS level and shows the pipeline
still completes.

Both are reported. Neither is presented as stronger than it is.

---

## 6. Data inventory

| Data | Where it lives | Leaves the device? | Deletable by user? |
|---|---|---|---|
| Text the user reads | Room database, app-private | **No** | Yes |
| Reading position | Room database | **No** | Yes |
| Voice samples | App-private files | **No** | Yes |
| Trained adapters | App-private files | **No** | Yes |
| Exported audio | User-chosen location via SAF | Only where the user puts it | Yes |
| Preferences | SharedPreferences | **No** | Yes |
| Crash reports | **None collected** | — | — |
| Analytics | **None collected** | — | — |

There is no account, no identifier, and no server. There is nothing to correlate.

---

## 7. Security posture

| Concern | Position |
|---|---|
| Network attack surface | **None** — no `INTERNET` permission |
| Cleartext traffic | Disabled |
| Exported components | `MainActivity` (launcher) and `ProcessTextActivity` (text share) only; both are intended entry points |
| Services | `SpeechService` and `FloatingOverlayService` are `exported="false"` |
| Secrets in the repo | None — no API keys, no keystore, no signing material |
| Third-party SDKs | AndroidX, Room, Media3, OkHttp, coroutines. No analytics, no ads, no crash reporting |
| Dependency vulnerabilities | **Not scanned** — no scanner run in this environment |

### OkHttp is present but unreachable

`OkHttpTtsTransport` exists in the tree. With `INTERNET` removed, any socket it
opens fails. It is dead code in the shipped build.

This is recorded as a **limitation**, not a feature: dead code that opens sockets is
a liability, and it should be deleted once the offline engine exists. It is not
deleted now because the offline engine does not exist, and removing the only
transport would leave the app with no synthesis path at all — which it already
effectively has, but the removal should happen with the replacement, not before it.

---

## 8. What is not verified

| Claim | Why it is unverified |
|---|---|
| The app makes no network call at runtime on a device | No device; the JVM sentinel is not a device |
| Backup exclusions work on a real Android version | No device; the rules are syntactically valid but unexecuted |
| No data leaks through a third-party library | No dependency scan run |
| The exported activities cannot be abused | No penetration test |
| Voice samples are deleted when a voice is deleted | No device; the code path is not exercised |

Each of these is a real gap. None is closed by the tests that do pass.

---

## 9. Privacy acceptance criteria

| # | Criterion | Status |
|---|---|---|
| P1 | No `INTERNET` permission | **Met** — verified against the real manifest |
| P2 | No `ACCESS_NETWORK_STATE` | **Met** |
| P3 | Backup disabled with exclusions | **Met** |
| P4 | No cleartext traffic | **Met** |
| P5 | No permission the app cannot use | **Met** |
| P6 | Pipeline makes no network attempt (JVM) | **Met** |
| P7 | Pipeline makes no network attempt (device, network namespace) | **Not met** — no device |
| P8 | No analytics or telemetry SDK | **Met** — by inspection |
| P9 | Dependency vulnerability scan clean | **Not met** — not run |
| P10 | Voice data deletable end-to-end | **Not met** — no device |

P1–P6, P8 are met and checkable. P7, P9, P10 are external gates.
