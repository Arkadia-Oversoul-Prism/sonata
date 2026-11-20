# E7 — Offline Sonata: reconciliation and Phase 1 handoff

**Date:** 2026-11-08
**Repository:** `Arkadia-Oversoul-Prism/sonata`
**Branch reconciled:** `feature/sonata-tts-app` @ `235346b`
**Plan:** `docs/evolution/E7-OFFLINE-SONATA-PLAN.md`

---

## 1. What was reconciled

The specification was reconciled against the committed tree, not against the
documentation. Every status in the plan's capability matrix is read from a file at
`235346b` or from a command run against it.

**The finding:** the repository is a thin, **online-first TTS client** — it reads
text aloud by calling the Arkadia backend (`POST /api/tts`), with the platform
`TextToSpeech` engine as a fallback. It has no library, no persistence, no export,
no voice capture, no voice cloning, no read-along canvas and no emotional narration.

**Of the five principal capabilities, none is complete:**

| Capability | State |
|---|---|
| A. Document and narration library | **ABSENT** — no database; `Prefs` holds one passage |
| B. Offline voice cloning | **ABSENT** — no `RECORD_AUDIO`, no ML runtime |
| C. Text preparation and Canvas | **ABSENT** except a chunker that serves request sizing, not prosody |
| D. Emotional narration | **ABSENT** — the backend ignores SSML/emotion |
| E. Export and file management | **ABSENT** — audio is a temp file deleted on completion |

**What is reusable:** `TtsEngine`'s request discipline (single owner, supersession,
cleanup on every exit path, honest fallback, honest resume), the injectable seams
(`AudioPlayer`, `OfflineVoice`, `TtsTransport`), the test source set, the emulator
CI job, and the single-screen discipline.

**What must change:** the engine's tier order (online is tier 1 today; the
specification requires offline to be the only tier), the shipped backend URL, the
backend voice catalogue, the transient audio lifetime, and the `INTERNET`
permission.

## 2. Voice technology decision

The plan adopts a **two-tier voice model**, because the specification itself draws
the distinction that makes on-device cloning tractable:

- **Enrollment** — capture samples, analyse quality, create a reusable profile.
  Cheap, always possible, shippable without a device.
- **Adaptation** — adapt a neural model to the voice. Expensive, **device-gated**.

**Runtime:** sherpa-onnx (Kotlin API, ships in production Android apps).
**Base voices:** Kokoro-82M for quality, Piper/VITS for speed.
**Cloning:** OpenVoice v2's tone-colour converter (ONNX, no PyTorch) over the base
voice — because **Piper alone does not provide arbitrary personal voice cloning**,
and OpenVoice is a converter, not a generator.

**The gate:** adaptation does not ship until a harness has measured peak RSS, model
and profile storage, sustained thermal behaviour and tokens/second on the target
device class. If the numbers do not support it, enrollment-only is the product.

## 3. Phase 1 delivered (this pass)

The local-first persistence foundation, in `app/src/main/kotlin/com/arkadia/sonata/data/`:

| File | Contents |
|---|---|
| `Entities.kt` | `Document`, `Narration`, `VoiceProfile`, `Collection`, `CollectionItem`, `ReadingSession` + enums |
| `Daos.kt` | One DAO per aggregate, plus `LibraryDao` for cross-table transactions |
| `SonataDatabase.kt` | Room database, version 1, `exportSchema = true`, no destructive fallback |
| `LibraryRules.kt` | The library filters, the status-transition table, playability and recovery — all pure |
| `SonataRepository.kt` | The single entry point; sequences the rules and the schema |

Tests, in `app/src/test/kotlin/com/arkadia/sonata/data/`:

| File | Contents |
|---|---|
| `LibraryRulesTest.kt` | 18 pure-JVM tests over the filters and the transition table |
| `SonataRepositoryTest.kt` | 24 Robolectric tests against a real in-memory SQLite database |

### The invariants, and where each is enforced

1. **Deletion independence** — `Narration.documentId` is nullable with
   `ON DELETE SET NULL`, so SQLite itself guarantees a document deletion cannot
   remove a narration. Pinned by `deleting a document keeps the narrations made
   from it` and four sibling tests (collection, voice profile, narration→document).
2. **Every completed session persisted** — a narration carries its audio path,
   voice profile, model version, duration, date and playback position. Pinned by
   `a completed narration is persisted with its audio, voice, duration and date`.
3. **Interrupted generation marked, never silently completed** — the transition
   table refuses `INTERRUPTED → COMPLETE`, and `recoverInterrupted()` marks rows
   left `GENERATING` at startup. Pinned by three tests, including
   `an interrupted narration cannot be completed without being retried`.
4. **The original is never overwritten** — `originalText` and `cleanedText` are
   separate columns. Pinned by `cleaning the text leaves the original untouched`.
5. **Model versions recorded** — a narration records the model version that made
   it. Pinned by `an adapted profile records its model version`.

## 4. Build and test status

- **Main sources: compile clean.** `./gradlew compileDebugKotlin` → `BUILD
  SUCCESSFUL` (Room + KSP code generation included).
- **Test sources:** compile check run; see the run log for the result.
- **Environment constraint:** the sandbox has a 2 GB memory cgroup and 1 CPU. The
  default Gradle daemon is killed by the OOM killer, so every build here must run
  `--no-daemon --max-workers=1 -Dorg.gradle.jvmargs='-Xmx1536m'`. This is a
  sandbox limit, not a repository problem; CI (`.github/workflows/build-apk.yml`)
  is unaffected.
- **No emulator** exists in this sandbox, so instrumented tests were not run.

## 5. What is next

**Phase 2 — complete audio export, rename and delete.** WAV via the platform
encoder, M4A via `MediaCodec`/`MediaMuxer`, MP3 only if an offline encoder exists
(otherwise it is not offered). Files land in app-private storage and are shared out
via `FileProvider`/MediaStore. The Phase 1 schema already carries everything this
needs: `Narration.audioPath`, `Narration.title`, and the deletion-independence rule
at the file level.

Then Phase 3 (offline TTS engine), Phase 4 (Voice Studio), Phase 5 (Canvas), Phase
6 (emotional narration), Phase 7 (device validation).

## 6. Authority

No merge, deploy, release or production configuration change accompanies this work.
It is on a feature branch for review. Human authority remains final.
