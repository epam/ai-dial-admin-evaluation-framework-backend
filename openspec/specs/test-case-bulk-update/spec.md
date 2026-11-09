# Test-Case Bulk Update

## Purpose

Array-body PUT/PATCH and composite selector/item PATCH, with their distinct limits, validation, uniqueness and response contracts. Single-item merge rules belong to [test-cases](../test-cases/spec.md), content checks to [test-case-validation](../test-case-validation/spec.md). [Bulk deletion by IDs](../test-case-bulk-delete-by-ids/spec.md) is a separate partial-success contract.

Status: **Implemented**

## Requirements

### Requirement: Array-body batch updates

Status: **Implemented**

The service SHALL support JSON-array PUT and PATCH at `/api/v1/datasets/{datasetId}/test-cases`, atomically in one transaction. All IDs SHALL exist in that dataset; neither operation creates cases.

| Operation | Item contract | Value handling |
|---|---|---|
| PUT | Existing `id` and required `testCaseName`; optional `data`/`multiTurnData` | Full replacement; omitted/null `data` becomes `{}` |
| PATCH | Required `id` plus any subset of `testCaseName`, `data`, `multiTurnData` | [Single-case PATCH rules](../test-cases/spec.md#requirement-single-case-patch-contract), including ignored values and name conversion |

Every item SHALL run [validation and NUMBER normalization](../test-case-validation/spec.md); `includeWarnings=true` returns structured warnings per item. Empty arrays, duplicate IDs, invalid/missing PATCH IDs and cap violations SHALL return HTTP 400. Unknown datasets/cases SHALL return HTTP 404 and leave the whole batch unchanged. Case-insensitive final-state name collisions SHALL return HTTP 409; permutations follow "Batch and item name permutations".

`test-case.batch.max-items` SHALL cap both array-body operations (default 256). It SHALL NOT cap composite `:bulk` requests.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Successful batch update | `PUT /api/v1/datasets/{datasetId}/test-cases` with a valid array of items, each containing an `id` of an existing test case in the dataset | SHALL update all items, recalculate `valid` for each against the dataset's schema, and return HTTP 200 with an ordered list of `TestCaseResponseDto` matching the input order |
| Batch update recalculates validation per item | batch PUT and some items have data that fails schema validation | SHALL save all items (including invalid ones with `valid=false` and `validationWarnings`), and return the full list; validation warnings do NOT cause rollback |
| Batch update with includeWarnings | `PUT .../test-cases?includeWarnings=true` | response items SHALL include `validationWarnings` for each test case; without the param, warnings SHALL be omitted |
| Batch update with non-existent test case ID | batch PUT and any item `id` does not exist in the specified dataset | SHALL respond with HTTP 404 and roll back all changes |
| Batch update with dataset not found | batch PUT with a `datasetId` that does not exist | SHALL respond with HTTP 404 |
| Batch update with empty array | batch PUT with an empty array `[]` | SHALL respond with HTTP 400 (VALIDATION_ERROR) |
| Batch update exceeds max items | batch PUT with more items than the configured `test-case.batch.max-items` limit | SHALL respond with HTTP 400 (VALIDATION_ERROR) with a message indicating the maximum allowed batch size |
| Batch update with duplicate IDs | batch PUT and the array contains two or more items with the same `id` | SHALL respond with HTTP 400 (VALIDATION_ERROR) with a message indicating duplicate IDs |
| Batch update name uniqueness within batch | batch PUT and two items in the batch specify the same `testCaseName` (case-insensitive) | SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) with a message identifying the duplicate name |
| Batch update name uniqueness with existing test cases | batch PUT and an item's `testCaseName` collides with an existing test case NOT included in the batch (case-insensitive, same dataset) | SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) and roll back all changes |
| Successful batch patch | `PATCH /api/v1/datasets/{datasetId}/test-cases` with a valid array of merge-patch items | SHALL apply each patch to the corresponding test case, recalculate `valid` for each against the dataset's schema, and return HTTP 200 with an ordered list of `TestCaseResponseDto` matching the input order |
| Batch patch merges data at map level | batch PATCH with an item `{ "id": "...", "data": { "prompt": "new" } }` | SHALL merge the `data` map (existing keys preserved, specified keys updated, keys set to null removed), according to the single PATCH map-update rules |
| Batch patch with includeWarnings | `PATCH .../test-cases?includeWarnings=true` | response items SHALL include `validationWarnings` for each test case |
| Batch patch with non-existent test case ID | batch PATCH and any item `id` does not exist in the specified dataset | SHALL respond with HTTP 404 and roll back all changes |
| Batch patch with missing id field | batch PATCH and any item does not contain an `id` field | SHALL respond with HTTP 400 (VALIDATION_ERROR) |
| Batch patch with invalid id format | batch PATCH and any item's `id` is not a valid UUID | SHALL respond with HTTP 400 (VALIDATION_ERROR) |
| Batch patch with empty array | batch PATCH with an empty array `[]` | SHALL respond with HTTP 400 (VALIDATION_ERROR) |
| Batch patch exceeds max items | batch PATCH with more items than the configured limit | SHALL respond with HTTP 400 (VALIDATION_ERROR) with a message indicating the maximum allowed batch size |
| Batch patch with duplicate IDs | batch PATCH and the array contains two or more items with the same `id` | SHALL respond with HTTP 400 (VALIDATION_ERROR) |
| Batch patch final-state name uniqueness within batch | batch PATCH and the final names of two or more batch items are the same (case-insensitive) — considering the new name for items that include `testCaseName` in their patch, and the current (unchanged) name for items that do not | SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) with a message identifying the duplicate name |
| Batch patch name uniqueness with existing test cases | batch PATCH and any item's final `testCaseName` collides with an existing test case NOT included in the batch (case-insensitive, same dataset) | SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) and roll back all changes |
| Default batch limit | no `test-case.batch.max-items` property is configured | SHALL use the default limit of 256 |
| Custom batch limit | `test-case.batch.max-items` is set to 100 | SHALL reject batch requests with more than 100 items |
| Batch limit applied to both PUT and PATCH | batch limit is configured | SHALL apply the same limit to both batch PUT and batch PATCH endpoints under the dataset path |

