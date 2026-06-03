## Context

The evaluation framework currently has a single Postgres datasource for metadata (test suites, test cases, runs, metric definitions). Design docs (`entity-relationship-model.md` section 6.5, `infrastructure-architecture.md` section 5) already envision a dual data-source architecture: **meta** (authoring, transactional) and **analytics** (results, append-oriented, OLAP-friendly).

The existing codebase uses:
- Single `DataSource` bean created in `PostgresConfiguration` (conditional on `datasource.vendor=POSTGRES`)
- Single `JdbcTemplate` / `NamedParameterJdbcTemplate` in `JdbcConfiguration`
- Flyway auto-configured at `classpath:db/migration/${datasource.vendor}`
- Repositories conditional on `@ConditionalOnProperty(name = "datasource.vendor", havingValue = "POSTGRES")`
- Generic filter framework (`filter=field:operator:value` query syntax) with per-entity whitelists, `WhereBuilder`, `OrderByBuilder`

Constraints:
- Must remain JDBC-only (no JPA/Hibernate).
- Analytics API must be portable to ClickHouse without service/web layer changes.
- Append-only data model — no UPDATE/DELETE on result records in analytics DB.
- Breaking changes to configuration properties are acceptable (early-stage project, no production deployments).

## Goals / Non-Goals

**Goals:**

- Introduce a second configurable datasource for analytics alongside the existing meta datasource.
- Create the `test_case_run_results` table with a flat, denormalized schema suitable for both Postgres and future OLAP engines.
- Provide batch write API for the evaluation job and keyset-paginated read API for FE.
- Reuse the existing filter framework (`filter=field:operator:value`) for analytics read endpoints, extended with JSONB path filtering on `testCaseData`.
- Anchor analytics timestamps to the run's `created_at_ms` from meta DB for partition pruning (run-anchored timestamps — no schema changes to `test_suite_runs`).
- Design repository interface abstraction that allows swapping Postgres for ClickHouse.

**Non-Goals:**

- ClickHouse implementation (future iteration — only the interface is designed for it).
- Postgres partitioning (future — table is partition-ready but not partitioned in v1).
- MetricResult table (separate future change when metrics system is implemented).
- Aggregation endpoints (future iteration).
- Result deletion API (results are immutable; cleanup via run lifecycle in meta DB).
- Object storage for large payloads (future — all payloads stored as JSONB in v1).
- Nested JSONB path filtering (only top-level keys in v1).

## Decisions

### D1: Dual datasource with symmetric config (**BREAKING**)

**Decision:** Rename existing `datasource.vendor` to `datasource.meta.vendor` and `postgres.datasource.*` to `postgres.meta.datasource.*`. Add symmetric `datasource.analytics.*` and `postgres.analytics.datasource.*` properties. Add optional `schema` property for schema-based separation. This is a breaking change — acceptable at this early stage (no production deployments).

**Configuration shape:**
```yaml
datasource:
  meta:
    vendor: POSTGRES
    auth:
      type: basic
  analytics:
    vendor: POSTGRES
    auth:
      type: basic

postgres:
  meta:
    datasource:
      url: jdbc:postgresql://localhost:5432/evaluation_db
      driver-class-name: org.postgresql.Driver
      username: postgres
      password: postgres
      schema: public         # optional, default: public
  analytics:
    datasource:
      url: jdbc:postgresql://localhost:5432/evaluation_analytics_db?reWriteBatchedInserts=true
      driver-class-name: org.postgresql.Driver
      username: postgres
      password: postgres
      schema: public         # optional, default: public
```

**Schema-based separation:** For simple dev/test setups, both datasources can point to the same database with different schemas:
```yaml
postgres:
  meta:
    datasource:
      url: jdbc:postgresql://localhost:5432/evaluation_db
      schema: meta
  analytics:
    datasource:
      url: jdbc:postgresql://localhost:5432/evaluation_db?reWriteBatchedInserts=true
      schema: analytics
```

**Both datasources are mandatory** — there is no opt-out or `enabled` flag for analytics. All deployments must configure both `datasource.meta.*` and `datasource.analytics.*` properties. This is acceptable at the current early stage. If a future use case requires running without analytics, a `datasource.analytics.enabled` flag can be introduced.

**Rationale:** Symmetric naming makes the config self-documenting. Both datasources are explicit peers — no "default" vs "other". Schema-based separation provides a lighter alternative for dev/test. Breaking change is acceptable at this stage.

**Impact:** All existing `datasource.vendor`, `datasource.auth.type`, `postgres.datasource.*` references in code, tests, and deployment configs must be updated to the `datasource.meta.*` / `postgres.meta.datasource.*` paths. `@ConditionalOnProperty` annotations in existing repositories change from `datasource.vendor` to `datasource.meta.vendor`.

### D2: Both datasources explicitly qualified

**Decision:** Both meta and analytics datasources use explicit `@Qualifier` annotations. No unqualified default.

| Bean | Qualifier | Created by |
|------|-----------|------------|
| `DataSource` (meta) | `@Qualifier("metaDataSource")` | `MetaPostgresConfiguration` (renamed from `PostgresConfiguration`) |
| `DataSource` (analytics) | `@Qualifier("analyticsDataSource")` | `AnalyticsPostgresConfiguration` |
| `NamedParameterJdbcTemplate` (meta) | `@Qualifier("metaJdbcTemplate")` | `MetaJdbcConfiguration` (renamed from `JdbcConfiguration`) |
| `JdbcTemplate` (meta) | `@Qualifier("metaRawJdbcTemplate")` | `MetaJdbcConfiguration` |
| `NamedParameterJdbcTemplate` (analytics) | `@Qualifier("analyticsJdbcTemplate")` | `AnalyticsJdbcConfiguration` |
| `JdbcTemplate` (analytics) | `@Qualifier("analyticsRawJdbcTemplate")` | `AnalyticsJdbcConfiguration` |

**Rationale:** Both qualifiers explicit is less error-prone — a developer cannot accidentally inject the wrong datasource by omitting a qualifier. If a repository or service forgets `@Qualifier`, the injection fails at startup (compile-time safety) rather than silently using the wrong DB. Consistent, symmetric naming matches the config structure from D1.

**Impact:** Existing meta repositories must be updated to inject `@Qualifier("metaJdbcTemplate")`. This is a mechanical change across all `@RequiredArgsConstructor` repositories — add `@Qualifier` on the constructor parameter (or use a custom annotation, see below).

**Lombok configuration required:** Project `lombok.config` at the root SHALL include `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier`. Without this, Lombok's `@RequiredArgsConstructor` won't copy `@Qualifier` annotations to generated constructor parameters, and Spring won't resolve the correct bean.

