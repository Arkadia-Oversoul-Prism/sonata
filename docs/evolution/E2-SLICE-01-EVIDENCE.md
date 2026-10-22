# E2 · Slice 01 — Build restoration and playback hardening

**Protocol:** `Arkadia-Oversoul-Prism/sonata#1` (bounded recursive evolution)
**Branch:** `feat/process-text-playback-hardening-01`
**Base:** `4f7e6674caf2e8c9b12de4516ce3b25b68e65574` (default branch `feature/sonata-tts-app`)
**Scope:** make the repository produce an APK at all, then harden the text→speech
path against the defects found in `TtsEngine`/`SpeechService`.

This slice claims **no replacement** of any existing user workflow. It establishes a
building, tested substrate. Acceptance evidence for replacement is a later slice.

---

## 1. Baseline (measured, not inherited)

| Item | Observation |
|---|---|
| Default branch | `feature/sonata-tts-app` (only branch) |
| Base SHA | `4f7e6674` |
| Open PRs | 0 |
| Open issues | 1 (`#1`, the governing protocol) |
| Test source sets | **none** |
| CI runs on record | **8, all failed** |
| Job logs | expired (HTTP 410); annotations empty |
| `./gradlew assembleRelease` at base | **BUILD FAILED** |

The three preceding `fix(ci)` commits (`4f7e6674` and its two parents) targeted the
Gradle wrapper. The wrapper was not the cause; the tree had never compiled.

Toolchain used, matching CI: Temurin JDK 17, Android SDK
(`compileSdk`/`build-tools` as declared in `app/build.gradle.kts`), Gradle 8.4.

## 2. Root causes

### RC-1 — `android:gap` is not a platform attribute
`activity_main.xml:136` and `overlay_player.xml:68` set `android:gap` on a
`LinearLayout`. `gap` is a ConstraintLayout **library** attribute, not an
`android:` platform one, so AAPT2 rejected both layouts:

```
error: attribute android:gap not found.
:app:processReleaseResources FAILED
```

This is the first failure in the chain and it hid RC-2 entirely.

**Repair:** replaced with equivalent per-child `layout_marginStart`/`layout_marginEnd`
so the intended spacing is preserved rather than dropped.

### RC-2 — `MediaSessionCompat` imported from a package that does not exist
`SpeechService.kt` imported `androidx.media.session.MediaSessionCompat` and
`PlaybackStateCompat`. In `androidx.media:media:1.7.0` those classes ship under
`android.support.v4.media.session`; there is no `androidx.media.session` package.

Verified against the resolved artifact:

```
$ javap -classpath media-classes.jar android.support.v4.media.session.MediaSessionCompat
public boolean isActive();          # a Java method, exposed in Kotlin as the isActive property
$ javap -classpath media-classes.jar 'androidx.media.app.NotificationCompat$MediaStyle'
public androidx.media.app.NotificationCompat$MediaStyle setMediaSession(...Token);
```

`MediaStyle` genuinely does live at `androidx.media.app` in the same artifact, which is
why the failure presented as a dependency-resolution problem when the dependency was
present and correct.

**Repair:** corrected the two imports. `isActive = true` remains valid — it is the
Kotlin property view of the `isActive()`/`setActive()` pair.

### RC-3 — `isActive` reported as unresolved
A downstream symptom of RC-2: the receiver's type was unresolved, so member access on
it could not resolve either. No separate change was required.

## 3. Defects found and repaired in the playback path

Each was found by reading the code against the backend contract, and each is now
pinned by a test that fails without the repair.

| # | Defect | Consequence | Repair |
|---|---|---|---|
| D-1 | Overlay speed stepper computed `index + 1 % size` (`%` binds tighter than `+`) | Stepping from 2.0× threw `IndexOutOfBoundsException` | `SpeedSteps.next` wraps correctly |
| D-2 | An empty/whitespace `SPEAK` promoted the foreground notification, then returned early | Live service + notification, no work | `SpeechCommand.resolve` refuses it before promotion |
| D-3 | `OkHttp` `Response` never closed | Connection leak per utterance | `response.use { … }` |
| D-4 | Temp file not deleted on completion, error, supersession, `stop` or `shutdown` | Unbounded cache growth | Single owner deletes on every exit path |
| D-5 | A superseded request could still start playback and overwrite the new utterance's state | Two utterances overlapping; wrong text | Generation token + `Job` cancellation; a stale result is dropped |
| D-6 | No client-side bound on request text | Backend silently truncates at 5000 chars | `TextLimits` truncates to the same bound **and reports it** |
| D-7 | Online failure fell back to offline speech silently | User hears a robotic voice believing it is the neural one | Degradation reported via `onError(…, fellBackTo=OFFLINE)` |
| D-8 | `fallBackTo` claimed even when the offline engine was unavailable | Reported a fallback that could not occur | Reported as an error with no fallback |
| D-9 | Offline resume never reported `onResumed` | UI could not reflect a resumed utterance | Reported |
| D-10 | A late `onRange` from a stopped utterance could overwrite the resume point | Resume re-spoke from the wrong character | Progress accepted only while the current utterance is live |
| D-11 | `ProcessTextActivity` ignored `EXTRA_PROCESS_TEXT_READONLY` and had no `onNewIntent` | Some sources could not be read; re-delivery mishandled | Both variants accepted; `onNewIntent` handled |
| D-12 | `app/build/` was not ignored (the root `/build` rule does not cover the module) | Build artifacts stageable | Ignored |
| D-13 | Engine cancelled an **injected** scope on `shutdown` | Tore down the caller's scope | Only a self-created scope is cancelled |

