# E0 — Baseline and Boundary

Governing issue: `Arkadia-Oversoul-Prism/sonata#1` (bounded recursive evolution).
Pass: `feat/process-text-playback-hardening-01`.
Observation date: 2026-10-21.

## 1. Repository state (measured)

| Fact | Value |
|---|---|
| Repository | `https://github.com/Arkadia-Oversoul-Prism/sonata` |
| Default branch | `feature/sonata-tts-app` (`refs/remotes/origin/HEAD`); `main` does **not** exist |
| Branches on remote | 1 — `feature/sonata-tts-app` |
| Base SHA (branch point) | `4f7e6674caf2e8c9b12de4516ce3b25b68e65574` |
| Working branch | `feat/process-text-playback-hardening-01` |
| Worktree state at base | clean (`git status --porcelain` → 0 lines) |
| Total commits | 7 |
| Tracked files | 33 |
| Open pull requests | 0 |
| Open issues | 1 (`#1`, this pass) |
| CI runs on record | 8 — **all 8 `conclusion=failure`**, including the tip |

CI history (all failures, `Build Sonata APK`):

```
26593805713 workflow_dispatch failure 4f7e6674   <- tip
26593588327 workflow_dispatch failure 4f7e6674
26593290721 workflow_dispatch failure 4f7e6674
26578212396 push              failure 4f7e6674
26578142941 push              failure 58cd6800
26578052076 push              failure 62eac96e
26577959040 push              failure 8498ef33
26577778928 push              failure 14dabdbb
```

The tip run `26593805713` fails at job `78359298432` step 5
(`Build Release APK (unsigned)`); step 6 (`Upload APK artifact`) is `skipped`,
so no APK artifact has ever been produced by CI.

### Commit history

```
4f7e6674 fix(ci): fix gradlew JVM opts quoting
58cd6800 fix(ci): replace stub gradlew with proper Gradle wrapper script
62eac96e fix(ci): simplify workflow — unconditional build, upload unsigned APK
8498ef33 fix(ci): correct workflow paths + remove legacy Piper/Hilt/Compose source
14dabdbb feat(sonata): full Android TTS app — PROCESS_TEXT, glass overlay, foreground service, CI
b2eb16d0 Add debug APK
4d6c65e5 Initial commit: Sonata Android TTS app
```

The last four commits are attempts to repair CI. None succeeded; the defect is
not in the wrapper or the workflow (see §4).

## 2. Toolchain and reproducibility

The sandbox had **no JDK, Gradle, or Android SDK** on entry
(`java`, `javac`, `gradle`, `adb`, `sdkmanager`, `kotlinc` all absent;
`ANDROID_HOME`/`ANDROID_SDK_ROOT` unset). `apt-cache` on Debian trixie offers no
`openjdk-17-*` package.

A toolchain was installed to make the baseline a measurement rather than a claim:

| Component | Source | Version |
|---|---|---|
| JDK | Adoptium API, `latest/17/ga/linux/x64/jdk/hotspot` | Temurin `17.0.20.1+1` |
| Android SDK | `commandlinetools-linux-11076708_latest.zip` | cmdline-tools |
| Platform / build-tools | `sdkmanager` | `platforms;android-34`, `build-tools;34.0.0`, `platform-tools` |
| Gradle | wrapper `distributionUrl` | `8.4-bin` (downloaded by the wrapper) |

The JDK distribution and version match the CI workflow
(`actions/setup-java@v4`, `java-version: 17`, `distribution: temurin`), so the
local build is a faithful reproduction of the CI build environment.

## 3. Baseline build — FAILED (reproduced)

Command (base SHA, clean tree):

```
./gradlew assembleRelease --no-daemon --stacktrace
```

Result: **FAILED**, `GRADLE_EXIT=1`, `BUILD FAILED in 19s`.

