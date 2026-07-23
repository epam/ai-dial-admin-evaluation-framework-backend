## Context

The evaluation engine today runs one HTTP request per test case: fill the suite's `requestTemplate` from the case's flat `data` map via `inputBindings`, invoke the deployment, extract response columns, score metrics. A prior POC (`feat/17-multistep-support-poc`) added multi-turn by modeling each turn as a *separate* `test_cases` row grouped by a client-supplied `multiTurnId` + `turnIndex`. That worked but carried heavy machinery: cross-row assembly (`MultiTurnAssembler`), a `broken` sentinel, non-contiguous-turn handling (`last_turn_index`), distinct-`multiTurnId` snapshot paging, and a `multi_turn_id` grouping column duplicated onto every analytics table.

This design re-models multi-turn as a **single test case carrying an ordered array of turn data** (`multiTurnData: List<Map<String,Object>>`). Because one conversation is now one `test_case_id` and turns come from an array, turns are inherently contiguous `0..N-1` and grouping is free — which deletes most of the POC's complexity. The change is additive and needs **no migration of existing rows**.

Current-state anchors (development baseline):
- `test_cases.data` is `JSONB NOT NULL DEFAULT '{}'`, carried as a JSON `String` in the model, exposed as `Map<String,Object>` at the DTO boundary, validated field-by-field against the dataset `test_case_schema` (`FieldDefinitionDto[]`) by `TestCaseValidationService`.
- The query DSL flattens `data` into `data::<field>` bindings (`data->>'field'` scalar / `data->'field'` json) in `TestCaseFieldBindingsBuilder`; array `co`/`nc` already compile to JSONB `?` / `@> to_jsonb(...)` in `FilterTranslator`. Suite `testCaseFilter` is compiled to a jOOQ `Condition` in `QueryDslRunnableTestCaseSelector.compile()` (`experimental.query.service`) via `FilterTranslator` + `TestCaseFieldBindingsBuilder`, then passed to `PostgresTestCaseRepository` as an opaque `extraCondition` (`findValidByDatasetIdExcludingIdsMatching` / `countValidByDatasetIdExcludingIdsMatching`), which AND-combines it with `dataset_id`/`is_valid`/exclusion. The repo never sees the DSL filter tree or element bindings.
- Run results keyed `(test_suite_run_id, test_case_id, run_index, created_at_ms)`; eval summaries add `computation_id`. Metric eval is stateless per result row (`InProcessMetricEvaluationExecutor` + `MetricEvaluationWorker`). No `condition` field exists on TSMD today.
- Next migrations: meta **V1.26**, analytics **V1.13**.

## Goals / Non-Goals