**Convenience annotation (optional):** Consider creating `@MetaJdbc` and `@AnalyticsJdbc` meta-annotations to reduce boilerplate:
```java
@Qualifier("metaJdbcTemplate")
@Retention(RUNTIME)
@Target({FIELD, PARAMETER})
public @interface MetaJdbc {}
```

### D3: Symmetric Flyway configuration for both datasources

**Decision:** Disable Spring Boot's auto-configured Flyway entirely. Manually configure both meta and analytics Flyway beans using the same approach. Move existing meta migrations to a `meta/` subdirectory for symmetric layout.

- Meta migrations: `classpath:db/migration/meta/${datasource.meta.vendor}/`
- Analytics migrations: `classpath:db/migration/analytics/${datasource.analytics.vendor}/`

Both use the default `flyway_schema_history` table name — since they always run in separate databases or separate schemas, there is no conflict.

**Implementation:** Disable `spring.flyway.enabled=false`. Create two manually configured Flyway beans: `metaFlywayMigration` (pointing to `metaDataSource`) and `analyticsFlywayMigration` (pointing to `analyticsDataSource`). Both execute via `afterPropertiesSet()` or `@PostConstruct`. Same naming convention: `V<version>__<Description>.sql`. Independent version numbering (each starts at V1.1 in its own directory). Each Flyway bean configures `defaultSchema` from the corresponding `postgres.*.datasource.schema` property. **Both beans SHALL preserve existing Flyway settings** from the former auto-config: `baselineOnMigrate(true)` and `validateMigrationNaming(true)`.

**Startup validation:** Before running migrations, the service SHALL validate that meta and analytics datasources are sufficiently isolated. If they point to the same database AND the same schema (or both default to `public`), the application SHALL fail to start with a descriptive error. Same database with different schemas is allowed. Different databases are always allowed. **URL comparison SHALL parse the JDBC URL** to extract host, port, and database name rather than comparing raw strings — this avoids false negatives from syntactically different but equivalent URLs (e.g., default port omitted vs explicit `:5432`, different query parameter ordering). **Note:** DNS-level equivalences (e.g., `localhost` vs `127.0.0.1`) are NOT resolved — only syntactic normalization is performed. This is a deliberate trade-off: resolving DNS would introduce startup-time network dependencies and still wouldn't catch all aliases (e.g., custom hostnames). **Ordering:** The validation is implemented inside a `@Configuration` class (`DatasourceValidationConfiguration`) that produces a marker bean (e.g., `DatasourceValidationResult`). Both Flyway `@Bean` methods SHALL declare this marker as a parameter, creating a hard bean dependency that guarantees validation completes before any Flyway migration runs. Note: `@Import` alone does NOT guarantee `@PostConstruct` ordering — an explicit bean dependency is required.

**Rationale:** Symmetric manual configuration is more predictable — both datasources follow the same pattern, no special-casing for meta vs analytics. Symmetric directory layout makes it obvious which migrations belong to which datasource. Schema-based separation support makes dev/test setups simpler. ClickHouse migration path can be added as `db/migration/analytics/CLICKHOUSE/` later.

### D4: Flat denormalized result table (single table, all suites)

**Decision:** One shared table for all test suites. No dynamic DDL.

**Table: `test_case_run_results`** (analytics DB):

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| `id` | VARCHAR(36) | NOT NULL | PK (UUID) |
| `test_suite_run_id` | VARCHAR(36) | NOT NULL | Soft ref to meta DB run |
| `test_suite_id` | VARCHAR(36) | NOT NULL | Denormalized for filtering |
| `test_case_id` | VARCHAR(36) | NOT NULL | Soft ref to meta DB test case |
| `test_case_name` | VARCHAR(255) | NOT NULL | Snapshot at execution time |
| `run_index` | INTEGER | NOT NULL | 0-based run attempt index (for multi-run suites with numberOfRuns > 1) |
| `test_case_data` | JSONB | NOT NULL | Snapshot of test case data (filterable via JSONB path queries) |
| `request_body` | JSONB | NULL | Actual request sent to endpoint |
| `response_body` | JSONB | NULL | Response received, or error body on failure |
| `response_status_code` | INTEGER | NULL | HTTP status code (null if call never completed) |
| `execution_status` | VARCHAR(20) | NOT NULL | SUCCESS, FAILED, TIMEOUT, ERROR |
| `exec_started_at_ms` | BIGINT | NOT NULL | When endpoint call started |
| `exec_completed_at_ms` | BIGINT | NOT NULL | When endpoint call finished |
| `exec_duration_ms` | BIGINT | NOT NULL | Precomputed duration (OLAP optimization — avoids recomputing in every query) |
| `trace_id` | VARCHAR(128) | NULL | Distributed tracing ID |
| `created_at_ms` | BIGINT | NOT NULL | Run creation timestamp from meta DB (see D8) — all results for a run share this value |

**Indexes and constraints (Postgres v1):**
```sql
PRIMARY KEY (created_at_ms, id)
UNIQUE (test_suite_run_id, test_case_id, run_index, created_at_ms)
INDEX idx_results_suite_run_case (test_suite_id, test_suite_run_id, test_case_name)
INDEX idx_results_id (id)
```

