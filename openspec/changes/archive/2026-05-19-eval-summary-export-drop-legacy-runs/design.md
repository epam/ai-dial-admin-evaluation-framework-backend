## Context

The `eval-summary-export` capability was introduced with a **legacy-run fallback** because, at landing time, the production database still contained a long tail of `test_suite_runs` rows whose `suite_snapshot` column was `NULL` (created before the snapshot phase was rolled out). To avoid breaking exports for those runs, `EvalSummaryExportService.resolveSnapshot(TestSuiteRun run)` falls back to:

1. Read the **live** `TestSuite` via `TestSuiteRepository.findById(run.getTestSuiteId())`.
2. Synthesize a transient `SuiteSnapshotDto` via `SuiteSnapshotBuilder.build(liveSuite)`.
3. Proceed with planning + streaming as if the run carried that snapshot.

If the live suite is also gone, the service throws `SnapshotSuiteMissingException` → `HTTP 422 SNAPSHOT_SUITE_MISSING`.

The fallback ships at the cost of two meta dependencies (`TestSuiteRepository`, `SuiteSnapshotBuilder`) on a path that otherwise only needs `TestSuiteRunRepository`, and it violates the spec's stated invariant that the column manifest is derived **exclusively** from the run's frozen snapshot (since the synthesized snapshot reflects current — not historical — schema).

Legacy runs are not regenerated; every run created since the snapshot phase landed has a non-null `suite_snapshot`. The population of affected runs only shrinks over time.

## Goals / Non-Goals

**Goals:**

- Remove the legacy-run fallback from the export path, making the snapshot invariant unconditional.
- Reduce `EvalSummaryExportService`'s constructor surface (drop `TestSuiteRepository` and `SuiteSnapshotBuilder`).
- Replace the implicit "legacy run with live suite" success path with an explicit, observable `HTTP 422 SNAPSHOT_SUITE_MISSING` failure.
- Keep the existing error code (`SNAPSHOT_SUITE_MISSING`) and the existing HTTP status (`422`) so clients that already handle the "live suite missing" case continue to work for the broader "no snapshot" case.

**Non-Goals:**

- Any one-shot data migration to backfill `suite_snapshot` for legacy runs. This change is a behavioral simplification, not a data-rescue effort. Callers who need legacy exports must re-run those suites.
- Removing `SuiteSnapshotBuilder` or `SnapshotSuiteMissingException` from the codebase — both remain in use by the snapshot phase in `TestSuiteEvaluationJob`.
- Changing the snapshot-version-mismatch path (`UNSUPPORTED_SNAPSHOT_VERSION` → 422) — that branch is orthogonal and stays as is.
- Touching analytics-side persistence, metric snapshots, or anything related to `run_metric_snapshots`.

## Decisions

### Decision 1 — Throw `SnapshotSuiteMissingException` directly when `suite_snapshot` is null or blank

**What:** `resolveSnapshot` becomes:

```java
private SuiteSnapshotDto resolveSnapshot(TestSuiteRun run) {
    String snapshotJson = run.getSuiteSnapshot();
    if (snapshotJson == null || snapshotJson.isBlank()) {
        throw new SnapshotSuiteMissingException(
                "Run " + run.getId() + " has no suite_snapshot; legacy runs are not exportable");
    }
    SuiteSnapshotDto snapshot;
    try {
        snapshot = objectMapper.readValue(snapshotJson, SuiteSnapshotDto.class);
    } catch (JsonProcessingException e) {
        log.error("Failed to deserialize suite_snapshot for run {}: {}",
                run.getId(), e.getMessage(), e);
        throw new IllegalStateException(
                "Failed to deserialize suite_snapshot for run " + run.getId(), e);
    }
    String version = snapshot.getSnapshotVersion() != null
            ? snapshot.getSnapshotVersion() : SuiteSnapshotDto.CURRENT_VERSION;
    if (!SuiteSnapshotDto.CURRENT_VERSION.equals(version)) {
        throw new UnsupportedSnapshotVersionException("Unsupported snapshot version: " + version);
    }
    return snapshot;
}
```

