## Why

Renaming a dataset schema column through `PUT /api/v1/datasets/{id}` silently destroys that column's data: the update diffs old vs new schema **by name**, so `[column1, column2] → [column1, column3]` is indistinguishable from "drop `column2`, add `column3`", and `removeDataFields` strips `column2` from every test case. The schema needs a stable per-field identity so a rename can be told apart from a delete + create.

## What Changes

- **Field identity.** `FieldDefinitionDto` (in `evaluation-runner-core`) gains a server-assigned `id` (UUID string). `name` stays the field's key everywhere (data maps, bindings, filters, CSV/ZIP headers); `id` exists only to classify schema diffs on dataset update. Every persisted dataset schema carries an `id` on every field.
- **Backfill.** Meta migration `V1.36__AddIdsToDatasetSchemaFields.sql` assigns `gen_random_uuid()` to every element of `datasets.test_case_schema` lacking an `id`. No DDL; no jOOQ regeneration. Run snapshots (`test_suite_runs`) are **not** migrated — historical data is immutable; `id` is additive-optional on the snapshot.
- **Id assignment / matching on every schema write.** A field without `id` takes the `id` of the existing field with the same name (unless that `id` is already claimed elsewhere in the request), otherwise gets a fresh UUID. A field with `id` must reference an existing field of this dataset.
- **Rename semantics on dataset PUT.** Same `id`, different `name` = rename. Renames move the values under the old key to the new key in `data` and in every `multi_turn_data[i]` element, in a single statement per dataset (swaps and chains supported). Only current fields no request entry resolves to (by `id`, or by name for id-less entries) are treated as removed and pruned. Field names must be unique (case-insensitive) — today's spec says so but nothing enforces it; now enforced. A rename combined with a type change moves the values and lets revalidation coerce them; a rename combined with a `perTurn` flip moves the key in place (no relocation between `data` and `multiTurnData`) — revalidation reports misplacement, as for a plain flip today.
- **Rename guard.** A rename is allowed only on a PRIVATE dataset or on a PUBLIC dataset with no bound suites; a PUBLIC dataset with ≥1 bound suite → **HTTP 409** (renaming would silently break other owners' suites). The bound-suite check runs after the dataset save, whose row lock serializes it with concurrent suite binding (the binding trigger locks the same row). No explicit lock in the PUT — concurrent PUTs rely on the existing optimistic `version`.
- **New 400 rejections** on dataset create/update: `id` on create; unknown `id` on update; duplicate `id` within a request.
- **CSV / ZIP import goes through `DatasetService`.** `CsvImportService` drops its direct `DatasetRepository` / `DatasetSchemaProvider` use; it reads via one new method, `DatasetService.getByIdForUpdate`, taken before any test-case write (same lock order as the PUT → no import/PUT deadlock, no stale-schema overwrite), and persists the schema via the existing `DatasetService.update`. Import stays name-based (fields without `id` → name matching), no ids in CSV/ZIP exports or manifests. The import no longer spawns its own revalidation (the update does, only when the schema actually changed), and the schema persist moves after the post-persist coercion fixup.
- **Revalidation runs synchronously.** `@Async` on `RevalidationService.runDatasetRevalidationAsync` was never effective (self-invocation bypasses the proxy) and has no external caller; it is removed and the method renamed `runDatasetRevalidation`. Behaviour is unchanged; the spec wording ("async") is corrected. Consequence: for a PRIVATE dataset, its single suite is revalidated before the PUT returns.
- **API (additive, not breaking):** `id` appears on each `testCaseSchema` entry in `DatasetResponseDto` and the dataset-bound views of the schema; clients that omit `id` on update keep today's name-based behaviour. OpenAPI examples updated.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `datasets`: `testCaseSchema` field identity (`id`), validation of ids, rename classification and data move, rename guard (409), schema-cleanup requirement narrowed to id-removed fields, revalidation trigger described as synchronous.
- `test-cases`: CSV/ZIP import schema persistence routed through `DatasetService.update` (name-matched ids, lock-first isolation from concurrent PUTs, revalidation only on an actual schema change).
- `test-case-zip-archive`: manifest omits field ids on export and ignores them on import (a foreign dataset's ids would otherwise fail the unknown-id check).

## Impact

- **Code:** `FieldDefinitionDto` (`evaluation-runner-core`), `DatasetService` (update flow, rename guard, new `getByIdForUpdate`), `TestCaseService` / `TestCaseRepository` / `PostgresTestCaseRepository` (new rename-keys operation over `data` + `multi_turn_data`), `CsvImportService` (dependency swap, persist ordering, revalidation trigger removal), `RevalidationService` (`@Async` removal — done), `ZipManifestSerializer` / export paths (ensure `id` is not written), `DefaultExceptionHandler` / error code for the 409. New injectable `DatasetSchemaFieldIdResolver` (id resolution + rename/removal classification).
- **DB:** `V1.36__AddIdsToDatasetSchemaFields.sql` (JSONB data backfill on `datasets.test_case_schema`; no index changes). `docs/database-schema.md` updated to document the `id` key in the JSONB shape.
- **API:** additive `id` on schema fields; new 400 cases; new 409 on rename of a PUBLIC dataset with bound suites. FE must round-trip `id` and never invent one.
- **Config:** none.
- **Risks:** (1) old clients that drop `id` fall back to name matching — renames from such clients still behave as delete + create (today's behaviour, not a regression). (2) Synchronous revalidation inside the PUT/import transaction already exists today; this change makes it explicit, not slower. (3) Concurrent rename vs suite binding is closed by checking after the save's row lock; import vs PUT by the import's lock-first read.
- **Rollout:** migration is idempotent (only fills missing ids); FE can ship after the backend since the field is additive.
- **Tests:** unit tests for id matching/validation and diff classification; repository test for key renames (incl. swap, multi-turn, null/non-object `multi_turn_data` shapes); functional tests: rename preserves data, rename of PUBLIC dataset with bound suite → 409, unknown/duplicate id → 400, CSV OVERRIDE/MERGE keep ids for surviving names, backfill migration fills ids.
