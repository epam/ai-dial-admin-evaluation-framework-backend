## Why

`allow-cross-suite-run-comparison` removed the same-suite guard outright, so any two runs are comparable —
including runs over unrelated datasets, where generic test case names (`case 1`) can pair falsely and produce
plausible-looking numbers. The target scenario was A/B over a **shared dataset**; restrict comparison to that,
plus the original same-suite case.

## What Changes

- The comparison endpoint SHALL accept two runs only when they belong to the same test suite **or** their
  suite snapshots reference the same dataset (`suiteSnapshot.datasetRef.id`).
- Otherwise it SHALL fail with 409 `INVALID_OPERATION`. A run whose snapshot has no `datasetRef` (legacy v1
  snapshot) can only be compared within its own suite.
- The dataset is taken from each run's snapshot (the dataset the run actually executed against), not from the
  suite's current binding. Dataset **version** is not compared.
- The check runs after the missing-snapshot 422, since it reads the snapshot.
- No change to the response shape, match key, recomputation or the unmatched-rows cap.

Goals: block comparisons with no shared population basis, with the smallest possible change.

Non-goals: comparing dataset versions; treating cloned datasets as equal; any response signal about suite or
dataset identity.

Current state: **Implemented** without any suite/dataset guard (`allow-cross-suite-run-comparison`). This
change is **Planned**.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `run-comparison-metric-scores`: the endpoint requires a shared suite or a shared snapshot dataset; the
  validation requirement gains the 409 for runs sharing neither, and its guard order.

## Impact

- Code: `RunComparisonService` (one guard), `RunComparisonController` (409 response text).
- API: tightening relative to the previous change — a cross-suite pair over different datasets, accepted since
  `allow-cross-suite-run-comparison`, now gets 409. Same-suite pairs are unaffected, including a same-suite pair
  whose dataset was re-bound between runs. Both changes are unreleased on this branch, so no deployed client
  sees the relaxation being withdrawn.
- Data / DB / config: none.
- Docs: main spec Purpose and `openspec/specs/README.md` summary.
- Tests: unit tests for each branch of the guard; the functional "different suites, no shared names → 200" test
  becomes "different suites, different datasets → 409".
- Rollout: none.