### Honest resume semantics
The platform `TextToSpeech` engine has no pause primitive. The engine therefore:

- **Online:** `MediaPlayer` pause/resume continues the same decoded stream — a real
  sample-accurate resume.
- **Offline:** pause stops the utterance and records the character reached via
  `UtteranceProgressListener.onRangeStart` (API 26+; `minSdk` is 29). Resume
  re-speaks the remainder from that character.

The offline case is a genuine **content** resume and is explicitly a **re-synthesis
seam**, not sample-accurate continuation. `PlaybackState` keeps the two positions
distinct (`positionMs` vs `charOffset`) rather than presenting one number as both, and
the KDoc says so. The UI must not imply sample-accurate offline resume.

## 4. Verification

Command:

```
./gradlew testDebugUnitTest assembleRelease --no-daemon
```

Result: **BUILD SUCCESSFUL**

| Suite | Tests | Failures | Errors |
|---|---|---|---|
| `TtsEngineOnlineTest` | 25 | 0 | 0 |
| `TtsEngineOfflineTest` | 12 | 0 | 0 |
| `PlaybackStateTest` | 10 | 0 | 0 |
| `SpeechCommandTest` | 8 | 0 | 0 |
| `SpeedStepsTest` | 7 | 0 | 0 |
| `TextLimitsTest` | 7 | 0 | 0 |
| **Total** | **69** | **0** | **0** |

Artifact:

```
app/build/outputs/apk/release/app-release-unsigned.apk
3,089,987 bytes
sha256 fdc39f7f9735211f6817a43fcd830e5e65aac3a3ae059d7209b16b51dbba0357
```

This is the **first APK this repository has produced**.

### How the tests are real
`TtsEngine`'s collaborators are injected (`TtsTransport`, `AudioPlayer`,
`OfflineVoice`, `TtsSettings`, `CoroutineDispatcher`). The tests exercise the engine's
own logic — validation, supersession, fallback, cleanup, resume — through hand-written
fakes that record what the engine actually did. They are not mock-interaction tests and
no Android framework behaviour is simulated: `isReturnDefaultValues` only stubs
`android.util.Log` so the JVM test task can run.

Two test-harness errors were found and corrected during the work, and both were
the-harness-not-the-code: a `TestScope` with its own scheduler (so `advanceUntilIdle()`
drove nothing) and a `stopCalls` assertion that ignored the stop performed by
supersession. The four genuine engine defects those runs exposed (D-7, D-8, D-9, D-10)
are listed above.

## 5. Not verified / not claimed

- **No device or emulator run.** Nothing here is evidence of runtime behaviour on
  Android. The APK is unsigned and was never installed.
- **No live backend call.** `POST /api/tts` is implemented against the contract read
  from Arkadia `api/main.py:2128` and `kernel/tts.py`; it has not been exercised
  against a running Arkadia deployment in this slice.
- **No CI green claim.** CI has not been re-run; the 8 recorded failures are all at
  revisions before this branch.
- **No replacement claim.** No user workflow (ElevenLabs, Eleven Reader, voice
  capture, STT) is claimed replaced. The STT boundary
  (`lab/engineering_lab/voice.py`) was read, not integrated.
- **No performance claim.** The `< 300ms` latency comment in the original
  `ProcessTextActivity` was aspirational and has been removed rather than restated;
  no latency was measured.

## 6. Next permitted action

The next slice should add acceptance evidence **before** any replacement claim, in
this order:

1. Instrumented (device/emulator) verification that a `PROCESS_TEXT` selection speaks,
   pauses, resumes and stops — the current evidence is JVM-only.
2. A live call against a running Arkadia backend to confirm the `POST /api/tts`
   contract, including the `X-TTS-Engine` header and the offline fallback on failure.
3. Only then: STT / voice capture, which is untouched here.

Human authority remains final. No merge, deployment, release or production change is
performed or implied by this slice.
