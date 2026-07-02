## Context

Test suites narrow their dataset's test cases only via `disabledTestCaseIds` (JSONB array on
`test_suites`), applied at run-creation (`RunnableTestCaseCounter` → guard #4) and snapshot
(`TestSuiteEvaluationJob.attemptSnapshot` → `PostgresTestCaseRepository.findValidByDatasetIdExcludingIds`).
The experimental Structured Query DSL (`experimental.query`) already provides a sealed
`Expr`/`FilterNode` model, a translation pipeline (`ExprTranslator`, `FilterTranslator` →
`org.jooq.Condition`), a schema-discovery SPI (`QueryableEntitySchemaProvider` + `QueryEntityRegistry`),
and per-entity repositories delegating to `StructuredQueryExecutor`. Two entities are complex
(`eval_summaries`, flattening `data::`/`response::`/`metric::` families); `test_cases` is not yet
queryable. The suite already stores a verbatim StructuredQuery in `overall_score` JSONB
(`V1.23`, `TestSuite.overallScore`, `JsonbMapper.mapOverallScore`) — the storage precedent for this
change.

`LayeredArchitectureTest` allows `experimental.query.service` → `service`/`data`, but forbids
`service` → `experimental`. The run pipeline lives in `service`, so applying a DSL-authored filter
there requires interface inversion (the pattern already used by `MetricScoreComputation`).

## Goals / Non-Goals

**Goals:**
- Make `test_cases` a complex Query-DSL entity so the frontend can discover `data::<field>` schema
  (keyed by `dataset_id`) and preview filters via `POST /api/v1/queries/execute`.
- Support JSONB array-element containment for `co`/`nc` on `ARRAY`-typed fields.
- Persist a per-suite `testCaseFilter`, validated at write time against the dataset schema.
- Apply the filter (AND with `is_valid` + `disabledTestCaseIds`) at run-creation count and snapshot.

**Non-Goals:**
- Routing run selection through the public `/queries/execute` executor (reuse translation only).
- Changing the public execute contract beyond the new entity.
- New configuration properties or new filter operators beyond the `co`/`nc` array semantics.
- Storing the filter into the run's `suite_snapshot` (selection is materialized in
  `test_case_run_inputs`, so the filter itself need not persist per-run).

## Decisions

### 1. `test_cases` as a complex queryable entity
`TestCasesSchemaProvider` (new, `experimental.query.service`) implements `QueryableEntitySchemaProvider`,
modeled on `EvalSummariesSchemaProvider`:
- `descriptor()` → `QueryEntityDto("test_cases", complex=true, "dataset_id")`.
- `baseSchema()` → `JooqTableSchemaResolver.resolve(TEST_CASES)` (JSONB `data` listed as-is).
- `detailedSchema(params)` → requires `dataset_id`; loads the dataset's `testCaseSchema` via
  `DatasetService` (stable-layer, allowed) and replaces `data` with `data::<field>` entries, mapping
  each `SchemaFieldType` to `QueryFieldType` — **preserving `ARRAY`** (needed for containment).
