## ADDED Requirements

### Requirement: Composite bulk partial update test cases (PATCH :bulk)

The service SHALL provide a composite bulk partial-update endpoint at `PATCH /api/v1/test-suites/{testSuiteId}/test-cases:bulk` that accepts a single JSON body combining homogeneous selector-scoped operations and heterogeneous per-item operations. The entire request SHALL execute within a single transaction (all-or-nothing atomicity). The endpoint SHALL be additive — the existing array-body `PATCH /api/v1/test-suites/{testSuiteId}/test-cases` endpoint SHALL remain available with unchanged semantics and its own `test-case.batch.max-items` cap.

The request body SHALL have the shape:

```json
{
  "bulkOperations": [
    { "selector": { "ids": ["<uuid>", ...] },          "patch": { "<field>": <value>, ... } },
    { "selector": { "filter": ["<filter-expr>", ...] }, "patch": { "<field>": <value>, ... } }
  ],
  "itemOperations": [
    { "id": "<uuid>", "patch": { "<field>": <value>, ... } }
  ]
}
```

`bulkOperations` and `itemOperations` are each optional but at least one non-empty array SHALL be present. `selector` SHALL contain exactly one of `ids` or `filter`. The `patch` object inside a `bulkOperations[i]` SHALL only contain fields in the code-defined bulk-patch whitelist (initially `{"enabled"}`). The `patch` object inside `itemOperations[i]` SHALL follow the same merge-patch semantics as single-row `PATCH /api/v1/test-suites/{testSuiteId}/test-cases/{id}`.

#### Scenario: Successful composite bulk patch with bulk and item operations
- **WHEN** client sends a well-formed request with at least one `bulkOperations` entry and at least one `itemOperations` entry
- **THEN** system SHALL apply all bulk operations first (in array order) via SQL `UPDATE`, then apply each item operation (in array order) via merge-patch on the already-bulk-updated state, commit everything in one transaction, and return HTTP 200 with a response body containing `bulkResults` and `itemResults`

#### Scenario: Bulk-only request is accepted
- **WHEN** client sends a request with a non-empty `bulkOperations` and an omitted or empty `itemOperations`
- **THEN** system SHALL apply only the bulk operations atomically and return HTTP 200

#### Scenario: Item-only request is accepted
- **WHEN** client sends a request with an omitted or empty `bulkOperations` and a non-empty `itemOperations`
- **THEN** system SHALL apply only the item operations atomically and return HTTP 200

#### Scenario: Empty body is rejected
- **WHEN** client sends a body with both `bulkOperations` and `itemOperations` absent or empty (including a missing body)
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Atomic rollback on any failure
- **WHEN** any validation, resolver, or DB error occurs during execution of any bulk or item operation
- **THEN** system SHALL roll back all changes from the request and respond with the corresponding error HTTP status (no partial apply)

### Requirement: Selector semantics for bulkOperations

Each `bulkOperations[i].selector` SHALL be either an `ids` selector (explicit UUID list) or a `filter` selector (list of filter expressions using the existing test-case filter whitelist). The set of test cases affected by a `bulkOperations[i]` SHALL be scoped to the URL's `{testSuiteId}`. A filter selector resolves to the set of test-case ids in that suite matching all filter expressions at selector-resolution time.

#### Scenario: IDs selector with all ids in the suite
- **WHEN** client provides `selector.ids` containing UUIDs that all belong to `{testSuiteId}`
- **THEN** system SHALL apply the shared `patch` to exactly those test cases via a single SQL UPDATE

#### Scenario: IDs selector with an id not in the suite
- **WHEN** client provides `selector.ids` containing one or more UUIDs that do not belong to `{testSuiteId}` (either nonexistent or belonging to a different suite)
- **THEN** system SHALL respond with HTTP 404 (NOT_FOUND) and roll back

#### Scenario: Filter selector with an empty filter list
- **WHEN** client provides `selector.filter` as an empty list `[]`
- **THEN** system SHALL treat the selector as matching every test case in the suite

#### Scenario: Filter selector with valid filter expressions
- **WHEN** client provides `selector.filter` with expressions referencing fields in the test-case filter whitelist
- **THEN** system SHALL apply the shared `patch` to every test case in the suite that matches all expressions

#### Scenario: Filter selector rejects unknown or non-whitelisted field
- **WHEN** client provides a `filter` expression referencing a field that is not in the test-case filter whitelist (or uses an operator not allowed for that field)
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR) and roll back. The data-layer `InvalidFilterException` raised by the underlying `WhereBuilder` SHALL be translated by the service layer into a `FilterValidationException` so the global exception handler maps it to HTTP 400; an unwrapped data-layer exception SHALL NOT reach the client.

#### Scenario: Selector must declare exactly one variant
- **WHEN** a `selector` contains both `ids` and `filter`, or neither
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

### Requirement: Execution order and conflict resolution

The service SHALL execute `bulkOperations` before `itemOperations` and SHALL preserve array order within each list. An `itemOperations[i].patch` SHALL be applied to the state that results from all preceding bulk and item operations in the same request; per-field last-writer-wins semantics apply across overlaps.

