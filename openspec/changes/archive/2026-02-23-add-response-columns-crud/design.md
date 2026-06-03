## Context

The prototype shows a "Columns" tab on the Eval Results page where users define named columns that extract data from each result's response body via JSONata. Each column has: `name` (stable identity), `displayName` (optional UI label), `expression` (JSONata string), `type` (for display formatting).

The backend needs to:
1. Store these definitions as part of TestSuite configuration.
2. Validate JSONata expressions on save.
3. Evaluate expressions at run time and persist extracted values in analytics results.
4. Enable downstream sort/filter/analytics over extracted columns.

## Goals / Non-Goals

**Goals:**
- New `response_columns` JSONB column on `test_suites` (same pattern as `test_case_schema`).
- Add optional `displayName` to `FieldDefinitionDto` (testCaseSchema fields) for UI label consistency.
- Server-side JSONata expression validation on suite create/update.
- Eager extraction: evaluate expressions against `responseBody` at result write time, store in `extracted_columns` JSONB on `test_case_run_results`.
- Error handling: null value + `extraction_warnings` JSONB on `test_case_run_results`.
- Reuse `SchemaFieldType` — unified type system for both input and extracted columns.
- OpenAPI examples updated to include response columns.
- Suite import/export naturally includes response column definitions (JSONB on suite).

**Non-Goals:**
- Separate REST endpoints for response columns — managed via existing suite PUT/PATCH.
- Re-evaluation of historical results when expressions change — v1 applies new expressions to future runs only.
- Eval results CSV export (not built yet — when built, should include both input + extracted columns).
- Unified schema at suite level (testCaseSchema + responseColumns merged into one array) — deferred. Eval results unify both at display time naturally.
- Position/ordering field — array order in JSONB is the implicit position.

## Decisions

### 1. JSONB on test_suites, not a separate table

Response columns are suite configuration metadata, just like `testCaseSchema`. They are a small collection (typically ≤50), always loaded/saved as a batch with the suite, and don't need individual row-level CRUD. Storing as a JSONB array on `test_suites`:
- Is consistent with `test_case_schema`, `input_bindings`, and other suite config fields.
- Avoids a new table, repository, service, controller stack.
- Gets import/export for free (part of suite JSON).
- Uses array order as implicit position (no explicit `position` field needed).

The original design considered a separate table for per-column PATCH/DELETE, but that granularity isn't needed — the FE manages the array and sends the whole thing via suite update.

### 2. Unified type system — reuse SchemaFieldType

`SchemaFieldType` (`STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`, `OBJECT`, `ARRAY`) is already used for testCaseSchema field types. Response columns use the same enum. No separate `ResultColumnType`. This keeps the type system unified and opens the door to merging input + extracted columns into a single eval results schema at display time.

### 3. name + optional displayName identity pattern

Both `FieldDefinitionDto` (testCaseSchema) and `ResponseColumnDefinitionDto` use:
- `name`: Stable identifier. Used as key in `extracted_columns` JSONB, CSV headers, etc. Must be unique within the array.
- `displayName`: Optional human-friendly label. FE falls back to `name` if not set.

Adding `displayName` to `FieldDefinitionDto` is backwards-compatible (nullable, optional).

### 4. Eager evaluation at result write time (v1)

When a test run produces a result, the system immediately evaluates all response column expressions against the `responseBody` and stores the extracted values. This means:
- Extracted values are pre-computed and available for SQL-level sort/filter/analytics.
- No lazy evaluation or on-demand computation needed.
- Trade-off: if a user changes expressions after a run, old results retain their original extractions. Acceptable for v1.

### 5. Extraction is a job-layer concern, not persistence-layer

There are two result write paths: (a) job execution layer (`MockResultsGenerator`, future real runner) which builds entities directly, and (b) the analytics batch write REST API (`POST /api/v1/analytics/results`) which receives DTOs and persists them.

Extraction happens in path (a) only — the job layer loads the suite's `responseColumns` from meta DB, evaluates expressions via a shared `ResponseColumnExtractor` component, and populates `extractedColumns`/`extractionWarnings` on each result entity before handing it to the analytics batch writer.

The batch write API (path b) is pure persistence — it does NOT trigger extraction. `TestCaseRunResultItemDto` exposes optional `extractedColumns` and `extractionWarnings` fields so external callers can pre-populate them if needed; when absent, the persistence layer defaults to `{}` and `[]`.

This keeps the analytics service clean (no cross-datasource reads) and avoids coupling persistence with business logic.

### 6. Future runs only for expression changes (v1)

When response column definitions are updated on a suite, only future test runs will use the new expressions. Historical `test_case_run_results` keep their existing `extracted_columns`. Re-evaluation of historical results is deferred.

### 7. Error handling: null + extraction warnings

When a JSONata expression fails against a specific `responseBody` (bad path, type mismatch, null response):
- `extracted_columns[columnName]` is set to `null`.
- An entry is added to `extraction_warnings` with: `column` (name), `expression`, `error` (message).

This follows the same pattern as `validationWarnings` on TestSuite/TestCase — a JSONB array of structured warning objects.

### 8. JSONata library — `com.dashjoin:jsonata:0.9.9`

**Chosen**: `com.dashjoin:jsonata:0.9.9` — 100% JSONata reference test coverage, zero extra transitive dependencies, 3–5× faster than the IBM library.

**Abstraction boundary**: `JsonataEvaluationService` is the single entry point for all JSONata operations. No other class in the codebase imports from `com.dashjoin.jsonata`. Library-specific exceptions are caught inside the service and re-thrown as domain exceptions. Swapping the library in the future = replacing one class.

### 9. Migration numbering

- Meta: current latest is V1.7 (`V1.7__RenameMetricDefinitionsToMetricDeclarations.sql`, merged via `fix/metric-definition-naming-to-declaration-v2`). This change adds `V1.8__AddResponseColumnsToTestSuites.sql`.
- Analytics: current latest is V1.1. This change adds `V1.2__AddExtractedColumnsToTestCaseRunResults.sql`.

## Risks / Trade-offs

- **Stale extractions**: If a user edits response column expressions, old results keep prior extractions. Acceptable for v1; recalculation can be added later.
- **JSONata library maturity**: Neither library is from the JSONata project itself. Need a spike to validate expression coverage against actual use cases (e.g., `choices[0].message.content`, `usage.total_tokens`).
- **Expression evaluation cost**: Eager evaluation adds processing to the result write path. For suites with many columns and large response bodies, this could slow batch writes. Mitigated by the small expected column count (≤50) and the fact that JSONata evaluation is fast (microseconds per expression).
- **No `visible` field persisted**: Visibility is a FE/UI concern, not stored in the column definition. If this assumption is wrong, it's easy to add later (JSONB is schema-flexible).
