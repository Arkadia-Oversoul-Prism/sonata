# E1 — Change Plan (first vertical slice)

Governing issue: `Arkadia-Oversoul-Prism/sonata#1`.
Base SHA: `4f7e6674caf2e8c9b12de4516ce3b25b68e65574`.
Branch: `feat/process-text-playback-hardening-01`.

## Objective

Make `PROCESS_TEXT → robust speech playback` a verified, honest capability:
the app must build, must not crash or overlap on empty/repeated/rapid input, must
clean up temporary audio on every exit path, must fall back safely on network
failure / non-success HTTP / malformed or empty audio / timeout, and must report
pause/resume/stop truthfully per engine.

## Scope

**In scope** (the issue's stated priority):

- S1 Restore a building APK (`android:gap` resource-link failure).
- S2 `PROCESS_TEXT` input validation: empty, whitespace-only, oversized.
- S3 Supersession: a new speak request cancels the in-flight request and cannot
  be clobbered by it; no overlapping playback.
- S4 HTTP hygiene: status validation, response body closed, `callTimeout`,
  content-type check, empty-body check.
- S5 Temporary-audio lifecycle: cleaned on completion, error, cancellation,
  supersession, stop, and shutdown.
- S6 Honest pause/resume/stop per engine, with the offline path resuming from the
  last spoken position rather than claiming a sample-accurate resume.
- S7 Truthful user-visible status/error feedback, including which engine spoke.
- S8 A real test source set with unit tests for the pure and injectable surfaces.

**Explicitly out of scope** (proposed in E4, not built here):

- Long-form reader, queue/chunking, bookmarks, audio library/export.
- Speech-to-text / microphone capture / N-ATLAS transcription.
- Neural Voice Studio, voice catalogue expansion, provider adapters.
- Arkadia voice-agent interaction.
- Release/migration audit against ElevenLabs / Eleven Reader.
- Any change to the Arkadia repository, any deploy, merge, release, or
  production configuration change.
- The `sonata-android/` substrate question (E0 §9) — not resolved here.

## Ranked risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| 1 | Testability refactor changes playback behaviour | medium | high | Introduce seams behind interfaces with the existing Android implementations preserved; keep `MediaPlayer`/`TextToSpeech` calls behaviourally identical where they already work; verify with a release build. |
| 2 | `android:gap` removal changes layout spacing | high | low | Replace with equivalent per-child margins so spacing is preserved. |
| 3 | Position-based offline resume is audibly imperfect (re-synthesis seam) | high | low | Label it truthfully in the UI/notification as resuming from the last spoken position; document the seam. |
| 4 | New test dependencies fail to resolve in CI | low | high | Use JUnit 4 + a plain JVM `test` source set with no Android framework dependency in the tested code; verify by running the tests locally before committing. |
| 5 | Supersession cancellation deletes a file still in use | low | medium | Track ownership: only the owning request deletes its file; `stop()` deletes the active file after releasing the player. |
| 6 | Scope creep into STT or the second substrate | medium | high | Enforced by the exclusion list above; no microphone permission is added. |

## Dependencies

- S2–S7 all depend on **S1** (nothing is installable or verifiable until the
  resource link succeeds).
- S5/S6 depend on S3 (a single-owner request model is what makes cleanup and
  resume deterministic).
- S8 depends on the seams introduced for S3–S6.

## Acceptance tests

| Test | Type | Asserts |
|---|---|---|
| `TextLimitsTest` | unit (pure) | empty/whitespace → EMPTY; > 5000 chars → TOO_LONG with truncation to the backend ceiling; normal text normalises whitespace and is preserved |
| `PlaybackStateTest` | unit (pure) | pause on a non-playing state is rejected; online pause → PAUSED (resumable); offline pause → PAUSED with a resume offset; stop clears offset; supersession resets to a fresh request |
| `SpeedStepsTest` | unit (pure) | stepping from the maximum wraps instead of throwing (regression for the `+1 % size` defect) |
| `SpeechCommandTest` | unit (pure) | action mapping, including SPEAK with missing/empty text → NONE |
| `TtsEngineOnlineTest` | unit (fakes) | success plays and cleans up; HTTP 500 → offline fallback; empty body → fallback; non-audio content-type → fallback; transport exception (timeout) → fallback; supersession cancels the first request and never plays it; `stop()` deletes the temp file |
| `TtsEngineOfflineTest` | unit (fakes) | pause records the spoken position; resume speaks the remainder; stop aborts |

## Expected files

New:

- `app/src/main/kotlin/com/arkadia/sonata/TextLimits.kt`
- `app/src/main/kotlin/com/arkadia/sonata/PlaybackState.kt`
- `app/src/main/kotlin/com/arkadia/sonata/SpeedSteps.kt`
- `app/src/main/kotlin/com/arkadia/sonata/SpeechCommand.kt`
- `app/src/main/kotlin/com/arkadia/sonata/TtsTransport.kt`
- `app/src/main/kotlin/com/arkadia/sonata/AudioPlayer.kt`
- `app/src/main/kotlin/com/arkadia/sonata/OfflineVoice.kt`
- `app/src/test/kotlin/com/arkadia/sonata/*Test.kt`
- `docs/evolution/E0-BASELINE.md`, `E1-PLAN.md`, `E3-VERIFICATION.md`

Modified:

- `app/src/main/kotlin/com/arkadia/sonata/TtsEngine.kt`
- `app/src/main/kotlin/com/arkadia/sonata/SpeechService.kt`
- `app/src/main/kotlin/com/arkadia/sonata/ProcessTextActivity.kt`
- `app/src/main/kotlin/com/arkadia/sonata/FloatingOverlayService.kt`
- `app/src/main/kotlin/com/arkadia/sonata/MainActivity.kt`
- `app/src/main/res/layout/activity_main.xml`, `overlay_player.xml`
- `app/build.gradle.kts`, `gradle/libs.versions.toml`
- `.github/workflows/build-apk.yml` (add a test step before the build)

## Rollback

Single branch, narrow commits, no history rewrite. Rollback is
`git checkout 4f7e6674` on the base branch; the base branch is never touched.
Each commit is independently revertible; the S1 layout fix is separable from the
S2–S8 behaviour work.

## Commit plan (narrow commits)

1. `fix(build)` — S1: resource-link failure.
2. `test(unit)` — S8: test source set + dependencies + pure-helper tests.
3. `refactor(tts)` — S3–S7: engine seams and hardening.
4. `fix(overlay)` — the speed-stepper crash.
5. `ci` — run unit tests before the APK build.
6. `docs` — E3 verification record.

## Stop conditions

Stop and report at the boundary (do not proceed) if:

- the toolchain cannot build or test the tree;
- a change would require a new permission, a new external provider, or an
  Arkadia-side change;
- a slice cannot be verified by an available check.
