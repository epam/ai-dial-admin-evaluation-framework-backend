## Why

Clients that need to delete a precise set of test cases must issue N individual `DELETE .../test-cases/{id}` calls, one per test case. There is no endpoint that accepts a list of IDs, leaving clients to manage retries and partial failures across N round-trips. A bulk delete by IDs endpoint removes this friction and aligns with the existing bulk-patch-by-IDs pattern already present in the API.

## What Changes

- New endpoint `DELETE /api/v1/datasets/{datasetId}/test-cases:bulk` that accepts a JSON body with a list of test case UUIDs.
- Partial-success semantics: a single SQL `DELETE … WHERE id IN (?) … RETURNING id` deletes all matching IDs atomically; IDs absent from the dataset are reported as `notFound` rather than causing a 404.
- Response returns two lists (both preserving input ordering): `deleted` (IDs removed) and `notFound` (IDs not present in the dataset).
- New configurable cap `test-case.bulk.max-delete-ids` (default 10 000) prevents oversized requests.
- A dedicated validator component enforces: non-empty list, size cap, no null elements, no duplicate IDs.
- The existing single-delete (`DELETE .../test-cases/{id}`, HTTP 204) and filter-based bulk delete (`DELETE .../test-cases?filter=…`, returns count) are unchanged.

## Capabilities

### New Capabilities

- `test-case-bulk-delete-by-ids`: Bulk deletion of test cases by explicit UUID list with per-ID outcome (deleted / not-found), partial-success semantics, configurable ID cap, duplicate and size validation.

### Modified Capabilities

_(none — existing single-delete and filter-bulk-delete requirements are unchanged)_

## Impact

- **New files**: `TestCaseBulkDeleteController`, `TestCaseBulkDeleteRequestDto`, `TestCaseBulkDeleteResponseDto`, `TestCaseBulkDeleteValidator` (all following the pattern of the existing bulk-patch equivalents).
- **Modified files**: `TestCaseRepository` + `PostgresTestCaseRepository` (new `deleteByIdsAndDatasetId` method), `TestCaseService` (new `bulkDelete` method), `TestCaseProperties.Bulk` (new `maxDeleteIds` field), `application.yml`, `docs/configuration.md`.
- **No DB schema change** — operates on the existing `test_cases` table; no Flyway migration required.
- **No breaking change** — additive endpoint under a new path segment (`:bulk`).
- **Tests**: unit test for the validator; functional tests covering all-found, partial, all-not-found, dataset-not-found, empty-list, duplicate, and cap-exceeded scenarios.
