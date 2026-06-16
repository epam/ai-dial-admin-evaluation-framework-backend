## Context

Today the only scalable path to update many test cases in one atomic request is `PATCH /api/v1/test-suites/{suiteId}/test-cases`, which takes a JSON array of `{id, ...patch}` items. It is capped at `test-case.batch.max-items` (default 256) and does one merge-patch + row UPDATE per item.

A typical UI Save for a large suite — "Deselect all test cases, select 1–2" ([admin-frontend#3021](https://github.com/epam/ai-dial-admin-frontend/issues/3021)) — produces a diff that is proportional to the number of rows with an `enabled` change. For a 801-row suite that means ~799 items in the array, which trips the cap. For a 10k-row suite (our upper-bound design target) the same workflow can produce ~10k items, which would mean ~5–10 MB of JSON, ~10k row UPDATEs, and a long-running transaction — even if we raised the cap.

What makes this workload special: **the diff is homogeneous.** 799 rows are all receiving the same `{enabled:false}` patch. A batch-PATCH API designed for per-item heterogeneous patches is structurally the wrong shape for it.

Existing surfaces informing the design:

- `DELETE /api/v1/test-suites/{id}/test-cases?filter=...` already implements a filter-based bulk mutation over the same table. It resolves a filter into a SQL `DELETE ... WHERE` in one statement. The new endpoint mirrors that mental model (selector + action) for UPDATE.
- `FilterWhitelists` + `FilterParser` + `WhereBuilder` already whitelist test-case filter fields and build parameterized WHERE clauses safely. The `filter`-selector variant plugs into this directly.
- `TransactionTimestampContext` is already the project's convention for a consistent `updated_at_ms` per transaction — the composite endpoint reuses it verbatim.
- The existing `PATCH .../test-cases` (batch PATCH) with its 256 cap stays in place for small heterogeneous edits and is not broken by this work.

## Goals / Non-Goals

**Goals:**

- Handle a single atomic Save that flips `enabled` on up to 10k rows with a **constant-size** request payload.
- Support a mixed Save: many homogeneous `enabled` toggles AND a handful of heterogeneous per-row edits (rename, change `data`, etc.), all in one transaction.
- Keep the per-row heterogeneous path (the existing batch PATCH) unchanged so current clients are unaffected.
- Make the new endpoint extensible to additional homogeneous-patch fields without API rework (whitelist config).
- Stay consistent with project conventions: JDBC-only, NamedParameterJdbcTemplate, `TransactionTimestampContext`, injectable service.domain components, OpenAPI examples, docs/configuration.md updates.

**Non-Goals:**

- Async/job-backed bulk updates. Upper bound is 10k; a synchronous single transaction remains tractable for the initial whitelist (`enabled`-only). Revisit if the whitelist grows to heavy fields or the suite cap grows beyond 10k.
- Changing the data model for `enabled` (e.g., "store disabled ids on the suite"). The column-per-row shape is kept.
- Returning full `TestCaseResponseDto` rows in the response. We return counts only by design.
- Rewriting the existing `PATCH /test-cases` (array) endpoint or changing its 256 cap. Not touching it is a feature.
- Changing the FE. Coordination with the FE to use the new shape is a separate effort.

## Decisions

### 1. New dedicated endpoint `PATCH /test-cases:bulk` (not an overload of the existing endpoint)

Matrix-style comparison versus the two alternatives we considered:

| Option | Endpoint shape | Back-compat | Clarity of semantics |
|---|---|---|---|
| A. Overload existing `PATCH /test-cases` to accept both array body and composite body | Same URL, dispatch by body shape | Breaks easily; middleware/clients may reject | Mixed semantics, hard to document |
| B. Raise `test-case.batch.max-items` to 10000 | Same URL, same shape | Works | Does not fix the underlying payload/duration problem |
| **C. New endpoint `PATCH /test-cases:bulk`** | Distinct URL, composite body | Pure addition | Clear contract; existing endpoint untouched |

Chosen **C**. Cost is one controller method + one service method; we gain a clean separation between "heterogeneous small-N batch" and "scalable composite bulk," and OpenAPI documents the two contracts independently.

The `:bulk` suffix uses the colon-segment convention already present in the codebase's mental model for "action on a collection resource" (Google API-style resource verbs). It disambiguates from `/{id}` path parameters.

### 2. Composite body: `bulkOperations` + `itemOperations` in one request

Why both in one body rather than two endpoints:

- **Atomicity.** The user's requirement ("update operation should be atomic") is the dominant constraint. Splitting bulk and item into two HTTP calls cannot be atomic at the BE without introducing a cross-request transaction mechanism.
- **One roundtrip.** A typical Save has at most a handful of bulk ops and a handful of item ops; one call is natural.
- **Symmetric with real UI intent.** "Deselect all + rename row A" is one user action.

### 3. Selector is `{ids}` XOR `{filter}`

- `ids` covers the most common case (UI already has the UUIDs in local state).
- `filter` covers "deselect all" efficiently — client sends `selector: { filter: [] }` which the resolver expands to "all test cases in suite".
- Filter expressions reuse `FilterWhitelists` for the test-case entity — safe, allowlisted, consistent with existing filter-capable endpoints.
- Rejecting requests with both `ids` and `filter`, or neither, keeps the contract unambiguous.

### 4. Execution order: bulk first, then item; last-writer-wins across overlaps

```
┌──────────────────────────────────────────────────────┐
│  @Transactional("metaTransactionManager")            │
│  ├─ TransactionTimestampContext.get() → tsMs         │
│  ├─ Validator: field whitelists, caps, duplicate ids │
│  ├─ For each op in bulkOperations (array order):     │
│  │    ids = BulkSelectorResolver.resolve(selector)   │
│  │    repo.updateFieldsByIds(suiteId, ids, patch,    │
│  │                           tsMs)                   │
│  │    record BulkResult {opIndex, matched, updated}  │
│  ├─ For each op in itemOperations (array order):     │
│  │    existing = repo.findByIdAndTestSuiteId(id)     │
│  │    applyMergePatch(existing, patch) (reused)      │
│  │    revalidate only if validation-relevant field   │
│  │    repo.update(existing)                          │
│  │    record ItemResult {id, updated}                │
│  ├─ Name-uniqueness check (only if any op touched    │
│  │  testCaseName in its final state)                 │
│  └─ Commit                                           │
└──────────────────────────────────────────────────────┘
```

- Bulk-before-item matches the "broad strokes, then refinements" mental model from the UI and makes overlap resolution deterministic by reading.
- Filter selectors are resolved **at the moment the op executes**, not up-front for the whole request, so `bulkOperations[1]` sees `bulkOperations[0]`'s effect. This matches intuition: "set enabled=false for everything, then set enabled=true where status=critical" works naturally.

### 5. Field whitelist for `bulkOperations[*].patch`, code-defined, initially `{"enabled"}`

- Prevents accidental bulk-update of heavy/complex fields (`data`, `requestTemplateOverride`, `inputBindingsOverride`) that require per-row validation work and can't be expressed in a simple `SET col=:val` UPDATE.
- Keeps the v1 of the endpoint narrowly scoped and safe to ship.
- The whitelist is **not** a configuration property. Extending it requires (a) adding the API-field → SQL-column entry, (b) considering validation/uniqueness impact, (c) extending tests. All of that is a code change. A config-driven whitelist would only meaningfully support "narrow below what code supports" (since widening past code is unsafe and would be rejected); that use case has no current demand and is better served by gateway/policy layers. So we keep one source of truth: the API-field → SQL-column map in code, whose key set IS the whitelist.
- `itemOperations[*].patch` has no such restriction — it's the existing per-row merge-patch path, full semantics preserved.

### 6. Return counts, not rows

- For a 10k `enabled=false` flip, returning 10k full DTOs defeats the payload-decoupling win entirely.
- The FE already has per-row state locally (that's how it built the diff) — it does not need rows echoed back to refresh its view. If a client later needs rows, a future `?return=rows` query param can be added as an explicit opt-in.
- Counts (`matched`, `updated`) are sufficient for the FE to show "X items updated" and to detect unintended matches.

### 7. Validation scope reduced by "patched-field relevance"

The validation set is `{data, requestTemplateOverride, inputBindingsOverride, testCaseName}` (the fields the single-row PATCH already triggers re-validation on). `enabled` is not in it — flipping `enabled` never changes validity. So:

- `enabled`-only bulk op → 0 re-validations regardless of selector size. This is the load-bearing optimization: it makes 10k-row toggles cheap.
- Item op touching `data` → re-validate just that row, same as today.

When the whitelist is extended in the future, the validator logic must be revisited (and may need a "re-validate matched rows for this bulk op" fast path).

### 8. Repository uses `unnest(:ids::uuid[])` for id-scoped UPDATEs, with `IS DISTINCT FROM` to make `updated` reflect real changes

- Binding a list of 10k as a PostgreSQL array via `NamedParameterJdbcTemplate` with `unnest` is the idiomatic, safe, performant way. Raw IN lists with 10k elements work but are slower to plan.
- Filter-selector UPDATEs build a parameterized `WHERE` from `FilterWhitelists` / `WhereBuilder` — same pipeline already used for the existing bulk-delete filter endpoint.
- The UPDATE statement appends `AND (<col1> IS DISTINCT FROM :v1 OR ...)` per whitelisted field. This is load-bearing for the spec's `bulkResults[i].updated` semantics: PostgreSQL's `UPDATE ... SET col = :v` rewrites the row even when the new value already equals the old one, so the JDBC affected-row count would otherwise always be `matched`. The `IS DISTINCT FROM` filter narrows the UPDATE to rows that actually change, so `updated = N - K` (where K is the no-op subset) holds without a separate read-then-update round trip. NULL-safety is provided by `IS DISTINCT FROM` (treats `NULL` as a comparable value), unlike `<>`.

### 8a. Single canonical API-field → column mapping (sole source of truth)

A single canonical map `BULK_PATCH_FIELD_TO_COLUMN` lives in `data.db.repository.sql.BulkPatchFields` (e.g., `Map.of("enabled", "is_enabled")`). Its key set IS the bulk-patch whitelist — `TestCaseBulkPatchValidator` reads `BulkPatchFields.allowedFields()` directly, and `PostgresTestCaseRepository` reads `BulkPatchFields.columnFor(...)` to translate API fields to SQL columns. The constants holder is in the data layer because `LayeredArchitectureTest` permits service → data but forbids data → service; placing it in service would force the repository (data layer) to import service code, which ArchUnit rejects. There is no parallel config property to keep in sync, so drift is structurally impossible. Adding a future bulk-patchable field is one localised code change (add a map entry, then update the validator's per-field validation logic if the field is validation-relevant).

### 9. New `TestCaseBulkSelectorResolver` and `TestCaseBulkPatchValidator` as injectable `service.domain` components

- Keeps parsing/validation out of the controller and the data layer (project layering rule).
- Both are independently unit-testable.
- `TestCaseBulkSelectorResolver` depends on `TestCaseRepository` (for filter→ids resolution), `FilterParser`, `FilterWhitelists`, and the config properties for id-cap.
- `TestCaseBulkPatchValidator` depends on the config properties (for caps) and on the canonical `BULK_PATCH_FIELD_TO_COLUMN` key set (for the whitelist); it validates request structure and whitelist compliance before any DB work. No startup-time consistency check is required because the whitelist and the column map share a single source.
- **Exception translation**: `TestCaseBulkSelectorResolver` MUST catch `InvalidFilterException` (raised by `WhereBuilder` from inside `findIdsByTestSuiteIdAndFilter` when the filter references a non-whitelisted field or operator) and rewrap as `FilterValidationException`. Reason: ArchUnit forbids the web layer from importing `data.db`, and `DefaultExceptionHandler` only registers a `@ExceptionHandler` for `FilterValidationException` — an unwrapped `InvalidFilterException` would surface as an unmapped 500. The existing services (`TestSuiteService`, `TestSuiteMetricDefinitionService`) follow the same wrap pattern; the resolver mirrors it.

### 10. Configuration lives under `test-case.bulk.*` (new subtree; not reusing `test-case.batch.*`)

- The existing `test-case.batch.max-items` describes a behavior that still exists (the array-body batch PATCH). Reusing it would mean one property with two meanings.
- `bulk` vs `batch` also reads naturally: "batch" = heterogeneous N-item array, "bulk" = selector-scoped homogeneous + optional heterogeneous tail.

## Component interaction

```
HTTP PATCH /api/v1/test-suites/{suiteId}/test-cases:bulk
        │
        ▼
TestCaseController.bulkPatch(...)               [web layer]
        │    body → TestCaseBulkPatchRequestDto (+ @Valid)
        ▼
TestCaseService.bulkPatch(...)                  [service layer, @Transactional]
        │
        ├── TestCaseBulkPatchValidator.validate(request)
        │      - max-operations, max-item-operations, max-ids-per-selector
        │      - bulk-patch field whitelist = BULK_PATCH_FIELD_TO_COLUMN.keySet() (code)
        │      - selector XOR (ids|filter)
        │      - duplicate ids within itemOperations / within one selector.ids
        │
        ├── for each bulkOperations[i]:
        │      ids = TestCaseBulkSelectorResolver.resolve(suiteId, selector)
        │              ids-selector  → pass-through + suite membership check
        │              filter-sel.   → FilterParser.parse + testCaseRepository
        │                              .findIdsByTestSuiteIdAndFilter(suiteId, filters, cap+1)
        │                              → throws if > cap
        │      testCaseRepository.updateFieldsByIds(suiteId, ids, patch, tsMs)
        │
        ├── for each itemOperations[i]:
        │      existing = testCaseRepository.findByIdAndTestSuiteId(id, suiteId)
        │      applyMergePatch(existing, patch)    [reused from TestCaseService]
        │      if any validation-relevant field changed: runValidation(existing, ctx)
        │      testCaseRepository.update(existing)
        │
        ├── if any op touched testCaseName:
        │      validateBatchNameUniqueness(finalStates, suiteId)  [reused]
        │
        └── return TestCaseBulkPatchResponseDto(bulkResults, itemResults)
        ▼
HTTP 200 application/json
```

## Data model / schema

No schema changes. No Flyway migration. The endpoint operates on existing `test_cases` columns using existing indexes.

## API contract (summary)

- **URL:** `PATCH /api/v1/test-suites/{testSuiteId}/test-cases:bulk`
- **Request:** see spec for shape and limits.
- **Responses:**
  - `200 OK` — `TestCaseBulkPatchResponseDto`
  - `400 VALIDATION_ERROR` — empty body, cap exceeded, whitelist violation, malformed selector, duplicate ids, unknown filter field
  - `404 NOT_FOUND` — suite not found, or id in a selector/item not in suite
  - `409 UNIQUE_CONSTRAINT_VIOLATION` — final-state name collisions (only reachable when `testCaseName` is patched)

## Transaction boundary

- Single `@Transactional("metaTransactionManager")` method on `TestCaseService`.
- Uses `TransactionTimestampContext.getTimestamp()` for consistent `updated_at_ms` across every UPDATE in the request (both bulk and item paths).
- On any exception: full rollback, handler maps to the appropriate HTTP error.

## Error handling

- Validation errors throw `ValidationException` (→ 400) from `TestCaseBulkPatchValidator` or `TestCaseBulkSelectorResolver`.
- Id selector referencing non-existent or cross-suite UUIDs → `EntityNotFoundException` (→ 404). Detected by comparing returned id count from a membership-check query against requested id count.
- Name collisions → `UniqueConstraintViolationException` (→ 409), reusing `UniqueConstraintViolationDetector` where the DB unique index surfaces.
- Filter-selector over-match → `ValidationException` ("selector matched N items, exceeds cap of M"). Detected by selecting `LIMIT cap+1` and checking overflow.

## Risks / Trade-offs

- **Row-lock duration under a 10k-row `UPDATE`.** A single `UPDATE test_cases SET is_enabled=:v WHERE id = ANY(:ids)` on 10k rows takes hundreds of ms and holds row locks for that duration. Mitigation: keep the initial whitelist to `enabled` (one narrow column and one index, `is_enabled`) — quick update, small write set. If the whitelist is extended to heavier fields later, revisit: possibly split huge id-sets into chunks within the same transaction, or move to async.
- **Filter-selector races.** Rows inserted concurrently between selector resolution and UPDATE aren't affected. Documented in the spec as expected behaviour; matches `DELETE ... ?filter=...`.
- **Response shape divergence from existing batch PATCH.** The existing endpoint returns full rows; this one returns counts. Trade-off: worth it for payload predictability. Documented in OpenAPI for both endpoints.
- **Frontend coordination.** The BE change alone doesn't fix the reported FE bug; the FE must migrate. Mitigation: ship BE first, FE integrates when ready; until then the existing batch PATCH + a (possibly temporary) raised `test-case.batch.max-items` can be used as a short-term palliative. Raising the batch limit is **not** part of this change.
- **Whitelist extension.** Adding a field to `BULK_PATCH_FIELD_TO_COLUMN` requires thinking about validation impact (e.g., adding `testCaseName` forces name-uniqueness over the full bulk-resolved id set — non-trivial at 10k). Mitigation: spec names the current whitelist explicitly; additions require a new change with its own review. Drift between "allowed" and "translatable" is structurally impossible because the map is the single source of truth.
- **TOAST / JSONB concerns.** None — `enabled` is a BOOLEAN column, not JSONB. This endpoint does not read or rewrite `data` / `input_bindings_override` / `request_template_override` for bulk ops; item ops use the existing per-row path that already handles JSONB correctly.
- **OpenAPI complexity.** Two sibling request DTOs with a polymorphic `selector` increase OpenAPI surface. Mitigation: provide two example files — `bulk-enable-disable.json`, `bulk-plus-heterogeneous.json` — documenting the two common shapes.

## Migration plan

- No data migration.
- Configuration: new `test-case.bulk.*` subtree added to `application.yml` with documented defaults. Operators can override via standard property/env-var binding. `docs/configuration.md` updated in the same change.
- Backwards compat: existing batch PATCH and all single-row endpoints remain unchanged. Clients not aware of `:bulk` continue to work.
- Rollout: deploy BE; FE integrates when ready. No feature flag required — the endpoint is purely additive.

## Open Questions

- **Should we accept `selector: { ids: [] }` as a no-op, or reject?** Lean: reject (HTTP 400) as a programming error, for the same reason we reject empty body.
- **Should filter selectors support the same operators as the list endpoint, or a reduced set?** Lean: full set, since we already allowlist fields and operators in `FilterWhitelists`. Safe to reuse.
- **Do we want a dry-run / preview mode (`?dryRun=true`)?** Could return the matched/updated counts without persisting. Nice-to-have; not required for the reported issue. Defer unless FE asks for it.
- **If the whitelist is extended to `testCaseName` in a future change, do we want to enforce a `<testCaseName>{pattern}` rule (e.g., `bulk-rename ids → pattern "Renamed_{id}"`)?** Out of scope now; flagged for when the whitelist grows.
