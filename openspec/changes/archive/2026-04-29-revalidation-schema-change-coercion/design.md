## Context

`RevalidationService.runRevalidationAsync` is the only path that re-evaluates **existing** test case `data` against a **changed** suite schema (triggered by `TestSuiteService.update` when `isSchemaChanged(...)` returns true). It currently calls `TestCaseValidationService.validateTestCase` directly on the deserialized `data` map without any conversion step, so a schema-type change such as BOOLEAN → STRING immediately turns every row containing the old type "invalid" — even when every cell is losslessly representable in the new type.

The codebase already has a coercion component, `service.domain.csv.SchemaTypeCoercer`, but it is wired only into `CsvImportService`. Its conversion table is intentionally permissive (e.g. Integer ↔ Boolean via `!= 0`, Boolean → Integer via `1`/`0`) because CSV cells arrive as ambiguous strings and the importer needs to pick *some* type. That permissiveness is wrong for the schema-change context: there a typed JSON value already exists, and the user's intent on changing the schema is "reinterpret what's there if it's safe; otherwise mark invalid." Adding the schema-change rules to the existing coercer would either weaken CSV import (if we tighten globally) or sprawl into a confusing context-flag on a single class.

The decision is therefore to add a sibling component, `SchemaChangeCoercer`, with a stricter, explicitly-different table. Both coercers live in `service.domain.csv` (the existing package for type-coercion logic) so they're discoverable side-by-side. Tests, callers, and the AGENTS.md "Unique Patterns" entry will document the contrast.

The other moving parts:
- `RevalidationService` orchestration: per-row, coerce → guarded data update → re-validate → guarded validation update.
- `TestCase` repository gains two `*IfUnchanged` SQL methods using an `updated_at = :seenAt` precondition.
- `RevalidationTask` gains a `coerced_cell_count` column (Long) — a **cell** counter, not a row counter.

## Goals / Non-Goals

**Goals:**
- Auto-convert existing test case data when a schema-type change has a well-defined, safe conversion (per the table in §Decisions).
- Mark rows invalid only when no safe conversion exists — preserve current validator semantics.
- Surface how much auto-conversion happened to the user via `coercedCellCount` on the revalidation task.
- Tolerate a concurrent test-case edit during revalidation: the user's edit wins, the revalidation simply skips that row.
- Keep CSV import behaviour bit-for-bit identical to today.

**Non-Goals:**
- No coercion on direct test-case writes (`POST/PUT/PATCH /test-cases`) — the user is supplying the value with full intent; type mismatch must remain a warning.
- No retroactive rewrite of historical run data (`test_suite_runs.suite_snapshot`, `test_case_run_inputs`) — those are immutable per the suite-run-snapshot contract.
- No new configuration property — the conversion table is hardcoded, like the existing `SchemaTypeCoercer`.
- No FE/UI changes — the backend simply exposes `coercedCellCount`; the UI can choose whether to surface it.
- No background job for pre-existing invalid rows — they will become valid on the next manual revalidation trigger.

## Decisions

### 1. Conversion table for `SchemaChangeCoercer`

