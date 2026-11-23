## Why

When a test case worker throws an unexpected exception, the executor is meant to write a synthetic `ERROR` result row so the failure stays visible. Today that row is built incomplete in three ways:

1. It has no suite id. The result write dereferences the suite id (and the column is NOT NULL), so the write of the batch containing the synthetic row fails. The failure is only logged, and the batch is not restored, so the synthetic row is lost together with up to `result-batch-size - 1` result rows of other test cases.
2. It has no extraction values (`extracted_columns` / `extraction_warnings`, both NOT NULL), which fails the same write even once the suite id is present. This surfaced during implementation, when the reproducing test still failed after the suite id fix.
3. It is stamped with `created_at_ms = clock.millis()`, while every other result of the run carries the run's own `created_at_ms`. Run-scoped reads select a run's rows by that timestamp, so even a persisted synthetic row would be skipped by metric evaluation, the run-scoped results API and exports.

The requirement's stated purpose ("makes per-case worker bugs visible in the analytics surface") is therefore not met: the failing case disappears, it can take healthy cases with it, and pass rates come out too high. This must be fixed before the System Health dashboard (pass-rate trends) is built on the same data.

## What Changes

- The synthetic `ERROR` result row SHALL carry the run's suite id, empty extraction values, and the run's `created_at_ms` — every value the result store requires, with the same suite id and timestamp every other result row of the run carries. `execStartedAtMs` / `execCompletedAtMs` keep the current clock time.
- `TestCaseRunResultFactory.errorResult(...)` receives the run's suite id and creation timestamp (from `EvaluationContext`) in addition to the current time; `TestCaseRunner` supplies them.
- Tests: a reproducing functional test first, then unit and functional coverage of the fixed behaviour, including that other test cases' rows in the same batch survive.

Current state: **Implemented** but defective (`TestCaseRunner.runWorker`, `TestCaseRunResultFactory`). Target state: **Planned** by this change.

Goals:
- A worker-exception test case gets a persisted `ERROR` result row, and persisting it does not lose other rows.
- That row is returned by run-scoped result reads and produces an eval summary row with a non-SUCCESS status.

Non-goals:
- Pass-rate handling of failed test cases in suites that have no overall score definition. Those cases are currently counted as passed for every kind of ERROR row, not only synthetic ones; that is a separate change.
- No recovery of rows lost by past runs.
- No change to the error envelope, to the best-effort / no-retry contract, to cancellation behaviour (no synthetic rows for interrupted cases), or to the existing double increment of the progress counter when a threshold flush fails.
- No schema, API, configuration or migration change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `eval-execution-engine`: requirement "Synthetic ERROR result for worker exception" — the synthetic row carries the run's suite id and creation timestamp, is persisted without failing its batch, and is visible to run-scoped reads and to metric evaluation.

## Impact

- Code: `evaluation-runner-core` — `TestCaseRunner`, `TestCaseRunResultFactory` (signature change, one caller). No new packages or classes.
- Specs: `eval-execution-engine` (delta in this change); `evaluation-runner-core-module` repeats the factory's inputs in prose and is updated by a task.
- Consumers: `eval-cli` uses the same runner and does not use the suite id; its output changes only in the `createdAt` value of a synthetic row.
- Behaviour visible to clients: runs with worker exceptions now show the failed test case in results, eval summaries and exports, and no longer lose neighbouring rows. For suites with an overall score definition their pass rate drops accordingly. This is a correction, not a contract change.
- Data / API / security / config: none.
- Risks: a synthetic row now enters metric evaluation; it is an `ERROR` result like the worker's own error rows, which that phase already propagates into eval summaries. Covered by the functional test.
- Rollout: ships with the next release; rollback is a plain revert (no data written in a new shape).
- Test plan: unit tests in `evaluation-runner-core` (`TestCaseRunResultFactoryTest`, `TestCaseRunnerTest`), plus a functional test under `PostgresFunctionalTests` proving the synthetic row and a neighbouring SUCCESS row are both persisted, the synthetic row is returned by a run-scoped read, and it produces an eval summary.
