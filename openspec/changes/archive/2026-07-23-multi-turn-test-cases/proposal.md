## Why

The evaluation engine runs exactly one request per test case, but many assistant use cases are multi-turn — the quality of a turn depends on the conversation that preceded it. An earlier POC (`feat/17-multistep-support-poc`) modeled a conversation as *many* test-case rows (one row per turn) grouped by a client-supplied `multiTurnId`, which forced non-trivial cross-row assembly, ordering/gap handling, and analytics grouping keys. This change re-models multi-turn as a **single test case carrying an ordered array of turn data** (`multiTurnData`), eliminating that machinery while keeping the POC's per-turn results and turn-scoped conditional metrics. It requires **no migration of existing test cases** — the new column is additive and nullable.

## What Changes

- **New `multiTurnData` field on a test case** — an ordered array of maps (each map is one turn's data, same shape as the flat `data` map). A test case is single-turn (`data`) **or** multi-turn (`multiTurnData`), mutually exclusive. Multi-turn is **emergent from data** — no suite flag.
- **Per-turn validation** — each turn map validates against the same dataset `test_case_schema`; the case is `is_valid` iff every turn passes; warnings carry the offending turn index. Configurable max-turns cap (default 10) invalidates over-long cases.
- **Multi-turn execution** — a new `MultiTurnExecutor` drives a sequential chat-completions turn loop (accumulate `messages` history, re-send full history each turn, non-streaming, append `choices[0].message` verbatim), fail-fast on the first failing turn. The existing single-turn path is untouched.
- **Per-turn result rows** — each turn persists as its own `test_case_run_results` / `test_case_eval_summaries` row with `turn_index` / `total_turns`; grouping is via the shared `test_case_id` (no `multiTurnId`), turns are always contiguous `0..N-1` (no `last_turn_index`).
- **Conditional metric execution** — an optional JSONata `condition` on a Test Suite Metric Definition, evaluated per result row over `{ data, response, turn }` (with `turn.index/total/last`), gating whether that metric runs on that turn.
- **ALL-turns-match test-case filtering** — the suite `testCaseFilter` (and the query DSL `test_cases` entity's filter predicate) matches a multi-turn case iff **every** turn satisfies the filter, compiled once against a coalesced turns array via a `NOT EXISTS` lateral.
- **Flat CSV import/export** — a multi-turn case is "multiplied" to one flat row per turn, grouped by a new reserved `turnIndex` column via contiguous-run assembly.
- **Guards** — MCP suites bound to a dataset containing any multi-turn case are rejected at run creation (409); a DB CHECK enforces `data`/`multiTurnData` mutual exclusivity.
- **BREAKING (internal wiring, not the public single-turn contract):** `EvaluationWorker.execute` returns `List<TestCaseRunResult>`; result/summary unique keys gain `turn_index`.

All single-turn behavior and the existing REST contract for single-turn test cases are preserved (fields are `@JsonInclude(NON_NULL)`, columns default to single-turn values).

## Capabilities

### New Capabilities
- `multi-turn-conversation`: the multi-turn domain concept — `multiTurnData` authoring model, mutual exclusivity, per-turn validation, turn-count bounds, the sequential turn-loop execution semantics (history assembly, fail-fast, contract), and flat CSV multiplication.
- `conditional-metric-execution`: optional per-metric `condition` (JSONata over `{data, response, turn}`), RUN/SKIP/ERROR runtime semantics, and write-time syntax validation.

### Modified Capabilities
- `test-cases`: add `multiTurnData` to request/response/batch DTOs and the model; per-turn schema validation; PATCH-able with mutual exclusivity; new `multi_turn_data` column + CHECK constraint.
- `test-suite-metric-definitions`: add nullable `condition` field to model and DTOs; new `condition` column.
- `tsmd-validation`: validate JSONata `condition` syntax at write time (400 on malformed).
- `suite-test-case-filter`: ALL-turns-match semantics for multi-turn cases (universal quantifier; null-turn = fail).
- `eval-execution-engine`: dispatch to `MultiTurnExecutor` on `multi_turn_data`; sequential turn loop; one permit per conversation; per-turn result emission; fail-fast.
- `suite-run-snapshot`: freeze `multi_turn_data` into `test_case_run_inputs` (one input row per case; no `broken`/assembler).
- `analytics-eval-results`: per-turn result rows; `turn_index` / `total_turns` columns; unique key extended with `turn_index`.
- `metrics-storage`: eval summaries gain `turn_index` / `total_turns`; natural key extended with `turn_index`.
- `metric-evaluation`: metrics evaluated per turn row; conditional gating integrated (SKIP omits the metric, ERROR surfaces as `metricError::<name>` without failing the row).

## Impact

- **DB migrations** — meta: `V1.26__AddConditionToTestSuiteMetricDefinitions.sql`, `V1.27__AddMultiTurnDataToTestCases.sql` (+ mutual-exclusivity CHECK), `V1.28__AddMultiTurnDataToTestCaseRunInputs.sql`; analytics: `V1.13__AddTurnColumnsToTestCaseRunResults.sql`, `V1.14__AddTurnColumnsToEvalSummaries.sql`. Regenerate jOOQ (`./gradlew generateJooq`); update `docs/database-schema.md`.
- **New config property** — configurable max-turns (default 10); add a row to `docs/configuration.md`.
- **New classes** — `MultiTurnExecutor`, `DeploymentTurnInvoker`/`DeploymentInvocationSupport`, `ConditionExpressionEvaluator` + `ConditionContext` + `ConditionDecision`, `TsmdEvaluationResult.ConditionError`, a per-turn `TestCaseFieldBindingsBuilder` overload; CSV turn grouping/expansion.
- **APIs** — additive fields on test-case, TSMD, run-result, and eval-summary DTOs; behavior change to run-creation guards (MCP 409). Update OpenAPI `@Schema`/example files.
- **Experimental query DSL** — `test_cases` filter predicate gains the coalesced ALL-turns-match lateral; no projection/aggregation change (out of scope).
- **Reused/adapted from POC** — turn-loop, assistant-reply extraction, conditional-metric evaluator, fail-fast semantics; **dropped** vs POC: `multiTurnId`, `last_turn_index`, `broken` flag, `MultiTurnAssembler`, distinct-id snapshot paging.