**Why:** The exception class and HTTP mapping already exist (`DefaultExceptionHandler` → 422). Reusing the same exception type avoids touching the handler, the `ErrorCode` enum, and OpenAPI 422 annotations.

**Alternatives considered:**

- *Introduce a separate `LegacyRunNotExportableException` and `LEGACY_RUN_NOT_EXPORTABLE` error code.* Rejected: clients already cope with `SNAPSHOT_SUITE_MISSING`; multiplying codes for the same observable condition ("the snapshot is not there") complicates the API surface without buying anything. A clear message inside `ErrorView.message` is enough to distinguish if needed.
- *Return `HTTP 404 NOT_FOUND` instead.* Rejected: the run itself exists; what's missing is a precondition of the export. `422 Unprocessable Entity` is the correct status for that distinction and matches the existing convention for snapshot-related failures.

### Decision 2 — Drop `TestSuiteRepository` and `SuiteSnapshotBuilder` from `EvalSummaryExportService`

**What:** Remove the two corresponding fields and constructor parameters; remove the imports for `TestSuiteRepository`, `SuiteSnapshotBuilder`, and `TestSuite`.

**Why:** With the live-suite fallback gone, these dependencies are dead weight on the export path. Trimming them aligns the constructor with the new behavior and yields a smaller blast radius for future bean-wiring changes.

**Alternatives considered:**

- *Leave the fields in place and simply stop calling them.* Rejected: a service with unused collaborators is a footgun for future refactors — someone WILL re-introduce a "while we're here, let's also try the live suite" branch. Removing them encodes the new contract in the constructor.

### Decision 3 — Spec is modified, not removed

**What:** Rewrite the existing "Legacy run snapshot handling" requirement in `openspec/specs/eval-summary-export/spec.md` rather than deleting it, and rewrite the affected preview-endpoint scenarios. The new wording continues to talk about legacy runs — but only to define their rejection.

**Why:** Spec readers benefit from explicit, named coverage of the legacy-run case (so they know the behavior was considered, not overlooked). Replacing the requirement preserves the cross-references from the rest of the spec.

**Alternatives considered:**

- *Delete the requirement outright and rely on the "Column set is derived from run snapshot" requirement to imply 422.* Rejected: that requirement talks about column derivation, not run admissibility — implicit coverage would be a regression in spec clarity.

### Decision 4 — Functional test slice

**What:** In `EvalSummaryExportFunctionalTests`, the existing "exports a legacy run by synthesizing from the live suite" scenarios are converted into "rejects a legacy run with 422" scenarios. We do not need a separate "live suite gone" scenario — the new behavior is independent of live-suite presence, so a single null-snapshot fixture row suffices for each endpoint (export and preview).

**Why:** The strongest signal of the new behavior is "snapshot null → 422, *no matter what else is true*". The previous matrix (live suite present vs gone) collapses to one case.

## Risks / Trade-offs

- **[Risk]** Operators export-running legacy runs see their requests start returning `422` after this lands. **Mitigation:** call this out in the merge commit body, the proposal, and (if applicable) the change-log channel before merging. The error response's `ErrorView.message` is unambiguous about the cause. The deferred-followup option is a one-shot backfill script outside this change.
- **[Risk]** Other call sites (outside `EvalSummaryExportService`) might still need the synthesize-from-live-suite path. **Mitigation:** `SuiteSnapshotBuilder` and the wrapper exception remain available; the snapshot phase in `TestSuiteEvaluationJob` uses both. We're only narrowing the *export* path.
- **[Trade-off]** We are choosing simplicity-of-contract over backwards-compatibility for an aging cohort. The cost is one round of operator communication; the benefit is a contract that matches the documented invariant.

## Migration Plan

- **Deploy:** Merge to `development` → CI/CD picks it up like any other change. No DB migration runs because the change is code-only.
- **Rollback:** Pure code revert. No data is mutated by this change, so there is no backward-compat constraint.
- **Validation post-deploy:** Confirm that the existing functional-test suite passes and that an ad-hoc curl against a known legacy run returns `422 SNAPSHOT_SUITE_MISSING`.

## Open Questions

_None._
