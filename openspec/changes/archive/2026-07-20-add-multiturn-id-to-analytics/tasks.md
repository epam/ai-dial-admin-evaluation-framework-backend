## 1. Schema & jOOQ codegen

- [x] 1.1 Add `V1.16__AddMultiTurnIdToTestCaseRunResults.sql` (analytics): idempotent `ALTER TABLE test_case_run_results ADD COLUMN IF NOT EXISTS multi_turn_id VARCHAR(36);` + `CREATE INDEX IF NOT EXISTS idx_results_run_multiturn ON test_case_run_results (test_suite_run_id, multi_turn_id, created_at_ms);` with a header comment explaining NULL = single-turn and the trailing-`created_at_ms` / partition-ready rationale.
- [x] 1.2 Add `V1.17__AddMultiTurnIdToEvalSummaries.sql` (analytics): idempotent `ADD COLUMN IF NOT EXISTS multi_turn_id VARCHAR(36);` + `CREATE INDEX IF NOT EXISTS idx_eval_summaries_run_multiturn ON test_case_eval_summaries (test_suite_run_id, multi_turn_id, created_at_ms);`.
- [x] 1.3 Run `./gradlew generateJooq` and commit the generated sources under `src/main/java-generated/` (both tables gain the `MULTI_TURN_ID` field + new index).

## 2. Domain models

- [x] 2.1 Add nullable `UUID multiTurnId` to `TestCaseRunResult`.
- [x] 2.2 Add nullable `UUID multiTurnId` to `EvalSummary`.

## 3. Data layer (record mappers + repositories)

- [x] 3.1 `TestCaseRunResultRecordMapper`: read `MULTI_TURN_ID` (VARCHAR → `UUID`, null-safe) into the model on all mapping paths.
- [x] 3.2 `PostgresTestCaseRunResultRepository`: set `MULTI_TURN_ID` in the batch INSERT and include it in SELECT projections (list, findById, export).
- [x] 3.3 `EvalSummaryRecordMapper`: read `MULTI_TURN_ID` into the model on all mapping paths (typed record + JOIN variants).
- [x] 3.4 `PostgresEvalSummaryRepository`: set `MULTI_TURN_ID` in the batch INSERT and include it in SELECT projections; leave the conflict target (natural key) unchanged.

## 4. Execution path (population)

- [x] 4.1 `MultiTurnExecutor.buildTurnRow` + `buildMultiTurnErrorRow`: set `multiTurnId(input.getMultiTurnId())` on every per-turn row and the degenerate error row.
- [x] 4.2 `EvaluationWorker`: set `multiTurnId` on the broken `0/0` sentinel row from the input's multi-turn id; leave the single-turn path setting NULL (no change).

## 5. Summary write mapping

- [x] 5.1 `EvalSummaryBatchWriteItemDto`: add optional nullable `UUID multiTurnId` (no `@NotNull`), with `@Schema` marking it optional/nullable.
- [x] 5.2 `EvalSummaryMapper`: map `multiTurnId` from the batch item to the model, defaulting to `null` when absent.
- [x] 5.3 Internal producer (`InProcessMetricEvaluationExecutor` / `EvalSummaryBatchWriteClient` path): copy `multiTurnId` verbatim from the source `TestCaseRunResult` into the batch-write item.

## 6. Read DTOs

- [x] 6.1 `TestCaseRunResultResponseDto`: add `UUID multiTurnId` with `@JsonInclude(NON_NULL)` + `@Schema`.
- [x] 6.2 `EvalSummaryResponseDto`: add `UUID multiTurnId` with `@JsonInclude(NON_NULL)` + `@Schema`.
- [x] 6.3 `TestCaseRunResultItemDto` (results batch-write): add optional nullable `UUID multiTurnId` + `@Schema`; persist it via the existing results batch-write path.
- [x] 6.4 Update the MapStruct/mapper wiring for the result/summary response DTOs so `multiTurnId` is auto-mapped (same-named field).

## 7. CSV export

- [x] 7.1 `EvalSummaryExportColumnPlanner`: add a `plain("multiTurnId", row -> row.getSummary().getMultiTurnId())` identity column positioned immediately before `turnIndex`.

## 8. Docs

- [x] 8.1 `docs/database-schema.md`: add the `multi_turn_id` column + new grouping index to both analytics tables; bump the analytics migration marker to V1.17; record it in the migration history table.
- [x] 8.2 AGENTS.md: update the multi-turn inline paragraph + turn-columns note to mention `multi_turn_id` on `test_case_run_results`/`test_case_eval_summaries`, and that it is exposed on the response DTOs and CSV.
- [x] 8.3 `docs/patterns/suite-run-snapshot.md`: if it enumerates result/summary columns, add `multi_turn_id`.

## 9. Tests

- [x] 9.1 Unit: `MultiTurnExecutor` sets the shared `multiTurnId` on all per-turn rows, the degenerate error row, and (via `EvaluationWorker`) the broken sentinel; single-turn path leaves it NULL.
- [x] 9.2 Unit: `EvalSummaryMapper` maps `multiTurnId` and defaults to null when absent.
- [x] 9.3 Functional: run a multi-turn suite end-to-end; assert all result rows and all summary rows for one multi-turn share the same non-null `multiTurnId`, and single-turn rows carry NULL / omit the field.
- [x] 9.4 Functional: `GET` single result + list results + list summaries expose `multiTurnId`; single-turn payload omits it (byte-compatible).
- [x] 9.5 Functional: `POST /api/v1/analytics/eval-summaries` without `multiTurnId` still succeeds (byte-compatible); with `multiTurnId` persists it. Same for the results batch-write item.
- [x] 9.6 Functional: eval-summary CSV export header contains `multiTurnId` immediately before `turnIndex`; multi-turn rows carry the id, single-turn cell is empty.
- [x] 9.7 Run `JooqSchemaDriftTest` + the analytics functional suite to confirm context boots with the new columns/indexes.
