## Why

A test suite can currently narrow which of its dataset's test cases run only via an explicit
exclusion list (`disabledTestCaseIds`). Users need a richer, *declarative* selection — e.g.
`column_a IN ('A','B','C') OR tags CONTAINS 'text'` — authored against test-case fields and applied
at run time, so a suite can target a subset of a large shared dataset without hand-listing every id.
This reuses the existing experimental Structured Query DSL as the filter language, adding `test_cases`
as a queryable entity and a per-suite stored filter.

## What Changes

- **Expose `test_cases` as a complex Query-DSL entity** (`query-schema-discovery`): schema discovery
  flattens `data::<field>` from the bound dataset's `testCaseSchema` (keyed by `dataset_id`), and
  `POST /api/v1/queries/execute` can run ad-hoc queries over test cases so the frontend can
  discover fields and preview a filter.
- **Extend the DSL `co`/`nc` operators for JSONB array-element containment** (`structured-query-model`):
  when the left operand is an `ARRAY`-typed field, `co` means "the array contains this element"
  (`data -> 'col' ? 'text'`) instead of substring `LIKE`. Scalar/text behavior is unchanged.
- **Store a per-suite `testCaseFilter`** on the test suite (`test-suites`): a StructuredQuery `filter`
  subtree persisted as JSONB (mirrors the existing `overallScore` field), **validated at write time**
  against the bound dataset's test-case schema (HTTP 400 on unknown field / type / malformed filter).
- **Apply the filter during runs** (`test-suite-runs`, `suite-run-snapshot`): run-creation guard #4
  (zero-runnable) and the snapshot selection now honor `is_valid AND NOT excluded AND (testCaseFilter)`.
  A filter matching nothing yields the existing 409 "no valid and enabled test cases".
- **DB**: new nullable JSONB column `test_suites.test_case_filter` (Flyway migration + jOOQ regen).

Non-goals: no change to the public `/queries/execute` contract beyond the new entity; runs do NOT
route through the DSL executor (they reuse only its translation layer — see design); no new
configuration properties expected.

## Capabilities

### New Capabilities
- `suite-test-case-filter`: a per-suite declarative test-case filter — stored as a StructuredQuery
  filter, validated at suite write time against the dataset schema, and applied (AND-combined with
  `is_valid` and `disabledTestCaseIds`) to select runnable test cases at run-creation and snapshot.

### Modified Capabilities
- `structured-query-model`: `co`/`nc` gain JSONB array-element containment semantics for `ARRAY`-typed
  fields (new requirement; scalar/text substring behavior preserved).
- `query-schema-discovery`: adds `test_cases` as a complex queryable entity (schema flattening keyed by
  `dataset_id`).
- `test-suites`: the suite gains a `testCaseFilter` field (create/update/response/clone), validated at
  write time.
- `test-suite-runs`: run-creation runnable-count guard honors the stored filter.
- `suite-run-snapshot`: snapshot test-case selection honors the stored filter.

## Impact

- **New (experimental.query.service)**: `TestCasesSchemaProvider`, `TestCaseQueryRepository` +
  `PostgresTestCaseQueryRepository`, `TestCaseFieldBindingsBuilder`, and the implementation of the new
  inverted `RunnableTestCaseSelector` interface.
- **New (service.domain.job)**: `RunnableTestCaseSelector` interface (inversion — keeps the run
  pipeline free of an `experimental` bytecode dependency, mirroring `MetricScoreComputation`).
- **Modified**: `FilterTranslator` (array containment); `StructuredQueryExecutor` wiring for
  `test_cases` bindings; `TestSuite` model + `PostgresTestSuiteRepository` + `TestSuiteRecordMapper`;
  `TestSuiteRequestDto`/`TestSuiteResponseDto`/`TestSuiteMapper`/`JsonbMapper`; `TestSuiteService`
  (write validation); `RunnableTestCaseCounter`, `TestSuiteRunService`, `TestSuiteEvaluationJob`.
- **DB**: `V1.24__AddTestCaseFilterToTestSuites.sql` + regenerated jOOQ meta sources; update
  `docs/database-schema.md`.
- **Docs**: OpenAPI examples + query-param docs for the new entity; `AGENTS.md` (new entity + the
  inversion pattern); `openspec/specs/README.md` (new spec folder).
- **Tests**: unit (`FilterTranslator`, `TestCasesSchemaProvider`, `TestSuiteMapper`) + functional
  (`test_cases` execute/schema, suite-filter write validation 200/400, run selection + zero-match 409).
- **Layering**: no `service → experimental` edge; `LayeredArchitectureTest` must stay green.
