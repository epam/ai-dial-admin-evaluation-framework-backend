## Context

See proposal.md — Why. After `allow-cross-suite-run-comparison`, `RunComparisonService.compare` loads both runs
via `TestSuiteRunService.getRun`, then `requireSnapshot` on each, then resolves computations. Each run's
`TestSuiteRunResponseDto` carries `testSuiteId` and the deserialized `SuiteSnapshotDto`, whose `datasetRef`
(`DatasetReferenceDto{id, version, name}`) is present for v2+ snapshots and null for v1.

## Goals / Non-Goals

**Goals:**
- One private guard in `RunComparisonService`, no new class, query or response field.

**Non-Goals:**
- Reading the live suite→dataset binding; comparing `datasetRef.version`.

## Decisions

### 1. Same suite OR same snapshot dataset id

`comparable = sameSuite || (refA != null && refB != null && refA.id == refB.id)`. The suite branch keeps every
same-suite comparison working, including one whose dataset was re-bound between runs and a legacy run without
`datasetRef`. The dataset branch is the A/B case.

*Alternative rejected:* dataset-only. It would break same-suite runs taken across a dataset re-bind, and
legacy v1 snapshots (no `datasetRef`) could never be compared at all.

### 2. Snapshot dataset, not live binding

The snapshot records what the run actually executed against; the live binding can change after the run.
Reading the snapshot also needs no extra meta query — it is already on the DTO.

### 3. Placement: after the snapshot guard

The dataset branch needs the snapshot, so the check runs after both `requireSnapshot` calls and before
computation resolution. Consequence: a legacy different-suite pair gets 422, not 409 — the same outcome as today,
and already asserted by `shouldFailDifferentSuiteLegacyRunOnSnapshotGuard`.

### 4. Error

409 `InvalidOperationException`, message naming that the runs share neither a suite nor a dataset. Same status
the original same-suite guard used, so the controller's 409 description just gains the case back.

## Risks / Trade-offs

- [Cloned datasets are not comparable] → Accepted; a clone has a new id. Out of scope.
- [Dataset edited between runs passes the check] → Accepted; same id, different version. Overlap reporting
  shows the effect.

## Migration Plan

None. Rollback is reverting the commit.
