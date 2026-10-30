# E6 — Canonical source, and a correction to the record

Observation date: 2026-10-27.
Origin: the external product diagnostic flagged "duplicate Android projects" and a
source-of-truth ambiguity, and asked for the dedicated repository to be made canonical
subject to a one-time reconciliation.

The recommendation is accepted. Two of the facts it rests on do not match the repository,
and the correction changes what the reconciliation actually is.

## 1. Correction: there is no top-level `sonata/` directory

The diagnostic's table lists three Android trees in `Arkadia`:

| Reported path | Actual status at revision `36f53be` |
|---|---|
| `sonata/` | **404 — does not exist** |
| `sonata-android/` | exists |
| `archive/legacy_android/sonata/` | exists |

A recursive listing of the full tree at that revision (567 directories, not truncated)
finds exactly two Android trees: `sonata-android/` and `archive/legacy_android/sonata/`.
There is no `sonata/`.

The report appears to have taken the path list from
`docs/recon/15_redundancy_audit.md`, which does say:

> **`sonata/` (Java) vs `sonata-android/` (Kotlin)** — two native Android clients for the
> same product surface.

That audit document is **stale**, and stale in a second way the report did not detect.
The tree it calls "(Java)" is now at `archive/legacy_android/sonata/`, where it is
**Kotlin, not Java** — 15 `.kt` files, zero `.java`, in `com.sonata.app`. The audit's
parenthetical is wrong, and so is the report's description of the archive as a "Java
Android implementation".

So the "two clients for the same surface" framing describes a state that has already been
resolved: one client is archived. The remaining work is confirming the archived tree has
no live build references — a smaller task than the report implies, and not urgent.

This also means the diagnostic's history section ("the original voice utility" as a Java
Android app) should not be relied on for the archive's language.

## 2. The real problem is worse than duplication

Duplication means two copies of one thing. What is actually present is **two different
products sharing a name**:

