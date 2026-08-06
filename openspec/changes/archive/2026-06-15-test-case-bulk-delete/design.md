## Context

Test cases currently support single-item deletion (`DELETE .../test-cases/{id}`, HTTP 204) and filter-based bulk deletion (`DELETE .../test-cases?filter=…`, returns a count). Neither allows a client to target a precise set of test cases by ID and receive per-ID feedback. Callers performing dataset cleanup (e.g., removing a selected set of items after an import) must issue N round-trips and stitch together partial failure state client-side.

The existing `:bulk` endpoint for composite PATCH (`PATCH .../test-cases:bulk`) establishes the precedent of Google API-style colon-segments for complex bulk operations and a dedicated controller class. This design follows the same pattern.

No database schema changes are required — the operation targets the existing `test_cases` table.

## Goals / Non-Goals

**Goals:**
- Single endpoint accepting a JSON list of test case UUIDs for deletion.
- Partial-success semantics: IDs present in the dataset are deleted; absent IDs are reported without aborting the operation.
- Response conveys exactly which IDs were deleted and which were not found, preserving input ordering.
- Configurable upper cap on IDs-per-request (`test-case.bulk.max-delete-ids`).
- Duplicate UUID and null element validation before any DB access.
- No DB schema migration.

**Non-Goals:**
- Cascading to other entities beyond standard FK `ON DELETE CASCADE` already in the schema.
- Cross-dataset deletion (one request, multiple `datasetId`s).
- Asynchronous or background deletion.
- Modification of existing delete endpoints (single-delete or filter-bulk-delete).

## Decisions

### 1. Endpoint path: `DELETE /api/v1/datasets/{datasetId}/test-cases:bulk`

**Chosen**: Google API-style colon-segment, matching the existing `:bulk` PATCH path.

**Alternatives considered**:
- `DELETE /api/v1/datasets/{datasetId}/test-cases/bulk` — slash-separated sub-resource; breaks RESTful interpretation (implies "bulk" is a resource, and Spring MVC would capture it before `/{id}`).
- Overloading `DELETE /api/v1/datasets/{datasetId}/test-cases` with an optional JSON body — ambiguous; conflicts with the existing filter-based endpoint on the same path/method.
- `POST .../test-cases:batchDelete` — using POST to carry a body is common but non-idiomatic for deletion; DELETE with body is valid HTTP and matches the pattern already established for the `:bulk` endpoint.

### 2. Dedicated controller class (`TestCaseBulkDeleteController`)

**Chosen**: Separate controller, mirroring `TestCaseBulkPatchController`.

**Rationale**: The colon-segment path (`:bulk`) cannot be expressed as a method-level mapping inside `TestCaseController` because Spring concatenates the class-level prefix with a `/` separator. A dedicated class with the full path on the method mapping avoids this constraint cleanly. The HTTP verb (`DELETE`) already distinguishes this endpoint from the `PATCH .../test-cases:bulk` endpoint, making a `:bulkDelete` suffix redundant.

### 3. Partial success semantics

**Chosen**: Execute a single `DELETE … WHERE id IN (?) AND dataset_id = ? RETURNING id`. IDs found and deleted appear in `deleted`; IDs absent from the dataset appear in `notFound`. No rollback for absent IDs — the transaction always commits if the dataset exists and no system error occurs.

**Alternatives considered**:
- All-or-nothing (throw 404 on first not-found ID): simpler but rejects requests with any stale reference, forcing clients to validate membership before deleting.
- Two-phase (SELECT then DELETE): extra round-trip; no stronger guarantee than RETURNING (SELECT result can become stale before DELETE executes in the same tx anyway, since READ COMMITTED won't see other in-flight deletes).

**Why RETURNING beats SELECT+DELETE**: atomically identifies deleted rows in a single statement; no TOCTOU race; no extra network round-trip.

### 4. Response shape: `{ deleted: [UUID], notFound: [UUID] }`

**Chosen**: Two UUID lists, both preserving input ordering.

**Rationale**: Returning IDs (not just counts) lets callers correlate results without re-querying. Input ordering is preserved by filtering the input list against a `HashSet` of RETURNING results — O(n) and stable.

**Alternative**: Return counts only (`{ deletedCount: N, notFoundCount: M }`) — sufficient for fire-and-forget callers but not for callers that need to retry or surface per-item feedback.

### 5. Validation as a dedicated injectable component (`TestCaseBulkDeleteValidator`)

**Chosen**: Top-level `@Component @LogExecution` in `service.domain`, injected into `TestCaseService`.

**Rationale**: Follows AGENTS.md rule "Use specialized, injectable components for conversion/validation logic". Mirrors `TestCaseBulkPatchValidator`. Enables independent unit testing of validation without starting a Spring context.

Validation responsibilities:
- `ids` is not null and not empty → `ValidationException` (HTTP 400)
- `ids.size()` ≤ `testCaseProperties.getBulk().getMaxDeleteIds()` → `ValidationException` (HTTP 400)
- No null elements → `ValidationException` (HTTP 400)
- No duplicate UUIDs (detected via `HashSet`) → `ValidationException` (HTTP 400)

### 6. Max-IDs cap as a configurable property (`test-case.bulk.max-delete-ids`)

**Chosen**: New field `maxDeleteIds` on the existing `TestCaseProperties.Bulk` inner class, default 10 000 in `application.yml`.

**Rationale**: Consistent with existing per-cap fields (`maxIdsPerSelector`, `maxOperations`, `maxItemOperations`) already in `Bulk`. Placing a hardcoded constant in `RunnerValidationConstants` would be wrong per AGENTS.md — non-configurable constants go there; this cap is operator-tunable.

### 7. Repository method returns `List<UUID>` (RETURNING clause)

**Chosen**: `List<UUID> deleteByIdsAndDatasetId(UUID datasetId, List<UUID> ids)` using jOOQ `returningResult(TEST_CASES.ID).fetch()`.

**Rationale**: `RETURNING` is idiomatic PostgreSQL and already used for UPDATE in `PostgresDatasetRepository` and `PostgresTestSuiteRepository`. The returned strings are mapped to UUIDs in the repository; the service layer receives typed values, not raw strings.

## Risks / Trade-offs

- **Large IN-list performance**: PostgreSQL handles thousands of values in `IN (?)` efficiently via a hash join on `pg_attribute`; 10 000 UUIDs is within safe bounds for this table size. The configurable cap is the primary guard.
- **Partial success may surprise callers expecting all-or-nothing**: mitigated by clear API documentation and the `notFound` list in the response — callers can detect and react to partial outcomes.
- **No Flyway migration**: no DB changes; no risk of migration drift or regenerating jOOQ sources.
- **Thread safety of `HashSet` in service**: local variable, not shared state — no concurrency concern.
