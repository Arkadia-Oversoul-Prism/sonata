# Sonata — Session Handoff

**Recorded:** 2026-10-22 (latest run)
**Repository:** `Arkadia-Oversoul-Prism/sonata`
**Default branch:** `feature/sonata-tts-app` (unchanged)
**Working branch for this run:** `feat/sonata-ux-and-playback-overhaul`

---

# 0.1 Round seven — the instrumented suites finally run, and voices you can hear

**Base:** `8a12970624e02f1d6de27a4f9e3f6da90ca27d4e` (default branch, unchanged)
**Branch:** `feat/sonata-ux-and-playback-overhaul`
**Pull request:** [#4](https://github.com/Arkadia-Oversoul-Prism/sonata/pull/4)

The founder asked for three things this round: run the instrumented suites on an
emulator, stop offering voices that fail with HTTP 502, and make the voice sound less
robotic. Two were done as asked. The third turned out to be impossible on the current
backend, and the evidence for that is below.

## 0.1.1 The instrumented suites run for the first time

Three `androidTest` suites have existed since the beginning and had **never once
executed**. CI compiled them — so the sources could not rot — but nothing ran them.
That is the same gap that made the floating-player crash take three rounds of reading
and guarding code without ever observing it.

The new `instrumented-tests` job runs `connectedDebugAndroidTest` on an API 34
`google_apis` x86_64 emulator via `reactivecircus/android-emulator-runner`. It is a
separate job from `build`, so an emulator failure is its own signal rather than
something that can hide behind, or mask, the unit suite, lint and APK steps.

**Evidence (execution, not inspection).** Workflow run
[`40330605140`](https://github.com/Arkadia-Oversoul-Prism/sonata/actions/runs/40330605140),
job `Instrumented tests (API 34 emulator)`, all steps `success`, and the log contains:

```
> Task :app:connectedDebugAndroidTest
Starting 20 tests on emulator-5554 - 14
BUILD SUCCESSFUL in 1m 49s
OK: killing emulator, bye bye
```

20 device tests, on a real Android runtime. This covers what Robolectric structurally
cannot: the platform `TextToSpeech` engine's real readiness behaviour, real audio
decoding, and real cache-directory semantics. Robolectric has no compositor and no real
TTS engine — which is exactly why it could not have caught the window-visibility class
of bug the founder is worried about, and why this job matters.

## 0.1.2 Voices that fail are no longer offered

`GET /api/tts/status` lists seven voices. Four work; `george` and `ryan` return HTTP 502
reproducibly (`Edge: No audio was received; Piper voice not loaded`), and `aetheria` is
not a distinct voice at all (below). `BackendConfig.VOICES` now offers `aria`, `jenny`,
`sonia`, `christopher`. A stored preference naming a removed voice resolves to a working
one rather than sending a request the gateway will answer with 502.

## 0.1.3 "Hear it" — audition a voice before choosing it

Choosing a voice from a list of names is guesswork, and "the others sound robotic" is a
judgement no label can support. The gateway synthesises four voices and they differ
audibly, so the only honest way to choose one is to hear it. **Hear it** now sits beside
the picker and plays a three-sentence sample of the selected voice — per voice, not one
generic line, because a voice can sound fine on a short phrase and wear out over a
paragraph.

A preview is deliberately not a read: its own action, its own command type, no
foreground claim (a one-second sample that leaves a notification behind is worse than
the sample is good), and a finished preview is never reported as a finished read.

Writing the tests found a real defect. The first version asserted a preview leaves the
passage untouched and it **failed**: `preview` supersedes the engine, because two audio
streams cannot play at once, so auditioning a voice mid-read stops the read. That is
unavoidable; silently losing the user's place is not. The activity now records the
position before dispatching a preview, so **Carry on** brings the user back.

## 0.1.3b The floating player's window, now verified on a device

The founder asked for emulator coverage precisely because Robolectric had caught a
window-class bug that device testing and code review had missed, and would also catch
window-visibility bugs. That instinct was right, and the coverage now exists.

Every previous check of the floating player asserted the service's own `attached` flag —
which says the service *believes* it has a window, not that the window is on screen, has
a size, or sits where the user can see it. `DeviceOverlayWindowTest` asks the window
manager instead, and never asks Sonata. Three properties, all passing on the API 34
emulator:

| Test | What it proves |
| --- | --- |
| `p4_1_the_floating_window_is_actually_visible_and_on_screen` | the window exists, is non-empty, and is on the display |
| `p4_2_collapsing_leaves_a_visible_window` | collapsing leaves a visible window, not a zero-sized or removed one |
| `p4_3_closing_removes_the_window` | closing removes the window rather than leaving it on screen |

**Evidence (execution).** Run
[`40391169216`](https://github.com/Arkadia-Oversoul-Prism/sonata/actions/runs/40391169216),
artifact `Sonata-instrumented-test-results-...`, test XML:

```
tests="23" failures="0" errors="0" skipped="0"
DeviceOverlayWindowTest  p4_1_...  PASSED
DeviceOverlayWindowTest  p4_2_...  PASSED
DeviceOverlayWindowTest  p4_3_...  PASSED
```

`skipped="0"` matters as much as `failures="0"`: the tests grant `SYSTEM_ALERT_WINDOW`
with `appops` and skip rather than fail if the grant does not take, so a silent skip
would have looked like a pass. It did not skip.

Two rounds were needed to get there, and both failures were in the test, not the app —
which is worth recording because it is the kind of thing that gets mistaken for a product
bug:

1. The first version required `getRoot() != null` on each window and filtered by package
   name. That discarded every window on a headless emulator, so a working overlay
   reported as absent.
2. The second used `UiAutomation.windows` unfiltered; it returns an empty list on a
   headless emulator regardless of what is on screen, because the window list it exposes
   is gated on accessibility flags the harness does not set.

The reliable source is the window manager's own record, which is also what a human runs
with `adb shell dumpsys window` — so the test and the manual procedure now agree. The
player's window was given a title (`SonataFloatingPlayer`), which costs nothing and makes
it identifiable in a device dumpsys. Failures now carry a diagnosis (the window list plus
the window manager's lines for the package) so the next one is readable from CI alone
rather than costing a round trip to interpret.

What this does **not** prove: that the window survives navigation between applications, or
that the founder's original report is fixed on the founder's specific device. It proves
the window is created, visible, and correctly torn down on a clean Android 14 runtime.

## 0.1.3c Immediate start, now measured rather than inferred

The founder's first reported defect was a delay before speech began. Every test of the
progressive read asserted the *shape* of the pipeline — a short first request, an ordered
split, nothing over the backend ceiling — and none of them measured the property. Shape is
a proxy for latency; it is not latency.

`p3_7` measures it. The server answers the opening piece immediately and every later piece
**3 seconds late**, so an engine that waits for the whole passage would be tens of seconds
from its first sound. The test asserts first audio inside a 3-second budget.

**Evidence (execution).** Run
[`40404868557`](https://github.com/Arkadia-Oversoul-Prism/sonata/actions/runs/40404868557),
test XML: `tests="25" failures="0" errors="0" skipped="0"`,

```
p3_7_the_first_audio_starts_without_waiting_for_the_rest_of_... PASSED 0.075s
p3_8_the_whole_passage_is_read_not_just_the_opening_piece      PASSED 0.830s
```

0.075 s is the test's whole wall time, so first audio arrived far inside the budget while
the rest of the passage was still deliberately stalled. `p3_8` guards the obvious cheat —
starting fast and stopping — by completing the read and asserting the request count equals
the piece count, which catches both stopping early and requesting a piece twice.

Both run at the **production prefetch depth of 1**, not the convenient 0. That distinction
has already cost this project once.

### Two test-soundness failures, both worth recording

The emulator job failed twice on this test before it was sound, and **both failures were
the test, not the engine**:

1. `p3_7` reported "first audio took 3091ms" — suspiciously exactly the delay placed on
   the *second* piece. `MockWebServer`'s `QueueDispatcher` is positional, and at prefetch
   depth 1 the opening piece and its successor are in flight together, so the delayed
   response can land on the opening request. The test measured its own mis-mapping. Fixed
   by choosing the response from the request body, so arrival order no longer matters —
   rather than by loosening the threshold, which would have hidden a real regression later.
2. `p3_8` *passed* but took 21 s: it waited for a completion the no-op player can never
   send, then fell back to a weak assertion. A test that cannot reach its own completion
   path is not testing progression. It now completes the read with a player that reports
   each piece finished.

A passing test that never reaches its assertion is worth less than a failing one.

## 0.1.3d The acceptance gate is now tested, not assumed

The founder's acceptance gate names one boundary explicitly: the interaction between
Sonata's 100,000-character accepted ceiling and the gateway's 5,000-character per-request
truncation, with the note that "a successful build alone cannot establish this."

It could not, and it was not established. The largest chunking fixture was **10,000
characters** — an order of magnitude below the ceiling — so the boundary was entirely
unexercised. Nothing tested the seam.

Two limits meet there and must not be conflated:

| Limit | Value | Failure mode past it |
|---|---|---|
| `MAX_INPUT_CHARS` | 100,000 | Sonata truncates and **tells the user** |
| `MAX_TTS_CHARS` | 5,000 | Backend truncates **silently**, returns 200 with audio for the first 5,000 |

The new test runs the real two-stage path — `TextLimits.validate` then
`SpeechChunker.splitForStreaming` — rather than either stage alone. A bug in the seam
between two different ceilings is invisible from outside: it presents to the user as text
that simply stopped, which is the original defect.

Asserted at ceiling scale: validation cuts to exactly `MAX_INPUT_CHARS`; the passage
becomes many requests; every request stays under `MAX_TTS_CHARS`; joining them reproduces
the validated text exactly; the opening piece is still short.

### The assertion was checked by mutation, not assumed

A test that passes is not evidence it can fail. Two mutations of the production chunker,
both caught:

| Mutation | Simulates | Result |
|---|---|---|
| `STREAM_LATER_LIMIT` 900 → 6000 | a piece the backend silently truncates | **FAILED** |
| skip one char at each boundary | a read that drops text | **FAILED** |

Production source restored exactly (`git diff` empty). Full suite: **151 tests, 0 failures,
0 skipped.**

This closes the local half of the acceptance gate. It does **not** close the device half —
whether the read sounds right, survives app switching, and resumes correctly is still a
physical-device question, and remains open.

## 0.1.4 The "more emotionally alive voice" request — measured, and not deliverable here

This is the honest part. The request was for something closer to ElevenLabs. Every
expressiveness route was probed and measured; none of them works on the current backend.

| What was tried | Result | How it was measured |
| --- | --- | --- |
| `GET /api/tts/status` | `engine: edge_tts`, `elevenlabs_active: false`, `key_pool.total: 0` | authoritative endpoint |
| SSML `<mstts:express-as style='cheerful'>` + `prosody pitch='+15%'` | **silently ignored** — f0 190.9 Hz and 2.76 s, identical to plain text | acoustic: f0 + duration |
| top-level `pitch: "+10Hz"` / `"-10Hz"` | **silently ignored** — f0 216.2 Hz for both, same as baseline | acoustic: f0 |
| top-level `emotion: "happy"` | **silently ignored** — f0 216.2 Hz, same as baseline | acoustic: f0 |
| `speed: 0.7 / 1.0 / 1.5` | **works** — 7.06 s / 4.97 s / 3.34 s | acoustic: duration |

So `voice` and `speed` are the only expressive controls the gateway honours. No style or
emotion control was added: it would be attractive, inert UI, which is worse than none.
`pitch` already existed in the app and is correctly labelled "device voice only" — it
affects only the offline engine, which the measurements now confirm is accurate.

One methodological note that matters for anyone repeating this: **the gateway is
non-deterministic.** Three identical requests returned three different byte streams
(different MD5s), so a byte-comparison test would have proved nothing either way. The
findings above rest on acoustic measurements — fundamental frequency and duration —
which survive that non-determinism. `aetheria` and `aria` returned byte streams of the
same length and identical f0 *and* identical duration, and the gateway's own catalogue
shows both map to `en-US-AriaNeural`; `aetheria` is a relabelled duplicate, not a
different voice.

## 0.1.5 The substrate vision — what is reachable, and what is not

The founder described a much larger system: a music pipeline, an intent/understanding
engine, voice swapping. That was treated as a direction to map rather than a licence to
invent. The gateway's own OpenAPI document (288 endpoints) shows the substrate exists,
and the reachable boundary is:

| Endpoint | Reachable from the app? |
| --- | --- |
| `GET /api/lab/engineering/n-atlas/catalog` | **Yes**, 200. Provider `n_atlas`, `configured: true`, `status: AVAILABLE`, `cost_note: "cost not asserted; provider tiers are not assumed free"` |
| `POST /api/lab/engineering/n-atlas/run` | No — 401 |
| `GET /api/lab/engineering/voice` | No — 401 |
| `POST /api/lab/engineering/voice/resolve` | No — 401 |
| `GET /solspire/voice/status` | No — 401 |
| `GET /solspire/voice/providers` | No — 401 |
| `/solspire/voice/events/{id}/understand` | No — 401 |

There is **no music endpoint** in the published API at all. The music-to-device pipeline
the founder described is not an API that exists and is unreachable; as far as the
published surface shows, it does not exist yet.

The honest summary: the substrate is real and the app's one public anchor into it is the
N-ATLAS catalogue. Everything with actual capability behind it requires authentication,
which the app does not have and must not be given by embedding a credential in an APK.
Building music or intent UI against endpoints that return 401 would be the "attractive
but inert" failure this run is explicitly trying to avoid. The integration path is a
backend-side authenticated route, which is an Arkadia decision, not a Sonata one.

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

## 0.00000 Round six — from repair to daily use

With playback and the floating player confirmed working on the device, this round moves
from fixing defects to making the app worth returning to.

**Carry on from where you stopped.** The gap: leaving a long passage and coming back
restarted it from the top. For a read-aloud app used in bursts, that makes a long
passage useless. The passage was remembered; the position was not.

- `TtsEngine.speakFrom(text, fromChar)` resumes at the piece containing the offset.
  The engine owns this because it knows where each piece begins. Resuming lands on a
  piece boundary, so a read never starts mid-word; the offset is clamped into the text
  so a stale position cannot start past the end.
- `ResumePoint.offset(storedPassage, storedOffset, currentPassage)` is the rule that
  decides whether to offer a resume. It is pure and separately tested because it is the
  safety property: an offset applied to text it does not describe drops the user into
  the wrong part of a passage. Every uncertain case resolves to "start from the top".
- The position is written on `onPause`, on stop, and every five seconds during playback
  — not on every 400 ms tick, which was the first cut and would have been 2.5 disk
  writes a second for a value only read when the user returns.
- The button says "Carry on from 42%" and is hidden when there is nothing to carry on
  from, so it is never a dead control.

**Speed changes now say when they take effect.** The speed applies per synthesis
request, so a change mid-read starts from the next piece. Nothing said so, and a
slider that appears to do nothing reads as broken.

**Progress after a mid-passage fallback (found, not reported).** When online synthesis
fails part-way, the offline voice takes over the *remainder* — a different string from
the passage. `spokenChars` reported that offset directly, so the progress bar dropped
back toward zero as the fallback happened, even though the audio was continuous. On a
long passage that looks like the read restarting. `PlaybackState.Offline` now carries
`originInPassage`, and progress is always against the passage the user offered.

**On the regression test for that fix.** The first version failed at the first piece,
where the origin is zero, so it passed with and without the fix — it proved nothing. It
now plays piece 1, lets piece 2 fail, and asserts progress is past zero after the
takeover, and it was confirmed to fail when the fix is reverted. That check is what the
float fix needed three rounds earlier and did not get.

Test count: 112 at the start of this work, **134 now**, all passing. Lint clean, debug
APK builds.

---

## 0.0000 Round five — both confirmed working on the device

The founder tested the round-four build on hardware: **playback works and the floating
player opens.** That closes the three defects that dominated rounds two to four:

| Reported | Status |
| --- | --- |
| Speech did not begin immediately | Fixed (round one: segmented pipeline) |
| Long passages truncated | Fixed (round one: chunked planning, `TextLimits`) |
| Read jumped the text / no sense | Fixed (round two: prefetch cut off playing audio) |
| Floating player did not open or float | **Fixed (round four: Service context carried no theme)** |

The float fix is now device-verified, not just JVM-verified. The one thing the
Robolectric tests could not show — whether a window actually becomes visible — is
confirmed by the person looking at the screen.

---

## 0.000 Round four — the float crash, reproduced and fixed

The round-three work was inference: every throwable path was guarded, but nothing was
reproduced, and the crash continued. Round four found the actual cause by making the
service runnable on the JVM and driving it.

**The reproduction.** Robolectric was added as a test dependency and
`FloatingOverlayServiceTest` constructs and starts the real service. It failed at once:

```
android.view.InflateException: Binary XML file line #23 in
  com.arkadia.sonata.debug:layout/overlay_player: Error inflating class
  com.google.android.material.card.MaterialCardView
Caused by: java.lang.IllegalArgumentException: The style on this component requires
  your app theme to be Theme.MaterialComponents (or a descendant).
```

**The cause.** The player is a `MaterialCardView` containing a `MaterialButton`, and it
was inflated with the Service's own context. A theme reaches an Activity by default; it
does not reach a Service. So the app's MaterialComponents theme was absent,
MaterialComponents refused to inflate, and the exception left `onCreate` uncaught — on
the app's main thread. The foreground notification appeared and the process died. That
is precisely the report: *"I see it trying to pop up on my notification bar but
immediately it just goes off and the app closes like something shut it down."*

**The fix.** `ContextThemeWrapper(this, R.style.Theme_Sonata)` — the supported way to
inflate Material components outside an Activity. Inflation is also inside the guard now,
so an OEM theme quirk cannot take the app down either.

**A second crash the test then exposed, on the same path:**

```
NullPointerException: Parameter specified as non-null is null:
  FloatingOverlayService$conn$1.onServiceConnected
```

`ServiceConnection`'s parameters are nullable in the platform contract, and the platform
does pass a null binder. Declaring them non-null inserted a Kotlin null check that threw
on the main thread. They are nullable now, with a safe cast.

**Why three rounds of guarding missed it.** There was no test for the overlay at all.
Every JVM test passed because none of them constructed the service, and the manifest
check that "verified" the round-two fix proved the *declaration* was present — not that
the player could start. Guarding throwable paths without reproducing the failure treats
symptoms. The lesson is recorded in `.openhands/memory/MEMORY.md`: when a defect cannot
be reproduced, build the harness that can, rather than enumerating suspects.

**What the new tests still cannot show.** Whether a window becomes visible to a user —
Robolectric has no compositor. They are a necessary condition, not proof, and that
distinction is kept honest in the evidence table above.

---

## 0.00 Round three — the founder reports a crash on Float, playback now fine

Playback works on the device. Tapping Float crashes the app.

**Why this class of bug looks the way it does.** `FloatingOverlayService` runs in
the app's own process, on the main thread. An uncaught throw in a `Service` or in a
touch/click listener therefore kills the *whole app*, not just the player. So
"the app crashes when I tap Float" means: something on that path throws, and there
is no single suspect — every throwable path presents identically. The ones found
and closed:

| Path | What threw | Now |
| --- | --- | --- |
| `startForegroundService` in `MainActivity` | `ForegroundServiceStartNotAllowedException` when the app may not start an FGS | Caught; the app says the player could not open and keeps playing |
| Collapse / expand | `removeView` + `addView` per tap, each able to throw `BadTokenException` | Both states live in the window from the start; a tap only toggles visibility, so no gesture performs a window operation |
| The 600 ms ticker | Runs on the main thread forever, so one throw becomes a crash *loop* | Guarded |
| `updateViewLayout` during a drag | Called on every touch move | Guarded |
| A stored off-screen position | After rotation or resize, the player is unreachable | Clamped at drag start as well as during |

**Instrumentation for the next report.** Because there is no device here, this
crash could not be reproduced. Step logging was added at each stage, so the next
occurrence can be pinpointed rather than guessed at:

```
adb logcat -s SonataOverlay SonataMain
```

It prints `starting floating player`, `foreground notification posted`, `overlay
permission granted`, `window attached` — and a `could not start` / `could not
attach` line with the exception message when a stage fails. Whichever line is last
identifies the stage.

---

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
| Unit tests | **118 pass, 0 failures** (`./gradlew testDebugUnitTest`) — 6 of them drive the real overlay service |
| Lint | `lintDebug` clean, and now a CI gate |
| Instrumented tests | 21 tests compile and package; **never executed** (no device, no KVM) |
| Debug APK | builds locally; `apksigner verify` — signed, installable |
| APK manifest verified | `foregroundServiceType=0x40000000` (specialUse), subtype property present, `FOREGROUND_SERVICE_SPECIAL_USE` present — read back from the built APK, not the source |
| CI (per-commit, all green) | `05e3ee1b`, `b93d2e0f`, `55c732f`, `28515d2`, `aac7311`, `d68d38a1`, `fa66ca4`, `9cf0205`, `c85df07`, `17571c2`, `9b1217d` |
| CI run for `55c732f` | [39732542990](https://github.com/Arkadia-Oversoul-Prism/sonata/actions/runs/39732542990) — success |
| CI run for the round-two tip `f1844ed4` | [39910367894](https://github.com/Arkadia-Oversoul-Prism/sonata/actions/runs/39910367894) — success |
| Installable debug APK (round two tip) | [artifact 12481809379](https://github.com/Arkadia-Oversoul-Prism/sonata/actions/runs/39910367894/artifacts/12481809379) (`Sonata-debug-installable-f1844ed4…`, 5.5 MB) — **this is the one to install** |
| Installable debug APK (round one) | [artifact 12405671869](https://github.com/Arkadia-Oversoul-Prism/sonata/actions/runs/39732542990/artifacts/12405671869) — superseded; the read hops and the float fails |
| Unit-test report | [artifact 12405671873](https://github.com/Arkadia-Oversoul-Prism/sonata/actions/runs/39732542990/artifacts/12405671873) |
| Handoff previous run | `reports/SONATA_HANDOFF_2026-10-22_single-screen.md` |

The debug APK is the installable one (Android debug key). The release APK is
unsigned and therefore not installable; it is a build product for inspection.

## 0.4 Confirmed by execution vs. still needing a device

**Confirmed by execution here:**
- The unit suite (112 tests), including the split invariant, the progressive
  pipeline, pause/resume between pieces, the mid-passage fallback, and — added in
  round two — two tests that run at the *production* prefetch depth and fail without
  the fix for the piece-cutting regression.
- `lintDebug` clean, which is how the API-29 crash and the dead icon tints were
  found.
- Debug and release APK assembly; instrumented sources compile.
- The built debug APK's merged manifest and signature, inspected with `aapt2` and
  `apksigner` rather than inferred from source.
- Every CI run on this branch is green.

**Not verified on a physical device — do not claim otherwise:**
- That speech now *audibly* begins in about a second. The latency figures above are
  backend measurements plus the design of the new path; the perceived improvement has
  not been heard on hardware.
- That the floating player survives *heavy* use — long drags, rapid collapse/expand,
  rotation while it is open, and low-memory pressure. It opens and floats on device,
  which was the reported defect, but the edges have not been exercised.
- That the read plays every piece in order over a *very* long passage. Ordering is
  pinned by tests at the production prefetch depth and the founder confirms playback
  works, but a full 20,000-character run has not been listened to end to end.
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

1. **Install the new debug APK and listen to a long passage end to end.** The
   piece-cutting regression is fixed and pinned by tests at the production prefetch
   depth, but the fix has never been heard. Confirm every sentence is spoken once, in
   order, with no repeats or jumps.
2. **Grant the overlay permission and exercise the floating player** — open, drag,
   collapse to the bubble, expand, switch apps, come back. It is a foreground service
   now with a verified manifest, but the last device report was that it did not work
   at all, so it needs a real check.
3. **Add an emulator job to CI** (`reactivecircus/android-emulator-runner`, API 34) so
   the 21 instrumented tests execute. They are written and compiling; they have never
   run, and the round-two regression is a direct consequence of device-facing
   behaviour resting on tests that did not exercise the production configuration.

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
