## 1. Database Migrations

- [x] 1.1 Create Flyway migration `V1.5__CreateTestCaseEvalSummariesTable.sql` in `db/migration/analytics/POSTGRES/` — table with composite PK `(created_at_ms, id)`, UNIQUE constraint on `(test_suite_run_id, test_case_id, run_index, computation_id, created_at_ms)`, indexes on `(test_suite_run_id, computation_id)`, `(computation_id)`, `(id)` (done: migration applies cleanly, `./gradlew test` passes)
- [x] 1.2 Create Flyway migration `V1.6__CreateRunMetricSnapshotsTable.sql` in `db/migration/analytics/POSTGRES/` — table with PK `id`, UNIQUE on `(computation_id, tsmd_id)`, index on `(test_suite_run_id)` (done: migration applies cleanly, `./gradlew test` passes)
- [x] 1.3 Update `docs/database-schema.md` with `test_case_eval_summaries` and `run_metric_snapshots` table definitions, columns, indexes, and JSONB schemas (done: schema doc accurately reflects new tables)

## 2. Data Layer — Models & RowMappers

- [x] 2.1 Create `EvalSummary` model in `data.db.analytics.model` — Lombok `@Data`/`@Builder`, fields matching table columns, JSONB fields as `String` (done: compiles, checkstyle passes)
- [x] 2.2 Create `EvalSummaryRowMapper` in `data.db.analytics.mapper` — maps ResultSet to `EvalSummary`, handles nullable `response_status_code`. Handles `metric_infos` being absent from ResultSet (list queries exclude it from SELECT) by leaving `metricInfos = null`; when present (detail query), maps it normally (done: compiles, checkstyle passes)
- [x] 2.3 Create `RunMetricSnapshot` model in `data.db.analytics.model` — Lombok `@Data`/`@Builder`, fields matching table columns, bindings/schema as `String` (done: compiles, checkstyle passes)
- [x] 2.4 Create `RunMetricSnapshotRowMapper` in `data.db.analytics.mapper` — maps ResultSet to `RunMetricSnapshot` (done: compiles, checkstyle passes)

## 3. Data Layer — Repositories

- [x] 3.1 Create `EvalSummaryRepository` interface in `data.db.analytics.repository` — methods: `saveAll(List<EvalSummary>)`, `findAll(filters, computationId, runCreatedAtMs, cursor, size) → CursorPage<EvalSummary>`, `findById(UUID) → Optional<EvalSummary>`, `count(filters, computationId, runCreatedAtMs) → long`, `aggregate(filters, computationId, runCreatedAtMs, List<MetricPath> metrics) → List<MetricAggregationResult>` where `MetricPath` is a record of `(metricName, outputName)` (done: interface compiles)
- [x] 3.2 Create `PostgresEvalSummaryRepository` in `data.db.analytics.repository` — `@Qualifier("analyticsJdbcTemplate")`, batch INSERT with ON CONFLICT DO NOTHING, keyset pagination on `(created_at_ms DESC, id DESC)`, WHERE builder with filter conditions including JSONB paths for `metric_values` and `test_case_data`, `aggregate()` method that dynamically builds `SELECT AVG((metric_values->:key1->>:key2)::numeric), MIN(...), MAX(...), COUNT(...)` for each requested metric path with parameterized JSONB keys. **Important**: `findAll()`, `count()`, and `aggregate()` queries MUST use explicit column list EXCLUDING `metric_infos` (avoids TOAST decompression on list pages); only `findById()` selects all columns including `metric_infos` (done: compiles, checkstyle passes)
- [x] 3.3 Create `RunMetricSnapshotRepository` interface in `data.db.analytics.repository` — methods: `saveAll(List<RunMetricSnapshot>)`, `findByRunId(UUID runId) → List<RunMetricSnapshot>`, `findLatestComputationId(UUID runId) → Optional<UUID>` (done: interface compiles)
- [x] 3.4 Create `PostgresRunMetricSnapshotRepository` in `data.db.analytics.repository` — `@Qualifier("analyticsJdbcTemplate")`, batch INSERT with ON CONFLICT DO NOTHING, latest computation resolution via `ORDER BY computed_at_ms DESC LIMIT 1` (done: compiles, checkstyle passes)

## 4. Filter Framework Extension

