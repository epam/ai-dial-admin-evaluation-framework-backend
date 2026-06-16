## Why

The just-introduced `Dataset` entity makes every dataset implicitly shareable across many test suites. In practice, users need two distinct ownership models: a **shared catalogue** of curated datasets, and **scratch datasets** that belong to a single suite and must not leak into other users' catalogue views. Without this distinction every per-suite dataset created by a user (especially during initial onboarding or one-off experiments) pollutes the catalogue and risks accidental reuse by unrelated suites. We also need to support the natural UX flow "create a suite first, then attach an existing public dataset or create a fresh private one", which today is blocked by the `NOT NULL` `test_suites.dataset_id` constraint.

## What Changes

- Add `visibility` enum (`PUBLIC` | `PRIVATE`) to `Dataset` with a CHECK constraint, surfaced on every Dataset response DTO.
- **BREAKING** — `POST /api/v1/datasets` now requires `visibility` in the request body. Requests without it return HTTP 400.
- `GET /api/v1/datasets` (list) hard-filters server-side to `visibility = 'PUBLIC'`. PRIVATE datasets are reachable only via `GET /api/v1/datasets/{id}` and the dataset-rooted revalidation-task endpoints.
- **BREAKING** — `test_suites.dataset_id` becomes NULLABLE; `TestSuiteRequestDto.datasetId` is no longer required on create. Suites may exist in an **unbound** state; starting a run (`POST /api/v1/test-suite-runs`) on an unbound suite returns HTTP 409 with code `SUITE_HAS_NO_DATASET`.
- Atomic create-and-bind for PRIVATE datasets: `POST /api/v1/datasets` accepts a new optional `bindToSuiteId`. Required when `visibility=PRIVATE` (missing → HTTP 400 `PRIVATE_DATASET_REQUIRES_SUITE_BINDING`), forbidden when `visibility=PUBLIC` (present → HTTP 400 `PUBLIC_DATASET_FORBIDS_SUITE_BINDING`). Server inserts the dataset and updates the target suite's `dataset_id` in a single transaction so no orphan PRIVATE dataset ever exists.
- Visibility transitions are performed via a dedicated `PATCH /api/v1/datasets/{id}/visibility` endpoint (request body: `{ "visibility": "PUBLIC" | "PRIVATE" }`):
  - `PRIVATE → PUBLIC`: always allowed.
  - `PUBLIC → PRIVATE`: allowed only when the dataset has **exactly one** bound suite; else HTTP 409 `PRIVATE_TRANSITION_INVALID_BINDING_COUNT`. Enforced by selecting the dataset row `FOR UPDATE` before counting bindings, so the trigger and the transition path serialize on the same row lock.
  - `PUT /api/v1/datasets/{id}` accepts `visibility` in the request body but **ignores** it. All visibility changes must go through the dedicated PATCH endpoint.
- A PostgreSQL `BEFORE INSERT OR UPDATE OF dataset_id` constraint trigger on `test_suites` locks the target `datasets` row `FOR UPDATE` and rejects any second concurrent binding to a PRIVATE dataset with HTTP 409 `PRIVATE_DATASET_ALREADY_BOUND`. The trigger raises with `ERRCODE='P0001'` (PL/pgSQL `RAISE EXCEPTION`) and MESSAGE TEXT `'PRIVATE_DATASET_ALREADY_BOUND'`; the global exception handler inspects `SQLException.getSQLState()` and maps `'P0001'` to HTTP 409 with the matching `errorCode`. The standard `23505 → UNIQUE_CONSTRAINT_VIOLATION` mapping is untouched. The trigger early-returns when `NEW.dataset_id IS NULL`, so unbind paths (rebind-to-null, PRIVATE-delete cascade) are not blocked by the same guard.
- Suite-side guards:
  - `PATCH /api/v1/test-suites/{id}` that changes `datasetId` when the **old** dataset is PRIVATE returns HTTP 409 `PRIVATE_DATASET_REBIND_FORBIDDEN`. Same for setting `datasetId` back to `null`.
  - `DELETE /api/v1/test-suites/{id}` cascade-deletes the bound dataset when it is PRIVATE (same `@Transactional` boundary, test cases cascade via existing FK).
    - The suite's `test_suite_runs` are cascade-removed via the existing V1.6 `ON DELETE CASCADE` FK from `test_suite_runs.test_suite_id` to `test_suites(id)`. This includes their stored `suite_snapshot` JSON rows. This is intentional: runs die with the suite. The PRIVATE-dataset cascade therefore leaves no dangling references (no runs, no test cases, no dataset row).
- Dataset deletion:
  - `DELETE /api/v1/datasets/{id}` on a PUBLIC dataset keeps existing FK-RESTRICT behavior (409 if any suite is bound).
  - `DELETE /api/v1/datasets/{id}` on a PRIVATE dataset atomically unbinds the bound suite (`test_suites.dataset_id := NULL`) and deletes the dataset row; test cases cascade.
