# Sonata — Session Handoff

**Recorded:** 2026-10-22 (latest run)
**Repository:** `Arkadia-Oversoul-Prism/sonata`
**Default branch:** `feature/sonata-tts-app` (unchanged)
**Working branch for this run:** `feat/sonata-ux-and-playback-overhaul`

---

# 0. Latest run — immediate-start playback, floating player, UX overhaul

**Base:** `8a12970624e02f1d6de27a4f9e3f6da90ca27d4e` (default branch, unchanged)
**Branch:** `feat/sonata-ux-and-playback-overhaul`
**HEAD at time of writing:** `b0fdf70bdcb539680e69e8429a4cdead37596fbd`
**Pull request:** [#4](https://github.com/Arkadia-Oversoul-Prism/sonata/pull/4) (draft)

Commits on this branch, oldest first:

| Commit | What it does |
| --- | --- |
| `05e3ee1b` | Progressive segmented synthesis — the latency and length-limit fix |
| `b93d2e0f` | Designed reading screen; three floating-player defects; callback list |
| `55c732f` | Mid-passage fallback dropped the rest of the passage (test-found) |
| `28515d2` | Device-protocol suite for the progressive read over real HTTP |
| `aac7311` | Handoff starts the read immediately; share filter declared |
| `d68d38a1` | Speed label describes the pace, not the navigation |
| `0d2724cd` | Mini-player no longer covers the end of the passage |
| `a92c02ed` | Handoff records the final SHA and the PR |
| `b29b1e66`, `b0fdf70b` | `.openhands/` ignored so agent notes cannot land in a feature branch |
**Branch:** https://github.com/Arkadia-Oversoul-Prism/sonata/tree/feat/sonata-ux-and-playback-overhaul
**Diff:** https://github.com/Arkadia-Oversoul-Prism/sonata/compare/feature/sonata-tts-app...feat/sonata-ux-and-playback-overhaul
**Status:** pushed and verified on the remote; **not merged** (merge is the
founder's call, and out of this run's authority).

> The HEAD above is the branch tip at the moment this section was written. Because
> writing it produced a further commit, the live tip is one or two commits ahead of
> the SHA printed here. The branch link and the commit table below are the reliable
> references; `git ls-remote origin refs/heads/feat/sonata-ux-and-playback-overhaul`
> is authoritative.

## 0.0 Round two — the founder tested, and two defects were mine

The first version of this branch introduced a regression and did not fix the
floating player. Both are now diagnosed and fixed, each with a test or a lint gate
that would have caught it.

### The read jumped the text and made no sense

Every piece played only its opening moment before the next started.

**Root cause.** `advanceIfPossible()` started the next piece whenever it was staged,
with no check that the current piece had finished. Production prefetches
(`prefetchDepth = 1`), so the next piece is synthesised *during* the current one —
which means "the next piece is available" is the normal condition, not a signal
that the current piece ended. Each staging event therefore pre-empted the piece
being listened to, and `MediaPlayer.play()` stops the previous stream to do it. The
read hopped forward one piece at a time.

**Why the suite missed it.** Every online test ran at `prefetchDepth = 0`, which
makes the read strictly sequential: the next piece is not requested until the
current one ends, so `advanceIfPossible` is never called with a later piece already
staged. The production default was never exercised by any test. That was the real
defect behind the defect, and two tests now pin the production depth — both fail
before the fix.

The fix is an explicit `playingSegment` flag, rather than inferring "is something
playing" from the player, which cannot tell "paused" from "finished".

Two further defects surfaced while fixing it:

- `pause()` left the read looking live while the player held a paused stream, so a
  later advance could start a piece over a pause. `resume()` now asks the *player*
  whether a stream is paused and falls through to the next piece when there is
  nothing to resume. Deciding that from a bookkeeping flag was the bug.
- `FakeAudioPlayer.complete()` left `paused` set, unlike the real player whose
  completion listener clears it. That let a test resume a stream that had already
  ended. The fake now matches `AndroidAudioPlayer`.

### The floating player still did not work

Four separate reasons, on top of the three already fixed:

1. **It was a plain background service holding an overlay window.** Android
   reclaims background processes and the window goes with them, so the player
   appeared then vanished, or never appeared. It is now a `specialUse` foreground
   service — which is what actually keeps a window of this kind alive — with the
   manifest permission, the declared `foregroundServiceType`, the required subtype
   property, and a silent minimum-importance channel for its ongoing notification.
2. **It would have crashed on Android 10.** `maxX`/`maxY` (the drag clamp) used
   `WindowMetrics`, which is API 30, on a `minSdk` of 29. Found by lint.
3. **The Float button hid the app behind the overlay.** `moveTaskToBack` sent Sonata
   away before the user could see whether anything appeared, so a failure left them
   on the home screen with no explanation. The app now stays in front and reports
   that the player is on.
4. **It could be dragged off-screen** and left there — invisible, unreachable, and
   indistinguishable from the player having closed itself. Dragging is now clamped.

Also found by lint: every icon tint was `android:tint`, which an AppCompat
`ImageView` does not reliably honour, so the icon colours set in the redesign may
never have taken effect. All seven are now `app:tint`. `lintDebug` is now a CI gate
because it found two defects that reading the code did not.

---

## 0.1 The two reported defects, traced to one cause

The founder reported that speech did not begin immediately and that a length limit
seemed to truncate input. Both are the same defect: **the engine requested the whole
passage as a single synthesis request and waited for the entire response before
playing a single sample.** Latency therefore scaled with passage length.

Measured against the live gateway before any change (warm requests):

| Request | Time to first audio | Response |
| --- | --- | --- |
| 340 characters | ~1.8 s | ~22 KB |
| 5000 characters (the cap) | **~29.3 s** | 1.89 MB |

So a paragraph took half a minute to start. That is "speech does not begin
immediately". And the backend itself does `text.strip()[:5000]`
(`kernel/tts.py::synthesize`), so anything longer was cut off server-side — the
"length limit". The client also declared the same 5000-character ceiling as the
*maximum accepted input*, which is why the user met it.

## 0.2 What changed

**Progressive, segmented playback (`SpeechChunker`, `TtsEngine`).** The passage is
split into pieces — a deliberately short first piece, larger ones after — and played
piece by piece. The next piece is synthesised while the current one is playing.

- First piece: at most 240 characters, floored at 60 so the opening utterance is a
  phrase and not a fragment. Time to first audio becomes roughly that of the 340-char
  measurement above (~1.8 s), not the 5000-char one.
- Later pieces: at most 900 characters, prefetched during playback.
- **The split is an exact partition.** `pieces.joinToString("") == text` is pinned by
  test, including for a 10,000-character unbroken run where every seam is a hard cut.
  That invariant is what lets the app claim the whole passage is read.
- A failure part-way through falls back to the device voice **with the remainder** —
  the failing piece plus everything after it. Taking only the failing piece (the
  first implementation) silently dropped the rest of a long passage; a regression
  test caught it and the fix is in `reportFallback`.
- Fixed a pipeline deadlock the tests exposed: nothing requested the piece after the
  playing one once playback advanced, so a multi-piece read stalled after the first.

**The length ceiling.** `MAX_TTS_CHARS` (5000) remains the *per-request* ceiling
because it mirrors the backend's own truncation point. `MAX_INPUT_CHARS` (100,000) is
now the ceiling on *accepted input*. A long passage is read in full; it is no longer
rejected or silently shortened at 5000 characters.

**The floating player (`FloatingOverlayService`).** "Does not open or float at all"
had three independent causes, each sufficient on its own:

1. **Crash.** `addView` with `TYPE_APPLICATION_OVERLAY` throws `BadTokenException`
   when the overlay grant is absent, out of `onCreate`. A crashing service looks
   exactly like a dead button. The grant is now checked and the service exits
   cleanly, with the read continuing in the notification as a usable fallback.
2. **Tap never reached the bubble.** The drag listener consumed `ACTION_DOWN`, which
   suppresses the framework's click detection, so the bubble's `OnClickListener`
   never fired and a collapsed player could not be reopened. The tap is now
   recognised inside the touch handler.
3. **Leaked window.** `onDestroy` unbound unconditionally and removed the view last;
   stopping before the bind completed threw and left an unresponsive window attached.
   The view is removed first and the unbind is conditional.

**Callback architecture (`SpeechService`).** Five single mutable callback slots
(`onStart`, `onPause`, …) were replaced with a `Client` listener list. The main screen
and the floating player are bound at the same time, so whichever bound last silently
stole the events from the other — which is why the overlay never learned a read had
finished and stayed on screen over silence.

**The interface.** It was a form: a title, a text box, two buttons, a settings dump.
It now has one token set (`dimens.xml`, `colors.xml`, `themes.xml`), a real icon set
replacing framework drawables, and a reading surface organised around the five
questions a listening user has. Everything optional is behind **More**; there is
still no second screen. New in this run:

- a real progress bar and a *Part N of M* position, driven by the engine's actual
  spoken-character count, shown only while a read is live;
- honest states: Preparing, Buffering (fetching the next part), Speaking, Paused;
- a persistent mini-player (play/pause, float, stop) so controls stay reachable;
- a word count and estimated listening time, so the read can be judged before starting;
- Paste and Clear as real actions; the last passage is restored on relaunch;
- an overlay-permission notice that says what to grant and takes the user there.

**Text handoff.** Two further defects, both met on first use:

- The **share** route was dead: `ProcessTextActivity` accepted `EXTRA_TEXT` but the
  manifest declared only a `PROCESS_TEXT` filter, so Sonata never appeared in the
  share sheet. The `SEND` filter is now declared. (The selection-menu route worked,
  which is why this looked like it worked.)
- The read did not start on handoff — it waited for a tap, gated behind an `auto read`
  preference that was never exposed. A handoff now starts reading immediately.
  Relaunching with the restored passage deliberately does *not* auto-read.

## 0.3 Evidence

| Item | Value |
| --- | --- |
| Unit tests | **110 pass, 0 failures** (`./gradlew testDebugUnitTest`) |
| Instrumented tests | 21 tests compile and package; **never executed** (no device, no KVM) |
| Debug APK | builds locally; `apksigner verify` — signed, installable |
| CI (per-commit, all green) | `05e3ee1b`, `b93d2e0f`, `55c732f`, `28515d2`, `aac7311`, `d68d38a1` |
| CI run for `55c732f` | [39732542990](https://github.com/Arkadia-Oversoul-Prism/sonata/actions/runs/39732542990) — success |
| Installable debug APK | [artifact 12405671869](https://github.com/Arkadia-Oversoul-Prism/sonata/actions/runs/39732542990/artifacts/12405671869) (`Sonata-debug-installable-55c732fa…`) |
| Unit-test report | [artifact 12405671873](https://github.com/Arkadia-Oversoul-Prism/sonata/actions/runs/39732542990/artifacts/12405671873) |
| Handoff previous run | `reports/SONATA_HANDOFF_2026-10-22_single-screen.md` |

The debug APK is the installable one (Android debug key). The release APK is
unsigned and therefore not installable; it is a build product for inspection.

## 0.4 Confirmed by execution vs. still needing a device

**Confirmed by execution here:**
- The unit suite, including the new regression tests for the split invariant, the
  progressive pipeline, pause/resume between pieces, and the mid-passage fallback.
- Debug and release APK assembly; instrumented sources compile.
- Every CI run on this branch is green.

**Not verified on a physical device — do not claim otherwise:**
- That speech now *audibly* begins in about a second. The latency figures above are
  backend measurements plus the design of the new path; the perceived improvement has
  not been heard on hardware.
- That the floating player opens, drags, collapses and expands. The three defects are
  fixed and the code paths are correct by inspection, but the overlay has never run on
  a device in this environment.
- That the redesigned screen looks right at real densities and font scales.
- The instrumented suite (21 tests) has never executed.

## 0.5 Known limitations, stated honestly

- **There is no true audio streaming.** The gateway returns a complete MP3 per
  request; there is no partial-audio protocol to consume. Playback begins early
  because the *first request is short*, not because audio is streamed. Each piece is
  a separate request, so the seam between pieces can be audible as a brief pause; the
  pipeline hides it by prefetching, but it is not gapless.
- **`MAX_INPUT_CHARS` is 100,000**, not unlimited. Beyond that the user is told the
  first 100,000 characters are being read, rather than being silently cut off.
- **The neural voice is Edge TTS.** `elevenlabs_active=false` on the backend. No
  provider choice is exposed in the UI, and none should be until a provider is
  actually configured — the previous UI implied one.
- **The pitch control applies to the device voice only.** The backend contract has no
  pitch parameter, so the control is labelled accordingly rather than silently ignored.
- **Audio cache** is the engine's staged MP3s in `cacheDir`; More reports the size and
  can clear it. There is no persistent library, export or history.

## 0.6 Three highest-value next actions

1. **Install the debug APK on the founder's device and confirm the two reported
   defects are gone** — specifically that a paragraph starts speaking in about a
   second, and that a 20,000-character passage is read to the end. This is the only
   way to close the report that started this run.
2. **Grant the overlay permission and exercise the floating player** — open, drag,
   collapse to the bubble, expand, and confirm it survives switching apps. The code is
   fixed but unproven on hardware.
3. **Add an emulator job to CI** (`reactivecircus/android-emulator-runner`, API 34) so
   the 21 instrumented tests execute. They are written and compiling; they have never
   run, and until they do, the device-facing behaviour rests on inspection alone.

---

# 1. Previous run — merged single-screen implementation

**Recorded:** 2026-10-22
**Repository:** `Arkadia-Oversoul-Prism/sonata`
**Default branch:** `feature/sonata-tts-app`

This is a checkpoint. It records what was merged, what is proven, what is not, and
how to continue. Claims are separated by evidence class; nothing here is asserted
beyond what was measured.

---

## 1. Merge result

| Item | Value |
| --- | --- |
| Pull request | [#2](https://github.com/Arkadia-Oversoul-Prism/sonata/pull/2) — **merged** |
| Merge commit | `7548819cf30c6c1e064857aa8e33245b772b17dd` |
| Merged at | 2026-10-22T04:44:49Z |
| Merge method | merge commit (history preserved, no squash) |
| Base before merge | `4f7e6674caf2e8c9b12de4516ce3b25b68e65574` |
| Feature branch head | `27d1108b2ae14df78654ef4676776e9361736a73` |
| Feature branch | `feat/sonata-single-screen-01` (retained, not deleted) |
| Files changed | 48 (+5346 / −647) |
| CI on merge commit | run [39724089542](https://github.com/Arkadia-Oversoul-Prism/sonata/actions/runs/39724089542) — **success** |

Integration verified after merge: the feature head is an ancestor of the default
branch, the intended diff landed, and the removed screens are absent from the
default-branch tree.

**No blocker.** The merge was clean; `mergeable_state` was `clean` immediately
before it.

---

## 2. Evidence

### CI

| Run | Event | Revision | Result |
| --- | --- | --- | --- |
| [39719599289](https://github.com/Arkadia-Oversoul-Prism/sonata/actions/runs/39719599289) | push | `27d1108b` | success |
| [39723152815](https://github.com/Arkadia-Oversoul-Prism/sonata/actions/runs/39723152815) | pull_request | `f50b6a53` (merge preview of base+head) | success |
| [39724089542](https://github.com/Arkadia-Oversoul-Prism/sonata/actions/runs/39724089542) | push | `7548819c` (merge commit) | success |

### Tests

- **94 unit tests, 0 failures, 0 ignored** — measured locally and independently
  reported by CI on the merged revision (unit-test artifact, `index.html`).
- 75 pre-existing + 19 added this session.

### Installable artifact (merged revision)

- **Run:** [39724089542](https://github.com/Arkadia-Oversoul-Prism/sonata/actions/runs/39724089542)
- **Artifact:** `Sonata-debug-installable-7548819cf30c6c1e064857aa8e33245b772b17dd`
- **Direct link:** https://github.com/Arkadia-Oversoul-Prism/sonata/actions/runs/39724089542/artifacts/12402088354
- **apksigner verify:** signed, installable
- **sha256:** `3e41facd2737e1b47bc1805b683d5482c5e027912001ccc935d0c26da388a644`
- **Note:** downloading an Actions artifact requires an authenticated GitHub
  session, even on a public repository. An unauthenticated request returns 404.
- **Note:** the debug APK is per-machine (CI and local debug certificates differ),
  so its hash is not reproducible. The release APK is byte-reproducible but
  unsigned, and therefore not installable.

---

## 3. Implemented, with supporting evidence

| Capability | Evidence |
| --- | --- |
| Single-screen UI; optional controls in a collapsible `panelMore` | `activity_main.xml` contains `panelMore` with `visibility="gone"`; `ProcessTextHandoffTest` |
| Built-in Arkadia backend default | `BackendConfig.DEFAULT_URL`; `Prefs` defaults to it; `BackendConfigTest` |
| PROCESS_TEXT handoff delivers the selected text | `ProcessTextActivity` forwards `EXTRA_PROCESS_TEXT` to `MainActivity`; `ProcessTextHandoffTest` |
| Subsequent selections handled | `MainActivity.onNewIntent` + `launchMode="singleTop"`; `ProcessTextHandoffTest` |
| Chunking of long passages without lost text | `SpeechChunker.split`; `AndroidOfflineVoice` queues with `QUEUE_FLUSH`/`QUEUE_ADD`; `SpeechChunkerTest` pins `joinToString("") == text` |
| Playback + notification controls | `SpeechService` foreground notification with `MediaSessionCompat`; observed working on the user's device in the previous session |
| Draggable floating player, collapse to bubble | `FloatingOverlayService.setCollapsed`; `overlay_bubble.xml` |
| `SettingsActivity` and `PlayerActivity` removed | Both absent from the merged tree; `ProcessTextHandoffTest` negative controls |
| No credentials in the APK | Source scan for key/secret/token patterns returns nothing but a `MediaSession` token reference |
| No accidental production config change | No deploy configuration in the repository; the only workflow has no deploy step |
| Cleartext confined to debug + loopback | `app/src/debug/res/xml/network_security_config.xml` permits `localhost`/`127.0.0.1` only; no `app/src/main/res/xml/` equivalent, so release keeps the platform default |

### Backend verification (live, at time of record)

- `POST https://arkadia-qzu4.onrender.com/api/tts` → `200`, `content-type: audio/mpeg`, 11232 bytes for `{"text":"hello","voice":"aria"}`
- `GET /api/tts/status` → `ready=true`, `engine=edge_tts`, `elevenlabs_active=false`

---

## 4. Completed but NOT verified on a physical device

- The redesigned single screen has never been seen on a device. The user installed
  the *previous* candidate; the redesign postdates that install.
- The PROCESS_TEXT forwarding fix has never been exercised by a real selection.
- The floating bubble's collapse/expand and drag have never been exercised.
- Instrumented suite (14 tests) compiles and packages into `app-debug-androidTest.apk`
  but **has never executed** — no device, no KVM, and `vmx`/`svm` flags are absent
  in this environment (`/proc/cpuinfo` shows 0).

**CI passing is not device acceptance.** No physical-device behaviour is claimed.

---

## 5. Known defects and outstanding work

- **ElevenLabs and MiniMax are not active.** The backend reports
  `elevenlabs_active=false`; the neural voice today is **Edge TTS**. Do not describe
  the current neural voice as ElevenLabs.
- Provider credentials must stay on the backend. Anything bundled in an APK can be
  extracted, so keys cannot ship with the app.
- Google sign-in authenticates a user to Sonata only. It does not grant ElevenLabs or
  MiniMax account access or credits.
- The previous session's `TtsEngine` hardening (staging ownership, failure recovery,
  platform-readiness await) is covered by unit tests but has never run against the
  live backend from a device.

## 6. Explicitly unimplemented

STT / voice capture, reading history, audio export, and streaming (progressive
playback that begins on the first chunk rather than after full synthesis). No source
or test claims otherwise.

---

## 7. Next development priorities

1. **Verify the Android text-selection handoff on a real device.** Select text in
   another app → ⋮ → Sonata. Confirm the text appears in the input and the read
   starts. This is the highest-value unverified path and the one the user reported
   broken.
2. **Verify the redesigned single screen.** Confirm one screen, the More panel
   collapses, and no settings page exists.
3. **Verify the floating bubble.** Overlay permission, collapse, drag, expand,
   background playback, notification controls.
4. **Verify long-passage playback.** A selection over 4000 characters must read
   through rather than fail silently.
5. Then, and only then, consider provider work (ElevenLabs/MiniMax), history, export,
   and streaming.

Do not begin a new feature or redesign pass before step 1–4 are answered, because the
current candidate's central claim — that the handoff works — is unverified.

---

## 8. Exact state and continuation commands

```
repo    : https://github.com/Arkadia-Oversoul-Prism/sonata
default : feature/sonata-tts-app @ 7548819cf30c6c1e064857aa8e33245b772b17dd
branch  : feat/sonata-single-screen-01 @ 27d1108b2ae14df78654ef4676776e9361736a73
base    : 4f7e6674caf2e8c9b12de4516ce3b25b68e65574
```

Working copy: `/workspace/sonata-work/sonata` (working tree clean).

```bash
# Environment
export JAVA_HOME=/workspace/tc/jdk
export ANDROID_HOME=/workspace/tc/android-sdk
export PATH=$JAVA_HOME/bin:$PATH

# Build and test
cd /workspace/sonata-work/sonata
./gradlew testDebugUnitTest          # 94 tests, expect 0 failures
./gradlew assembleDebug              # -> app/build/outputs/apk/debug/app-debug.apk

# Verify installability
$ANDROID_HOME/build-tools/34.0.0/apksigner verify app/build/outputs/apk/debug/app-debug.apk

# Fetch the merged-revision installable APK (requires authenticated gh session)
gh run download 39724089542 -n Sonata-debug-installable-7548819cf30c6c1e064857aa8e33245b772b17dd
```

The local debug keystore is `/home/openhands/.android/debug.keystore`.

---

## 9. Boundaries observed

Nothing was deployed, no release was published, no production configuration was
changed, and no provider credentials were enabled. The merge is a code checkpoint
only; it is not authorization for any of those actions.