**Goals:**
- Multi-turn as one additive, nullable `multiTurnData` array field; zero migration of existing test cases.
- One unified representation for filtering/validation (`turns(tc) = COALESCE(multi_turn_data, jsonb_build_array(data))`), so single-turn is the trivial 1-element case.
- Per-turn result/summary rows enabling turn-scoped conditional metrics (the POC's payoff).
- Preserve the battle-tested single-turn execution path untouched (regression safety).
- Flat CSV import/export via row-per-turn multiplication.

**Non-Goals:**
- Multi-turn projection/aggregation in `POST /queries/execute` (filter predicate only; aggregation is a run-results concern and results stay flat by design).
- Any change to `overallScore` semantics beyond per-turn rows being counted as equal samples in Phase-3 aggregates (accepted).
- MCP / tool-call multi-turn sequences (rejected at run creation with 409; forward-compatible).
- Configurable history/response paths — the chat-completions `messages` + `choices[0].message` shape stays hardcoded (as in the POC).

## Decisions

### D1 — Hybrid: coalesce at data/filter/validation, split at execution
`data` and `multiTurnData` are **mutually exclusive** (multi-turn ⇒ `data = '{}'`); the discriminator is `multiTurnData` present & non-empty. For filtering and validation we normalize to a coalesced turns array (one code path). For execution we keep the existing single-turn `EvaluationWorker` branch and add `MultiTurnExecutor`, dispatched on `multi_turn_data != null`.
- *Why:* filtering's ALL-turns-match is a universal quantifier over an array — SQL forces the coalesce form regardless, so unifying there removes duplicated translation. Execution's hot single-turn path is well-tested; routing it through a degenerate turn loop adds regression risk for no gain.
- *Alternatives:* full-unify everything (regresses hot path); fully-separate paths (duplicates the filter translation the POC-style split couldn't avoid).

### D2 — Contiguous turns ⇒ drop `multiTurnId` and `last_turn_index`
One conversation = one `test_case_id`; turns come from an ordered array and filtering/disabling act on the whole case, never individual turns. So turn indices are always contiguous `0..N-1`. Grouping is `test_case_id` (+ `run_index`); `turn.last ≡ (turn_index == total_turns - 1)`.
- *Alternatives (POC):* `multi_turn_id` column (needed only because turns were separate cases) and `last_turn_index` (needed only for non-contiguous survivors) — both unnecessary here.

### D3 — Validation & bounds
Each turn map validates against the same dataset schema via `TestCaseValidationService`; `is_valid` iff every turn passes; `validationWarnings` aggregate with an added per-warning `turnIndex`. `multiTurnData`, if present, MUST be non-empty (empty `[]` → 400). A configurable max-turns cap (`@ConfigurationProperties`, default 10) invalidates over-long cases via a warning (not 400), so bad CSV rows don't fail the whole import and invalid cases simply never run.

### D4 — ALL-turns-match filter, compiled once via a NOT EXISTS lateral
A multi-turn case is runnable iff every turn satisfies the filter. The lateral is built in `QueryDslRunnableTestCaseSelector.compile()` (`experimental.query.service`), which re-points `data::field` bindings from `TEST_CASES.DATA` to a lateral element `elem`, compiles the filter against `elem` via `FilterTranslator`, and wraps the resulting `Condition`:
```sql
AND NOT EXISTS (
  SELECT 1 FROM jsonb_array_elements(COALESCE(multi_turn_data, jsonb_build_array(data))) AS t(elem)
  WHERE (<filter compiled against elem>) IS NOT TRUE)
```
`compile()` feeds both the count and the load paths, so the wrapping covers both. The finished lateral `Condition` is handed to `PostgresTestCaseRepository` as the opaque `extraCondition` — the repo never sees the DSL filter tree or element bindings (building the lateral in the repo would require `data` to depend on `experimental.query.service`, which `LayeredArchitectureTest` forbids). Added **only when a filter is present** (no-filter selection is byte-identical to today). `IS NOT TRUE` makes a turn whose predicate is false *or unknown* (missing field) count as failing. `FilterTranslator` is untouched; only a `TestCaseFieldBindingsBuilder` overload building against a supplied JSONB source `Field` (`elem`, in `experimental.query.service`) is added.
- *Alternatives:* dual-branch `(multi_turn_data IS NULL AND <direct>) OR (… AND <lateral>)` compiles the filter twice; SQL/JSONPath rewrite of the whole tree is a larger, riskier change.

### D5 — Execution: reuse the POC turn loop, one input row per case
`EvaluationWorker.execute` returns `List<TestCaseRunResult>`. `MultiTurnExecutor` (permit-per-conversation, turns sequential): for each turn resolve the suite's single template/bindings against that turn's map → require a top-level `messages` array → append to running history → send full history non-streaming → append `choices[0].message` verbatim → extract per-turn scalar columns → persist a SUCCESS row (`turn_index=i`, `total_turns=N`). Fail-fast: first failing turn persists one ERROR row and stops; earlier SUCCESS rows are kept. Snapshot adds a `multi_turn_data` column to `test_case_run_inputs`; the existing per-case paging writes one input row per case (no `broken` flag, no `MultiTurnAssembler` — invalid/over-cap cases are already `is_valid=false` and excluded by runnable selection).

### D6 — Conditional metrics (reuse POC)
Nullable `condition` (`VARCHAR(2000)`) on TSMD. JSONata, evaluated per result row (per turn) over `{ data, response, turn }` with `turn.{index,total,last}`. Blank/null ⇒ always run; malformed ⇒ 400 at write time. Runtime (`ConditionExpressionEvaluator` → `ConditionDecision`): `true`→run; `false`→skip & omit the metric entirely; throws/non-boolean/null→skip but surface as `metricError::<name>` while the result stays SUCCESS. Only evaluated on SUCCESS rows. Dictionary preserves explicit JSON nulls (so `$exists(response.x)` works).

### D7 — Flat CSV via reserved `turnIndex` + contiguous-run assembly
Reserved headers become `{testCaseName, turnIndex}` (both excluded from `data` & schema auto-detection). Export: single-turn → 1 row (`turnIndex` blank); multi-turn → N rows sharing `testCaseName`, `turnIndex` `0..N-1`, in order. Import: group consecutive runs of equal `testCaseName`; a run with any non-blank `turnIndex` (or >1 row) ⇒ multi-turn, assembled into `multiTurnData` sorted by `turnIndex` (an ordering hint only — final positions are `0..N-1`); a lone blank-`turnIndex` row ⇒ single-turn. Contiguous-run assembly keeps import streaming/bounded-memory; export always emits multi-turn rows contiguously, so round-trips are safe. Conflict is per assembled `TestCase` through existing `save`/`insertOrSkip`/`insertOrOverride`.

### D8 — Guards
MCP suite bound to a dataset with any multi-turn case ⇒ 409 `INVALID_OPERATION` at run creation (cheap `existsMultiTurnByDatasetId`). DB CHECK `chk_test_cases_multi_turn_exclusive` (`multi_turn_data IS NULL OR data = '{}'::jsonb`) as defense-in-depth.

## Risks / Trade-offs

- Single-turn selection *with* a filter now runs a 1-element `jsonb_array_elements` lateral → Filtering runs at snapshot/count time (not per-request) and the array is trivially small; no-filter selection is unchanged. Acceptable.
- Per-turn rows weight Phase-3 aggregates by turn count (a 5-turn case contributes 5 samples) → Accepted by design; conditional metrics (`turn.last`) let authors scope a metric to one turn when needed.
- Routing multi-turn through new `MultiTurnExecutor` while the assistant-reply path is hardcoded to `choices[0].message` (OpenAI shape) → Kept isolated behind the executor so a non-chat body fails per-conversation at runtime (ERROR row), not at suite validation; generalization deferred.
- `EvaluationWorker.execute` return-type change to `List<TestCaseRunResult>` touches the single-turn call site → Wrap existing single result in `List.of(...)`; covered by existing functional tests plus new ones.
- Hand-edited CSVs that scatter a multi-turn name non-contiguously → Treated as a conflict/row error (documented), not silently merged.

## Migration Plan

1. Meta migrations V1.26 (`condition` on `test_suite_metric_definitions`), V1.27 (`multi_turn_data` on `test_cases` + CHECK), V1.28 (`multi_turn_data` on `test_case_run_inputs`); analytics V1.13/V1.14 (`turn_index`,`total_turns` + extended unique keys, `NOT NULL DEFAULT 0/1` backfill). All additive/metadata-only on PG 11+.
2. `./gradlew generateJooq`; commit generated sources; update `docs/database-schema.md`.
3. Add the max-turns config property (default in `application.yml`) + a `docs/configuration.md` row.
4. Rollback: columns are nullable/defaulted and the CHECK only rejects a state the app never writes; dropping the columns reverts behavior. No data backfill to undo.

## Open Questions

None blocking. Deferred by explicit decision: generalized history/response extraction, MCP multi-turn, `/execute` multi-turn projection, and any turn-count-aware weighting of `overallScore`.