| | `Arkadia/sonata-android` | `Arkadia-Oversoul-Prism/sonata` |
|---|---|---|
| Last commit | 2026-10-04 | active (PR #4) |
| Kotlin sources | 22 | 19 |
| Reading / TTS | yes | yes |
| **MIE audio capture + analysis** | **yes — 7 files** | **no** |
| **Oracle / Knowledge / Timeline / Search** | **yes** | **no** |
| Instrumented emulator CI | no | yes — 23 tests, API 34 |
| **Signed release pipeline** | **yes — green 2026-10-10** | **no — release APK unsigned** |
| Segmented long-passage read | no | yes |

Neither is a superset, and the exchange runs in **both** directions. The dedicated
repository has the reliability work and the emulator CI that `sonata-android` never had.
`sonata-android` has MIE, the four Arkadia-facing activities, and — notably — a
**working signed release pipeline**, which the dedicated repository still lacks
(its release APK builds unsigned). The diagnostic correctly identifies signing as an open
gap; it does not identify that the solution already exists and is proven green in the
other tree.

A naive "make the dedicated repo canonical and archive the other" would therefore delete
the MIE implementation *and* discard a working signing pipeline. Both must be migrated
deliberately, or the canonical repository ends up less capable than the tree it replaced.

## 3. Why `sonata-android` stopped

Last commits, 2026-10-04:

```
8de26d5e65 fix(mie): keep Gate 06 trash visible when all active objects are deleted
c1c0ef14af docs(mie): record Gate 06 native build state
33c59362b3 test(mie): cover Gate 06 object controls provenance
```

The work stopped mid-gate, on a branch of MIE development with tests and provenance
tracking. It did not stop because the approach was abandoned; it stopped because
attention moved to the dedicated repository for the reliability pass. There is no commit
explaining a decision to drop MIE.

## 4. Decision

**The dedicated `Arkadia-Oversoul-Prism/sonata` repository is the canonical home of the
native application.** It has the active development, the instrumented CI and the merged
reading experience. Splitting future work across two trees is the failure mode to avoid.

**`Arkadia/sonata-android` is frozen, not deleted.** Freezing is a commit or a README
notice, reversible and cheap; deletion is not, and MIE has not been migrated.

**MIE is migrated, not copied.** A copy leaves two divergent MIE implementations, which
recreates the original problem one directory down. The migration is its own slice with
its own tests — see `E5-VOICE-CONTRACT.md` §5. It is not part of the reading work.

## 5. Agreed execution sequence

The diagnostic's checklist put reconciliation at step A, before accepting the reading
journey at step B. That order is **inverted**, and the inversion is now agreed on both
sides.

Reconciliation touches build files, source trees and CI references. Doing it while the
reading candidate is unaccepted means the candidate would need re-verification against a
moved tree, and any failure would be ambiguous — the regression could come from either the
reconciliation or the reading change. Accepting first keeps the two separable.

The agreed sequence:

1. **Accept the reading journey.** Physical device: cross-app floating-player behaviour and
   the complete long-passage workflow.
2. **Authorize and complete integration.** Reconcile the final commit, checks and review
   evidence; merge only after acceptance and explicit authorization.
3. **Freeze the legacy development target.** Add the notice to `Arkadia/sonata-android`
   through an authorized change in the Arkadia repository; verify its build consumers
   separately.
4. **Migrate MIE and the signed release pipeline** into the canonical repository, without
   maintaining two divergent implementations. Preserve the verified signing workflow and
   test the migrated result.

Steps 3 and 4 are not urgent. Nothing is blocked by `sonata-android` existing: it is stale,
not conflicting, and its CI does not gate the dedicated repository. The immediate goal is
narrow — finish the dependable reading experience, preserve the existing assets, then
consolidate.

This ordering avoids both extremes named in the review: freezing Sonata as a basic TTS
utility forever, or destabilizing a nearly accepted reading experience by migrating the
whole Arkadia voice ecosystem at once.

## 5a. Status: pushed and green is not merged and accepted

Recorded explicitly, because the two are easy to conflate and this project has already
been misread once in exactly that way.

Measured 2026-10-27:

| Fact | Value |
|---|---|
| Remote HEAD of the feature branch | `badb3859c5f55e0095cdbb33f927df6615390697` |
| PR #4 state | `open`, `draft: true`, `merged_at: null` |
| PR #4 base | `feature/sonata-tts-app` @ `8a129706…` |
| PR #4 mergeable | `true`, `mergeable_state: clean` |
| CI on `badb385` | success (runs `40496570303`, `40496565070`) |
| Default branch | `feature/sonata-tts-app`, unchanged |
| Physical-device acceptance | **not performed** |

So: the work is published and its checks pass. It is **not** merged, **not** released, and
**not** device-accepted. The release APK builds unsigned. A green check on a draft branch is
not an accepted product.

The `mergeable: clean` field is worth noting for step 2 — the branch is not merely
unmerged, it is currently free of conflicts against its base, so integration is available
whenever acceptance occurs.

## 6. Freeze notice

`sonata-android` should carry a notice recording that it is frozen, that its MIE
implementation is the migration source, and that new work belongs in the dedicated
repository. Until that notice exists, an engineering agent inspecting the Arkadia tree
can reasonably conclude that `sonata-android` is the push target — which is exactly the
"push, test or report from the wrong project tree" hazard the diagnostic names.

The notice cannot be added from this branch: it lives in a different repository, and the
authorization boundary for this run covers the dedicated repository's feature branch only.
It is recorded here as the concrete next action for whoever holds Arkadia write access.

## 7. Build-reference check (not yet performed)

Before `archive/legacy_android/sonata/` is treated as settled, its remaining consumers
must be verified — CI workflows, Gradle settings, documentation links. That check is not
performed here because it requires the Arkadia tree, which is outside this run's
authorization. Stating it as outstanding rather than assuming it is clean.

## 8. The strategic shape

The product sequence is not `reader → voice assistant`. It is:

> reliable listening → preserved musical and knowledge capabilities → governed voice
> interaction

The middle step is the one most likely to be skipped, and skipping it is what would make
Sonata a basic TTS utility rather than Arkadia's voice interface. MIE and the knowledge
activities already exist; they need migrating, not reinventing.

The Solspire lifecycle — `understand → propose → decision → revise → authorize → execute →
verify` — is a promising architectural shape for the final layer. It is not an available
Sonata integration today (see `E5-VOICE-CONTRACT.md` §4), but it gives Arkadia a concrete
target for the contract it needs to publish. The reason it is promising is the separation
of `authorize` from `execute` and `verify` from `execute`: that is the structure required
to keep "spoken" and "done" distinguishable, which is the same discipline this project
already applies to its own progress reporting.

The immediate goal stays narrow: finish the dependable reading experience, preserve the
existing assets, then consolidate on a stable foundation.