#### Scenario: Item operation overrides a field set by a prior bulk operation
- **WHEN** `bulkOperations` sets `enabled=false` on a set of rows that includes id `X`, and `itemOperations` contains `{ id: X, patch: { enabled: true } }`
- **THEN** the final persisted state for `X` SHALL have `enabled=true`

#### Scenario: Item operation patches a field untouched by the bulk operation
- **WHEN** `bulkOperations` sets `enabled=false` on rows including id `X`, and `itemOperations` contains `{ id: X, patch: { testCaseName: "A2" } }`
- **THEN** the final persisted state for `X` SHALL have `enabled=false` and `testCaseName="A2"`

#### Scenario: Two overlapping bulk operations
- **WHEN** `bulkOperations[0]` sets `enabled=false` on all rows and `bulkOperations[1]` sets `enabled=true` on ids `[X]`
- **THEN** the final persisted state for `X` SHALL have `enabled=true` and for every other row `enabled=false`

### Requirement: Field whitelist for bulkOperations

The service SHALL restrict the set of fields allowed inside `bulkOperations[i].patch` to a code-defined whitelist (initially `{"enabled"}`). The whitelist SHALL be the key set of a single canonical API-field → SQL-column map maintained in code; it is NOT a configuration property. Any request with a bulk patch referencing a field outside this whitelist SHALL be rejected with HTTP 400 (VALIDATION_ERROR). `itemOperations[i].patch` SHALL NOT be subject to this whitelist and SHALL follow the existing single-row PATCH field set.

#### Scenario: Bulk patch with whitelisted field
- **WHEN** `bulkOperations[i].patch` contains only keys in the code-defined bulk-patch whitelist
- **THEN** system SHALL accept the operation

#### Scenario: Bulk patch with non-whitelisted field
- **WHEN** `bulkOperations[i].patch` contains any key not in the code-defined bulk-patch whitelist
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR) naming the offending field, and roll back

#### Scenario: Item operation patches a field not in the bulk whitelist
- **WHEN** `itemOperations[i].patch` contains keys outside the bulk whitelist but valid for single-row PATCH (e.g., `data`, `requestTemplateOverride`, `inputBindingsOverride`, `testCaseName`)
- **THEN** system SHALL accept the item operation

### Requirement: Configurable limits for composite bulk patch

The service SHALL enforce the following configurable caps. Violations SHALL result in HTTP 400 (VALIDATION_ERROR) and a message identifying which cap was exceeded.

- `test-case.bulk.max-operations` (default `512`) — maximum combined count of `bulkOperations.length + itemOperations.length`. SHALL be configured to a value greater than or equal to `test-case.bulk.max-item-operations`; otherwise the item-operations cap would be unreachable.
- `test-case.bulk.max-ids-per-selector` (default `10000`) — maximum `selector.ids.length` for a single `bulkOperations[i]`; also an upper bound on the id-set materialised from a `filter` selector.
- `test-case.bulk.max-item-operations` (default `500`) — maximum `itemOperations.length`.
The bulk-patch field whitelist itself is NOT a configuration property — it is the key set of the code-defined API-field → SQL-column map (see "Field whitelist for bulkOperations" requirement above).

The new endpoint SHALL NOT apply `test-case.batch.max-items` — that property continues to govern only the array-body batch endpoint at `PATCH /api/v1/test-suites/{testSuiteId}/test-cases`.

#### Scenario: Combined op count exceeds max-operations
- **WHEN** `bulkOperations.length + itemOperations.length` exceeds `test-case.bulk.max-operations`
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Selector ids exceed max-ids-per-selector
- **WHEN** a `bulkOperations[i].selector.ids.length` exceeds `test-case.bulk.max-ids-per-selector`
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Filter selector materialises more than max-ids-per-selector
- **WHEN** a `filter`-based selector would match more test cases than `test-case.bulk.max-ids-per-selector`
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR) and roll back

#### Scenario: Item operations exceed max-item-operations
- **WHEN** `itemOperations.length` exceeds `test-case.bulk.max-item-operations`
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Defaults apply when properties are unset
- **WHEN** no `test-case.bulk.*` property is configured
- **THEN** system SHALL use the defaults listed above

#### Scenario: Legacy batch cap does not apply
- **WHEN** `itemOperations.length` is between `test-case.batch.max-items` (e.g., 256) and `test-case.bulk.max-item-operations` (e.g., 500)
- **THEN** system SHALL accept the request (the legacy `test-case.batch.max-items` cap SHALL NOT apply to `:bulk`)

### Requirement: Duplicate-id detection within a request

The service SHALL reject `itemOperations` arrays containing two or more entries with the same `id`. Duplicates inside a single `bulkOperations[i].selector.ids` SHALL also be rejected. Repeating an id across different operations (e.g., a row appears in a bulk selector AND in `itemOperations`) SHALL be allowed — last-writer-wins semantics apply.