**Composite primary key `(created_at_ms, id)`:** The PK includes `created_at_ms` as the leading column to enable future time-based RANGE partitioning without a PK migration (Postgres requires the partition key to be part of the primary key). The PK index also fully supports the keyset cursor `(created_at_ms DESC, id DESC)` ordering via backward index scan — no separate index is needed. All records for a run share the same `created_at_ms` (the run's creation timestamp, see D8), and the `id` tiebreaker ensures a unique position.

**Standalone index on `id`:** Because the PK has `created_at_ms` as the leading column, a `WHERE id = :id` lookup cannot use the PK efficiently (requires full index scan, O(n)). The standalone `idx_results_id` index ensures `findById(UUID id)` remains O(log n). The write overhead is minimal (one additional B-tree entry per insert in an append-only table).

**UNIQUE constraint on `(test_suite_run_id, test_case_id, run_index, created_at_ms)`:** Each test case is evaluated exactly once per run index within a run. The constraint provides data integrity and enables idempotent batch writes via `INSERT ... ON CONFLICT DO NOTHING` — if the eval job retries after a network failure (successful write, lost response), duplicate rows are silently skipped. The `run_index` column distinguishes attempts for multi-run suites (`numberOfRuns > 1`). **Partition-ready from day one:** The constraint includes `created_at_ms` from the initial migration so that future time-based RANGE partitioning (which requires the partition key in every UNIQUE constraint) needs no `DROP + ADD CONSTRAINT` migration on a potentially large table. Because all results for a run share the same `created_at_ms` (the run's creation timestamp, see D8), this extra column does NOT weaken uniqueness semantics and does NOT break idempotent retries — retries for the same run always use the same `created_at_ms` value.

**ClickHouse note:** ClickHouse has no UNIQUE constraints. Deduplication is handled via `ReplacingMergeTree` engine which deduplicates during background merges using a version column. The Postgres UNIQUE constraint is implementation-specific — the repository interface does not expose it.

**Rationale:** Single table is simplest, supports cross-suite queries, maps cleanly to ClickHouse MergeTree. JSONB for variable payloads — no extracted columns in v1 (but JSONB top-level keys are filterable via the extended filter framework, see D12). Denormalized `test_suite_id` and `test_case_name` avoid JOINs. `test_case_id` provides stable link back to meta. `response_body` serves double duty — contains success response or error body. Append-only — no `updated_at_ms`.

**Alternative considered:** Dynamic per-suite tables. Rejected — runtime DDL complexity, migration headache, cross-suite queries become UNION ALL.

**JSONB model representation:** JSONB columns (`test_case_data`, `request_body`, `response_body`) are stored as `String` (raw JSON) in the `TestCaseRunResult` model — consistent with the existing pattern used by `TestSuiteRun.runConfig` and `TestSuiteRun.errorDetails`. The `RowMapper` reads `PGobject.getValue()` as `String`. The MapStruct mapper (or service layer) converts between `String` (model) and `JsonNode` (DTOs) using `ObjectMapper`. The `PostgresJsonbSqlParameter.fromJson()` utility handles writes. This keeps the data layer model free of Jackson dependencies.

**Future path (Option C):** Materialized views / extracted columns per suite when specific suites need faster typed-column access.

### D5: Keyset pagination for analytics reads

**Decision:** Use cursor-based (keyset) pagination instead of OFFSET/LIMIT for analytics read endpoints.

**Cursor shape:** Composite `(created_at_ms, id)` — both monotonically orderable, together uniquely identify a position.

**API contract:**
```
GET /api/v1/analytics/test-case-results?filter=suiteId:eq:<uuid>&filter=runId:eq:<uuid>&size=50
GET /api/v1/analytics/test-case-results?filter=suiteId:eq:<uuid>&filter=runId:eq:<uuid>&size=50&cursor=<encoded>
```

**Response:**
```json
{
  "content": [...],
  "size": 50,
  "nextCursor": "eyJjcmVhdGVkQXQiOjE3Mzk3NTA0MDAwMDAsImlkIjoiYWJjLi4uIn0=",
  "hasMore": true
}
```

**`size` field semantics:** `size` is the **requested** page size (the value resolved by `PaginationParamResolver`, reflecting the client's `size` query parameter or the configured default). It is NOT the actual `content` array length (which may be smaller on the last page). This provides API discoverability — clients can see what page size was applied, especially when the default was used.

**Cursor encoding:** Base64-encoded JSON `{"createdAt":<ms>,"id":"<uuid>"}`. Opaque to the client.

**SQL pattern:**
```sql
SELECT * FROM test_case_run_results
WHERE <filter_conditions>   -- built by WhereBuilder from filter=field:operator:value params (see D12)
  AND (created_at_ms, id) < (:cursorCreatedAt, :cursorId)   -- cursor condition added by repository
ORDER BY created_at_ms DESC, id DESC
LIMIT :size + 1   -- fetch one extra to determine hasMore
```

**Sort is fixed:** Always `created_at_ms DESC, id DESC` — no user-configurable sort. This is required for keyset pagination correctness. **The `sort` query parameter is NOT accepted** — if a client provides it, the endpoint SHALL return HTTP 400 with `VALIDATION_ERROR`.

**Within-run sort order (temporary limitation):** All results for a single run share the same `created_at_ms` (see D8). With the fixed sort, within-run pagination orders results by UUID — effectively random. For within-run browsing (the primary FE use case), the FE SHALL fetch all pages for a run and sort client-side (by test case name, status, duration, etc.). This is viable because within-run result sets are bounded (typically < 2,000 items for realistic suites). The FE can use the `/count` endpoint to determine the total before deciding whether to fetch all pages. **This is a temporary solution** — a future iteration should add server-side sort support for within-run queries (e.g., via secondary sort options with supporting indexes, or a dedicated run-scoped endpoint with offset pagination).

**Rationale:** OFFSET/LIMIT is expensive on OLAP databases (scans from start). Keyset is O(1) regardless of page depth. Compatible with ClickHouse. New `CursorPageResponseDto` — does not change existing `PageResponseDto` used by meta endpoints.

**Alternative considered:** OFFSET/LIMIT for consistency with existing endpoints. Rejected — poor OLAP performance at scale, and analytics is a new domain with its own response contract.

### D6: Batch-only write API with configurable limits

**Decision:** The write endpoint accepts an envelope object containing `testSuiteId`, `testSuiteRunId`, and a `results` array. No single-result write endpoint. **Batch write is all-or-nothing** — if any record fails validation, the entire batch is rejected. Duplicate rows (matching the UNIQUE constraint `(test_suite_run_id, test_case_id, run_index, created_at_ms)`) are silently skipped via `INSERT ... ON CONFLICT DO NOTHING`.

```
POST /api/v1/analytics/test-case-results
Body: {
  "testSuiteId": "<uuid>",
  "testSuiteRunId": "<uuid>",
  "results": [{ ... }, { ... }, ...]
}
```

**Envelope DTO:** `testSuiteId` and `testSuiteRunId` are declared once at the top level — not repeated in every result item. This enforces the batch uniformity constraint by schema (not runtime validation), reduces payload size (~800KB savings at 10,000 items), and makes the API harder to misuse. The service merges these IDs into each result model before persisting.

Returns: HTTP 201 with `{"totalItems": N}` where N is the number of items in the request's `results` array. This echoes the input count (not the DB-level insert count, which is unreliable with `reWriteBatchedInserts=true`) and confirms the server received and processed the batch.

**Batch size limits (configurable):**
```yaml
analytics:
  results:
    batch:
      max-items: 10000           # max array elements per request
      max-request-size-bytes: 10485760  # 10MB max request body
```

- **Item count limit:** Validated at service layer after deserialization (checks `results` array size). Returns HTTP 400 if exceeded.
- **Byte size limit:** Enforced via a path-scoped `MaxRequestBodyFilter` servlet filter (see D13). Returns HTTP 413 (Payload Too Large) if exceeded before deserialization.

**Implementation:** Use `NamedParameterJdbcTemplate.batchUpdate()` with `INSERT ... ON CONFLICT (test_suite_run_id, test_case_id, run_index, created_at_ms) DO NOTHING`. The Postgres JDBC driver optimizes this when `reWriteBatchedInserts=true` is set on the connection URL (rewrites multiple batch statements into a single multi-row INSERT internally). `ON CONFLICT DO NOTHING` enables idempotent retries — if the eval job retries after a network failure, duplicate rows are silently skipped. The response returns `{"totalItems": N}` echoing the input count — not the DB-level insert count, which is unreliable with `reWriteBatchedInserts=true` (`SUCCESS_NO_INFO`).

**Intra-batch duplicates:** If the same batch contains two items with identical `(testCaseId, runIndex)` (combined with the envelope's `testSuiteRunId`), the second is silently skipped via `ON CONFLICT DO NOTHING`. The response's `totalItems` reflects the input count (not the number of rows actually inserted), so the client is not notified of skipped duplicates. This is intentional — the data integrity guarantee (one result per case per run index) is preserved by the DB constraint regardless of client behavior.

**Run existence and suite ID validation:** Before persisting results, the service reads the `test_suite_runs` record from meta DB using the envelope's `testSuiteRunId`. If the run does not exist, the batch is rejected with HTTP 404 (`EntityNotFoundException`). The service SHALL also validate that `envelope.testSuiteId` matches `run.getTestSuiteId()` — if they differ, the batch is rejected with HTTP 400 (`VALIDATION_ERROR`) indicating suite ID mismatch. This prevents silent data corruption where results are stored with an incorrect `test_suite_id`, which would corrupt suite-level queries (since `suiteId` is the required filter on all read endpoints). All records in the batch receive the run's `created_at_ms` as their `created_at_ms` value (see D8). UUIDs are generated for each record's `id`. This existence check is mandatory — it obtains the timestamp needed for partition-compatible writes and prevents garbage data referencing non-existent runs.

**Rationale:** OLAP engines strongly prefer batch inserts. The evaluation job naturally produces results in batches. Configurable limits prevent abuse and OOM. All-or-nothing semantics simplify error handling — no partial state. Envelope DTO eliminates redundant data and makes uniformity constraint schema-enforced. A single-result convenience endpoint can be added later if needed.

### D7: ExecutionInfo grouping in DTOs

**Decision:** Flat columns in the DB, grouped as `ExecutionInfoDto` in the API response DTO.

**DB columns:** `execution_status`, `exec_started_at_ms`, `exec_completed_at_ms`, `exec_duration_ms`, `trace_id` — all top-level. `exec_duration_ms` is **computed server-side** as `exec_completed_at_ms - exec_started_at_ms` during batch write — the client does NOT send `durationMs` in the request DTO.

**Timing validation:** `completedAt` SHALL be >= `startedAt`. Validated at service layer — returns HTTP 400 if violated. This prevents negative `durationMs` from being stored permanently in the append-only table. `runIndex` SHALL be >= 0 and <= 99999 — validated via Jakarta annotations on the request DTO.

**Timing for ERROR status:** For `ERROR` status (e.g., DNS resolution failure, connection refused), `startedAt` and `completedAt` should reflect the time the attempt was initiated and abandoned, respectively. These fields are always required regardless of execution status.

**testCaseData shape validation:** `testCaseData` SHALL be a JSON object (not array, string, number, or null). Validated at service layer — returns HTTP 400 if the value is not a JSON object. This is required because JSONB path filtering (`->>key`) only operates on object structures.

**API response:**
```json
{
  "id": "...",
  "testSuiteRunId": "...",
  "testCaseName": "...",
  "runIndex": 0,
  "executionInfo": {
    "status": "SUCCESS",
    "startedAt": 1739750400000,
    "completedAt": 1739750401234,
    "durationMs": 1234,
    "traceId": "abc-123"
  },
  "requestBody": { ... },
  "responseBody": { ... }
}
```

**`responseBody` semantics:** Contains the response body regardless of success or failure. For error responses (e.g., HTTP 500 from the target endpoint), `responseBody` contains the error body returned by the endpoint. This simplifies the schema — no separate `errorPayload` field.

**Rationale:** Flat DB columns are OLAP-friendly (ClickHouse reads columnar data efficiently). Nested DTO provides clean API structure and avoids confusion with `MetricResult` (scored metrics, separate entity, future iteration). Single `responseBody` field is simpler than separate success/error fields — the `executionStatus` already indicates whether the response represents a success or error.

### D8: Run-anchored timestamps for partition pruning

**Decision:** All records in `test_case_run_results` use the parent run's `created_at_ms` (from `test_suite_runs` in meta DB) as their `created_at_ms` value. All results for a given run share the same `created_at_ms`. No additional time range columns are added to `test_suite_runs`.

**Implementation:** The `batchCreate` method (annotated with `@Transactional("analyticsTransactionManager")`) reads the `test_suite_runs` record from meta DB using the envelope's `testSuiteRunId`. The run's `createdAt` is set as `created_at_ms` on all result records. If the run does not exist → HTTP 404 (`EntityNotFoundException`). The service also validates that `envelope.testSuiteId` matches the run's `testSuiteId` — mismatch → HTTP 400 (`VALIDATION_ERROR`). **Note on transaction boundaries:** The meta read occurs within the analytics transaction boundary, but uses the meta `DataSource` (a separate JDBC connection managed by the meta connection pool). The `analyticsTransactionManager` only governs the analytics `DataSource` — the meta read runs with auto-commit on its own connection and is functionally independent. No cross-datasource writes, no nested transaction managers, no distributed transaction concerns.

**Benefits over time-range hint approach:**
- **Partition-safe idempotent retries:** Retries for the same run always produce the same `created_at_ms`, so extending the UNIQUE constraint with `created_at_ms` for partitioning does not break `ON CONFLICT DO NOTHING` semantics (see D4).
- **Exact partition pruning on reads:** When `runId` filter is present, the service looks up the run's `createdAt` and adds `WHERE created_at_ms = :runCreatedAt` — an exact partition match, not a range scan.
- **No cross-datasource writes:** The batch write is a pure analytics-only operation (after the initial meta read). No `@Transactional("metaTransactionManager")` calls during batch write. No distributed transaction concerns, no best-effort error handling, no stale hint correction.
- **Existence validation for free:** The mandatory meta read verifies that `testSuiteRunId` exists, preventing garbage data referencing non-existent runs.

**Partition pruning on reads:**
- **With `runId` filter (run exists in meta):** The service looks up the run's `createdAt` from meta → adds `WHERE created_at_ms = :runCreatedAt` for exact partition match. Combined with user-provided `createdAt` filters (if any) via AND semantics.
- **With `runId` filter (run deleted from meta — orphan scenario):** If the run does not exist in meta DB, the service SHALL **skip partition pruning** and query the analytics DB with the `runId` filter only (no `created_at_ms` equality condition). This means orphaned results (run deleted from meta after results were written) remain queryable until the future cleanup job removes them. Returning an empty result set would make orphaned results invisible through the API, creating a confusing gap between "results exist" and "results are inaccessible." Partition pruning is an optimization, not a correctness requirement — skipping it for orphaned runs has negligible performance impact (orphaned runs are rare, and the `test_suite_run_id` filter still uses the composite index).
- **Without `runId` (suite-level only):** User-provided `createdAt` filters enable time-range pruning. Without them, all partitions are scanned (filtered by `test_suite_id` index). This is the same behavior as the time-range hint approach — time range hints were per-run, not per-suite.

**Semantic note:** `created_at_ms` represents the run's creation time, not the physical result write time. This is more meaningful for analytics — it groups results by their logical context (the run) and ensures all results for a run co-locate in the same partition.

**No schema changes to `test_suite_runs`:** The existing `created_at_ms` column on `test_suite_runs` provides the needed timestamp. No new columns, no V1.7 migration.

**Future optimization — run metadata caching for reads:** Every paginated read with a `runId` filter calls `TestSuiteRunRepository.findById()` to obtain `createdAt` for partition pruning. For a typical pagination flow (user pages through 10+ pages), this repeats the same PK lookup per page. Since `created_at_ms` is immutable, a lightweight in-memory cache (e.g., Caffeine, 5-min TTL, bounded size) in `AnalyticsResultService` could eliminate redundant meta DB reads. Not required for v1 (single PK lookup is sub-millisecond), but worth introducing if read-path meta DB load becomes a concern.

**Rationale:** Simpler, more correct, more efficient. Eliminates cross-datasource write complexity, stale hint correction, and the partitioning UNIQUE constraint problem — all in exchange for a single read-only meta lookup per batch write (sub-millisecond, indexed PK lookup).

### D9: Analytics repository interface for storage abstraction

**Decision:** Define repository interface in `data.db.analytics.repository`. Postgres implementation conditional on `datasource.analytics.vendor=POSTGRES`. The interface uses `List<FilterCondition>` (from the existing filter framework) for query filtering — the implementation translates these to SQL using `WhereBuilder`. The repository returns `CursorPage<T>` with a `Cursor` object (not an encoded String) — cursor encoding is a service-layer concern handled by `CursorCodec`.

```java
public interface TestCaseRunResultRepository {
    void saveAll(List<TestCaseRunResult> results);
    CursorPage<TestCaseRunResult> findAll(List<FilterCondition> filters,
                                           Long runCreatedAtMs,
                                           Cursor cursor, int size);
    Optional<TestCaseRunResult> findById(UUID id);
    long count(List<FilterCondition> filters, Long runCreatedAtMs);
}
```

`runCreatedAtMs` is the exact `created_at_ms` value for partition pruning when `runId` filter is present (null otherwise). The service obtains this from the run's `createdAt` in meta DB.

**Note:** No `deleteByRunId` in v1. When a run is deleted from meta DB, analytics results become orphans. A scheduled cleanup job (future iteration) will handle orphan detection and removal. This is deliberate — OLAP databases handle deletes poorly, and decoupling deletion avoids distributed transaction complexity.

**Rationale:** The interface hides SQL dialect differences. Postgres uses `NamedParameterJdbcTemplate` with SQL text blocks and `WhereBuilder` for filter translation. Future ClickHouse implementation uses ClickHouse JDBC driver with its own SQL dialect and a ClickHouse-specific `WhereBuilder`. Service layer doesn't change. Using `List<FilterCondition>` keeps the interface database-agnostic while leveraging the existing filter infrastructure.

### D10: Package structure for analytics

**Decision:** Analytics data layer in a separate package subtree under `data.db`:

```
data.db.analytics/
  model/
    TestCaseRunResult.java
    ExecutionStatus.java (enum)
    cursor/
      Cursor.java          (pure data record — no encoding logic, free of Jackson dependency)
      CursorPage.java      (nextCursor is Cursor type, not String — encoding handled by service-layer CursorCodec)
  mapper/
    TestCaseRunResultRowMapper.java
  repository/
    TestCaseRunResultRepository.java (interface)
    PostgresTestCaseRunResultRepository.java
```

Service and web layers use the existing package structure:
```
service.domain/
  analytics/
    AnalyticsResultService.java
    CursorCodec.java
  dto/
    analytics/
      BatchWriteRequestDto.java (envelope: testSuiteId, testSuiteRunId, results list)
      BatchWriteResponseDto.java (totalItems — echoes input count)
      TestCaseRunResultItemDto.java (single result item within envelope — no suiteId/runId)
      TestCaseRunResultResponseDto.java
      ExecutionInfoDto.java (uses ExecutionStatus enum)
      ResultCountResponseDto.java
      CursorPageResponseDto.java
  mapper/
    TestCaseRunResultMapper.java (MapStruct, computes durationMs server-side)

web.controller/
  AnalyticsResultController.java

web.filter/
  MaxRequestBodyFilter.java
```

Filter framework extensions in existing packages:
```
data.db.repository.sql/
  FilterWhitelists.java  (add ANALYTICS_RESULTS whitelist)
  FilterFieldType.java   (add JSONB_STRING type)
  WhereBuilder.java      (extend for JSONB path access)
```

**Rationale:** Keeps analytics isolated from meta within the data layer. Shared infrastructure (`data.db.model.filter`, `data.db.repository.sql`) is reused and extended. The `analytics` subpackage makes it clear which repositories talk to which datasource.

### D11: Qualified transaction managers

**Decision:** Create explicitly qualified `PlatformTransactionManager` beans for both datasources. No unqualified default. All `@Transactional` annotations must specify which transaction manager to use.

| Bean | Qualifier | Created by |
|------|-----------|------------|
| `DataSourceTransactionManager` (meta) | `metaTransactionManager` | `MetaJdbcConfiguration` |
| `DataSourceTransactionManager` (analytics) | `analyticsTransactionManager` | `AnalyticsJdbcConfiguration` |

**Impact on existing code:**
- All 8 existing meta services using `@Transactional` must be updated to `@Transactional("metaTransactionManager")`.
- All existing `@Transactional(readOnly = true)` must become `@Transactional(value = "metaTransactionManager", readOnly = true)`.
- New analytics write methods use `@Transactional("analyticsTransactionManager")`.
- New analytics read methods use `@Transactional(value = "analyticsTransactionManager", readOnly = true)`.

**TransactionTimestampAspect:** The aspect SHALL be scoped to meta transactions only. It SHALL check the `@Transactional` annotation's `value()` (transaction manager qualifier) and skip timestamp initialization for non-meta transactions (e.g., `analyticsTransactionManager`). Analytics services do not use `TransactionTimestampContext` — they use the run's `created_at_ms` from meta DB (see D8). Binding an unused timestamp in analytics transactions would be wasted work, misleading during debugging, and a maintenance trap (future analytics code might accidentally consume the wrong timestamp). **Note:** The analytics batch write does NOT perform cross-TM transactional calls. The meta read for run existence/timestamp is called from within the `@Transactional("analyticsTransactionManager")` method, but it uses the meta `DataSource` (a separate JDBC connection with auto-commit), so it is functionally independent of the analytics transaction. No nested different-TM transaction scenarios arise. **Action required:** The existing aspect has a `// todo: adjust to work with nested transactions` comment. This TODO refers to same-TM nested transactions (e.g., `PROPAGATION_REQUIRES_NEW`), which are not used in this codebase. As part of this change, remove the TODO, add a clarifying comment, and add the meta-only scoping logic.

**Rationale:** Consistent with D2's "no unqualified default" principle. Without a `@Primary` transaction manager, an unqualified `@Transactional` fails at the first transactional method call with `NoUniqueBeanDefinitionException` (runtime detection — unlike unqualified `JdbcTemplate` injection from D2, which fails at startup). This is still effective because tests exercise transactional methods early. Explicit qualification prevents accidentally using the wrong datasource's transaction.

### D12: Filter framework reuse with JSONB extension

**Decision:** The analytics list endpoint reuses the existing `filter=field:operator:value` query syntax. No dedicated query params (`runId`, `suiteId`, etc.) — all filtering goes through the generic framework. The framework is extended with JSONB path filtering for `testCaseData` fields.

**Required filter:** `suiteId:eq:<uuid>` is **always required**. `runId` is optional but commonly used alongside `suiteId`. Validated at service layer — returns HTTP 400 if `suiteId` is missing. This ensures the composite index `(test_suite_id, test_suite_run_id, test_case_name)` always has its leading column available.

**Analytics filter whitelist:**

| API Field | DB Column | Type | Allowed Operators |
|-----------|-----------|------|-------------------|
| `runId` | `test_suite_run_id` | UUID | EQ |
| `suiteId` | `test_suite_id` | UUID | EQ |
| `testCaseId` | `test_case_id` | UUID | EQ |
| `testCaseName` | `test_case_name` | STRING | EQ, NE, CONTAINS |
| `executionStatus` | `execution_status` | STRING | EQ, NE |
| `runIndex` | `run_index` | LONG | EQ, GT, GTE, LT, LTE |
| `createdAt` | `created_at_ms` | LONG | GT, GTE, LT, LTE |
| `execDurationMs` | `exec_duration_ms` | LONG | GT, GTE, LT, LTE |
| `responseStatusCode` | `response_status_code` | LONG | EQ, GT, GTE, LT, LTE |
| `testCaseData.<key>` | `test_case_data->>'<key>'` | JSONB_STRING | EQ, NE, CONTAINS |

**JSONB path filtering (new capability):**
- Dot-notation: `testCaseData.<key>` where `<key>` is a top-level key in the JSONB column.
- Example: `filter=testCaseData.prompt:contains:hello` → SQL: `test_case_data->>:jsonbKey ILIKE '%' || :param || '%'` (where `:jsonbKey` is bound to `'prompt'` as a parameterized value).
- **JSONB key MUST be parameterized** — the key is user input and SHALL be bound via a named parameter (e.g., `:jsonbKey`), never interpolated into the SQL string. This prevents SQL injection.
- Only top-level keys in v1. No nested paths (e.g., `testCaseData.meta.category` is NOT supported).
- All JSONB values treated as STRING for comparison purposes.

**Framework extension:**
- New `FilterFieldType.JSONB_STRING` — signals that the field supports dot-notation JSONB path access.
- `FilterWhitelists.ANALYTICS_RESULTS` defines `testCaseData` as a JSONB-pathable column (mapped to `test_case_data`).
- `WhereBuilder` extended: when field contains `.`, splits on first dot, **rejects empty JSONB key** (e.g., `testCaseData.` → error), looks up prefix in whitelist, verifies it's `JSONB_STRING` type, generates `column->>:jsonbKeyParam` SQL with the JSONB key bound as a named parameter (preventing SQL injection). **`buildPredicate` signature change required:** The current `buildPredicate(String column, FilterOperator operator, String paramName)` does not receive type information. It SHALL be extended to accept the `FilterFieldType` (or `FilterFieldDefinition`) so it can decide between regular column access (`column <op> :param`) and JSONB accessor syntax (`column->>:jsonbKeyParam <op> :param`). For JSONB fields, the method also needs the JSONB key parameter name — either as an additional parameter or by passing the full definition. The enclosing `build()` method SHALL create and bind the JSONB key parameter before calling `buildPredicate`. The `parseValue` switch must add `case JSONB_STRING -> rawValue` (treat as string). The existing `validateOperator` guard (`CONTAINS` only for `STRING`) must be updated to also allow `CONTAINS` for `JSONB_STRING` — note: this guard runs after `allowedOperators.contains()`, so the type check must include `JSONB_STRING` before the exception is thrown.

**InvalidFilterException handling:** `WhereBuilder` throws `InvalidFilterException` (from `data.db.exception`). `DefaultExceptionHandler` currently handles `FilterValidationException` but NOT `InvalidFilterException` directly. An `InvalidFilterException` handler SHALL be added to `DefaultExceptionHandler` — mapping it to HTTP 400 with `INVALID_FILTER` error code (same response shape as `FilterValidationException`). This allows analytics services to let `InvalidFilterException` propagate directly from `WhereBuilder` without wrapping.

**Rationale:** Consistent API across meta and analytics — FE uses the same `filter=` query syntax. Reuses proven, SQL-injection-safe infrastructure (whitelists, parameterized queries, type conversion). JSONB path filtering enables querying on test case data without schema changes. The extension is minimal — one new type, one branch in `WhereBuilder`.

### D13: Request body size limit via MaxRequestBodyFilter

**Decision:** Enforce `analytics.results.batch.max-request-size-bytes` via a path-scoped servlet filter (`MaxRequestBodyFilter`), not a global Tomcat setting.

**Implementation:**
- `MaxRequestBodyFilter` extends `OncePerRequestFilter`, registered only for `POST /api/v1/analytics/test-case-results`. **Filter ordering:** The filter SHALL be registered via `FilterRegistrationBean` with an order that ensures it runs **after** the Spring Security filter chain (which handles CORS). Use `Ordered.LOWEST_PRECEDENCE - 1` (or any value greater than `SecurityProperties.DEFAULT_FILTER_ORDER`, which is `-100`). This guarantees that CORS headers are present on the 413 response from the eager rejection path — without this, the browser blocks the response due to missing `Access-Control-Allow-Origin`, and the FE sees a network error instead of a meaningful 413.
- **Eager `Content-Length` check (common case):** If the request has a `Content-Length` header and its value exceeds the configured limit, the filter SHALL reject the request immediately by writing a JSON response directly — `response.setStatus(413)`, `response.setContentType("application/json")`, and writing a serialized `ErrorView` (error code `PAYLOAD_TOO_LARGE`) via `response.getWriter()` — then returning without calling `filterChain.doFilter()`. **Note:** `HttpServletResponse.sendError(413)` MUST NOT be used because it triggers Tomcat's default error page mechanism and does not produce JSON. This avoids the exception chain entirely for the ~95% of requests that declare their size upfront. The filter SHALL inject `ObjectMapper` to serialize the `ErrorView`.
- **Lazy stream wrapping (fallback for chunked encoding):** For requests without `Content-Length` (chunked transfer encoding), the filter wraps `HttpServletRequest.getInputStream()` with a byte-counting `InputStream` decorator that throws `PayloadTooLargeException` lazily when the cumulative byte count exceeds the limit during deserialization. This ensures the exception is thrown within the Spring MVC controller context (during `@RequestBody` argument resolution), where `DefaultExceptionHandler` can catch it.
- **Exception wrapping chain (lazy path only):** When the byte-counting `InputStream` throws `PayloadTooLargeException`, Jackson catches the `IOException` and wraps it in `JsonProcessingException`, which Spring wraps in `HttpMessageNotReadableException`. Therefore, `DefaultExceptionHandler`'s existing `handleWrongJsonError` method SHALL be modified to check the cause chain for `PayloadTooLargeException` (via root cause inspection). If `PayloadTooLargeException` is found in the cause chain → HTTP 413 with `PAYLOAD_TOO_LARGE`. Otherwise, fall through to the existing handling (HTTP 400). **Signature change required:** The method's return type SHALL change from `ErrorView` to `ResponseEntity<ErrorView>` and the `@ResponseStatus(HttpStatus.BAD_REQUEST)` annotation SHALL be removed — when a `@ExceptionHandler` method returns `ResponseEntity`, the status comes from the entity, making `@ResponseStatus` inapplicable for conditional status codes. Requires adding `PAYLOAD_TOO_LARGE` to the `ErrorCode` enum.

**Global safety net:** Additionally, configure `server.tomcat.max-http-post-size: 10485760` (10MB) in `application.yml` as a Tomcat-level backstop for all endpoints.

**Rationale:** Path-scoped enforcement avoids impacting other endpoints. Byte-counting wrapper handles chunked TE (no Content-Length header). The filter runs before deserialization, protecting against OOM from huge payloads.

### D14: ClickHouse compatibility notes

**Note:** These are documentation-only notes for future implementation. No code changes in v1.

| Aspect | Postgres v1 | ClickHouse (future) |
|--------|-------------|---------------------|
| Uniqueness | UNIQUE constraint + `ON CONFLICT DO NOTHING` | `ReplacingMergeTree` (eventual dedup during background merges) |
| JSONB filtering | `column->>'key'` | `JSONExtractString(column, 'key')` |
| Case-insensitive search | `ILIKE '%' \|\| :param \|\| '%'` | `ilike(column, '%' \|\| :param \|\| '%')` or `positionCaseInsensitive()` |
| Tuple comparison | `(a, b) < (x, y)` | Supported (tuple comparison) |
| Transactions | Full ACID via `@Transactional` | Not supported — batch insert is implicitly atomic |
| Batch insert | `NamedParameterJdbcTemplate.batchUpdate()` | ClickHouse JDBC with batch insert |

**Design for portability:**
- Repository interface uses standard Java types (`List<FilterCondition>`, `String`, `Long`, `UUID`) — no Postgres-specific types.
- `WhereBuilder` is the only component with Postgres-specific SQL. A future `ClickHouseWhereBuilder` would generate ClickHouse-dialect SQL.
- Service and web layers are database-agnostic.

## Risks / Trade-offs

**[Mitigated] Analytics and meta DB interaction** → The batch write reads from meta (run existence + timestamp) and writes to analytics. The meta read occurs within the `@Transactional("analyticsTransactionManager")` method but uses the meta `DataSource` (separate JDBC connection with auto-commit), so it is functionally independent. No cross-datasource writes, no distributed transaction concerns. If the meta read succeeds but the analytics write fails, no side effects occur (the meta read was read-only). See D8.

**[Risk] Large JSONB payloads in analytics table** → `request_body` and `response_body` can be arbitrarily large. Mitigation: v1 accepts this trade-off. Future iteration can add payload size limits and offload to object storage. For now, Postgres TOAST handles large values transparently.

**[Risk] Keyset pagination unfamiliar to FE** → FE is used to OFFSET/LIMIT from existing endpoints. Mitigation: the cursor is opaque (Base64), and the API contract is simple (`cursor` + `hasMore`). Document clearly in OpenAPI. The first page needs no cursor — just `size`.

**[Mitigated] Two Flyway instances on same database** → Startup validation (D3) ensures meta and analytics are either in different databases or different schemas. Both use the default `flyway_schema_history` table name without conflict. Both are manually configured with symmetric approach.

**[Trade-off] No foreign keys in analytics DB** → `test_suite_run_id`, `test_suite_id`, `test_case_id` are soft references. Orphaned results possible if meta records are deleted after results are written. Accepted: OLAP databases don't support FK constraints. The batch write validates run existence (D8), preventing writes for non-existent runs. Orphans from later meta deletion are tolerated in v1; a scheduled cleanup job will handle them in a future iteration.

**[Mitigated] Unsupported analytics vendor at startup** → If `datasource.analytics.vendor` is set to a value without a repository implementation (e.g., `CLICKHOUSE`), `@ConditionalOnProperty` skips the Postgres repo and any service injecting the repository gets a cryptic `NoSuchBeanDefinitionException`. Mitigation: the `DatasourceValidationConfiguration` SHALL also validate that the configured vendor has an available repository implementation. If not, fail startup with a clear error message (e.g., "Analytics vendor 'CLICKHOUSE' is not yet supported. Supported vendors: POSTGRES").

**[Trade-off] JSONB path filtering performance** → Filtering on `test_case_data->>'key'` requires scanning the JSONB column. No GIN/BTREE index on JSONB paths in v1. Acceptable because queries always filter on `test_suite_id` (required), which is indexed. Future optimization: GIN index or materialized extracted columns for frequently-queried keys.

**[Action required] Analytics database health indicator** → The analytics datasource MUST have its own health indicator (`AnalyticsDatabaseHealthIndicator`) registered with the actuator health endpoint. Without it, the service reports healthy while the analytics DB could be unreachable, causing silent write failures.

## Migration Plan

No existing data to migrate. Steps:

1. Add `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier` to `lombok.config` at project root.
2. Rename datasource config: `datasource.vendor` → `datasource.meta.vendor`, `postgres.datasource.*` → `postgres.meta.datasource.*`. Add `postgres.*.datasource.schema` optional properties. Update all `@ConditionalOnProperty` annotations.
3. Create qualified transaction managers: `metaTransactionManager`, `analyticsTransactionManager`. Update all existing `@Transactional` annotations to specify `"metaTransactionManager"`.
4. Move existing meta migrations from `db/migration/POSTGRES/` to `db/migration/meta/POSTGRES/`.
5. Disable Spring Boot Flyway auto-config. Create manually configured meta Flyway bean with `defaultSchema` support.
6. Add analytics datasource configuration (properties, DataSource bean, JdbcTemplate bean, Flyway bean, transaction manager). Add startup validation that meta ≠ analytics (same DB + same schema rejected).
7. Create analytics Flyway migration: `V1.1__CreateTestCaseRunResultsTable.sql` (in `db/migration/analytics/POSTGRES/`).
8. Extend filter framework: add `FilterFieldType.JSONB_STRING`, extend `WhereBuilder` for JSONB path access (parameterized JSONB key, `parseValue` JSONB_STRING case), add `FilterWhitelists.ANALYTICS_RESULTS`.
9. Implement analytics data layer: model (with `runIndex`, JSONB fields as `String`), row mapper, repository interface (with `List<FilterCondition>`), Postgres implementation (with JDBC `batchUpdate`, `PostgresJsonbSqlParameter.fromJson()` for JSONB writes).
10. Implement `MaxRequestBodyFilter` for batch write size enforcement (lazy exception via stream wrapping).
11. Implement service layer: `AnalyticsResultService` (reads run from meta for existence validation and `created_at_ms` timestamp, writes to analytics), DTOs (`BatchWriteRequestDto` envelope, `TestCaseRunResultItemDto` items — no `errorPayload`, `ExecutionStatus` enum in DTOs, `durationMs` computed server-side), MapStruct mapper (merges envelope IDs into model).
12. Implement web layer: `AnalyticsResultController` with filter syntax, `@LogExecution`, `@Validated`. Batch write accepts `BatchWriteRequestDto` envelope, returns HTTP 201 with no body.
13. Update test infrastructure: remove `@ServiceConnection`, use `@DynamicPropertySource` with dual databases.
14. Add functional tests with dual Testcontainers datasource.
15. Update `docs/database-schema.md` and `docs/configuration.md`.

**Rollback:** Drop the analytics table, remove the analytics datasource config. No meta DB schema changes to revert.

## Resolved Questions

- **Testcontainers setup:** Same Postgres container, different database (or schema). `@DynamicPropertySource` replaces `@ServiceConnection`. Init script creates the analytics database. See D3 startup validation.
- **Batch size limits:** Yes — configurable max items + configurable max request body bytes via path-scoped `MaxRequestBodyFilter`. See D6, D13.
- **Orphan cleanup:** Leave orphans when runs are deleted from meta. Scheduled cleanup job in future iteration. No `deleteByRunId` in v1 repository. See D9.
- **Breaking config changes:** Acceptable — early stage, no production deployments. See D1.
- **Transaction management:** Qualified transaction managers for both datasources. All `@Transactional` annotations specify which manager. See D11.
- **Filter approach:** Reuse existing `filter=field:operator:value` framework. Extended with JSONB path filtering. See D12.
- **error_payload vs response_body:** Single `response_body` field serves both purposes. `executionStatus` indicates success/failure. See D7.
- **UNIQUE constraint:** `UNIQUE (test_suite_run_id, test_case_id, run_index, created_at_ms)` added. Each test case is evaluated exactly once per run index. Includes `created_at_ms` from day one to avoid future partitioning migration. Enables `ON CONFLICT DO NOTHING` for idempotent retries. All results for a run share the same `created_at_ms` (D8), so the extra column doesn't weaken uniqueness. ClickHouse path uses `ReplacingMergeTree`. See D4.
- **Batch insert strategy:** JDBC `batchUpdate()` with `reWriteBatchedInserts=true` and `ON CONFLICT DO NOTHING`. Not single giant INSERT string. Envelope DTO enforces uniformity by schema. See D6.
- **Required filter:** Changed from "runId or suiteId" to "`suiteId` always required". Ensures the composite index `(test_suite_id, ...)` is always usable. See D12.
- **Batch uniformity:** Enforced by envelope DTO schema (testSuiteId/testSuiteRunId at top level) — no runtime validation needed. See D6.
- **Timestamp source for results:** Uses the run's `created_at_ms` from meta DB instead of `TransactionTimestampContext`. All results for a run share the same `created_at_ms`. This simplifies transactions (no cross-DB writes), makes the UNIQUE constraint partition-safe, and provides exact partition pruning. The meta read also validates run existence and suite ID match. See D6, D8.
- **InvalidFilterException:** NOT converted at service layer. `DefaultExceptionHandler` SHALL be extended with an `InvalidFilterException` handler (currently only handles `FilterValidationException`). The new handler maps `InvalidFilterException` to HTTP 400 with `INVALID_FILTER` — same response shape. See D12.
- **Batch write response:** HTTP 201 with `{"totalItems": N}` echoing the input count. `insertedCount` (DB-level) omitted — with `reWriteBatchedInserts=true`, the PG JDBC driver returns `SUCCESS_NO_INFO` (-2) for batch entries, making accurate row counts unreliable. The `totalItems` confirms the server received the batch; idempotent retries are safe via `ON CONFLICT DO NOTHING`. See D6.
- **durationMs computed server-side:** `exec_duration_ms` is computed as `exec_completed_at_ms - exec_started_at_ms` in the mapper/service layer. The client does not send `durationMs` in the request DTO. See D7.
- **ExecutionStatus enum in DTOs:** `ExecutionInfoDto.status` uses the `ExecutionStatus` enum (not String) for type safety and automatic Jackson validation. See D7.
- **JSONB key parameterization:** The JSONB key in `WhereBuilder` is bound via a named parameter (`:jsonbKeyParam`), never interpolated into SQL, to prevent SQL injection. See D12.