```
* What went wrong:
Execution failed for task ':app:processReleaseResources'.
> A failure occurred while executing
  com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask$TaskAction
   > Android resource linking failed
     .../layout/activity_main.xml:136: error: attribute android:gap not found.
     .../layout/overlay_player.xml:68: error: attribute android:gap not found.
     error: failed linking file resources.
```

This is the same failure class as CI (task `:app:processReleaseResources`
aborts before any APK is produced). The local reproduction is a stronger claim
than the CI signal because it yields the message; CI job logs for these runs
have expired (`HTTP 410` from the job-logs endpoint), so the CI conclusion is
`failure` with no retrievable text.

### Root cause (definitive)

`android:gap` on a `LinearLayout` is a **library attribute**
(`app:gap` from `androidx.constraintlayout`), not a platform attribute. It
resolves only when the element is a `ConstraintLayout` descendant in a layout
that binds the `app` namespace for that attribute. Used as `android:gap` on a
plain `LinearLayout` it is unknown to the platform resource table, and AAPT2
rejects the layout. Both occurrences are identical in form:

- `app/src/main/res/layout/activity_main.xml:136` — `android:gap="12dp"` on the controls `LinearLayout`
- `app/src/main/res/layout/overlay_player.xml:68` — `android:gap="8dp"` on the controls `LinearLayout`

There are exactly two occurrences repository-wide
(`grep -rn "gap" app/src/main/res/layout/`).

## 4. What is *not* the cause (measured, so the next pass does not re-chase it)

- **The Gradle wrapper is sound.** `gradlew` execs
  `org.gradle.wrapper.GradleWrapperMain` from `gradle/wrapper/gradle-wrapper.jar`;
  the jar is a valid archive (56 entries, contains
  `org/gradle/wrapper/GradleWrapperMain.class`) and downloaded
  `gradle-8.4-bin.zip` successfully. The three `fix(ci)` wrapper commits were
  chasing a symptom: the resource link failure aborts the build regardless of
  how `gradlew` is invoked.
- **The workflow is sound for this failure.** It checks out, installs JDK 17,
  and runs `./gradlew assembleRelease --no-daemon`; all steps before the build
  are `success`.
- **The layouts' view IDs all resolve.** Every `binding.<id>` referenced in
  Kotlin exists in the corresponding layout (checked for `activity_main.xml`,
  `activity_settings.xml`, `overlay_player.xml`), so view-binding generation is
  not the blocker.
- `local.properties` is gitignored (`.gitignore:10`), so the SDK path used
  locally cannot leak into a commit.

## 5. Source inventory (exact SHAs)

Application source, `app/src/main/kotlin/com/arkadia/sonata/`:

| File | Lines | Role |
|---|---|---|
| `TtsEngine.kt` | 242 | Two-tier engine: `/api/tts` + `MediaPlayer`; Android `TextToSpeech` fallback |
| `SpeechService.kt` | 185 | Foreground service, `MediaSessionCompat`, notification, command handling |
| `ProcessTextActivity.kt` | 70 | `ACTION_PROCESS_TEXT` entry point |
| `MainActivity.kt` | 150 | Launcher UI, voice spinner, speed seek, service binding |
| `SettingsActivity.kt` | 86 | Speed, pitch, switches, backend URL, cache management |
| `FloatingOverlayService.kt` | 152 | Draggable `TYPE_APPLICATION_OVERLAY` player |
| `Prefs.kt` | 61 | `SharedPreferences` wrapper |
| `SonataApp.kt` | 30 | `Application`, notification channel |

There is **no test source set** — `git ls-files | grep -iE "test|spec"` → none,
and no `app/src/test` or `app/src/androidTest` directory exists. `app/build.gradle.kts`
declares no test dependencies (no JUnit, no Robolectric). The protocol requires
tests for the slice; a test dependency and a `test` source set must therefore be
introduced as part of the slice.

## 6. Capability matrix — Sonata at `4f7e6674`

Status vocabulary: implemented / partial / absent / broken / unknown.
"Broken" means present in source but unable to function.

