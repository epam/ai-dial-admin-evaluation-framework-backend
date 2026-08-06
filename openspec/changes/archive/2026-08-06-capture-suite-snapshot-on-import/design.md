## Context

`TestSuiteEvaluationJob.executeRunAsync(runId, token, skipDeploymentPhase)` currently gates the entire snapshot phase behind `!skipDeploymentPhase`:

```java
if (!skipDeploymentPhase && !executeSnapshotPhase(runId)) {
    return;
}
...
if (!skipDeploymentPhase) {
    // inconsistent-snapshot guard
    // Phase 1: Deployment evaluation
}
// Phase 2 / Phase 3 (always run)
```

`executeSnapshotPhase` → `attemptSnapshot` does two logically separate things inside one `ISOLATION_REPEATABLE_READ` transaction on the meta datasource:
1. Build `SuiteSnapshotDto` (via `SuiteSnapshotBuilder.build(suite, dataset)`) and persist it to `test_suite_runs.suite_snapshot`.
2. Page all currently-runnable `TestCase` rows via `RunnableTestCaseSelector.loadRunnablePage` and batch-insert `test_case_run_inputs`, then overwrite `test_suite_runs.number_of_test_cases` with the paged count.

Imported runs (`TestSuiteRunService.importResultsAndEvaluate`) are dispatched with `skipDeploymentPhase=true` and already set `numberOfTestCases = results.size()` (the actual CSV row count) at `createAndSaveRun` time. Because the whole snapshot phase is skipped for them, `suite_snapshot` stays `null`, and `computeMetricScores` → `resolveSnapshot(run)` falls into the legacy branch that re-synthesizes a transient (non-persisted) snapshot from the **live** suite/dataset at Phase-3 time — the exact "config could have drifted since the run happened" problem the snapshot mechanism exists to prevent for normal runs.

Metric snapshots (`run_metric_snapshots`, written by Phase 2 unconditionally) and score results (`MetricScoreResult`, written by Phase 3 unconditionally) are already captured correctly for imports — this design only closes the `suite_snapshot` gap.

## Goals / Non-Goals

**Goals:**
- Persist `suite_snapshot` for every run, including imported runs, so Phase 3 and retrospective `GET .../runs/{id}` reads see a frozen record instead of a live resynthesis.
- Never write `test_case_run_inputs` or overwrite `number_of_test_cases` for imported runs — that bookkeeping exists solely to drive Phase 1, which imported runs never execute, and overwriting `numberOfTestCases` would corrupt the CSV-derived count already set at run creation.
- Keep the change confined to `TestSuiteEvaluationJob`; no API, DTO, or DB schema changes.