- Modify Flyway migration **`V1.22__IntroduceDataset.sql` in place** (the parent `introduce-dataset-entity` change is archived but the branch has not shipped): add the `visibility` column, backfill all rows to `PRIVATE`, relax `test_suites.dataset_id` to NULLABLE, add the constraint trigger. Snapshot v2 backfill is unchanged — visibility is access control, not part of `datasetRef`.

## Capabilities

### New Capabilities

_None._ This change is layered onto the existing `datasets` and `test-suites` capabilities.

### Modified Capabilities

- `datasets`: adds `visibility` to the entity model and DTOs, makes it required on create, introduces `bindToSuiteId` for atomic create-and-bind, hard-filters PRIVATE out of list responses, defines transition rules, redefines PRIVATE deletion to atomically unbind the suite. The baseline "Delete dataset rejected by RESTRICT" scenario is rewritten to apply to PUBLIC datasets only.
- `test-suites`: relaxes `datasetId` to optional/nullable, defines the unbound state, rejects rebind/unbind when the current dataset is PRIVATE, and cascade-deletes a PRIVATE dataset when the bound suite is deleted. This rewrites two existing baseline requirements: "Suite references a dataset (required `datasetId`)" is relaxed to optional/nullable, and "Suite delete does not cascade to dataset" becomes visibility-conditional (cascades for PRIVATE, unchanged for PUBLIC).
- `test-suite-runs`: extends the "Trigger a test suite run" failure modes — starting a run for an unbound suite (`datasetId IS NULL`) returns HTTP 409 with error code `SUITE_HAS_NO_DATASET`. The unbound check runs before the existing `valid = false` check.

## Impact

- **Migration** (in place): `src/main/resources/db/migration/meta/POSTGRES/V1.22__IntroduceDataset.sql`. After modification: `./gradlew generateJooq` to regenerate the typed jOOQ sources under `src/main/java-generated/data/db/jooq/meta/`; commit the diff. Snapshot v2 backfill (Step 11) is unaffected. Local dev environments with a previously-applied V1.22 must **drop and recreate the local DB**. `flyway repair` alone is NOT sufficient — it only fixes the Flyway history table's checksum entry; it does NOT re-run schema DDL for already-applied migrations, so the new `visibility` column and constraint trigger will be missing and the application will fail at runtime / jOOQ codegen will drift from the live schema.
- **Domain models**: `data.db.model.Dataset` (adds `visibility`), new `data.db.model.DatasetVisibility` enum, `data.db.model.TestSuite` (`datasetId` becomes nullable).
- **Repositories**: `PostgresDatasetRepository` (list predicate, `countBoundSuites`, `unbindAllSuites`), `PostgresTestSuiteRepository` (accepts nullable `dataset_id` writes).
- **Services**: `DatasetService` (atomic create+bind, transition rules, PRIVATE delete path), `TestSuiteService` (nullable binding, rebind rejection, suite-delete cascade), `TestSuiteRunService` / run start path (unbound-suite guard).
- **DTOs & mapper**: `DatasetRequestDto` (`visibility @NotNull`, `bindToSuiteId`) is used for both create and update — `visibility` is required on create and ignored on update (handled in `DatasetService`, not via a separate DTO); `DatasetResponseDto` adds `visibility`; `TestSuiteRequestDto` (`datasetId` no longer `@NotNull`); MapStruct mappers updated.
- **Web**: `DatasetController` adds `PATCH /api/v1/datasets/{id}/visibility`; `DatasetController` and `TestSuiteController` payload/OpenAPI updates; global exception handler picks up new business-error codes (`PRIVATE_DATASET_REQUIRES_SUITE_BINDING`, `PUBLIC_DATASET_FORBIDS_SUITE_BINDING`, `PRIVATE_DATASET_ALREADY_BOUND`, `PRIVATE_DATASET_REBIND_FORBIDDEN`, `PRIVATE_TRANSITION_INVALID_BINDING_COUNT`, `SUITE_HAS_NO_DATASET`).
- **Tests**: new functional nested class `DatasetVisibilityTests` (concurrency race, atomic create+bind, transitions, cascade behavior, unbound-suite run guard) plus targeted unit tests for `DatasetService` / `TestSuiteService`.
- **Docs**: `docs/database-schema.md` (visibility column, nullable `dataset_id`, the trigger), AGENTS.md "Dataset Entity" section, OpenAPI example JSONs under `src/main/resources/openapi/examples/datasets/` and `.../test-suites/`. No `docs/configuration.md` update (no new properties). No `openspec/specs/README.md` update (no new spec folder).
- **No impact on the analytics datasource** or the eval-summary export path; visibility is meta-only.