| Capability | Status | Evidence |
|---|---|---|
| Build produces an APK | **broken** | `:app:processReleaseResources` fails on `android:gap` |
| `PROCESS_TEXT` registration | implemented | `AndroidManifest.xml` activity `.ProcessTextActivity`, `action.PROCESS_TEXT`, `mimeType text/plain` |
| `PROCESS_TEXT` empty-input handling | partial | `ProcessTextActivity.kt:28` null/empty → `finish()`; but the entry point `onCreate` is the only lifecycle hook, so a re-delivery while alive is not handled |
| Text-size limits | **absent** | no bound anywhere in the client; `TtsEngine.speak` forwards text unbounded |
| Online playback (`/api/tts`) | partial | `TtsEngine.kt:154-191` POST + `MediaPlayer`; see contract gaps in §7 |
| Offline fallback (Android TTS) | partial | `TtsEngine.kt:214-241`; correctness defects in §8 |
| True pause/resume — online | implemented | `MediaPlayer.pause()` / `.start()` |
| True pause/resume — offline | **broken** | `pause()` calls `androidTts.stop()` (aborts the utterance); `resume()` only fires `onResumed()` without re-speaking — a restart is not a resume, and here nothing is restarted at all |
| Stop | implemented | `TtsEngine.stop()` resets + releases `MediaPlayer`, stops Android TTS |
| Rapid/repeated requests | **broken** | `speak()` calls `stop()` but does not cancel the in-flight coroutine; a superseded request still writes a temp file and calls `playFile`, clobbering the new playback |
| In-flight request cancellation | **absent** | no `Job` handle retained; `scope = CoroutineScope(Dispatchers.IO + Job())` only |
| Temp-file cleanup on success | implemented | `playFile` completion + error listeners delete the file |
| Temp-file cleanup on supersession / stop / cancel | **broken** | `stop()` does not delete the temp file; a superseded or stopped request leaks it in `cacheDir` |
| HTTP status validation | partial | `resp.isSuccessful` checked; non-2xx → `RuntimeException("HTTP <code>")`, body not read, no `resp.close()` |
| Malformed/empty audio validation | **absent** | bytes are written and handed to `MediaPlayer` with no check; zero-byte body is written as a 0-byte file |
| Timeouts | partial | connect 8 s, read 30 s; no `callTimeout`, no write timeout |
| Response/connection release | **absent** | `Response` is never closed (`.use {}` or `close()`), so connections are not returned to the pool |
| Error feedback to user | **broken** | `SpeechService.ttsListener.onError` swallows the message and `stopSelf()`s; nothing is shown |
| Engine truthfulness | partial | the app never surfaces which engine produced the audio; `X-TTS-Engine` response header is ignored |
| Speed/voice preference preservation | partial | both are read at request time; `Prefs.speed` bounds are unenforced and the overlay's speed stepper can index out of range (§8) |
| Backend contract verification before send | **absent** | no `/api/tts/status` probe; the app assumes the endpoint shape |
| Voice-capture / STT | **absent** | no microphone permission, no recorder, no transcription call |
| Overlay permission flow | partial | `Settings.canDrawOverlays` + settings intent; `ProcessTextActivity` also starts the overlay when permitted |

## 7. Arkadia backend contract (source-backed)

Inspected in `Arkadia-Oversoul-Prism/Arkadia` at
`04e1b83b89e51a994af68b529a1076665f9b3ae5` (read-only; that repository was not modified).

`POST /api/tts` — `api/main.py:2128`:

- Request body (JSON): `text` (string, required), `voice` (string, default `"aria"`),
  `speed` (number, default `1.0`, clamped server-side to `[0.5, 2.0]`).
- `text` is stripped; empty → `400 {"detail":"text is required"}`.
- Malformed JSON → `400 {"detail":"Invalid JSON body"}`.
- Success → raw audio bytes, `media_type` from the kernel (`audio/mpeg` for
  ElevenLabs and Edge TTS, `audio/wav` for Piper), headers
  `Cache-Control: public, max-age=3600` and `X-TTS-Engine: <engine>`.