The table differs from `SchemaTypeCoercer` in three places: `Integer/Long → BOOLEAN` is dropped, `Boolean → INTEGER/NUMBER` is dropped, and `anything → FILE` is restricted to String-identity only. `Object/Array → STRING` is dropped (we will not stringify a Map/List into Java's default `{a=1}` form).

| Source ↓ \ Target →     | STRING       | INTEGER          | NUMBER          | BOOLEAN              | FILE         | OBJECT     | ARRAY      |
|-------------------------|--------------|------------------|-----------------|----------------------|--------------|------------|------------|
| String                  | identity     | `Long.parseLong` | `Double.parseDouble` | `"true"`/`"false"` only | identity | skip       | skip       |
| Integer/Long            | `String.valueOf` | identity     | `doubleValue`   | **skip**             | **skip**     | skip       | skip       |
| Double                  | `String.valueOf` | only if `% 1 == 0` | identity   | **skip**             | **skip**     | skip       | skip       |
| Boolean                 | `"true"`/`"false"` | **skip**   | **skip**        | identity             | **skip**     | skip       | skip       |
| Object (Map)            | **skip**     | skip             | skip            | skip                 | skip         | identity   | skip       |
| Array (List)            | **skip**     | skip             | skip            | skip                 | skip         | skip       | identity   |
| `null`                  | identity     | identity         | identity        | identity             | identity     | identity   | identity   |

"skip" means the coercer returns the input value unchanged. The downstream validator will then add a `TYPE` warning and the row will be marked invalid — the *correct* outcome when no safe conversion exists.

**Why** — explicit reasoning per dropped pair:

| Dropped pair | Reason |
|--------------|--------|
| `Integer/Long → BOOLEAN` (`!= 0` rule) | Hides errors: `42` silently becomes `true`, `0` silently becomes `false`. Acceptable for CSV (string source, ambiguous) but not for a schema reinterpretation of existing typed data — users would lose the distinction between "intentional integer" and "intended boolean." |
| `Boolean → INTEGER/NUMBER` | Same asymmetry: `true → 1`, `false → 0` is a convention, not a fact. CSV import accepts it because it has nothing else to do; revalidation would be inferring intent it doesn't have. |
| `*  → FILE` (except String identity) | A FILE value must be a DIAL reference (`@ef/...` or `public/...`). `String.valueOf(true)` would yield `"true"`, which is not a file path. `FileRefValidator` would then reject it on the next pass anyway, but only after we'd corrupted the persisted data. Cleanest behaviour is to never coerce **into** FILE except when the source already is a (possibly valid) String. |
| `Object/Array → STRING` | `String.valueOf(Map)` produces Java's debug form like `{a=1, b=2}` — never what a user wants. Treat as unconvertible. |
| Any → `Object/Array` | No deterministic conversion exists. |

### 2. New component: `SchemaChangeCoercer`

```java
package com.epam.aidial.evaluation.service.domain.csv;

@Component
@LogExecution
public class SchemaChangeCoercer {

    /** Returns coerced value, or the input value unchanged if no safe conversion exists. */
    public Object coerce(Object value, SchemaFieldType targetType) { ... }
}
```

Sibling of `SchemaTypeCoercer`; same package so the contrast is discoverable. No shared interface — the two have different rules, and an interface would obscure that. Stateless, idempotent, no I/O.

**Convenience method**:
```java
public CoercionResult coerceMap(Map<String, Object> data, List<FieldDefinitionDto> schema);
```
where `CoercionResult` is a small record `(Map<String,Object> coercedData, int coercedCellCount, boolean changed)`. `RevalidationService` consumes this once per row.

### 3. `RevalidationService` per-row flow

```
read TestCase tc, capture seenAt = tc.updatedAt
                    │
                    ▼
result = schemaChangeCoercer.coerceMap(tc.data, schema)
                    │
                    ▼
if result.changed:                          ← at least one cell coerced
    rows = repo.updateDataIfUnchanged(
        tc.id, suiteId,
        serialize(result.coercedData),
        seenAt, now)
    if rows == 0:                           ← guard miss: someone edited the row
        skippedCount++
        continue                            (do NOT touch validation either)
    seenAt = now                            (we now own the row's latest updatedAt)
                    │
                    ▼
validation = validator.validateTestCase(    ← post-coercion data
    result.coercedData, schema,
    effectiveTemplate, effectiveBindings, …)
                    │
                    ▼
rows = repo.updateValidationIfUnchanged(
    tc.id, suiteId,
    validation.isValid, serialize(validation.warnings),
    seenAt, now)
if rows == 0:
    skippedCount++
    continue
                    │
                    ▼
coercedCellCountTotal += result.coercedCellCount
validCount or invalidCount++
```

**Why two updates instead of one combined SQL** — when there's nothing to coerce (the common case after the first revalidation), only the second update fires; the first is skipped entirely. Combining into a single statement would force every row to write the JSONB column even when the data didn't change, and would muddle the "data persistence vs validation persistence" boundaries. With two updates, both stay narrow and easy to reason about.

**Why guard both updates** — if the data update succeeds and the validation update is then blocked by a concurrent edit, validation would reflect a stale interpretation. Easier rule: any guard miss anywhere = abandon the row, increment `skippedCount`, move on. The next revalidation run (or none, if the user's edit already produced valid data) will catch up.

**Counter accounting on skip** — Skipped rows DO count toward `processedCases` (the row was attempted) but DO NOT contribute to `validCount` / `invalidCount` (no validation outcome was committed). `coercedCellCount` likewise reflects only cells whose coerced value was actually persisted via a successful guarded data update — cells from a row where the data update was skipped do NOT count, even if the in-memory `coerceMap` produced a converted value for them.

### 4. Repository methods

```java
public interface TestCaseRepository {
    int updateDataIfUnchanged(UUID id, UUID testSuiteId, String dataJson,
                              long expectedUpdatedAt, long newUpdatedAt);
    int updateValidationIfUnchanged(UUID id, UUID testSuiteId, boolean isValid, String warningsJson,
                                    long expectedUpdatedAt, long newUpdatedAt);
}
```

SQL (Postgres impl):

```sql
-- updateDataIfUnchanged
UPDATE test_cases
   SET data = :data::jsonb, updated_at = :newUpdatedAt
 WHERE id = :id AND test_suite_id = :testSuiteId AND updated_at = :expectedUpdatedAt;

-- updateValidationIfUnchanged
UPDATE test_cases
   SET is_valid = :isValid, validation_warnings = :warnings::jsonb, updated_at = :newUpdatedAt
 WHERE id = :id AND test_suite_id = :testSuiteId AND updated_at = :expectedUpdatedAt;
```

Both return `int` rows-affected (0 = guard miss, 1 = success). Existing unguarded `updateValidation` is kept for the CSV import path, which already runs inside the import transaction and doesn't need the guard.

### 5. `RevalidationTask` schema and DTO additions

Flyway migration on **meta** datasource:

```sql
-- V{next}.0__AddCoercedCellCountToRevalidationTasks.sql
ALTER TABLE revalidation_tasks
    ADD COLUMN coerced_cell_count BIGINT NOT NULL DEFAULT 0;
```

`DEFAULT 0` makes the migration safe on a non-empty table; existing rows show 0 cells coerced (which is accurate — those tasks ran before this feature shipped).

`RevalidationTask` model gains `Long coercedCellCount` (defaulted to `0L` via `@Builder.Default`); `RevalidationTaskRowMapper` reads the column; `PostgresRevalidationTaskRepository` writes it on insert/update; `RevalidationTaskDto` exposes it; OpenAPI examples are updated.

### 6. Counter semantics: cells, not rows

A "cell" = one coerced value for one (row, field) pair. A row with 3 BOOLEAN→STRING fields contributes 3 to `coercedCellCount`; a row with 1 BOOLEAN→STRING and 1 unchanged STRING contributes 1. This gives the user an honest "amount of data rewritten" number and avoids the awkward "this row has 5 cells but we counted it as 1." Persisted as `BIGINT` because suites with millions of rows times multiple coerced fields can overflow `INT`.

### 7. Idempotency

`SchemaChangeCoercer.coerce` is identity for already-matching types (e.g. `String → STRING` returns the same reference). Re-running revalidation against the same schema as last time produces zero coerced cells and zero data writes — only the validation update fires. Naturally idempotent; no extra "did we already coerce?" flag needed.

### 8. Logging

`RevalidationService` logs per task at completion: `Revalidated suite={suiteId} task={taskId}: total={n} valid={v} invalid={i} coerced_cells={c} skipped={s}`. Per-row logs only on failure or guard miss (DEBUG level for guard misses to avoid log spam during a hot edit cycle).

## Risks / Trade-offs

- **Risk**: Coercion bug silently corrupts data (e.g. wrong String for a Double).
  → Mitigation: `SchemaChangeCoercerTest` exercises every cell of the conversion table including identity cases; round-trip property tests over `(value, targetType)` pairs verify `coerce(coerce(x, T), T) == coerce(x, T)`.

- **Risk**: `updated_at` guard race — user PATCHes a row exactly during the window between read and update.
  → Mitigation: explicitly handled — both updates are guarded; on guard miss, the row is skipped and a `skippedCount` is logged. The skip is observable in logs but not in the API today (we could add it to `RevalidationTaskDto` later if useful; out of scope here).

- **Risk**: Asymmetry between revalidation behaviour and direct API write behaviour confuses users — "I edited via API and got an invalid warning, but the schema-change run made the same field valid."
  → Mitigation: documented in spec ("revalidation coerces, direct writes don't") and surfaced in the validator's existing TYPE warnings already emphasizing "API path produces TYPE warnings." Long-term, we could add the same coercion to direct writes; intentionally deferred.

- **Risk**: Flyway migration on a large `revalidation_tasks` table briefly locks the table.
  → Mitigation: `ADD COLUMN ... DEFAULT 0 NOT NULL` in Postgres ≥ 11 is a metadata-only operation (no full-table rewrite) — fast even on large tables.

- **Trade-off**: Two updates per row instead of one means double the round-trips for *changed* rows. For unchanged rows it's still one update (same as today). Worst case: every row coerces → 2× round-trips × N rows. Acceptable for an async, batched job; no SLA on revalidation completion time. Combining into a single statement was rejected for the readability reasons in §3.

- **Trade-off**: Adding a sibling coercer (`SchemaChangeCoercer`) means two classes with similar shape. The alternative — a single class with a `policy` enum — was rejected because the rules genuinely differ and a flag would invite "just one more if-branch" drift. Two clear classes with two clear test suites is the cleaner contract.

## Migration Plan

1. Deploy this change. The new column has `DEFAULT 0`, so existing tasks read back `coercedCellCount=0` (accurate).
2. Pre-existing test cases that were marked invalid by a prior schema-type change remain invalid until the next revalidation runs against them. Triggers: any subsequent suite edit that flips `isSchemaChanged`, or a manual `POST /api/v1/test-suites/{id}/revalidation-tasks` (existing endpoint).
3. No rollback DB migration is required: the new column is purely additive and ignored by old code that doesn't read it.

## Open Questions

None. The user-facing semantics (which conversions are safe, what `coercedCellCount` counts, what happens on a concurrent edit) are settled in the proposal discussion. Implementation-level details (exact test fixtures, OpenAPI example wording) are deferred to the implementation phase.
