## Why

When a user changes the type of a field in `testCaseSchema` (e.g. BOOLEAN → STRING), every existing test case row whose `data` still holds a value of the old type is immediately marked **invalid** by the post-update `RevalidationService`. Reported as bug [admin-backend#927](https://github.com/epam/ai-dial-admin-backend/issues/927) — "Test case becomes invalid after schema type change (boolean to string)". The validator's behaviour is correct (a Boolean *is not* a String), but the missing piece is an **auto-coercion pass before validation** for safe, well-defined conversions. CSV import already does this via `SchemaTypeCoercer`; the schema-change revalidation path does not. As a result, a one-line schema edit can turn thousands of valid rows red, even when every cell could be losslessly reinterpreted.

## What Changes

- **NEW** `SchemaChangeCoercer` (`service.domain.csv` package, alongside `SchemaTypeCoercer`) — a sibling coercer that implements a **stricter** conversion table specifically for schema-change revalidation. CSV import keeps using the existing permissive `SchemaTypeCoercer`; the two contexts now have explicitly distinct rules.
- **MODIFIED** `RevalidationService.runRevalidationAsync` — for each test case, before validation: attempt coercion of every `data` field to its current schema type. If any cell was coerced (its value changed), persist the new `data` JSONB **first**, then re-validate the (in-memory, post-coercion) data and persist `is_valid`/`warnings` in a **second** UPDATE. Both writes are guarded by an `updated_at` precondition; if the row was edited by another caller mid-revalidation, both updates are skipped and the row is left untouched.
- **MODIFIED** `TestCaseRepository` — two new methods: `updateDataIfUnchanged(id, suiteId, dataJson, expectedUpdatedAt, newUpdatedAt)` and `updateValidationIfUnchanged(id, suiteId, isValid, warnings, expectedUpdatedAt, newUpdatedAt)`, both returning `int` rows-affected for guard-miss detection. Existing unguarded `updateValidation(...)` is kept as-is for callers that don't need the guard.
- **MODIFIED** `RevalidationTask` model + `revalidation_tasks` table — new `coerced_cell_count` (BIGINT, default 0) column, surfaced as `coercedCellCount` (Long) in `RevalidationTaskDto` and the GET task response. Counts the **total cells** auto-converted across all rows in the run (not rows). Flyway migration on the meta DB.
- **MODIFIED** validation spec scenarios for the Boolean→STRING (and other coercible) cases that today say "produces TYPE warning" must be qualified: they still hold for **direct API writes (POST/PUT/PATCH)**, but on the **schema-change revalidation path** the value is coerced first and the row stays valid.
- **OUT OF SCOPE**: no changes to CSV import coercion table; no coercion on direct test case PATCH/PUT writes; no retroactive coercion of historical `test_suite_runs.suite_snapshot` or `test_case_run_inputs` (snapshots are immutable per the suite-run-snapshot contract); no UI/FE work.

## Capabilities

### New Capabilities
<!-- None — this change extends an existing capability rather than introducing a new bounded context. -->

### Modified Capabilities
- `test-cases`: the "Schema update triggers async re-validation" requirement and the validation/coercion scenarios are extended so revalidation **coerces before validating** under a strict conversion table; type-mismatch scenarios for coercible source/target pairs now produce a coerced cell instead of a TYPE warning (only on the revalidation path; direct API writes are unchanged). New scenarios cover the strict conversion table, FILE-target carve-out, the two-update-with-`updated_at`-guard persistence pattern, and the new `coercedCellCount` field.

## Impact

- **Code**:
  - New: `service.domain.csv.SchemaChangeCoercer` (@Component, @LogExecution).
  - Modified: `service.domain.RevalidationService`, `data.db.repository.TestCaseRepository` + `PostgresTestCaseRepository`, `data.db.model.RevalidationTask`, `data.db.mapper.RevalidationTaskRowMapper`, `service.domain.dto.RevalidationTaskDto`, `service.domain.mapper.RevalidationMapper` (or inline mapping in `RevalidationService.toDto`).
- **Database**: Flyway migration on **meta** DB — `db/migration/meta/POSTGRES/V{next}.0__AddCoercedCellCountToRevalidationTasks.sql` adds `coerced_cell_count BIGINT NOT NULL DEFAULT 0`.
- **API**: `GET /api/v1/test-suites/{id}/revalidation-tasks/{taskId}` response gains `coercedCellCount` (Long). `POST .../revalidation-tasks` HTTP 202 body and pagination response also include it. Backwards compatible additive field.
- **OpenAPI**: update revalidation example JSON files in `src/main/resources/openapi/examples/` to include the new field; update `@Schema example` on `RevalidationTaskDto.coercedCellCount`.
- **Docs**: update `docs/database-schema.md` with the new column; AGENTS.md "Unique Patterns" gets a one-paragraph entry contrasting `SchemaTypeCoercer` (CSV import) vs `SchemaChangeCoercer` (schema-change revalidation).
- **Tests**:
  - Unit: `SchemaChangeCoercerTest` exercises every cell of the conversion table (positive coercions + every "skip" case) and confirms identity behaviour for already-matching types.
  - Functional (`PostgresFunctionalTests$RevalidationTests`): Boolean→STRING happy path (cells coerced, row stays valid, `coercedCellCount > 0`), Object→STRING (skipped, row invalid), Number→FILE (skipped, row invalid), concurrent edit during revalidation (guard miss → row's `data` and validation untouched, `coercedCellCount` reflects only completed rows).
- **Risk**: low. The change is additive on the API surface, gated to a single async code path, and writes back already-validated data. Failure modes: (a) coercion bug producing wrong type — covered by unit tests; (b) `updated_at` guard race — explicitly handled by skipping the row; (c) Flyway migration on a non-empty `revalidation_tasks` table — `DEFAULT 0` makes this safe.
- **Rollout**: ship behind no flag — the schema-change revalidation path is the only caller, so the change takes effect on the next suite update after deploy. Pre-existing invalid rows from a Boolean→STRING change before deploy will become valid on the next manual revalidation trigger.
