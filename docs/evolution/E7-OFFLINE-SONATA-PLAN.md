# E7 — Offline Sonata: specification reconciliation and implementation plan

**Repository:** `Arkadia-Oversoul-Prism/sonata`
**Default branch:** `feature/sonata-tts-app` @ `235346b` (merge of PR #4)
**Reconciliation date:** 2026-11-08
**Method:** every claim below is read from the committed tree at `235346b` or from a
command run against it. Nothing is inferred from documentation alone, and nothing is
claimed that was not measured. Where a capability is absent it is marked **ABSENT**
rather than described as "planned".

---

## 0. Executive summary

The specification describes a **fully offline, personal reading, narration,
voice-generation and audio-library application**. The repository today is a
**thin, online-first TTS client**: it reads text aloud by calling the Arkadia
backend (`POST /api/tts`), with the platform `TextToSpeech` engine as a fallback.
It has no library, no persistence, no export, no voice capture, no voice cloning,
no read-along canvas, and no emotional narration.

The pivot is therefore not an extension — it is a **re-foundation**. The good news
is that the parts that are hardest to get right (a hardened, tested, honest
playback engine; a real test source set; an emulator CI job; a single-screen
discipline) already exist and are reusable. The parts that are missing are almost
entirely **new subsystems**, and they are additive rather than a rewrite of the
engine.

Three findings dominate the plan:

1. **The engine is reusable but its online tier must be demoted.** `TtsEngine`
   already models single-owner requests, supersession, cleanup on every exit path,
   honest fallback and honest resume. That is exactly the discipline a local
   engine needs. What must change is the *tier order*: today online is tier 1 and
   offline is the fallback; the specification requires **offline to be the only
   tier**, with the backend removed from the core path entirely.

2. **Nothing is persisted.** `Prefs` stores a single "last passage" and a
   character offset in `SharedPreferences`; audio lives in `cacheDir` and is
   deleted on completion. There is no database, no library, no export. Capability
   A (the library) and E (export) are **ABSENT** and are the correct first phase.

3. **Voice cloning is genuinely hard on-device and must not be promised before it
   is measured.** The specification itself says so ("do not advertise arbitrary
   on-device training until those tests pass"). The plan treats voice cloning as a
   **staged capability with a device-validation gate**, and separates *enrollment*
   (cheap, always possible) from *model adaptation* (expensive, device-gated).

---

## 1. Repository state (measured at `235346b`)

| Fact | Value |
|---|---|
| Default branch | `feature/sonata-tts-app` (there is no `main`) |
| HEAD | `235346b` — merge of PR #4 (`feat/sonata-ux-and-playback-overhaul`) |
| Other remote branches | `feat/process-text-playback-hardening-01`, `feat/sonata-single-screen-01`, `feat/sonata-ux-and-playback-overhaul` |
| Main Kotlin sources | 19 files, `app/src/main/kotlin/com/arkadia/sonata/` |
| Unit tests | 12 files, `app/src/test/kotlin/com/arkadia/sonata/` |
| Instrumented tests | 4 files, `app/src/androidTest/kotlin/com/arkadia/sonata/` |
| Build | Gradle 8.4 wrapper, AGP 8.2.2, Kotlin 1.9.22, `compileSdk`/`targetSdk` 34, `minSdk` 29 |
| CI | `.github/workflows/build-apk.yml` — unit tests, lint, release APK, debug APK, and an API-34 emulator job |
| Persistence | **none** — `SharedPreferences` only (`Prefs.kt`) |
| Database | **none** — no Room, no SQLite, no DataStore |
| Network | `INTERNET` permission; `OkHttpTtsTransport` → `POST {backend}/api/tts` |
| Microphone | **no `RECORD_AUDIO`** — no capture of any kind |
| Export | **none** — audio is a temp file in `cacheDir`, deleted on completion |

### 1.1 What the engine already does well (reusable)

`TtsEngine.kt` (973 lines) is the most valuable asset in the repository. It already
implements, with tests:

- **Single-owner requests** — every `speak()` supersedes in-flight work; a
  superseded request can never start playback (`generation` token + `Job`
  cancellation).
- **Cleanup on every exit path** — completion, error, supersession, `stop`,
  `shutdown` all delete staged files.
- **Honest fallback** — an online failure falls back to the offline voice *with the
  remainder of the passage*, and reports the degradation rather than hiding it.
- **Honest resume** — online resume continues the decoded stream; offline resume
  re-speaks from the last character and is labelled a re-synthesis seam.
- **Bounded input** — `TextLimits` (100 000 accepted, 5 000 per request) and
  `SpeechChunker` (exact partition, pinned by test).

`SpeechChunker`, `TextLimits`, `PlaybackState`, `ResumePoint`, `SpeedSteps`,
`SpeechCommand`, `AudioPlayer`, `OfflineVoice` and `TtsTransport` are all clean,
injectable seams with tests. **The plan builds on these rather than replacing
them.**

### 1.2 What the engine does that the specification forbids

| Behaviour | Where | Why it conflicts |
|---|---|---|
| Online synthesis is tier 1 | `TtsEngine.start()` → `speakOnline` when `preferOnline && url.isNotEmpty()` | Spec: "no network dependency for core TTS" |
| Backend URL is a shipped default | `BackendConfig.DEFAULT_URL = "https://arkadia-qzu4.onrender.com"` | Spec: "not an interface that depends on Arkadia's backend" |
| Voice catalogue is the backend's | `BackendConfig.VOICES` (aria/jenny/sonia/christopher) | Spec: voices are the user's own profiles |
| Audio is transient | `writeTempFile` → `cacheDir`, deleted on completion | Spec: "no completed narration should disappear" |
| `INTERNET` permission | `AndroidManifest.xml` | Spec: "no account or internet connection required" |

These are the **must-change** items. None of them requires rewriting the engine's
logic; they require changing what the engine is *wired to*.

---

## 2. Capability reconciliation matrix

Status vocabulary: **EXISTS** (implemented and tested) · **PARTIAL** (present but
insufficient) · **ABSENT** (not present) · **MUST-CHANGE** (present and conflicts
with the specification).

### A. Personal document and narration library

| Requirement | Status | Evidence |
|---|---|---|
| Saved item per completed TTS operation (title, original text, cleaned text, audio, voice, duration, date, position) | **ABSENT** | no database; `Prefs` stores one `lastPassage` + `lastSpokenChars` |
| Editable document title | **ABSENT** | no document model |
| Playback position / resume | **PARTIAL** | `ResumePoint.kt` + `Prefs.lastSpokenChars` — one passage only, not per-item |
| Library organisation (All / Recent / Narrations / Imported / Recordings / Favorites / Collections) | **ABSENT** | no library UI or model |
| Deletion independence (delete document ≠ delete narration) | **ABSENT** | nothing to delete; no model to express the rule |
| Audio downloadable/shareable independently of its document | **ABSENT** | audio is a temp file deleted on completion |

### B. Native offline voice cloning

| Requirement | Status | Evidence |
|---|---|---|
| Voice enrollment (capture samples → reusable profile) | **ABSENT** | no `RECORD_AUDIO`, no recorder |
| Voice model training / adaptation (local) | **ABSENT** | no ML runtime, no model management |
| Voice Studio experience | **ABSENT** | no such screen |
| Voice profiles reusable across documents | **ABSENT** | no profile model |
| Offline neural TTS (Piper / ONNX / sherpa-onnx) | **ABSENT** | offline tier is the platform `TextToSpeech` engine (`AndroidOfflineVoice.kt`) |

### C. Intelligent text preparation and Canvas Read-Along

| Requirement | Status | Evidence |
|---|---|---|
| Text normalisation (emojis, markdown, HTML, tables, URLs, abbreviations) | **ABSENT** | `TextLimits.validate` only trims whitespace |
| Narration preparation (paragraphs, sentence boundaries, breathing points) | **PARTIAL** | `SpeechChunker` splits on strong/weak boundaries for *request sizing*, not for prosody |
| Read-Along Canvas (synchronised highlight, auto-scroll, font size) | **ABSENT** | no canvas; `MainActivity` is a text field + controls |
| Word-level highlighting | **ABSENT** | no alignment data |
| Original document unchanged; cleaned text separate | **ABSENT** | there is only one text |

### D. Emotional and contextual narration

| Requirement | Status | Evidence |
|---|---|---|
| Narration Intelligence Layer (context, emotion, prosody) | **ABSENT** | no such layer |
| Context treatments (letter / statement / reflective / storytelling / technical / dialogue / inspirational) | **ABSENT** | none |
| Punctuation-based pauses and expressive segmentation | **ABSENT** | `SpeechChunker` boundaries are not emitted as pauses |
| Emotional delivery via the neural model | **ABSENT** | the backend ignores SSML/emotion (measured in `E5-VOICE-CONTRACT.md` §2) |

### E. Complete export and file management

| Requirement | Status | Evidence |
|---|---|---|
| Export WAV / M4A / MP3 | **ABSENT** | no encoder, no export path |
| Export original text / cleaned text | **ABSENT** | no files |
| Share audio | **ABSENT** | audio is transient |
| Rename / retitle / delete individual files | **ABSENT** | no file model |
| Bulk export | **ABSENT** | — |
| Recover interrupted generation sessions | **ABSENT** | no session record |

### Architecture and engineering boundaries

| Requirement | Status | Evidence |
|---|---|---|
| Local-first Android, no backend dependency | **MUST-CHANGE** | `BackendConfig`, `TtsEngine` online tier, `INTERNET` |
| Room database + local storage | **ABSENT** | no Room dependency |
| Preserve single-screen minimalism | **EXISTS** | `MainActivity` is one screen; `SettingsActivity`/`PlayerActivity` were removed (PR #2) |
| Offline operation for core TTS | **PARTIAL** | platform TTS works offline, but it is the *fallback*, not the core |
| Persistence of every completed session | **ABSENT** | — |
| Interrupted generation recoverable / clearly marked | **ABSENT** | — |
| Privacy: voice samples local | **ABSENT** | no samples exist yet |
| Explicit user-controlled deletion | **ABSENT** | — |
| Export accessible outside Sonata | **ABSENT** | — |
| Read-along audio/text synchronised | **ABSENT** | — |
| Model versions and compatibility recorded | **ABSENT** | — |
| Device RAM/storage/thermal validation before advertising training | **ABSENT** | no measurement harness |

**Summary:** of the five principal capabilities, **none is complete**; A and E are
entirely absent, B and D are entirely absent, C is absent except for a chunker that
serves a different purpose. The reusable substrate is the playback engine, the test
infrastructure, and the single-screen discipline.

---

## 3. The pivot: from online-first client to local-first application

The specification's core principle — *everything created in Sonata belongs to the
user* — is incompatible with the current architecture in one specific way: **the
current app produces nothing that outlives the utterance.** Every design decision
below follows from fixing that.

### 3.1 What stays

- `TtsEngine`'s request discipline (single owner, supersession, cleanup, honest
  state) — reused verbatim as the shape of the new local engine.
- `SpeechChunker`, `TextLimits`, `PlaybackState`, `ResumePoint`, `SpeedSteps`,
  `SpeechCommand` — reused, extended.
- `AudioPlayer` / `AndroidAudioPlayer` — reused for playback of generated audio.
- The test source set, the emulator CI job, and the single-screen discipline.

### 3.2 What changes

| Change | From | To |
|---|---|---|
| Engine tier order | online → offline fallback | **local neural → platform TTS fallback** (no network tier) |
| Voice source | backend catalogue | **user voice profiles** + bundled base voices |
| Audio lifetime | temp file, deleted on completion | **persisted file** owned by a narration record |
| Text | one editable field | **document records** with original + cleaned representations |
| Backend | shipped default URL | **removed from the core path**; `INTERNET` dropped from the core build |
| State | `SharedPreferences` | **Room database** |

### 3.3 What is explicitly *not* changed

- No second screen is introduced for its own sake; the library, canvas and studio
  are **modes of the one screen**, consistent with the merged single-screen
  direction.
- No account, no sign-in, no telemetry.
- No credential is ever shipped in the APK (the existing rule, kept).

---

## 4. Voice technology decision (evidence-based)

The user's confirmed decisions: **on-device enrollment AND local model training
(both)**; **highest natural quality while remaining offline**; **no ElevenLabs,
Google TTS, cloud STT, or paid API credentials**.

### 4.1 The two-tier voice model

The specification itself draws the distinction that makes this tractable:

- **Enrollment** — capture samples, analyse quality, create a reusable *profile*.
  This is cheap, always possible, and is the user-facing "Create Voice" flow.
- **Model training / adaptation** — adapt a neural speech model to reproduce the
  vocal characteristics. This is expensive and device-gated.

The plan implements **enrollment first** (Phase 4) and **adaptation second**
(Phase 4b, behind the device gate). This is not a compromise: enrollment alone
already delivers "voice profiles reusable across documents", and it is the part
that can be built and tested without a device.

### 4.2 Candidate technologies (investigated, not committed)

| Technology | Role | Evidence |
|---|---|---|
| **sherpa-onnx** | On-device inference runtime for Android (Kotlin API) | `k2-fsa/sherpa-onnx` supports Android, iOS, embedded; ships a Kotlin API; powers shipping Android apps (VoxSherpa TTS, MediaTek BreezeApp, China Life) |
| **Piper / VITS** | Lightweight offline neural TTS voices | Piper voices run under sherpa-onnx; `vits-piper-en_US-lessac-high` is the reference model |
| **Kokoro-82M** | Higher-quality offline neural TTS | Runs fully offline on a mobile CPU under sherpa-onnx; "studio-grade" per its authors |
| **OpenVoice v2 (ONNX)** | Tone-colour voice conversion (cloning) | `tone_extract.onnx` + `tone_color.onnx` run under ONNX Runtime without PyTorch; MIT-licensed; needs a base TTS to supply speech |
| **ZipVoice / Pocket TTS** | Voice cloning under sherpa-onnx | sherpa-onnx ships voice-cloning examples (ZipVoice, Pocket TTS) |

**The honest reading of this table:**

- **Piper alone does not provide arbitrary personal voice cloning** — the
  specification says so, and it is correct. Piper gives *fixed* voices.
- **OpenVoice is a converter, not a generator.** It changes the tone colour of
  speech produced by a base TTS. So a cloning pipeline is
  `base TTS (Piper/Kokoro) → OpenVoice tone-colour conversion → user's voice`.
  That is the architecture the plan adopts, and it is why the two-tier model
  matters: the base TTS is the "engine", the conversion is the "cloning".
- **"Highest natural quality while offline" points at Kokoro-82M as the base
  voice**, with Piper as the fast/lightweight option. Both run under sherpa-onnx,
  so the runtime decision is the same either way.

### 4.3 The device gate (non-negotiable)

The specification forbids advertising on-device training before it is measured
against **RAM, storage, thermal limits and processing speed**. The plan therefore
places a **hard gate** between Phase 4 (enrollment) and Phase 4b (adaptation):

> Phase 4b does not begin until a measurement harness has recorded, on the target
> device class: peak RSS during inference, model + profile storage footprint,
> sustained thermal behaviour over a long synthesis run, and tokens/second. If the
> measurements do not support adaptation, the feature is **not shipped** and the
> enrollment-only path is the product.

This is the same discipline the repository already applies to its own claims
("do not claim what was not measured"), applied to the hardest capability.

---

## 5. Implementation plan

Seven phases, in the specification's order. Each phase states its deliverable, its
tests, and its gate. Phases 1–3 are buildable and testable **without a device**;
Phases 4b–7 require device validation.

### Phase 1 — Persistent document and narration library  ← **started in this pass**

**Deliverable.** A Room database and repository that persist documents, narrations,
voice profiles, collections and reading sessions, with the specification's
invariants enforced in code and pinned by tests.

**Invariants (the whole point of the phase):**

1. **Deletion independence** — deleting a document must not delete a narration the
   user wants to keep. A narration references its source document *optionally*; the
   document reference is nulled, the narration survives.
2. **Persistence of every completed session** — a completed narration is written
   with its audio path, voice profile, duration, date and playback position.
3. **Interrupted generation is recoverable or clearly marked** — a narration has an
   explicit status (`PENDING → GENERATING → COMPLETE | FAILED | INTERRUPTED`); a
   session left `GENERATING` when the process dies is surfaced as `INTERRUPTED`,
   never silently shown as complete.
4. **Original text is immutable; cleaned text is separate** — a document holds
   `originalText` and `cleanedText` as distinct columns; the original is never
   overwritten by normalisation.
5. **Model versions recorded** — a narration records the voice profile and the
   model version that produced it.

**Tests.** Robolectric in-memory Room tests for each invariant, plus pure-JVM tests
for the library-filter and status-transition rules.

**Gate.** `./gradlew testDebugUnitTest` green, including the new tests; the
invariants each have a test that fails when the invariant is removed.

### Phase 2 — Complete audio export, rename and delete

**Deliverable.** Export to WAV (uncompressed), M4A (`MediaCodec`/`MediaMuxer`,
offline), and MP3 (only if an offline encoder is available — otherwise MP3 is
**not offered**, per the specification's "if the chosen offline encoder supports
it"). Rename, retitle, delete individual files, bulk export, and export of original
and cleaned text. Files land in app-private storage and are shared out via
`FileProvider` / MediaStore so they are accessible outside Sonata.

**Tests.** Export produces a decodable file of the expected format; a deleted
document leaves its narration's audio intact; bulk export writes every selected
item.

**Gate.** Exported files open in an external player; deletion independence holds at
the file level, not just the row level.

### Phase 3 — Offline TTS engine

**Deliverable.** Replace the online tier with a **local neural tier** (sherpa-onnx
+ Piper/Kokoro), keeping the platform `TextToSpeech` engine as the fallback. The
backend is removed from the core path; `INTERNET` is dropped from the core build.

**Design.** The existing `TtsEngine` shape is preserved: a `LocalVoice` seam
mirroring `OfflineVoice`, with the same single-owner/supersession/cleanup
discipline. `SpeechChunker` continues to bound request size; the local engine's own
input ceiling is read from the model rather than assumed.

**Tests.** The engine's existing test suite is re-pointed at the local tier; a
`LocalVoice` fake drives the same scenarios (supersession, fallback, cleanup,
resume).

**Gate.** Speech is produced with the device in airplane mode; no network call is
made on the core path (asserted by a transport that fails the test if called).

### Phase 4 — Voice Studio and personal voice profiles

**Deliverable (4a, enrollment).** `RECORD_AUDIO` permission, a guided recording
flow, sample-quality analysis, and a persisted voice profile. Profiles are reusable
across documents. Samples remain local.

**Deliverable (4b, adaptation — device-gated).** Local model adaptation using the
OpenVoice tone-colour converter over a base TTS voice, producing a per-user voice
model. **Blocked behind the device gate in §4.3.**

**Tests.** Enrollment: a profile is created, persisted, and selectable; a poor
sample is rejected with a reason. Adaptation: gated.

**Gate.** 4a ships on enrollment alone. 4b ships only if the device measurements
support it.

### Phase 5 — Read-Along Canvas and text normalisation

**Deliverable.** The preparation pipeline (original → extraction/normalisation →
narration preparation → canvas) and the canvas itself: sentence-level highlighting,
word-level where the engine provides reliable alignment, auto-scroll, adjustable
font size, paragraph spacing, speed, pause/resume, background playback.

**Tests.** Normalisation is pure and heavily tested (emojis, markdown, HTML,
tables, URLs, abbreviations); the original is asserted unchanged; alignment maps
audio position to text span.

**Gate.** Highlighting stays synchronised across a long passage and a mid-passage
fallback.

### Phase 6 — Emotional narration and contextual prosody

**Deliverable.** The Narration Intelligence Layer: context classification, pause
and breath placement, expressive segmentation. Punctuation-based pauses and
segmentation are handled by Sonata itself; emotional delivery depends on what the
selected neural model can synthesise, and the UI says so.

**Tests.** Context classification and pause placement are pure and tested; the
layer never claims expressiveness the model cannot produce.

**Gate.** A letter, a technical document and a story are each prepared with the
right treatment, and the difference is audible on-device.

### Phase 7 — Performance, recovery and device validation

**Deliverable.** The measurement harness (RAM, storage, thermal, speed), the
interrupted-session recovery flow, and the device-validation report.

**Gate.** The report exists and its numbers are the basis for any performance
claim. Until then, no performance claim is made.

---

## 6. Risks and non-claims

| # | Risk | Mitigation |
|---|---|---|
| 1 | On-device neural TTS is too slow on low-end devices | Piper as the fast tier; Kokoro as the quality tier; the device gate decides |
| 2 | On-device voice adaptation is infeasible on the target device | Enrollment-only path is a complete product; adaptation is gated |
| 3 | Room migration churn as the schema evolves | Versioned schema with explicit migrations from the first release |
| 4 | Export formats the platform cannot produce offline (MP3) | MP3 is offered only if an offline encoder exists; otherwise WAV + M4A |
| 5 | The pivot breaks the existing tested engine | The engine's seams are preserved; its suite is re-pointed, not deleted |
| 6 | Scope creep into a multi-page app | The single-screen discipline is kept; library/canvas/studio are modes |

**Non-claims.** This document does not claim that any phase is complete, that
on-device training works, that any export format is implemented, or that the app is
offline today. It claims only what §1 and §2 measured, and it states the plan.

---

## 7. Phase 1 — detailed design (implemented in this pass)

### 7.1 Schema

```
documents        id, title, originalText, cleanedText, sourceType, importedAt, updatedAt
narrations       id, documentId?, title, audioPath, voiceProfileId?, modelVersion,
                 durationMs, createdAt, playbackPositionMs, status, errorReason
voice_profiles   id, name, kind (ENROLLED|ADAPTED), samplePaths, modelPath?,
                 modelVersion?, createdAt, qualityScore?
collections      id, name, createdAt
collection_items collectionId, narrationId, addedAt
reading_sessions id, narrationId, startedAt, endedAt?, lastPositionMs
```

`narrations.documentId` is **nullable with `ON DELETE SET NULL`** — this is the
deletion-independence invariant expressed in the schema itself, so it cannot be
violated by a careless caller.

### 7.2 Status model

```
PENDING → GENERATING → COMPLETE
                     ↘ FAILED
                     ↘ INTERRUPTED   (a GENERATING row found after a process death)
```

`INTERRUPTED` is never silently promoted to `COMPLETE`; recovery is an explicit
user action.

### 7.3 Library filters

`All · Recent · Narrations · Imported · Recordings · Favorites · Collections`,
implemented as a pure function over the repository's queries so the rule is
testable without a database.

### 7.4 Files added in this pass

- `app/src/main/kotlin/com/arkadia/sonata/data/` — entities, DAOs, database,
  repository, status model, library filters.
- `app/src/test/kotlin/com/arkadia/sonata/data/` — the invariant tests.
- `gradle/libs.versions.toml`, `app/build.gradle.kts` — Room + KSP.

---

## 8. Authority

No merge, deploy, release, or production configuration change accompanies this
document. The work is on a feature branch for review. Human authority remains
final.