**Non-Goals:**
- Reconciling the imported CSV's actual test-case set against the live dataset's runnable set (out of scope — CSV rows already carry their own `testCaseId`/`testCaseData`, independent of `test_case_run_inputs`).
- Deriving `test_case_run_inputs` rows for an imported run from its `test_case_run_results`. `test_case_run_inputs` is not a retrospective "what were the inputs" record for *any* run, imported or normal — it is Phase 1's execution work queue (the runnable test cases Phase 1 will invoke the deployment for), and it is deliberately ephemeral: `TestCaseRunInputsRetentionJob` deletes it a day (default) after the run reaches a terminal status — the only retention job in the codebase, and it targets `test_case_run_inputs` exclusively. `test_suite_runs.suite_snapshot` is untouched by that job (or any other) and persists for the life of the run row, which is exactly why it — not `test_case_run_inputs` — is the right thing to make durable for imported runs. The durable, queryable record of what inputs actually produced a result — for both normal and imported runs — is `test_case_run_results.test_case_data` (analytics, append-only, no retention job); for imports it is populated directly and exactly from the caller-supplied CSV `testCaseData` column. Since Phase 1 never runs for an imported run, nothing would ever read a derived `test_case_run_inputs` row, so writing one would just be a second, redundant, soon-deleted copy of data that already lives durably elsewhere.
- Changing how Phase 2 discovers TSMDs (it already reads live TSMD config by design — that's a separate, intentional "computation versioning" decision, not part of this gap).
- Introducing a `run_source`/kind column to distinguish imported runs (the codebase deliberately has none — see `eval-results-import` spec's Implementation Notes).

## Decisions

### Decision 1: Split `attemptSnapshot` via a `captureTestCaseInputs` boolean, rather than two separate methods

`attemptSnapshot(UUID runId, boolean captureTestCaseInputs)`:
- Always: delete leftover `test_case_run_inputs` (idempotent no-op when there never were any for this run), load suite + dataset, build + serialize `SuiteSnapshotDto`, call `repository.updateSuiteSnapshot(runId, snapshotJson, now)`.
- Only when `captureTestCaseInputs`: run the existing paging loop + `testCaseRunInputRepository.insertBatch` + `repository.updateNumberOfTestCases(runId, totalInputs, now)`.

`executeSnapshotPhase(UUID runId, boolean captureTestCaseInputs)` threads the flag through on every retry attempt; the SQLSTATE-40001 retry logic and error-code mapping (`SNAPSHOT_SERIALIZATION_CONFLICT`/`SNAPSHOT_SUITE_MISSING`/`SNAPSHOT_DATASET_MISSING`/`SNAPSHOT_FAILED`) are unchanged and apply identically regardless of the flag.

**Alternative considered**: two separate methods (`attemptSuiteSnapshot` / `attemptSuiteSnapshotWithInputs`). Rejected — it would duplicate the transaction setup, the suite/dataset loading, and the retry wrapper for a difference that's a single conditional block; a boolean parameter keeps the one code path and one retry loop, and the two behaviors are never independently reused elsewhere.

### Decision 2: Call `executeSnapshotPhase` unconditionally; keep the inconsistent-snapshot guard and Phase 1 gated by `!skipDeploymentPhase`

```java
if (!executeSnapshotPhase(runId, !skipDeploymentPhase)) {
    return;
}
now = clock.millis();
repository.updateToRunning(runId, now, now);
notifySse(runId);
TestSuiteRun run = repository.findById(runId).orElseThrow(...);

if (!skipDeploymentPhase) {
    // inconsistent-snapshot guard (hasSnapshot != hasInputs)
    // Phase 1: Deployment evaluation
}
// Phase 2 / Phase 3 (unchanged)
```

The inconsistent-snapshot guard checks `suite_snapshot != null` XOR `test_case_run_inputs exists`. For imported runs this pair is now, by design, `(true, false)` — an intentional asymmetry, not a corruption signal — so the guard must stay scoped to `!skipDeploymentPhase`, where the invariant "both or neither" still holds because Phase 1 is the only consumer of `test_case_run_inputs`.

**Alternative considered**: generalize the guard to compare `hasSnapshot` against `captureTestCaseInputs`-aware expectations (e.g., `hasSnapshot != (hasInputs || skipDeploymentPhase)`). Rejected as unnecessary complexity — the guard's purpose is to protect Phase 1's data dependency; once Phase 1 is unconditionally skipped for a run, there's nothing left for the guard to protect, so leaving it inside the existing `!skipDeploymentPhase` block is simplest and preserves its original, well-tested semantics for normal runs untouched.

### Decision 3: No change to `resolveSnapshot`, `buildContext`, `SuiteSnapshotBuilder`, or any DTO/controller

Once `suite_snapshot` is reliably non-null for imported runs, `resolveSnapshot(run)`'s existing "primary path" (deserialize the stored JSON, validate `snapshotVersion`) naturally takes over from the "legacy fallback" path — no branching logic needs to change there. `TestSuiteRunMapper.toDto` and `TestSuiteRunResponseDto.suiteSnapshot` already expose the field unconditionally.

## Risks / Trade-offs

- **[Risk] A suite could be mutated between fetch/run (in `eval-cli`) and import**, so the persisted snapshot reflects the suite's config *at import time*, not at run time (the CLI doesn't call back into the backend until import). → **Mitigation**: this is the same caveat that already exists for the CSV data itself (results were produced against whatever the deployment/endpoint were when `eval-cli run` executed, not necessarily what's configured now); it's a pre-existing characteristic of the "run outside, import later" design, not something this change worsens — the alternative (no snapshot at all) is strictly worse for retrospection.
- **[Risk] Extra DB round-trip (suite + dataset load, JSON serialize, one UPDATE) per imported run.** → **Mitigation**: negligible — identical cost to what normal runs already pay in the snapshot phase; imports are not a high-frequency, latency-sensitive path.
- **[Trade-off] `attemptSnapshot`'s signature grows a boolean parameter**, which slightly weakens self-documentation at call sites. → **Mitigation**: the sole call site (`executeSnapshotPhase`, itself only called from `executeRunAsync`) makes the intent clear via the `!skipDeploymentPhase` argument name; no external callers exist.
