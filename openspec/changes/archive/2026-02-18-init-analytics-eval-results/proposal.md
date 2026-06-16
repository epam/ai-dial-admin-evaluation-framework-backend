## Why

The evaluation framework can trigger test suite runs but has no storage for per-test-case execution results. The evaluation job (currently mock) needs a place to write results, and FE/clients need APIs to read them. This change introduces the analytics data layer — a separate configurable datasource designed for append-only result storage, with an API shape portable to OLAP engines (ClickHouse) in future iterations.

## What Changes

- **New analytics datasource**: Separate configurable database connection (`datasource.analytics.*`) alongside the existing meta datasource. Both can point to the same Postgres instance (different schemas/databases) for simple deployments. First iteration: Postgres. Future: ClickHouse or other OLAP stores.
- **New `test_case_run_results` table** (analytics DB): Flat, denormalized, append-only fact table storing per-test-case execution outcomes — test case snapshot, request/response payload (also used for error bodies), execution info (timing, traceId, status), run index (0-based attempt number for multi-run suites). Single shared table for all suites (no dynamic DDL).
- **Batch write API**: `POST /api/v1/analytics/test-case-results` accepting an envelope with `testSuiteId`, `testSuiteRunId`, and a `results` array. Suite/run IDs are declared once at the top level, enforcing batch uniformity by schema. Designed for bulk inserts (OLAP-friendly). Used by the evaluation job.
- **Read API with keyset pagination**: `GET /api/v1/analytics/test-case-results` with cursor-based pagination (not OFFSET/LIMIT) and generic filter support reusing the existing `filter=field:operator:value` query syntax — including JSONB path filtering on `testCaseData` fields. `suiteId` filter is always required (ensures index utilization). Used by FE.
- **Run-anchored timestamps for partition pruning**: All results for a given run share the run's `created_at_ms` from meta DB. This enables exact partition pruning on reads, makes the UNIQUE constraint partition-safe for future time-based partitioning, and provides run existence validation for free. No schema changes to `test_suite_runs`.
- **Repository interface abstraction**: Analytics repository interface with Postgres implementation (`@ConditionalOnProperty(datasource.analytics.vendor=POSTGRES)`). Designed so ClickHouse implementation can be added later without changing service/web layers.

## Capabilities

### New Capabilities

- `analytics-datasource`: Dual datasource configuration — separate analytics DB alongside existing meta DB. Configurable vendor, auth, connection properties. Separate Flyway migration path. Shared and per-vendor DataSource/JdbcTemplate beans.
- `analytics-eval-results`: Test case run result storage and retrieval. Batch write API with envelope DTO (testSuiteId/testSuiteRunId at top level, results array — all-or-nothing, JDBC batch insert), keyset-paginated read API with generic filter framework (reuses existing `filter=field:operator:value` syntax, extended with JSONB path filtering on `testCaseData`). Flat denormalized data model with `run_index` for multi-run suites. Append-only (immutable results). ExecutionInfo grouping in API DTOs (flat columns in DB).

### Modified Capabilities

- `test-suite-runs`: No schema changes. The existing `created_at_ms` column is reused as the timestamp source for analytics results (run-anchored timestamps — design D8).
- `database-and-migrations`: Extend Flyway setup to support a second migration path for the analytics datasource (separate migration location, separate Flyway bean).

## Impact

- **New packages**: `data.db.analytics.*` (or similar) for analytics repository interfaces, models, row mappers.
- **Configuration**: New `datasource.analytics.*` properties in `application.yml`. Existing `datasource.*` properties renamed to `datasource.meta.*` (**BREAKING** — acceptable at early stage). Optional `postgres.*.datasource.schema` properties for schema-based separation. Qualified transaction managers (`metaTransactionManager`, `analyticsTransactionManager`) — all `@Transactional` annotations updated.
- **Database**: New Flyway migration for `test_case_run_results` table in analytics DB. No meta DB schema changes.
- **Dependencies**: No new external dependencies expected (same JDBC/Postgres stack).
- **Testing**: Functional tests need a second Testcontainers Postgres instance (or same instance, different schema) for the analytics datasource.

## Open Questions (to discuss in future iterations)

### Data Model
- **Payload size limits**: Should `request_body` / `response_body` JSONB have size limits? When payloads exceed a threshold (e.g., >256KB), should they be offloaded to object storage with references in the DB?
- **Extracted columns from JSONB**: In future, should suites define which fields to extract from request/response as top-level columns for faster filtering? (Option C from explore: materialized projections)
- **MetricResult table**: Scored evaluation metrics (accuracy, relevance, etc.) will be a separate flat table in analytics DB. Not in scope for this iteration — to be added when the metrics system is implemented.

### Infrastructure
- **Postgres partitioning**: Time-based RANGE partitioning (monthly) is the preferred future approach for retention management. Not needed in v1 — the table is designed to be partition-ready (composite PK `(created_at_ms, id)`, run-anchored timestamps ensure all results for a run co-locate in the same partition). The UNIQUE constraint can be extended with `created_at_ms` without breaking idempotent retries.
- **ClickHouse ORDER BY**: `(test_suite_id, test_suite_run_id, test_case_name)` is the planned ordering key — optimized for "all results for a run" (the most common UI query). Cross-run comparison for a specific test case is a secondary pattern.
- **Datasource renaming migration**: If `datasource.vendor` is renamed to `datasource.meta.vendor`, existing deployments need a migration path (env var aliases, deprecation period, or keep old property as fallback).

### API
- **Aggregation endpoints**: Future iterations may add aggregation APIs (avg metrics per test case, trend analysis across runs). Not in scope for v1.
- **Result deletion**: Should results be deletable independently, or only via cascade when a run is deleted from meta DB? OLAP databases don't handle deletes well — lean toward cascade-only.
- **Cross-run comparison API**: Dedicated endpoint for comparing results across runs of the same suite, keyed by `test_case_name` or `test_case_id`. Future iteration.
