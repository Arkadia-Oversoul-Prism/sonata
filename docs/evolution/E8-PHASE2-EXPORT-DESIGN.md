# E8 — Phase 2 groundwork: complete audio export, rename and delete

**Status:** design only — not implemented. Groundwork for the phase that follows
Phase 1 (`feature/sonata-offline-phase1`).
**Depends on:** Phase 1 schema (`Narration.audioPath`, `Narration.title`,
`Narration.durationMs`, deletion independence).
**Specification clauses covered:** E (complete export and file management), and the
file-level half of A (deletion independently controllable).

---

## 1. Goal

Every narration the user has made must be able to leave Sonata as a real file, and
every file must be removable without collateral damage. Concretely:

- Export a narration as **WAV** (uncompressed, high quality), **M4A** (compact),
  and **MP3** *only if an offline encoder is present* — never by calling a cloud
  service.
- Export the **original text** and the **cleaned narration text** as `.txt`.
- **Share** audio out to other apps.
- **Rename** a narration and **change a document title**.
- **Delete** an individual file, and **bulk export** a selection.
- **Recover** an interrupted generation session rather than losing it.

## 2. What Phase 1 already provides

Phase 2 does not need new persistence. The Phase 1 schema already carries:

| Need | Phase 1 field / rule |
|---|---|
| Where the audio is | `Narration.audioPath` (nullable until complete) |
| What to name the export | `Narration.title`, `Document.title` |
| How long it is | `Narration.durationMs` |
| Whether it is exportable | `LibraryRules.isPlayable(narration)` |
| Whether it is recoverable | `LibraryRules.isRecoverable(narration)` |
| Deletion independence | `Narration.documentId` nullable, `ON DELETE SET NULL` |

The one thing Phase 1 deliberately left to the caller is the **file/record
pairing**: `SonataRepository.deleteNarration` removes the record and documents
that the caller must remove the file. Phase 2 must make that pairing impossible to get
wrong — see §5.

## 3. Format strategy (offline only)

| Format | How | When offered |
|---|---|---|
| **WAV** | Write the PCM the engine already produces, with a RIFF header. No encoder needed. | Always |
| **M4A** | `MediaCodec` (AAC) + `MediaMuxer`. Platform, offline, no licence. | Always (API 29+) |
| **MP3** | Only if a bundled offline encoder exists (e.g. LAME via NDK, or a pure-Kotlin encoder). | Only when the encoder is present |

**Non-negotiable:** no format is offered by calling a network service. If MP3 has no
offline encoder, the UI does not show MP3 — it does not silently fall back to a
cloud encoder. This is the same discipline the voice decision applies to TTS.

## 4. Storage and sharing

- **Canonical audio** lives in app-private storage
  (`context.filesDir/narrations/<narrationId>.<ext>`), so it survives cache
  eviction and is never deleted by the OS.
- **Export** writes to a user-visible location: `MediaStore.Audio` on API 29+, or
  the app's external files dir with a `FileProvider` share URI.
- **Share** uses `FileProvider` with a `content://` URI and
  `FLAG_GRANT_READ_URI_PERMISSION` — never a `file://` URI (which throws
  `FileUriExposedException` on API 24+).
- **Text export** writes `.txt` next to the audio, or to the same MediaStore
  collection as a document.

## 5. The deletion-independence rule, at the file level

Phase 1 guarantees a document deletion cannot remove a narration *record*. Phase 2
must guarantee the same for the *file*:

- Deleting a **document** never touches any narration file.
- Deleting a **narration** removes its record **and** its file, in one operation,
  so neither is orphaned.
- Deleting a **collection** removes neither records nor files.

The clean way to make this structural is a single `FileStore` that owns the
mapping and is the only thing that deletes files:

```kotlin
interface FileStore {
    fun pathFor(narrationId: Long, format: AudioFormat): File
    suspend fun write(narrationId: Long, format: AudioFormat, bytes: ByteArray): File
    suspend fun delete(narrationId: Long): Boolean   // idempotent
    fun exists(narrationId: Long): Boolean
}
```

`SonataRepository.deleteNarration` then becomes a two-step the repository itself
sequences (record + file), rather than a contract the caller must remember.

## 6. Interrupted-generation recovery

Phase 1 marks rows left `GENERATING` as `INTERRUPTED` at startup. Phase 2 adds the
user-facing half:

- The library surfaces interrupted narrations via `LibraryRules.isRecoverable`.
- A **Retry** action re-runs generation for that narration id, reusing the same
  row (so the user's title and document link are preserved).
- A **Discard** action deletes the row and any partial file.
- A partial file, if one exists, is written to a `.partial` path and only renamed
  to the final path on success — so a crash never leaves a half-file that looks
  complete.

## 7. Interfaces to add

```kotlin
enum class AudioFormat { WAV, M4A, MP3 }

interface AudioExporter {
    fun supportedFormats(): Set<AudioFormat>          // MP3 only if encoder present
    suspend fun export(narration: Narration, format: AudioFormat): Uri
}

interface TextExporter {
    suspend fun exportOriginal(document: Document): Uri
    suspend fun exportCleaned(document: Document): Uri
}
```

Both are injectable seams, matching the existing `AudioPlayer` / `OfflineVoice` /
`TtsTransport` pattern, so the logic is testable on the JVM without a device.

## 8. Test plan

| Test | Type | What it pins |
|---|---|---|
| `exporting a narration produces a readable file` | Robolectric | WAV header + bytes round-trip |
| `mp3 is not offered when no encoder is present` | JVM | the no-cloud-encoder rule |
| `deleting a document leaves every narration file on disk` | Robolectric | file-level deletion independence |
| `deleting a narration removes both record and file` | Robolectric | no orphaned file, no orphaned record |
| `a partial file is never presented as complete` | JVM | `.partial` → final rename |
| `retry reuses the interrupted narration row` | Robolectric | recovery preserves title + link |
| `bulk export writes one file per selected narration` | Robolectric | bulk path |

## 9. Risks

- **MP3 encoder availability** is the one open question; the design degrades to
  WAV + M4A rather than reaching for a cloud encoder.
- **MediaStore scoping** differs across API levels; the `FileProvider` path is the
  fallback and must be tested on API 29 and 34.
- **Large files**: a long narration's WAV can be tens of MB; export must stream,
  not buffer the whole file in memory (the sandbox and low-end devices both have
  little RAM).

## 10. Authority

Design only. No merge, deploy or production change. Human authority remains final.
