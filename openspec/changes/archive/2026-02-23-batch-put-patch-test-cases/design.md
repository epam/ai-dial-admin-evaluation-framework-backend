## Context

The FE test cases grid allows editing multiple rows at once. Currently each edit requires an individual PUT or PATCH call, resulting in N round-trips. The single-item PUT and PATCH flows already handle validation, uniqueness enforcement, and merge-patch semantics — the batch operations wrap these in a single transactional boundary.

Existing batch patterns in the codebase:
- **CSV import**: batch create with collision strategies (`insertOrSkip`/`insertOrOverride`), processes rows in a loop within one transaction
- **Analytics batch write**: `BatchWriteRequestDto` with configurable `maxItems`, validates batch size, persists all items
- **Bulk delete**: `DELETE /test-cases` with optional filter, returns count

The test case domain uses the **meta datasource** (`metaTransactionManager`, `metaJdbcTemplate`).

## Goals / Non-Goals

**Goals:**
- Provide atomic batch PUT and PATCH for test cases within a single test suite
- Reuse existing single-item update/patch logic (validation, uniqueness, merge-patch)
- Configurable batch size limit via application properties
- Consistent API conventions (`includeWarnings`, error format, response DTOs)

**Non-Goals:**
- Batch create (upsert) — only existing test cases can be updated
- Cross-suite batch operations — all items must belong to the same suite
- Partial success / mixed-status responses — all-or-nothing only
- Batch operations on other entities (test suites, metrics, etc.)

## Decisions

### Decision 1: Endpoint placement — collection resource, not `/batch` sub-path

**Choice**: `PUT /test-suites/{id}/test-cases` and `PATCH /test-suites/{id}/test-cases`

**Rationale**: The collection resource already hosts bulk DELETE. Using the same path for batch PUT/PATCH is consistent. Different HTTP methods prevent ambiguity. A `/batch` sub-path adds URL complexity without benefit.

**Alternative considered**: `/test-cases/batch` — rejected for inconsistency with existing bulk DELETE pattern.

### Decision 2: All-or-nothing atomicity

**Choice**: Single `@Transactional("metaTransactionManager")` wrapping the entire batch. Any error (not found, unique constraint, validation limit exceeded) rolls back everything.

**Rationale**: For the FE "save grid" use case, partial success creates confusing state — some rows saved, some not. All-or-nothing is simpler to reason about for both FE and BE. Validation warnings (soft validation) do NOT cause rollback — items are saved with `isValid=false`.

**Alternative considered**: Per-item status with partial success — rejected as over-engineering for the grid use case and adds response complexity.

### Decision 3: Batch PATCH uses `List<Map<String, Object>>` with mandatory `id`

**Choice**: Each batch PATCH item is a raw `Map<String, Object>`. The `id` field is extracted and validated; the remaining map is processed as an RFC 7396 merge patch (same as single PATCH).

**Rationale**: RFC 7396 merge patch requires distinguishing "field absent" (no change) from "field: null" (delete/clear). A typed DTO cannot express this distinction for nullable fields. The single PATCH already uses `Map<String, Object>` — this is consistent.

**Alternative considered**: Typed `TestCaseBatchPatchItemDto` with nullable fields — rejected because it cannot distinguish absent vs null.

### Decision 4: Batch PUT uses a typed DTO

**Choice**: `TestCaseBatchPutItemDto` — extends `TestCaseRequestDto` fields plus a required `@NotNull UUID id`. Uses standard Jakarta validation.

**Rationale**: PUT replaces all mutable fields — there's no absent-vs-null ambiguity. A typed DTO gives compile-time safety and Jakarta validation for free.

### Decision 5: Configuration via `TestCaseProperties`

**Choice**: New `TestCaseProperties` class under `configuration.properties.testcase` with a nested `Batch` class containing `maxItems` (default 256). Configured via `test-case.batch.max-items` in application.yml.

**Rationale**: Follows the existing `AnalyticsResultsProperties.Batch` pattern. Configurable per environment. Default 256 covers typical FE grid scenarios with headroom.

### Decision 6: Suite fetched once, validation per item

**Choice**: Fetch the test suite once at the start of the batch operation. For each item, fetch the existing test case, apply changes, validate against the suite's schema/template/bindings, and update in DB.

**Rationale**: The suite is the same for all items in the batch (scoped by `testSuiteId` in the URL). Fetching it once is an obvious optimization. Per-item validation is necessary because each test case may have different overrides.

### Decision 7: Batch fetch + JDBC batch update

**Choice**: Fetch all batch items in a single query (`SELECT ... WHERE id IN (:ids) AND test_suite_id = :suiteId`). Persist all modified items via a new `batchUpdate(List<TestCase>)` repository method that uses `NamedParameterJdbcTemplate.batchUpdate(sql, SqlParameterSource[])` — JDBC batching sends all UPDATE statements in a single network round-trip.

**New repository methods**:
- `findAllByIdsAndTestSuiteId(Collection<UUID> ids, UUID testSuiteId)` — batch fetch
- `batchUpdate(List<TestCase>)` — JDBC batch update, reuses the existing `UPDATE_SQL`

**Rationale**: Batch fetch (1 SELECT instead of N) and JDBC batch update (1 round-trip instead of N) significantly reduce DB communication overhead. The existing `UPDATE_SQL` is reused — same columns, same WHERE clause — just batched via `SqlParameterSource[]`. The `batchUpdate` method is generic and reusable by other features that need to update multiple test cases (e.g., future bulk operations).

**Trade-off**: JDBC batch still executes individual UPDATE statements on the DB side, so PostgreSQL checks the unique constraint per-statement. Name swaps (A→B, B→A) will hit a transient constraint violation. This is acceptable — name swaps are an extremely rare edge case and will produce a clear 409 error. Supporting name swaps would require either deferrable constraints (schema migration) or a two-pass temp-name approach, both adding significant complexity for negligible benefit.

### Decision 8: Final-state uniqueness validation in application code

**Choice**: Before persisting, compute the final name for every item in the batch (new name if provided, current name if not). Check for duplicates within this final-name set. Also check final names against existing DB names excluding batch item IDs.

**Rationale**: For PUT, all items have names — straightforward duplicate check. For PATCH, items may or may not include `testCaseName`. We must consider the current (unchanged) name for items that don't patch their name. This prevents the case where item A renames to "bar" while item B (currently "bar") only patches `data` — the final state would have duplicate "bar".

## Risks / Trade-offs

- **[Risk] Name uniqueness across batch items** → Two items in the same batch could rename to the same name. Mitigation: validate for duplicate names within the batch before persisting (in addition to DB unique constraint). Return 409 with details.
- **[Risk] Large batch performance** → 256 items each triggering validation + DB update. Mitigation: configurable limit; suite fetched once; validation is in-memory after initial fetch. Expected latency: <1s for typical batches.
- **[Risk] Optimistic locking not applied** → Single test cases don't use versioning (unlike test suites with ETag). The batch operation could overwrite concurrent changes. Mitigation: this is the same behavior as single PUT/PATCH today — no regression. If needed, test-case-level versioning can be added later.
