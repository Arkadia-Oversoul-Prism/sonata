# E3 — Verification and inspection audit (slice 01)

**Branch:** `feat/process-text-playback-hardening-01`
**Candidate SHA at time of audit:** `15ac078` + this commit
**Base:** `4f7e6674caf2e8c9b12de4516ce3b25b68e65574`

## Commands run and exact results

| Command | Result |
|---|---|
| `./gradlew testDebugUnitTest assembleRelease --no-daemon` | **BUILD SUCCESSFUL** |
| unit tests (JVM) | **69 tests, 0 failures, 0 errors, 0 skipped** |
| release APK | produced (unsigned) |
| `git grep -nIE "(api[_-]?key\|secret\|token\|password\|bearer)\s*[=:]\s*['\"][A-Za-z0-9_\-]{16,}"` | **no matches** — no credentials in tracked files |
| manifest permission audit | see §1 |

### Test breakdown

| Suite | Tests | Failures | Errors |
|---|---|---|---|
| `TtsEngineOnlineTest` | 25 | 0 | 0 |
| `TtsEngineOfflineTest` | 12 | 0 | 0 |
| `PlaybackStateTest` | 10 | 0 | 0 |
| `SpeechCommandTest` | 8 | 0 | 0 |
| `SpeedStepsTest` | 7 | 0 | 0 |
| `TextLimitsTest` | 7 | 0 | 0 |
| **Total** | **69** | **0** | **0** |

### Artifact

```
app/build/outputs/apk/release/app-release-unsigned.apk
3,090,851 bytes
sha256 3c710101757e938264c1db37a1746c09e0c3eaac24ee1a40685fac8da3f3a679
```

(The byte size differs from the first slice commit because this audit adds the
runtime notification-permission request; the earlier artifact is superseded.)

## 1. Findings from the audit

### F-1 (fixed) — `POST_NOTIFICATIONS` was declared but never requested
`targetSdk` is **34**, so from API 33 the playback notification is suppressed until the
user grants `POST_NOTIFICATIONS`. The manifest declared the permission, but nothing ever
requested it, so on Android 13+ the notification — which carries the pause/resume/stop
actions — would never appear, and playback would be uncontrollable once the activity was
gone. This is a functional defect in the primary use case, not a lint nit.

**Repair:** `MainActivity.requestNotificationPermissionIfNeeded()` requests it via
`ActivityResultContracts.RequestPermission` on API 33+. A refusal is non-fatal and the
user is told, rather than being left with silently missing controls.

### F-2 (fixed) — `RECEIVE_BOOT_COMPLETED` declared with no receiver
No `BroadcastReceiver` for `BOOT_COMPLETED` exists anywhere in the source. An unused
sensitive permission is a needless grant surface, and the governing protocol asks for an
audit of unsafe/unneeded permissions. **Removed.**

### F-3 (accepted, documented) — `SYSTEM_ALERT_WINDOW` is load-bearing
Required for the floating overlay. It is a powerful permission, and it is requested
only through `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` (the user must toggle it in
system settings), never silently. Retained deliberately; recorded here so it is not
mistaken for drift.

### F-4 (accepted, documented) — audio retention is bounded and user-clearable
Temp audio lives in `cacheDir` as `sonata_<uuid>.mp3` and is deleted on completion,
error, supersession, `stop` and `shutdown` (see the slice-01 evidence). `SettingsActivity`
shows the cached size and offers a clear action. Retention is therefore bounded, but
there is **no automatic age-based eviction** — if a process dies mid-utterance the file
survives until the user clears it. Recorded as residual risk R-3.

## 2. Residual risks (not repaired in this slice)

| # | Risk | Why not repaired here |
|---|---|---|
| R-1 | **No device or emulator evidence.** No instrumented test, no install, no manual run. | No device/emulator available in this environment. The governing protocol requires this be listed as unverified rather than claimed. |
| R-2 | **The backend contract is read, not exercised.** `POST /api/tts` was implemented from Arkadia `api/main.py:2128` and `kernel/tts.py` and has never been called against a running deployment from this app. | Requires a live Arkadia origin; out of scope for a build/unit slice. |
| R-3 | **No age-based cache eviction.** See F-4. | Would need a policy decision (retention window) that is not this slice's to make. |
| R-4 | **Voice list is a hardcoded client-side list** (`aria, jenny, sonia, christopher, george, ryan`) that is not validated against the backend catalogue. A voice the backend no longer serves would fail at synthesis and fall back offline. | Fetching `GET /api/tts/voices` is a separate bounded change; noted for the next slice. |
| R-5 | **No CI re-run.** The 8 recorded CI failures are all at revisions before this branch. | Pushing was not performed; see §3. |
| R-6 | **`allowBackup="true"`** means preferences can be included in device backups. No secrets are stored in preferences today, so this is informational. | Changing backup policy is a product decision. |

## 3. Explicit statements

- **No merge was performed.** The base branch `feature/sonata-tts-app` is untouched;
  the work is on `feat/process-text-playback-hardening-01` only.
- **No deployment, release, or production configuration change** was made.
- **No secret was read, printed, or committed.** The tracked-file scan found none.
- **No STT, voice-capture, or MIE/N-ATLAS integration was added**, and none is
  claimed. Those are E4 proposals, not this slice.
- **No capability is claimed beyond the measured results above.** A green unit suite
  and a built APK are evidence about this exact tree; they are not device behaviour,
  not backend behaviour, and not release authorization.
- **No replacement claim.** No ElevenLabs or Eleven Reader workflow is asserted
  replaced. The dependency/workflow audit that would be needed to make such a claim
  has not been performed.

## 4. Required handoff summary

1. **Baseline / source-of-truth SHAs** — base `4f7e6674caf2e8c9b12de4516ce3b25b68e65574`
   (default branch `feature/sonata-tts-app`); see `E0-BASELINE.md`.
2. **Capability / contract matrix** — see `E0-BASELINE.md` §5.
3. **Change plan and exclusions** — see `E1-PLAN.md`.
4. **Files changed and rationale** — see `E2-SLICE-01-EVIDENCE.md` §3 and the two
   commits on this branch.
5. **Commands and results** — §1 above.
6. **Candidate SHA and diff summary** — the tip of this branch; see §5.
7. **Tests not run and why** — R-1 (no device/emulator), R-5 (no CI re-run).
8. **Residual risks and next bounded candidate** — §2, and E4 candidate 1 below.
9. **No merge/deploy/release performed** — §3.

## 5. Next bounded candidate (proposal only)

**E4 candidate 1 — device-verified `PROCESS_TEXT` playback.** Establish instrumented
evidence that a selection speaks, pauses, resumes and stops, and that the offline
fallback is reachable. This is the shortest path to closing R-1, which currently gates
every user-facing claim. It needs an emulator or device, so it should be proposed and
authorized rather than assumed.

Candidates 2–5 (long-form reader, STT, Neural Voice Studio, Arkadia voice agent,
release/migration audit) remain as listed in issue #1 §E4 and are **not** started.
