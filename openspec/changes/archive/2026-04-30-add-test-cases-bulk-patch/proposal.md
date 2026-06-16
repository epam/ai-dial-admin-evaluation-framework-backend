## Why

The existing `PATCH /api/v1/test-suites/{suiteId}/test-cases` batch endpoint caps the request at `test-case.batch.max-items` (default 256) items and requires one JSON merge-patch object per affected row. In suites with up to ~10,000 test cases, a single UI Save action — most commonly "deselect all, select a few" — produces a diff larger than 256 items purely to toggle the `enabled` flag homogeneously across the suite (reported in [ai-dial-admin-frontend#3021](https://github.com/epam/ai-dial-admin-frontend/issues/3021)). Raising the limit alone would scale payload, parse cost, and transaction duration linearly with suite size. We need an endpoint shape where payload is decoupled from the number of affected rows for homogeneous operations, while still supporting heterogeneous per-row edits atomically in the same request.

## What Changes

- Add a new endpoint `PATCH /api/v1/test-suites/{testSuiteId}/test-cases:bulk` that accepts a composite body with two parallel sections:
  - `bulkOperations[]`: each op has a `selector` (either `{ids: [...]}` or `{filter: [...]}` reusing the existing test-case filter whitelist) and a shared `patch` applied to all matched rows as a single SQL `UPDATE`.
  - `itemOperations[]`: each op is a per-row merge-patch `{id, patch}`, identical semantics to the current batch PATCH item.
- The whole request SHALL execute inside a single `@Transactional("metaTransactionManager")` — all-or-nothing atomicity across bulk + item operations.
- Execution order: `bulkOperations` applied first in array order, then `itemOperations` applied on the already-bulk-updated state. Overlaps resolve as last-writer-wins, with per-row intent (item) taking precedence over broad strokes (bulk).
- Bulk operations SHALL restrict the allowed `patch` fields to a code-defined whitelist (initially `{ enabled }`). The whitelist is **not** a configuration property — extending it requires a code change anyway (column mapping, validation impact, tests), so a config knob would be a footgun (operators could not safely widen it past code support, and only narrow-below-code-support would remain — which has no concrete demand and is better served at gateway/policy layers). Item operations retain the full merge-patch field set already supported by single-row PATCH.
- Response returns compact counts (`{ bulkResults: [{opIndex, matched, updated}], itemResults: [{id, updated}] }`) rather than full entities — keeps the response constant-size for large-selector bulk ops.
- Add configuration namespace `test-case.bulk.*`:
  - `test-case.bulk.max-operations` (default 512) — cap on `bulkOperations[].length + itemOperations[].length`. MUST be ≥ `max-item-operations` so the per-section cap remains reachable.
  - `test-case.bulk.max-ids-per-selector` (default 10000) — cap on `selector.ids.length`; also enforced for the implicit id-set materialised by a filter selector (hard upper bound = suite size).
  - `test-case.bulk.max-item-operations` (default 500) — cap on heterogeneous per-row ops.
- Keep the existing `PATCH /api/v1/test-suites/{testSuiteId}/test-cases` (heterogeneous array body) unchanged, keep its 256-item cap, and document the new `:bulk` endpoint as the scalable path for large Saves.
- Update OpenAPI: add operation annotations, request/response schemas, request examples under `src/main/resources/openapi/examples/test-cases-bulk-*.json`, and register the endpoint with `OpenApiQueryParamCustomizer` if any rich query-param docs become relevant (none currently — body-only).
- Update `docs/configuration.md` with the three new `test-case.bulk.*` properties.

## Capabilities

### New Capabilities
_(none — this extends an existing capability rather than creating a new one)_

### Modified Capabilities
- `test-cases`: Add requirements for the composite bulk-patch endpoint (`:bulk`), its selector semantics, execution order, conflict resolution, atomicity, field whitelist, configurable limits, and error responses. The existing "Batch partial update test cases (PATCH)" requirement is not modified — the new endpoint is additive and coexists.

## Impact

- **API**: New endpoint at `PATCH /api/v1/test-suites/{testSuiteId}/test-cases:bulk`. No breaking changes to existing endpoints. OpenAPI spec grows by one operation + two example files.
- **Configuration**: Three new properties under `test-case.bulk.*` in `application.yml`; `docs/configuration.md` updated in the same change (per configuration-docs spec). The bulk-patch field whitelist itself is **not** a configuration property — it lives in code as a single immutable set keyed off the canonical API-field → SQL-column map.
- **Web layer** (`web.controller.TestCaseController`): new handler method, new request DTOs under `service.domain.dto.testcase.bulk.*` (`TestCaseBulkPatchRequestDto`, `TestCaseBulkOperationDto`, `TestCaseBulkSelectorDto`, `TestCaseItemOperationDto`, `TestCaseBulkPatchResponseDto`). Validation annotations enforce size/whitelist caps.
- **Service layer** (`service.domain.TestCaseService`): new `bulkPatch(...)` method. Supporting injectable components under `service.domain`:
  - `TestCaseBulkSelectorResolver` — resolves a `selector` into a concrete `List<UUID>` of matched ids (ids pass-through for id-selector; filter selector runs a repository id-projection query). Caps materialised ids against `max-ids-per-selector`.
  - `TestCaseBulkPatchValidator` — enforces field whitelist for bulk patches, global op-count caps, selector size caps, duplicate-id detection inside a single op, and overlap-friendly composition rules.
- **Data layer** (`data.db.repository.TestCaseRepository` / `PostgresTestCaseRepository`): new repository methods
  - `updateFieldsByIds(UUID suiteId, List<UUID> ids, Map<String, Object> setClause, long updatedAt)` using `unnest(:ids::uuid[])` binding for 10k-element safety.
  - `findIdsByTestSuiteIdAndFilter(UUID suiteId, List<FilterCondition> filters, int limit)` — id-only projection used by filter selectors.
  - Existing per-row merge-patch path reused for `itemOperations`.
- **Transaction semantics**: Single `@Transactional("metaTransactionManager")`. Uses `TransactionTimestampContext` for `updatedAt` (same timestamp across bulk + item ops, per project convention).
- **Validation**: Re-validation (`TestCaseValidationService`) runs only on rows whose patched fields belong to the validation-relevant set (`data`, `requestTemplateOverride`, `inputBindingsOverride`). The inaugural `enabled`-only bulk toggle skips per-row re-validation entirely — required to keep toggles cheap on 10k suites.
- **Uniqueness**: Name-uniqueness is re-checked only when an op touches `testCaseName` (not possible in the initial whitelist). Existing `validateBatchNameUniqueness` pattern is reused when a future field addition opens this door.
- **Database**: No schema changes, no Flyway migration.
- **Observability**: `@LogExecution` on controller + service; standard correlation-id propagation.
- **Frontend impact**: FE is the consumer that motivates this work. FE will switch from "emit all diffed rows into a flat array" to "aggregate homogeneous `enabled` flips into `bulkOperations` and send truly heterogeneous edits as `itemOperations`." Coordination with the FE team is required but is out of this change's scope.
- **Testing**: New unit tests for `TestCaseBulkSelectorResolver`, `TestCaseBulkPatchValidator`. New functional tests under `functional.tests.TestCaseBulkPatchFunctionalTests` covering: homogeneous toggle across a 10k-row suite (seeded), mixed bulk + item atomicity, selector overlap, field-whitelist rejection, op-count caps, validation skip for `enabled`-only flips, and 4xx error paths. Existing `PATCH /test-cases` (heterogeneous batch) tests remain unchanged.
- **Risks**:
  - Large `unnest`/filter UPDATEs hold row locks for the duration of the tx. Mitigation: capped at 10k ids per selector; caller owns the "whole-suite" case. For `enabled`-only flips, updates touch one narrow column and one index (`is_enabled`), keeping lock duration small.
  - Filter selector race: a row inserted between selector resolution and UPDATE is not affected by the bulk op. Documented as expected; bulk ops SHALL NOT guarantee "seen at commit time = matched." For id selectors this is not a concern.
  - Response compactness: returning counts (not rows) is a deliberate contract difference from the existing batch PATCH endpoint, which returns the full list. Documented in OpenAPI; FE doesn't need row echoes because it already has per-row state locally.
