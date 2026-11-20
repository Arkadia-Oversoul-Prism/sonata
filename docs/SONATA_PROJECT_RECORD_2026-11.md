# SONATA_PROJECT_RECORD_2026-11

**Append-only.** This file records the factual project state for November 2026. It
does not modify any earlier record. Where it contradicts an earlier entry, the
earlier entry stands as written and this entry states the correction.

---

## Entry: 2026-11-20 — Stage 7b production candidate

### Factual state

| Field | Value |
|---|---|
| Project | SONATA-PRODUCT-RECOVERY-001 |
| Stage | 7b — production candidate and review package |
| Candidate commit | `3d860c6fe8d88396d537f202b7d3bb7947b5c59f` |
| Branch | `integration/stage7b-prod-candidate` |
| Base | `235346bee72b9162935ef74e05c84caa91c7bd13` |
| Merge commit | `07c68fe` |
| Tests executed | 281 |
| Tests failed | 0 |
| APK produced | No |
| Device gates executed | 0 of 6 |
| Merged to default | No |
| Released | No |
| Deployed | No |
| Verdict | NOT READY — IMPLEMENTATION OR EVIDENCE INCOMPLETE |

### What changed

1. The offline branch (`feat/sonata-offline-phases-1-7`, 13 commits, 61 files) was
   merged into the S0 baseline. This is the combined integration tree that Stage 7a
   reported as never having been published.
2. The Stage 4–6 narration/privacy/reliability/validation code was added as
   `narration-core/`, a real module rather than a standalone harness.
3. The privacy policy was verified against the real `AndroidManifest.xml` for the
   first time. It failed, exposing four live violations: `INTERNET` declared,
   `allowBackup="true"`, no backup exclusions, and `RECORD_AUDIO` requested with no
   recording code. All four are fixed.
4. Three tests that did not test what their names claimed were corrected. Two of
   them failed on first correction, which is the evidence that they now execute.
5. The neural TTS tier was reclassified from "implemented" to **unsupported**.
6. The README was rewritten with a five-way claim classification.

### What did not change

- No model was added. No inference runtime was added. No JNI bridge was written.
- No APK or AAB was produced.
- No device validation was performed.
- The default branch was not modified.

### Corrections to earlier records

| Earlier claim | Correction |
|---|---|
| "Stages 4–6 complete… validated on real audio" | The audio validation was of a standalone harness, not of the product. The product has no engine. |
| "Offline neural TTS" in the README | No neural TTS exists in the tree. |
| "Cold start < 1 s · Speech start < 2 s" in the README | Never measured. Removed. |
| Stage 2/3 commits referenced by earlier stages | `c1c3b03`, `85e15aa`, `916d5c2`, `7fef1d2` do not exist on the remote. Verified absent with `git cat-file -t` against a fresh clone. |

### Open items

| # | Item | Owner | Blocking |
|---|---|---|---|
| 1 | Bundle a speech model with checksum and licence | Engineering | Yes |
| 2 | Implement the JNI inference bridge | Engineering | Yes |
| 3 | Implement `SpeechModel` against the runtime | Engineering | Yes |
| 4 | Produce a signed APK/AAB | Release engineering | Yes |
| 5 | Execute device gates G1–G6 | QA | Yes |
| 6 | Measure S0-1..S0-4 | QA | Yes |
| 7 | Re-measure WER against the new corpus hash | Engineering | No |
| 8 | Delete `OkHttpTtsTransport` once the engine exists | Engineering | No |
| 9 | Add licence attributions to an about screen | Engineering | No |
| 10 | Run a dependency vulnerability scan | Security | No |

---

## KDR entry

**KDR-2026-11-20-01 — Sonata Stage 7b: candidate prepared, verdict NOT READY**

**Decision.** Do not merge the Stage 7b candidate to the default branch. Do not
release. Do not deploy.

**Rationale.** The candidate is source-complete for the platform-independent logic
and passes 281 tests, but the product cannot perform its core function: there is no
speech engine. This is an implementation gap, not an external gate, so the
"conditionally ready" verdict does not apply.

**What was gained.** The integration tree now exists at a named commit; the privacy
policy is genuinely enforced and the enforcement found a real violation that had
been shipping; three non-tests were replaced with real tests; and the documentation
now matches the implementation, including where the implementation is absent.

**What remains.** A model, a runtime, a JNI bridge, an APK, and device validation.
Until those exist, no further documentation or process work changes the verdict.

**Reversal condition.** This decision is reversed when items 1–6 in the open-items
table are closed and the device gates pass.

**Recorded by.** DevOps Automator, 2026-11-20.
