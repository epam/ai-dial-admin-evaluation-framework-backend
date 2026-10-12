## Why

A/B evaluation — two suites bound to the same dataset that differ in deployment, prompt or metrics — cannot be
compared today: `GET /api/v1/analytics/metric-scores/comparison` rejects runs of different suites with 409. The
guard protects nothing technical: matching, recomputation and every downstream read are scoped per run and
never consult `test_suite_id`, and even within one suite the guard does not guarantee a shared dataset
(`datasetRef` may change between runs).

## What Changes

- Remove the same-suite guard from the run-comparison endpoint: any two distinct runs with a suite snapshot and
  a resolvable computation are comparable.
- Remove the corresponding 409 `INVALID_OPERATION` ("runs belong to different test suites") from the error
  semantics and renumber the guard order.
- No new guard (e.g. same-dataset) is introduced. Whether a comparison is meaningful — overlap, differing metric
  sets or `overallScore` definitions — is left to the client, which already receives per-run `totalRowCount`,
  `matchedRowCount` and the unmatched id lists.
- No change to the response shape, match key, recomputation or the `analytics.comparison.max-unmatched-rows` cap.

Goals: enable A/B comparison over a shared dataset with the smallest possible change.

Non-goals: a same-dataset / same-suite guard; signalling dataset, dataset-version or metric-set mismatches;
pairing differently named metrics across suites; the per-side test-case exclusion asymmetry and the
row-vs-test-case cap unit (pre-existing, tracked separately).

Current state: **Implemented** with the same-suite guard. This change is **Planned**.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `run-comparison-metric-scores`: the endpoint no longer requires both runs to belong to the same test suite;
  the different-suites 409 is removed from the validation and error-semantics requirement and its guard order.

## Impact

- Code: `RunComparisonService` (remove `requireSameSuite`), `RunComparisonController` (OpenAPI operation
  description and 409 response text).
- API: relaxation only — a request that previously failed with 409 now succeeds. No contract shape change; not
  breaking for existing clients.
- Data / DB / config: none. No migration, no new property.
- Security: unchanged — each run is still read through `TestSuiteRunService.getRun`, so access to a run is
  governed exactly as before.
- Risk: a comparison of runs over unrelated datasets returns low or zero overlap (empty `scores`, all rows
  unmatched) or hits the existing unmatched-rows cap 409; both are already-defined outcomes. Generic test case
  names shared by unrelated datasets can also pair falsely (see design.md — Risks).
- Docs: `openspec/specs/README.md` summary and the main spec's Purpose lose their "of one suite" wording.
- FE: no contract change, but a UI that currently restricts comparison to one suite may now offer cross-suite
  pairs.
- Tests: drop the unit guard test in `RunComparisonServiceTest`; turn the "different suites → 409" functional
  test into "different suites sharing a dataset → 200 with matched rows".
- Rollout: none beyond deployment; no feature flag.