#### Scenario: Duplicate id within itemOperations
- **WHEN** `itemOperations` contains two entries with the same `id`
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Duplicate id within a single selector's ids
- **WHEN** `bulkOperations[i].selector.ids` contains the same UUID twice
- **THEN** system SHALL respond with HTTP 400 (VALIDATION_ERROR)

#### Scenario: Same id referenced across a bulk selector and itemOperations
- **WHEN** an id appears in a `bulkOperations[i].selector.ids` AND in `itemOperations`
- **THEN** system SHALL accept the request and apply the item operation on top of the bulk state (last-writer-wins)

#### Scenario: Same id referenced across two bulkOperations entries
- **WHEN** an id appears in `bulkOperations[0].selector.ids` AND in `bulkOperations[1].selector.ids` (or is materialised by a filter selector in either op)
- **THEN** system SHALL accept the request and apply both updates in array order (last-writer-wins per field)

### Requirement: Response shape for composite bulk patch

The service SHALL return a compact response body containing counts per operation, not full entity rows. The response SHALL preserve the input order of operations.

Response shape:

```json
{
  "bulkResults": [ { "opIndex": 0, "matched": <int>, "updated": <int> }, ... ],
  "itemResults": [ { "id": "<uuid>", "updated": true | false }, ... ]
}
```

`matched` is the number of test cases the selector resolved to; `updated` is the number of rows whose state actually changed (may be less than `matched` when the patched value already equals the existing value). The `updated` count SHALL exclude rows whose every whitelisted patched column already equals the requested value (NULL-safe comparison, i.e., `NULL` and `NULL` count as "equal" and a single non-NULL value differing from `NULL` counts as "changed"). For an item operation, `updated` is `true` if the merge patch changed at least one column.

#### Scenario: Bulk op counts reflect the selector
- **WHEN** a bulk op's selector resolves to N test cases and the patch differs from current state for all N
- **THEN** `bulkResults[i]` SHALL report `matched=N, updated=N`

#### Scenario: Bulk op no-op for already-matching state
- **WHEN** a bulk op's selector resolves to N test cases and the patch equals current state for K of them
- **THEN** `bulkResults[i]` SHALL report `matched=N, updated=N-K`

#### Scenario: Item op no-op when patch equals current state
- **WHEN** an item op's patch values equal the test case's current state
- **THEN** `itemResults[i].updated` SHALL be `false`

### Requirement: Validation scope for composite bulk patch

The service SHALL re-run per-row test-case validation (recomputing `valid` and `validation_warnings`) only for rows whose applied patch touches a validation-relevant field (`data`, `requestTemplateOverride`, `inputBindingsOverride`, `testCaseName`). Rows whose only change is to a validation-irrelevant field (e.g., `enabled`) SHALL NOT be re-validated. Re-validation cost thus scales with the number of rows actually receiving a relevant field change, not with the selector size of any `enabled`-only bulk op.

#### Scenario: Bulk enabled toggle skips per-row re-validation
- **WHEN** the only changes are bulk `enabled` flips on N rows
- **THEN** system SHALL NOT re-run validation on those N rows and their `valid` / `validation_warnings` values SHALL remain unchanged

#### Scenario: Item op on data triggers per-row re-validation
- **WHEN** an `itemOperations[i].patch` modifies `data`
- **THEN** system SHALL re-run validation for that row and persist the updated `valid` / `validation_warnings`

### Requirement: Name uniqueness when composite bulk patch affects testCaseName

When the composite request touches `testCaseName` on one or more rows (only possible via `itemOperations` under the default whitelist, or via `bulkOperations` if the whitelist is extended), the service SHALL validate name uniqueness against the final state, applying the same case-insensitive rules and HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) semantics already in effect for the existing batch PATCH endpoint.

#### Scenario: Item ops produce duplicate names within the request
- **WHEN** two `itemOperations` result in the same `testCaseName` (case-insensitive)
- **THEN** system SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) and roll back

#### Scenario: Item op collides with existing name outside the request
- **WHEN** an `itemOperations[i]` sets `testCaseName` to a value already used by a test case in the same suite that is not part of the request
- **THEN** system SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) and roll back

### Requirement: Filter selector resolution semantics

A `filter` selector SHALL be resolved to a concrete set of test-case ids inside the same transaction that performs the UPDATE. Rows inserted into the suite between the selector-resolution query and the UPDATE that were not part of the resolved id set SHALL NOT be affected by the bulk operation. This matches the behaviour documented for other filter-based bulk endpoints and SHALL be documented in the OpenAPI description.

#### Scenario: Rows inserted concurrently are not matched
- **WHEN** rows are inserted into the suite after the filter-selector resolution but before transaction commit
- **THEN** those rows SHALL NOT be affected by the bulk operation

#### Scenario: Rows matching at resolution time are updated even if the filter stops matching after a prior op
- **WHEN** a `bulkOperations[0]` changes a field used by `bulkOperations[1].selector.filter`
- **THEN** `bulkOperations[1]` SHALL be resolved against the post-`bulkOperations[0]` state, i.e., filter selectors are resolved at the moment each op executes, not up-front for the whole request
