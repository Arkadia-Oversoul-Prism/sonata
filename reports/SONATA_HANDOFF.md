# Sonata — Session Handoff

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