- Synthesis failure → `502 {"detail":"TTS synthesis failed: ..."}`.
- No authentication and no API key are required on this route.
- Server-side text ceiling: `kernel/tts.py:314` — `text.strip()[:5000]`, i.e. the
  backend silently truncates beyond 5000 characters.
- Engine priority (`kernel/tts.py::synthesize`): ElevenLabs (only if a key is
  configured) → Edge TTS (free, no key) → Piper. The response can therefore be
  `audio/mpeg` or `audio/wav` depending on engine.

`GET /api/tts/voices` — `api/main.py:2189`: `{"voices": {...}, "default": "aria"}`.
`GET /api/tts/status` — `api/main.py:2196`: engine, `elevenlabs_active`,
`edge_tts_active`, `ready`, voice keys, default, key-pool counts.

### Live contract observation (read-only)

Canonical origin `https://arkadia-qzu4.onrender.com`, observed 2026-10-21:

- `GET /api/version` → `200`, `source_revision: 04e1b83b89e51a994af68b529a1076665f9b3ae5`,
  `revision_conflict: false` — the runtime matches the inspected source revision.
- `GET /api/tts/status` → `200`,
  `{"engine":"edge_tts","elevenlabs_active":false,"edge_tts_active":true,"ready":true,
  "voices":["aetheria","aria","jenny","sonia","christopher","george","ryan"],
  "default":"aria","elevenlabs_voices":false,"key_pool":{"total":0,"available":0,"quota_hit":0},
  "preferred_engine":"edge_tts"}`
- `GET /api/tts/voices` → `200`, the seven voices above.

Two consequences for Sonata, both source-backed:

1. The backend default voice is `aria`, and Sonata's `Prefs.voice` default is
   also `aria` — but Sonata's spinner offers `aria, jenny, sonia, christopher,
   george, ryan` and **omits `aetheria`**, the dedicated Oracle voice. Voice
   catalogue drift between client and backend.
2. Sonata's `TtsEngine` sets Android TTS pitch from `prefs.pitch` but sends no
   pitch for online synthesis; the backend has no pitch parameter. This is a
   real capability gap, not a defect to hide.

## 8. Correctness defects found by source inspection (for E1/E2 scoping)

Ranked by user-visible impact. These are the slice candidates.

1. **Build cannot produce an APK** — `android:gap` (blocker; nothing is testable
   or installable until fixed).
2. **Offline "resume" is not a resume** — `TtsEngine.pause()` in `ANDROID_TTS`
   mode calls `stop()`, destroying the utterance; `resume()` reports success
   (`onResumed()`) without speaking. The UI then shows "Resume" with nothing to
   resume. This is precisely the misrepresentation the acceptance criteria forbid.
3. **Rapid/repeated requests overlap** — no in-flight cancellation; a superseded
   `speakEdge` coroutine can call `playFile` after a newer `speak()`, so the
   older audio starts (or replaces the newer `mediaPlayer`) and stale temp files
   accumulate.
4. **Temp files leak** on supersession, `stop()`, and service teardown —
   `stop()` releases `MediaPlayer` without deleting its file.
5. **No empty/malformed-audio validation** — a `200` with a zero-byte or
   non-audio body is handed to `MediaPlayer`, producing an opaque
   `MediaPlayer error` rather than a fallback.
6. **`Response` never closed** — connection leak under repeated use.
7. **Errors are invisible to the user** — `onError` swallows the message and
   stops the service.
8. **Overlay speed stepper can throw** — `FloatingOverlayService.kt:134`:
   `speeds[(speeds.indexOf(prefs.speed).takeIf { it >= 0 } ?: 1) + 1 % speeds.size]`.
   `%` binds tighter than `+`, so the index is `index + 1` unclamped; at the last
   speed (`2.0f`, index 4) the index is 5 → `IndexOutOfBoundsException`. It also
   never persists the `1.5f` step boundary correctly for unlisted speeds.
9. **Text size unbounded client-side** — the backend truncates at 5000 chars
   silently; the client neither limits nor informs.
10. **`preferEdgeTts` is not honoured for fallback** — when the network path
    fails, the engine silently switches to Android TTS, but the UI cannot tell
    the user which engine is speaking.

## 9. Boundary: two Sonata substrates exist (recorded, not resolved here)

A second Android substrate is tracked in the Arkadia repository at
`sonata-android/` (55 files) carrying `MieActivity.kt`, `MieAudioRecorder.kt`,
`MieMusicalInterpreter.kt`, `OracleFragment.kt`, `KnowledgeActivity.kt`,
`ArkadiaRepository.kt`, and its own `TtsEngine.kt` / `SpeechService.kt` /
`ProcessTextActivity.kt`. `musical-intention-engine/BUILD-STATE.md` describes
that tree as "the existing Android application substrate".

This pass treats **`Arkadia-Oversoul-Prism/sonata` as the repository in scope**
because the governing issue names it explicitly. The relationship between the
two trees (migration source, parallel lineage, or supersession) is **not
established by this pass** and is recorded as an open boundary. No file in
`sonata-android/` was read for behaviour beyond this inventory, and nothing in
Arkadia was modified.

Consequence for the mission's later slices: MIE and N-ATLAS STT capabilities are
projected in `sonata-android/`, not in this repository. Slice E4-2 (MIE/N-ATLAS
STT) therefore depends on a substrate decision that this pass does not have the
authority to make.

## 10. Related Arkadia contracts (source-backed, for later slices)

- **STT/ASR**: Arkadia has a canonical ASR provider interface —
  `solspire/voice_asr.py` with `TestProvider`, `LocalProvider`, `NAtlasProvider`,
  `CloudProvider`, env keys `ENV_NATLAS_KEY`, `ENV_NATLAS_MODEL`,
  `ENV_NATLAS_URL`, `ENV_PROVIDER_SELECT`, and `DEFAULT_PRIORITY`
  (`tests/test_voice_asr.py`). N-ATLAS requires official configuration and
  selection never silently substitutes a provider.
- **Voice intent boundary**: `lab/engineering_lab/voice.py` (EL-07) resolves
  voice into explicit intents; `MERGE_PR` and `DEPLOY_PRODUCTION` are
  human-only and never dispatched by voice; an unconfigured recognizer reports
  `UNCONFIGURED` and invents nothing.
- **N-ATLAS routes**: `api/lab_routes.py:375-546` —
  `/api/lab/engineering/n-atlas/{catalog,test-session,run}`; `run` requires auth
  and returns `503` with a truthful `state`/`detail` when the provider is
  unconfigured.
- **No STT endpoint on the Sonata-facing surface.** There is no `/api/voice/*`
  route; transcription contracts live behind the Lab/N-ATLAS and SolSpire ASR
  interfaces above. Any STT slice must not fabricate a Sonata endpoint.

## 11. Blockers and explicit non-claims

- **Device/emulator: unavailable.** No `adb`, no emulator image, no physical
  device. Therefore `PROCESS_TEXT` intent delivery, interruption behaviour,
  background playback, overlay rendering, and offline fallback are **unverified
  on-device** by this pass and are listed as such rather than claimed.
- **CI job logs for the recorded failures have expired** (`HTTP 410`), so the
  historical failure text is not retrievable; the local reproduction supplies it.
- **No release, deploy, merge, or configuration change** was performed. The
  canonical Arkadia deployment was only read.
- `Vercel`/deployment questions are out of scope for this repository.

## 12. E1 entry point

The baseline is established and the blocker is unambiguous. The first bounded
vertical slice is therefore:

- **S1** — restore a building APK (the `android:gap` defect) so any behaviour can
  be verified at all; then
- **S2** — `PROCESS_TEXT` → robust speech playback hardening per §8, with a real
  test source set.

Both are inside the issue's stated priority. Nothing beyond them is in scope for
this pass.
