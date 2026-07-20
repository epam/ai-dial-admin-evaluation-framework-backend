## Why

The frontend needs to group multi-turn test-case rows back into a single multi-turn when rendering results and summaries. Today the two analytics surfaces are unequal: `test_case_run_results` rows can be grouped via the shared `trace_id` + `run_index`, but `test_case_eval_summaries` rows carry **no span identifier at all** (`trace_id` was never added there), so each turn — now its own `test_case_id` — is an unlinkable island. The `multi_turn_id` that already keys the source `test_cases` rows is simply not propagated onto either analytics table. Exposing it directly gives both surfaces one explicit, stable grouping key.

## What Changes

- Add a nullable `multi_turn_id VARCHAR(36)` column to **both** `test_case_run_results` and `test_case_eval_summaries` (NULL ⇒ single-turn, matching the `test_cases` convention). Two Flyway analytics migrations + `generateJooq`.
- Add a plain (non-unique) btree index `(test_suite_run_id, multi_turn_id, created_at_ms)` to both tables — grouping/equality prefix first, `created_at_ms` trailing to align with the existing `(created_at_ms, id)` keyset spine and stay time-partition-ready. **Not** part of any UNIQUE/natural key.
- Thread `multiTurnId` (nullable `UUID`) onto the `TestCaseRunResult` and `EvalSummary` domain models, both analytics record mappers, and both repository INSERT/read paths.
- Populate it in `MultiTurnExecutor` from `input.getMultiTurnId()` on every per-turn row, the degenerate "no readable turns" ERROR row, and the broken `0/0` sentinel row (`EvaluationWorker`); the single-turn execution path sets NULL.
- Expose `multiTurnId` (nullable `UUID`, `@JsonInclude(NON_NULL)`) on `TestCaseRunResultResponseDto` and `EvalSummaryResponseDto`.
- Add `multiTurnId` as an **optional nullable** `UUID` field on both public batch-write item DTOs — `EvalSummaryBatchWriteItemDto` and `TestCaseRunResultItemDto` (no `@NotNull`; mappers default to NULL) — so existing single-turn external callers of `POST /api/v1/analytics/eval-summaries` and the results batch-write endpoint stay byte-compatible, exactly as `turnIndex`/`totalTurns` already are.
- Add `multiTurnId` to the eval-summary CSV export as an identity column placed **before** `turnIndex` (`…runIndex, multiTurnId, turnIndex, totalTurns`), mirroring the test-case CSV column order.
- Record ClickHouse design intent (see `design.md`): the future CH provider partitions monthly by `toYYYYMM(created_at_ms)` and prepends `multi_turn_id` to its `ORDER BY` natural key; the Postgres btree index added here is a Postgres-only optimization that CH does not use.

No breaking changes: every addition is a nullable/optional field or an appended index; single-turn shapes are unchanged and the response DTOs omit the field when NULL.

## Capabilities

### New Capabilities
<!-- none — this extends existing analytics capabilities -->

### Modified Capabilities
- `analytics-eval-results`: `test_case_run_results` gains a `multi_turn_id` column + grouping index; the results batch-write item DTO (`TestCaseRunResultItemDto`) gains an optional `multiTurnId`; the result response DTO and single-result GET expose `multiTurnId`.
- `eval-summary-export`: eval-summary rows gain a `multi_turn_id` column + grouping index; the summary response DTO exposes `multiTurnId`; the CSV export gains a `multiTurnId` identity column before `turnIndex`.
- `multi-turn`: each persisted turn row (result and summary) now carries the source multi-turn's `multi_turn_id`; single-turn rows carry NULL.

## Impact

- **Schema / migrations (Planned):** two new analytics Flyway migrations `V1.16__AddMultiTurnIdToTestCaseRunResults.sql` and `V1.17__AddMultiTurnIdToEvalSummaries.sql` (each: `ADD COLUMN IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS`, idempotent). Re-run `./gradlew generateJooq` and commit generated sources. Update `docs/database-schema.md`.
- **Domain models:** `TestCaseRunResult`, `EvalSummary` (+ nullable `UUID multiTurnId`).
- **Data layer:** `TestCaseRunResultRecordMapper`, `EvalSummaryRecordMapper`, `PostgresTestCaseRunResultRepository`, `PostgresEvalSummaryRepository` (INSERT + SELECT projections).
- **Service / job layer:** `MultiTurnExecutor`, `EvaluationWorker` (sentinel row), `EvalSummaryMapper`, and the internal summary-write producer (`InProcessMetricEvaluationExecutor` / `EvalSummaryBatchWriteClient` path).
- **Web / DTOs:** `TestCaseRunResultResponseDto`, `EvalSummaryResponseDto`, `EvalSummaryBatchWriteItemDto`, `TestCaseRunResultItemDto`; OpenAPI `@Schema` examples for the new field.
- **CSV:** `EvalSummaryExportColumnPlanner` identity-column list.
- **Docs:** `docs/database-schema.md`; AGENTS.md multi-turn inline paragraph + turn-columns note; `docs/patterns/suite-run-snapshot.md` if it enumerates the result/summary columns.
- **No config changes**, no new external dependencies, no security surface change.