- [x] 4.1 Add `JSONB_NUMERIC` value to `FilterFieldType` enum. Update `WhereBuilder.parseValue()` to parse raw values as `BigDecimal` (via `new BigDecimal(rawValue)`) for JSONB_NUMERIC — exact decimal representation avoids IEEE 754 floating-point precision loss when compared against PostgreSQL `numeric` values. Update `WhereBuilder.validateOperator()` to allow comparison operators (EQ, NE, GT, GTE, LT, LTE) for JSONB_NUMERIC (done: compiles, checkstyle passes)
- [x] 4.2 Extend `WhereBuilder` JSONB path handling for `JSONB_NUMERIC` fields. Three changes required: **(a)** Update the type gate at line 72 (`definition.getType() != FilterFieldType.JSONB_STRING`) to also allow `JSONB_NUMERIC` — without this, JSONB_NUMERIC fields with dot-notation are rejected before reaching any path logic. **(b)** Reorder the nested-dot rejection at lines 61-63: move the `jsonbKey.contains(".")` check to AFTER the definition lookup (line 65), so the field type is known; reject nested dots only for non-JSONB_NUMERIC types; for `JSONB_NUMERIC`, split jsonbKey on first dot into two components (`jsonbKey1`, `jsonbKey2`), reject if more than one dot remains. **(c)** Add new `buildJsonbNumericPredicate()` method generating `(column->:key1Param->>:key2Param)::numeric <op> :valueParam` with all three parameters bound. Existing `JSONB_STRING` single-level behavior unchanged (done: compiles, unit tests pass)
- [x] 4.3 Add `FilterWhitelists.EVAL_SUMMARIES` whitelist with fields: `suiteId` (UUID), `runId` (UUID), `testCaseId` (UUID), `testCaseName` (STRING), `executionStatus` (STRING), `runIndex` (LONG), `execDurationMs` (LONG), `responseStatusCode` (LONG), `testCaseData` (JSONB_STRING), `metricValues` (JSONB_NUMERIC) (done: compiles, checkstyle passes)

## 5. Service Layer — DTOs & Mappers

- [x] 5.1 Create DTOs in `service.domain.dto.analytics`: `EvalSummaryBatchWriteRequestDto` (envelope with testSuiteId, testSuiteRunId, computationId, computedAtMs, items — `testSuiteId` and `computedAtMs` are envelope-level, shared by all items, matching existing BatchWriteRequestDto pattern), `EvalSummaryBatchWriteItemDto` (per-item fields with validation — does NOT include `testSuiteId` or `computedAtMs`), `EvalSummaryBatchWriteResponseDto` (totalItems), `EvalSummaryResponseDto` (list response without metricInfos), `EvalSummaryDetailResponseDto` (detail response with metricInfos), `MetricAggregationRequestDto` (with `@Size(max=50)` on metrics list), `MetricAggregationResponseDto` (done: compiles, checkstyle passes)
- [x] 5.2 Create DTOs in `service.domain.dto.analytics`: `RunMetricSnapshotBatchWriteRequestDto` (envelope with testSuiteRunId, computationId, computedAtMs, snapshots — `computedAtMs` is computation-level), `RunMetricSnapshotBatchWriteItemDto` (does NOT include `computedAtMs`), `RunMetricSnapshotResponseDto` (done: compiles, checkstyle passes)
- [x] 5.3 Create `EvalSummaryMapper` in `service.domain.mapper` — MapStruct mapper: `toEntity(EvalSummaryBatchWriteItemDto, UUID suiteId, UUID runId, long createdAtMs, long computedAtMs) → EvalSummary` (note: `computedAtMs` comes from the envelope, not the item), `toDto(EvalSummary) → EvalSummaryResponseDto`, `toDetailDto(EvalSummary) → EvalSummaryDetailResponseDto`. Map `metricValues`/`metricInfos` between String (model) and JsonNode (DTO) (done: compiles, checkstyle passes)
- [x] 5.4 Create `RunMetricSnapshotMapper` in `service.domain.mapper` — MapStruct mapper: `toEntity(RunMetricSnapshotBatchWriteItemDto, ...) → RunMetricSnapshot`, `toDto(RunMetricSnapshot) → RunMetricSnapshotResponseDto`. Map bindings/schema between String and List/Map DTOs (done: compiles, checkstyle passes)

## 6. Service Layer — Business Logic

- [x] 6.1 Create `EvalSummaryService` in `service.domain.analytics` — `@Transactional("analyticsTransactionManager")` for writes, read-only for reads. Methods: `batchCreate(EvalSummaryBatchWriteRequestDto) → response`, `listByFilter(filters, computation, cursor, size) → CursorPageResponseDto`, `getById(UUID) → EvalSummaryDetailResponseDto`, `countByFilter(filters, computation) → ResultCountResponseDto`, `aggregate(filters, computation, metrics) → MetricAggregationResponseDto`. Validates run existence via meta DB, validates envelope `testSuiteId` matches run's `testSuiteId`, validates `metricValues` structural integrity (all leaf values numeric or null), resolves latest computation via snapshot repository (done: compiles, checkstyle passes)
- [x] 6.2 Create `RunMetricSnapshotService` in `service.domain.analytics` — `@Transactional("analyticsTransactionManager")` for writes, read-only for reads. Methods: `batchCreate(RunMetricSnapshotBatchWriteRequestDto) → response`, `listByRunId(UUID runId) → List<RunMetricSnapshotResponseDto>`, `findLatestComputationId(UUID runId) → Optional<UUID>` (done: compiles, checkstyle passes)