### Requirement: Composite bulk partial update test cases (PATCH :bulk)

Status: **Implemented**

`PATCH /api/v1/datasets/{datasetId}/test-cases:bulk` SHALL accept one JSON object with optional `bulkOperations` and `itemOperations` arrays; at least one SHALL be non-empty. The whole request SHALL be atomic in one transaction.

```json
{
  "bulkOperations": [{"selector": {"ids": ["<uuid>"]}, "patch": {"data": {"key": "value"}}}],
  "itemOperations": [{"id": "<uuid>", "patch": {"testCaseName": "new name"}}]
}
```

Selectors may instead use `{"filter":["<filter-expression>"]}`; exactly one selector variant is required. Operations SHALL follow "Selector resolution and execution order".

| Path | Accepted fields / merge rule | Validation |
|---|---|---|
| Selector-scoped `bulkOperations` | Fixed, non-configurable whitelist `testCaseName`/`data`; `data` replaces the whole map. A `null` `data` or `testCaseName` is passed through to the UPDATE; both columns are NOT NULL, so when the selector matches rows the request fails with HTTP 500 and rolls back (known limitation: no 400 mapping) | No per-row validation or NUMBER normalization; stored validity/warnings remain unchanged |
| `itemOperations` | [Single-case PATCH rules](../test-cases/spec.md#requirement-single-case-patch-contract), including `multiTurnData` | Recompute validation/normalization when any `data`, `multiTurnData`, or `testCaseName` key is present, even for ignored/unchanged values; otherwise skip |

An unknown selector patch field SHALL return HTTP 400 naming it and roll back. Validation cost follows relevant item operations, not selector match count. Limits, response flags and name permutations are specified below.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Atomic item-only composite PATCH | client sends a well-formed request with at least one `itemOperations` entry and an omitted or empty `bulkOperations` | SHALL apply each item operation (in array order) using the single-row PATCH rules, commit the transaction, and return HTTP 200 with a response body containing `itemResults` (and an empty or omitted `bulkResults`) |
| Bulk-only data replacement is supported | client sends a non-empty bulk operation whose selector matches cases and whose patch contains a `data` object | SHALL replace each matched case's shared data with that object and return HTTP 200 with matched/updated counts; existing keys absent from the supplied object SHALL be removed; stored validity and warnings SHALL remain unchanged |
| Empty body is rejected | client sends a body with both `bulkOperations` and `itemOperations` absent or empty (including a missing body) | SHALL respond with HTTP 400 (VALIDATION_ERROR) |
| Atomic rollback on any failure | any validation, resolver, or DB error occurs during execution of any bulk or item operation | SHALL roll back all changes from the request and respond with the corresponding error HTTP status (no partial apply) |
| Bulk patch with non-whitelisted field | `bulkOperations[i].patch` contains a key other than `testCaseName` or `data` | SHALL respond with HTTP 400 (VALIDATION_ERROR) naming the offending field, and roll back |
| Bulk patch with null data or name | a bulk operation whose selector matches at least one case sets `data` or `testCaseName` to `null` | the NOT NULL constraint violation SHALL surface as HTTP 500 and the whole request SHALL roll back (known limitation) |
| Item operation patches a field not in the bulk whitelist | `itemOperations[i].patch` contains `multiTurnData`, which is valid for single-row PATCH but outside the bulk whitelist | SHALL accept the item operation |
| Item op on data triggers per-row re-validation | an `itemOperations[i].patch` modifies `data` | SHALL re-run validation for that row and persist the updated `valid` / `validation_warnings` against the dataset's schema |
| Item op on testCaseName triggers per-row re-validation | an `itemOperations[i].patch` modifies `testCaseName` only | SHALL re-run validation for that row (name uniqueness + downstream checks) and persist the updated state |
| Bulk data replacement does not recompute validity | a selector-scoped bulk operation replaces shared data with values that violate the dataset schema | stored validity and validation warnings SHALL remain unchanged until a validating write or revalidation processes the case |
| Relevant unchanged item patch still revalidates | an item patch contains a validation-relevant key whose submitted value is ignored or unchanged | per-row validation SHALL still run because selection is based on key presence |

### Requirement: Selector resolution and execution order

Status: **Implemented**

A selector SHALL contain exactly one of `ids` or `filter`. IDs must exist in the specified dataset; unknown/wrong-dataset IDs SHALL return HTTP 404 atomically. Empty IDs match nothing. Filters use the test-case whitelist; invalid/non-whitelisted fields SHALL return HTTP 400. An empty filter list matches all cases, subject to the materialized-ID cap.

Bulk operations SHALL execute sequentially in array order, followed by item operations prepared from the resulting rows. Overlapping writes use last-writer-wins. Each filter SHALL resolve to concrete IDs inside the update transaction when its operation executes, against the state left by prior operations—not once at request start. Rows inserted after that resolution and absent from its ID set SHALL not be updated. Item writes may override prior bulk values or patch fields untouched by them. Immediate bulk uniqueness failures cannot be rescued by later items.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| IDs selector with all ids in the dataset | client provides `selector.ids` containing UUIDs that all belong to `{datasetId}` | SHALL apply the shared `patch` to exactly those test cases via a single SQL UPDATE |
| IDs selector with an id not in the dataset | client provides `selector.ids` containing one or more UUIDs that do not belong to `{datasetId}` (either nonexistent or belonging to a different dataset) | SHALL respond with HTTP 404 (NOT_FOUND) and roll back |
| Filter selector with an empty filter list | client provides `selector.filter` as an empty list `[]` | SHALL treat the selector as matching every test case in the dataset |
| Filter selector with valid filter expressions | client provides `selector.filter` with expressions referencing fields in the test-case filter whitelist | SHALL apply the shared `patch` to every test case in the dataset that matches all expressions |
| Filter selector rejects unknown or non-whitelisted field | client provides a `filter` expression referencing a field that is not in the test-case filter whitelist (or uses an operator not allowed for that field) | SHALL respond with HTTP 400 (INVALID_FILTER) and roll back. The data-layer `InvalidFilterException` raised by the underlying `WhereBuilder` SHALL be translated by the service layer into a `FilterValidationException` so the global exception handler maps it to HTTP 400; an unwrapped data-layer exception SHALL NOT reach the client. |
| Selector must declare exactly one variant | a `selector` contains both `ids` and `filter`, or neither | SHALL respond with HTTP 400 (VALIDATION_ERROR) |
| Empty IDs selector matches no cases | a bulk operation uses `selector.ids:[]` | it SHALL be accepted as an empty selection with matched and updated counts of zero |
| Item operation overrides a field set by a prior bulk operation | a bulk operation sets `data:{"tag":"bulk"}` on cases including `X`, and an item operation patches `X` with `data:{"tag":"item"}` | the final shared data for `X` SHALL contain `tag:"item"`, with the item patch applied to the bulk-replaced map |
| Item operation patches a field untouched by the bulk operation | `bulkOperations` replaces `data` on rows including id `X`, and `itemOperations` contains `{ id: X, patch: { testCaseName: "A2" } }` | the final persisted state for `X` SHALL retain the bulk-op's field change AND have `testCaseName="A2"` |
| Two overlapping bulk operations | `bulkOperations[0]` and `bulkOperations[1]` both replace `data` on overlapping id sets — `bulkOperations[0]` sets it for all rows and `bulkOperations[1]` sets a different value on ids `[X]` | the final persisted state for `X` SHALL reflect `bulkOperations[1]`'s value and for every other row SHALL reflect `bulkOperations[0]`'s value (array-order, last-writer-wins) |
| Rows inserted concurrently are not matched | rows are inserted into the dataset after the filter-selector resolution but before transaction commit | those rows SHALL NOT be affected by the bulk operation |
| Later filter selectors resolve against earlier operations' resulting state | a `bulkOperations[0]` changes a field used by `bulkOperations[1].selector.filter` | `bulkOperations[1]` SHALL be resolved against the post-`bulkOperations[0]` state, i.e., filter selectors are resolved at the moment each op executes, not up-front for the whole request |

### Requirement: Composite limits and duplicate IDs

Status: **Implemented**

The service SHALL enforce the following configurable caps on the dataset-rooted `PATCH /api/v1/datasets/{datasetId}/test-cases:bulk` endpoint. Violations SHALL result in HTTP 400 (VALIDATION_ERROR) and a message identifying which cap was exceeded.

- `test-case.bulk.max-operations` (default `512`) — maximum combined count of `bulkOperations.length + itemOperations.length`. Each cap is independently configurable; the combined-operation cap also applies to item-only requests.
- `test-case.bulk.max-ids-per-selector` (default `10000`) — maximum `selector.ids.length` for a single `bulkOperations[i]`; also an upper bound on the id-set materialised from a `filter` selector.
- `test-case.bulk.max-item-operations` (default `500`) — maximum `itemOperations.length`.
The bulk-patch field whitelist is the code-defined `testCaseName`/`data` map, not a configuration property (see "Composite bulk partial update test cases (PATCH :bulk)").

The endpoint SHALL NOT apply `test-case.batch.max-items` — that property continues to govern only the array-body batch endpoint at `PATCH /api/v1/datasets/{datasetId}/test-cases`.

The service SHALL reject `itemOperations` arrays containing two or more entries with the same `id`. Duplicates inside a single `bulkOperations[i].selector.ids` SHALL also be rejected. Repeating an id across different operations (e.g., a row appears in a bulk selector AND in `itemOperations`) SHALL be allowed — last-writer-wins semantics apply. The endpoint is rooted at `PATCH /api/v1/datasets/{datasetId}/test-cases:bulk`.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Combined op count exceeds max-operations | `bulkOperations.length + itemOperations.length` exceeds `test-case.bulk.max-operations` | SHALL respond with HTTP 400 (VALIDATION_ERROR) |
| Selector ids exceed max-ids-per-selector | a `bulkOperations[i].selector.ids.length` exceeds `test-case.bulk.max-ids-per-selector` | SHALL respond with HTTP 400 (VALIDATION_ERROR) |
| Filter selector materialises more than max-ids-per-selector | a `filter`-based selector would match more test cases than `test-case.bulk.max-ids-per-selector` | SHALL respond with HTTP 400 (VALIDATION_ERROR) and roll back |
| Item operations exceed max-item-operations | `itemOperations.length` exceeds `test-case.bulk.max-item-operations` | SHALL respond with HTTP 400 (VALIDATION_ERROR) |
| Defaults apply when properties are unset | no `test-case.bulk.*` property is configured | SHALL use the defaults listed above |
| Array-body batch cap does not apply | `itemOperations.length` is between `test-case.batch.max-items` (e.g., 256) and `test-case.bulk.max-item-operations` (e.g., 500) | SHALL accept the request (the array-body `test-case.batch.max-items` cap SHALL NOT apply to `:bulk`) |
| Duplicate id within itemOperations | `itemOperations` contains two entries with the same `id` | SHALL respond with HTTP 400 (VALIDATION_ERROR) |
| Duplicate id within a single selector's ids | `bulkOperations[i].selector.ids` contains the same UUID twice | SHALL respond with HTTP 400 (VALIDATION_ERROR) |
| Same id referenced across a bulk selector and itemOperations | an id appears in a `bulkOperations[i].selector.ids` AND in `itemOperations` | SHALL accept the request and apply the item operation on top of the bulk state (last-writer-wins) |
| Same id referenced across two bulkOperations entries | an id appears in `bulkOperations[0].selector.ids` AND in `bulkOperations[1].selector.ids` (or is materialised by a filter selector in either op) | SHALL accept the request and apply both updates in array order (last-writer-wins per field) |

### Requirement: Response shape for composite bulk patch

Status: **Implemented**

The service SHALL return a compact response body containing counts per operation, not full entity rows, from the dataset-rooted `PATCH /api/v1/datasets/{datasetId}/test-cases:bulk`. The response SHALL preserve the input order of operations.

Response shape:

```json
{
  "bulkResults": [ { "opIndex": 0, "matched": <int>, "updated": <int> }, ... ],
  "itemResults": [ { "id": "<uuid>", "updated": true | false }, ... ]
}
```

`matched` SHALL count selector-resolved cases. Bulk `updated` SHALL count rows whose whitelisted columns differ using NULL-safe SQL comparisons (including JSONB equality), excluding timestamp-only changes. Item `updated` SHALL compare the prepared name, serialized shared data, serialized turn array, validity, and serialized warnings with their pre-patch values; normalization or warning changes MAY therefore make it true even when submitted values appear unchanged. Timestamps SHALL be excluded from this comparison. Item rows SHALL still be persisted when `updated=false`.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Bulk op counts reflect the selector | a bulk op's selector resolves to N test cases and the patch differs from current state for all N | `bulkResults[i]` SHALL report `matched=N, updated=N` |
| Bulk op no-op for already-matching state | a bulk op's selector resolves to N test cases and the patch equals current state for K of them | `bulkResults[i]` SHALL report `matched=N, updated=N-K` |
| Item op no-op when patch equals current state | an item operation leaves the name, serialized data/turns, validity, and serialized warnings equal to their pre-patch values | `itemResults[i].updated` SHALL be `false` |

### Requirement: Batch and item name permutations

Status: **Implemented**

Selector-scoped names obey immediate database case-insensitive uniqueness: assigning one name to multiple matches or creating an intermediate collision SHALL fail the request with HTTP 500 and roll back before later operations. Known limitation: the selector write does not translate the unique-index violation, so it is not reported as HTTP 409 `UNIQUE_CONSTRAINT_VIOLATION`.

Array-body PUT/PATCH and composite `itemOperations` SHALL instead permit pairwise swaps and arbitrary rename cycles when their final state is case-insensitively unique. Items are prepared after bulk operations, checked against each other and cases outside the request, parked at temporary names, then persisted. A genuine final duplicate SHALL return HTTP 409 `UNIQUE_CONSTRAINT_VIOLATION` atomically. This prepare/park/apply guarantee SHALL NOT extend to selector-scoped bulk writes.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Item ops produce duplicate names within the request | two `itemOperations` result in the same `testCaseName` (case-insensitive) inside the same dataset | SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) and roll back |
| Item op collides with existing name outside the request | an `itemOperations[i]` sets `testCaseName` to a value already used by a test case in the same dataset that is not part of the request | SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) and roll back |
| Later item rename cannot rescue a bulk name collision | a selector-scoped bulk rename creates a database name collision before a later item operation would remove it | the bulk write SHALL fail with HTTP 500 (known limitation, not 409) and roll back the entire request rather than evaluate uniqueness only at the whole-request final state |
| Pairwise name swap via batch PUT succeeds | a dataset has test cases named `A` and `B`, and `PUT /api/v1/datasets/{datasetId}/test-cases` with the first item renaming `A → B` and the second renaming `B → A` | SHALL return HTTP 200 and the two test cases SHALL have their names swapped (no HTTP 409) |
| Pairwise name swap via batch PATCH succeeds | a dataset has test cases named `A` and `B`, and `PATCH /api/v1/datasets/{datasetId}/test-cases` with the first item renaming `A → B` and the second renaming `B → A` | SHALL return HTTP 200 and the two test cases SHALL have their names swapped (no HTTP 409) |
| Multi-way rename cycle succeeds | a dataset has test cases named `A`, `B`, and `C`, and a single batch operation renames `A → B`, `B → C`, and `C → A` | SHALL return HTTP 200 and the three test cases SHALL reflect the rotated names (no HTTP 409) |
| Name swap via composite bulk patch itemOperations succeeds | a dataset has test cases named `A` and `B`, and `PATCH /api/v1/datasets/{datasetId}/test-cases:bulk` with two `itemOperations`, one renaming `A → B` and the other renaming `B → A` | SHALL return HTTP 200 and the two test cases SHALL have their names swapped (no HTTP 409) |
| Genuine final-state duplicate within a permutation still rejected | a batch operation assigns the same final `testCaseName` to two items (a real duplicate, not a permutation) | SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) and roll back all changes |

## Implementation notes

- [TestCaseController.java](../../../src/main/java/com/epam/aidial/evaluation/web/controller/TestCaseController.java): array-body PUT/PATCH endpoints; [TestCaseBulkPatchController.java](../../../src/main/java/com/epam/aidial/evaluation/web/controller/TestCaseBulkPatchController.java): composite `:bulk` endpoint only.
- [TestCaseService.java](../../../src/main/java/com/epam/aidial/evaluation/service/domain/TestCaseService.java): sequential selectors, item preparation and response comparisons.
- [TestCaseBulkPatchValidator.java](../../../src/main/java/com/epam/aidial/evaluation/service/domain/TestCaseBulkPatchValidator.java), [TestCaseBulkSelectorResolver.java](../../../src/main/java/com/epam/aidial/evaluation/service/domain/TestCaseBulkSelectorResolver.java): limits, duplicate IDs and materialization.
- [BulkPatchFields.java](../../../src/main/java/com/epam/aidial/evaluation/data/db/repository/sql/BulkPatchFields.java), [PostgresTestCaseRepository.java](../../../src/main/java/com/epam/aidial/evaluation/data/db/repository/PostgresTestCaseRepository.java): fixed whitelist, NULL-safe change detection and prepare/park/apply uniqueness.
