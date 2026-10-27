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

## 5. Ordering constraint

The diagnostic's checklist puts reconciliation at step A, before accepting the reading
journey at step B. That order should be **inverted**.

Reconciliation touches build files, source trees and CI references. Doing it while the
reading candidate is unaccepted means the reading candidate would need re-verification
against a moved tree, and a failure would be ambiguous — the regression could come from
either the reconciliation or the reading change. Accepting first keeps the two separable.

Correct order:

1. **Accept the reading journey** on the physical device (diagnostic step B).
2. **Merge the accepted candidate** (step C, human authorization).
3. **Then** reconcile the trees (step A), with the reading baseline stable and CI green.

Reconciliation is not urgent. Nothing is blocked by `sonata-android` existing: it is
stale, not conflicting, and its CI does not gate the dedicated repository.

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