## 7. Configuration

- [x] 7.1 Create `EvalSummaryProperties` in `configuration.properties.analytics` — prefix `analytics.eval-summaries`, fields: `batch.maxItems` (int), `batch.maxRequestSizeBytes` (long). Add defaults in `application.yml`: 10000 and 10485760 (done: compiles, properties bind correctly)
- [x] 7.2 Update `docs/configuration.md` with new `analytics.eval-summaries.*` properties (done: config doc updated)

## 8. Web Layer — Controllers

- [x] 8.1 Create `EvalSummaryController` in `web.controller` — `@RestController`, `@LogExecution`, `@Validated`. Endpoints: `POST /api/v1/analytics/eval-summaries` (batch write, HTTP 201), `GET /api/v1/analytics/eval-summaries` (list with cursor pagination, computation param), `GET /api/v1/analytics/eval-summaries/{id}` (get by ID, detail response), `GET /api/v1/analytics/eval-summaries/count` (count), `GET /api/v1/analytics/eval-summaries/aggregate` (aggregation). OpenAPI annotations. PaginationParamResolver for size (done: compiles, checkstyle passes)
- [x] 8.2 Create `RunMetricSnapshotController` in `web.controller` — `@RestController`, `@LogExecution`, `@Validated`. Endpoints: `POST /api/v1/analytics/run-metric-snapshots` (batch write, HTTP 201), `GET /api/v1/analytics/run-metric-snapshots` (list by runId). OpenAPI annotations (done: compiles, checkstyle passes)
- [x] 8.3 Register eval summaries in `OpenApiQueryParamCustomizer` — add registry entry for filter/sort/pagination param descriptions (done: Swagger UI shows parameter docs)
- [x] 8.4 Register `MaxRequestBodyFilter` for `/api/v1/analytics/eval-summaries` endpoint in `MaxRequestBodyFilterConfiguration` — add a second `FilterRegistrationBean` (or extend the existing one) using `EvalSummaryProperties.batch.maxRequestSizeBytes` as the size limit (done: POST requests exceeding limit return HTTP 413)

## 9. OpenAPI Examples

- [x] 9.1 Create OpenAPI example JSON files in `src/main/resources/openapi/examples/` for eval summary endpoints: list (minimal + full), get-by-id (minimal + full), batch write request/response, count response, aggregate response (done: examples valid JSON, referenced from controller)
- [x] 9.2 Create OpenAPI example JSON files for run metric snapshot endpoints: list (minimal + full), batch write request/response (done: examples valid JSON, referenced from controller)

## 10. Functional Tests

- [x] 10.1 Create `AnalyticsTestDataHelper` methods for eval summaries and run metric snapshots — `createEvalSummary(...)`, `createRunMetricSnapshot(...)`, cleanup methods (done: helpers compile, usable in tests)
- [x] 10.2 Create `EvalSummaryFunctionalTests` — batch write (happy path, validation errors, idempotency, run existence, suite ID mismatch, batch limit, metricValues structural validation — non-numeric leaf rejected), list with cursor pagination, filter by identity/execution/metric value JSONB path, computation resolution (latest, specific UUID, no computation), get by ID (found, not found), count (done: all tests pass)
- [x] 10.3 Create `EvalSummaryAggregationFunctionalTests` — aggregate per run, multiple metrics, empty result, missing metrics param, metrics count limit exceeded (>50 rejected) (done: all tests pass)
- [x] 10.4 Create `RunMetricSnapshotFunctionalTests` — batch write (happy path, validation, idempotency), list by runId (ordered by computed_at DESC), required runId filter, latest computation resolution (done: all tests pass)

## 11. Unit Tests

- [x] 11.1 Unit tests for `EvalSummaryMapper` — entity/DTO mapping, JSONB conversion for metric_values/metric_infos (done: tests pass)
- [x] 11.2 Unit tests for `RunMetricSnapshotMapper` — entity/DTO mapping, bindings/schema JSONB conversion (done: tests pass)
- [x] 11.3 Unit tests for JSONB_NUMERIC filter type in WhereBuilder — two-level path extraction, numeric casting, parameterized path injection safety (done: tests pass)

## 12. Documentation & Specs

- [x] 12.1 Update `openspec/specs/README.md` per Spec Index Maintenance Policy — add `metrics-storage` spec entry under Analytics section (done: index reflects new spec)