`TestCaseQueryRepository` + `PostgresTestCaseQueryRepository` bind entity `test_cases` to `TEST_CASES`
on `@Qualifier("metaDsl")` and delegate to `StructuredQueryExecutor` (mirrors
`PostgresTestSuiteQueryRepository`); auto-registered via `@Component` + `supportedEntity()`.
Unlike the cache-backed entities, `test_cases` execute is **instance-aware**: the repository requires a
`dataset_id` equality filter on the query (the entity's `schemaIdField`) — used both to scope rows to
that dataset AND to type `data::<field>` bindings — builds instance bindings via
`TestCaseFieldBindingsBuilder`, and passes them to a new `StructuredQueryExecutor` overload that
bypasses the per-`Table<?>` binding cache. A missing or non-UUID `dataset_id` filter is rejected with
`ValidationException` → HTTP 400. `eval_summaries` and `test_suites` are unchanged (still cache-backed).

### 2. Type-aware flattened bindings (`TestCaseFieldBindingsBuilder`)
New shared component (`experimental.query.service`) producing the
`Map<String, QueryFieldBinding>` for a `test_cases` query = base `TEST_CASES` columns + one
`data::<field>` binding per dataset schema field, each carrying a JSONB-path `Field`
(`data ->> 'field'` for scalars, `data -> 'field'` for array/object) **and its `QueryFieldType`**.
Rationale: the eval-summaries `JsonbFieldResolver` is keyed on column names (`test_case_data`,
`metric_values`, …) that don't match `test_cases.data`; a dedicated builder keeps type info reachable
at comparison-translation time (needed for array containment) and is the single source of truth reused
by the executor path (via the precomputed-bindings overload), the run-selection path, and write-time
validation. Because `test_cases` execute uses this builder rather than the static `bindingsCache`, the
flattened `data::<field>` typing (incl. ARRAY) is single-sourced across preview, run selection, and
write-time validation.

### 3. Array-element containment in `FilterTranslator`
In `toComparison`, before the existing `co`/`nc` LIKE branch, inspect the left operand. Today the left
operand is resolved via `exprTranslator.toField(arg0, bindings)`, which discards the
`QueryFieldBinding.type()`; array detection therefore needs an explicit precondition. The array branch
triggers **only** when the left operand is a bare `FieldExpr` and `bindings.get(fieldExpr.name()).type()
== QueryFieldType.ARRAY`. A non-`FieldExpr` left operand (e.g. a function-wrapped expression) is never
array-detected and keeps today's scalar substring-`LIKE` `co`/`nc` behavior. Builder bindings must win
over the `JsonbFieldResolver` fallback — `ExprTranslator.resolveFieldOrNull` checks `bindings.get(name)`
first, so the `TestCaseFieldBindingsBuilder` entry (which is guaranteed present) is consulted before the
fallback. For an array field:
- `co` → JSONB element containment: string element via the `?` operator
  (`DSL.condition("{0} ?? {1}", jsonbCol, DSL.val(text))` — `??` escapes `?` in jOOQ plain SQL);
  non-string literal via `@>` with a one-element JSON array.
- `nc` → the negation.
All key/value operands are bound parameters (never concatenated). Scalar/text fields keep today's
substring-`LIKE` behavior. The right operand must be a scalar literal (existing `co`/`nc` validation).

### 4. Suite `testCaseFilter` storage (mirror `overallScore`)
- **Migration** `V1.24__AddTestCaseFilterToTestSuites.sql`: `ALTER TABLE test_suites ADD COLUMN
  test_case_filter JSONB;` (nullable = "no filter"); then `./gradlew generateJooq` + commit.
- **Model/DTO/mapper**: `TestSuite.testCaseFilter` (String JSONB); `PostgresTestSuiteRepository`
  insert/update + `TestSuiteRecordMapper`; `JsonbMapper.mapTestCaseFilter` (both directions);
  `TestSuiteRequestDto`/`TestSuiteResponseDto.testCaseFilter` as `Map<String,Object>` with `@Schema`
  example; `TestSuiteMapper` `toEntity`/`update`/`toDto`/`toRequestDto`/`toCloneEntity` (clone inherits
  source filter, like `overallScore`).

### 5. Run application via inverted `RunnableTestCaseSelector`
New `service.domain.job.RunnableTestCaseSelector` interface (stable layer; signatures use only
primitives + `data.db.model.TestCase`, so no `experimental` type leaks upward):
- `long countRunnable(UUID datasetId, String filterJson, Collection<UUID> excludedIds)`
- `List<TestCase> loadRunnablePage(UUID datasetId, String filterJson, Collection<UUID> excludedIds, int offset, int limit)`
- `void validateFilter(UUID datasetId, String filterJson)`

Implemented in `experimental.query.service`: parse `filterJson` → `StructuredQuery`/`FilterNode`; build
bindings via `TestCaseFieldBindingsBuilder`; translate filter → `Condition`; AND with the base
predicate `dataset_id = ? AND is_valid = TRUE AND NOT (id = ANY(?::text[]))` (reuse the shape from
`PostgresTestCaseRepository.validNotExcludedCondition`). The paged SELECT/COUNT themselves are
delegated to new `TestCaseRepository` methods (`findValidByDatasetIdExcludingIdsMatching` /
`countValidByDatasetIdExcludingIdsMatching`) rather than issued directly against `metaDsl` from the
selector — keeping SQL construction in the data layer — with deterministic order
(`created_at_ms asc, id asc`) + `limit`/`offset`. Null/blank `filterJson` short-circuits to the base
predicate (behavior identical to today). Runs inside the ambient `REPEATABLE READ` snapshot
transaction, preserving the `40001` retry.

**Rewire**: `RunnableTestCaseCounter.countRunnable` delegates to `RunnableTestCaseSelector.countRunnable`
(filter threaded from `TestSuiteRunService.createRun` guard #4); `attemptSnapshot` replaces the direct
`testCaseRepository.findValidByDatasetIdExcludingIds` call with
`RunnableTestCaseSelector.loadRunnablePage`. Existing `deserializeDisabledIds` is retained; the filter
is an additional AND, not a replacement. Guard order is unchanged.

### 6. Write-time validation
`TestSuiteService` create/update calls `RunnableTestCaseSelector.validateFilter(datasetId, filterJson)`
after the dataset binding is known; the impl builds `test_cases` bindings for that dataset and runs the
filter through `FilterTranslator` (translation success = valid). Failures raise `ValidationException`
→ HTTP 400 (`VALIDATION_ERROR`) via the existing handler. An unbound suite (no `datasetId`) with a
non-null filter is rejected, matching the "unbound suites cannot run" model.

### Component interaction flow
```
Write:  TestSuiteController → TestSuiteService.create/update
          → RunnableTestCaseSelector.validateFilter(datasetId, filterJson)   [inverted → experimental]
          → TestSuiteMapper/JsonbMapper → PostgresTestSuiteRepository (JSONB)

Preview: POST /api/v1/queries/execute {entity:test_cases}
          → StructuredQueryRepository → PostgresTestCaseQueryRepository
          → StructuredQueryExecutor (+ TestCaseFieldBindingsBuilder, FilterTranslator)

Run:    TestSuiteRunService.createRun (guard #4)
          → RunnableTestCaseCounter → RunnableTestCaseSelector.countRunnable
        TestSuiteEvaluationJob.attemptSnapshot  [REPEATABLE READ tx, metaDsl]
          → RunnableTestCaseSelector.loadRunnablePage → test_case_run_inputs batch
```

## Risks / Trade-offs

- **jOOQ `?` operator escaping**: the JSONB `?` containment operator collides with jOOQ bind
  placeholders; must escape as `??` in plain SQL. Mitigated by a focused `FilterTranslator` unit test.
- **Two selection code paths**: run selection reuses only the DSL translation layer, not the executor,
  so array-containment SQL is exercised by both the executor (preview) and the run query. Keeping
  bindings single-sourced in `TestCaseFieldBindingsBuilder` avoids divergence.
- **Two executor overloads / `test_cases` excluded from cache**: `StructuredQueryExecutor` gains a
  precomputed-bindings overload used only by `test_cases` (instance-specific `data::<field>` typing
  cannot be cached per static `Table<?>`); `eval_summaries`/`test_suites` keep the cache-backed
  overload. Accepted as the minimal channel for dataset-specific bindings.
- **Write-time validation coupling**: `TestSuiteService` now depends on the inverted selector for
  validation. Accepted — it is the same inversion already used for run application, and keeps the
  DSL/experimental dependency one-directional (no `LayeredArchitectureTest` change).
- **Unbound-suite semantics**: rejecting a filter on an unbound suite is the conservative choice;
  alternative (defer validation until binding) risks storing an unvalidatable filter. Chosen to reject.
- **Snapshot consistency**: the selector query must use the same `metaDsl` bean/transaction as the
  snapshot; using a separate connection would break `REPEATABLE READ`. Enforced by injecting `metaDsl`
  and running within `attemptSnapshot`'s `TransactionTemplate`.
- **Filter over huge datasets**: selection stays paginated (existing `SNAPSHOT_PAGE_SIZE`); the filter
  only adds predicates, so no full-dataset materialization is introduced.
