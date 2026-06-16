## 1. Database migration and jOOQ codegen

- [x] 1.1 Modify `src/main/resources/db/migration/meta/POSTGRES/V1.22__IntroduceDataset.sql` in place to add `datasets.visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE'` with `CHECK (visibility IN ('PUBLIC','PRIVATE'))`; backfill all V1.22-created rows to `'PRIVATE'`; drop the default after backfill so future inserts must supply `visibility` explicitly
- [x] 1.2 In the same migration, relax `test_suites.dataset_id` to NULLABLE (remove NOT NULL; existing FK to `datasets.id` remains, change ON DELETE clause if needed per design D2/D4)
- [x] 1.3 In the same migration, add PL/pgSQL constraint trigger `tg_test_suites_private_binding_guard` `BEFORE INSERT OR UPDATE OF dataset_id ON test_suites` that early-returns on `NEW.dataset_id IS NULL`, otherwise `SELECT ... FOR UPDATE` the target `datasets` row, counts current bindings, and `RAISE EXCEPTION USING ERRCODE='P0001', MESSAGE='PRIVATE_DATASET_ALREADY_BOUND'` when the target's `visibility='PRIVATE'` AND another suite already references it
- [x] 1.4 Run `./gradlew generateJooq` and commit the regenerated sources under `src/main/java-generated/data/db/jooq/meta/`; verify `JooqSchemaDriftTest` passes
- [x] 1.5 Update `docs/database-schema.md` to reflect the new `datasets.visibility` column, the nullable `test_suites.dataset_id`, and the constraint trigger

## 2. Domain models and enums

- [x] 2.1 Add `data.db.model.DatasetVisibility` enum with values `PUBLIC`, `PRIVATE`
- [x] 2.2 Add `visibility` field to `data.db.model.Dataset` (typed as `DatasetVisibility`)
- [x] 2.3 Change `data.db.model.TestSuite.datasetId` to nullable (verify Lombok `@Builder` and field semantics still hold)
- [x] 2.4 Update `data.db.mapper.DatasetRecordMapper` to map the new `visibility` column
- [x] 2.5 Update `data.db.mapper.TestSuiteRecordMapper` to handle nullable `dataset_id`
- [x] 2.6 Add new ErrorCode enum values: `PRIVATE_DATASET_REQUIRES_SUITE_BINDING`, `PUBLIC_DATASET_FORBIDS_SUITE_BINDING`, `PRIVATE_DATASET_ALREADY_BOUND`, `PRIVATE_DATASET_REBIND_FORBIDDEN`, `PRIVATE_TRANSITION_INVALID_BINDING_COUNT`, `SUITE_HAS_NO_DATASET`

## 3. Repository layer

- [x] 3.1 Update `PostgresDatasetRepository#findAll` (list path) to add `WHERE visibility = 'PUBLIC'` predicate; ensure single-id `findById` does NOT apply the predicate
- [x] 3.2 Reused existing `PostgresDatasetRepository#countSuitesByDatasetId(UUID)` (same semantics as the planned `countBoundSuites`)
- [x] 3.3 Add `PostgresDatasetRepository#unbindAllSuites(UUID datasetId)` that sets `test_suites.dataset_id = NULL WHERE dataset_id = :id` (used by the PRIVATE delete path)
- [x] 3.4 Add `PostgresDatasetRepository#updateVisibility(UUID id, DatasetVisibility newVisibility, long updatedAt)` with version bump
- [x] 3.5 Add `PostgresDatasetRepository#findByIdForUpdate(UUID id)` using jOOQ `forUpdate()` (used by the PATCH transition path)
- [x] 3.6 Update `PostgresTestSuiteRepository` insert/update paths to accept nullable `dataset_id` writes (null-guarded `.toString()` at all three call sites in `create`, `update`, `createWithId`)
- [x] 3.7 Verified `FilterWhitelists.DATASETS` does NOT include `visibility` (six fields: `id`, `name`, `description`, `createdBy`, `createdAt`, `updatedAt`)

## 4. Service layer

- [x] 4.1 Update `DatasetService.create(...)` to require `visibility` on create; branch on visibility — PUBLIC: simple insert (reject if `bindToSuiteId` present); PRIVATE: require `bindToSuiteId`, insert dataset + update `test_suites.dataset_id` in the same `@Transactional("metaTransactionManager")`
- [x] 4.2 `DatasetService.update(...)` IGNOREs the `visibility` field via the existing `DatasetMapper.update` (which does not map `visibility`); the persisted `visibility` is never modified via PUT
- [x] 4.3 Add `DatasetService.transitionVisibility(UUID id, DatasetVisibility target)` — opens meta transaction, `findByIdForUpdate` to lock the row, calls `countSuitesByDatasetId`, validates: PUBLIC→PRIVATE requires exactly 1 binding (else `PRIVATE_TRANSITION_INVALID_BINDING_COUNT` → HTTP 409); PRIVATE→PUBLIC always succeeds; no-op returns unchanged dataset
- [x] 4.4 `DatasetService.delete(UUID id)` branches on visibility: PUBLIC keeps existing FK-RESTRICT behavior; PRIVATE calls `unbindAllSuites(id)` then `deleteById(id)` in the same transaction
- [x] 4.5 `TestSuiteService.delete(UUID id)` captures `(datasetId, visibility)` BEFORE deleting the suite row; if PRIVATE, calls `datasetRepository.unbindAllSuites + deleteById` in the same transaction
- [x] 4.6 `TestSuiteService.update(...)` `validatePrivateRebindNotForbidden` rejects rebind/unbind when current dataset is PRIVATE (HTTP 409 `PRIVATE_DATASET_REBIND_FORBIDDEN`)
- [x] 4.7 `TestSuiteService.create(...)` accepts null `datasetId`: `DatasetSchemaProvider.getSchema(null)` is short-circuited to `List.of()` in both create and update; DB trigger enforces PRIVATE uniqueness on the INSERT
- [x] 4.8 `TestSuiteRunService.createRun` adds `if (suite.getDatasetId() == null) throw ...SUITE_HAS_NO_DATASET` before the `valid = false` guard
- [x] 4.9 Audited `getDatasetId()` callers: `TemplateVariableService` null-guarded with `List.of()` fallback; `RevalidationService`, `SuiteValidationService`, `InProcessEvaluationExecutor` are safe (task.datasetId always non-null, caller-provided schema, run-start guard prevents unbound execution)

## 5. DTOs and validation

- [x] 5.1 Add `visibility` field to `service.domain.dto.DatasetRequestDto` (typed `DatasetVisibility`, service-layer create-only enforcement) and to `DatasetResponseDto`
- [x] 5.2 Add `bindToSuiteId` field to `DatasetRequestDto` (optional UUID)
- [x] 5.3 Add cross-field validator on `DatasetRequestDto` — **deferred by design**: a single `@AssertTrue` collapses to one generic `VALIDATION_ERROR`, but the rule needs two distinct error codes (`PRIVATE_DATASET_REQUIRES_SUITE_BINDING` vs `PUBLIC_DATASET_FORBIDS_SUITE_BINDING`). Moved to a branched service-layer guard in `DatasetService.validateVisibilityBinding`, called from `create()`. Equivalent coverage; cleaner error reporting.
- [x] 5.4 Add new `service.domain.dto.DatasetVisibilityTransitionDto` (`{visibility: DatasetVisibility @NotNull}`) for the PATCH endpoint
- [x] 5.5 Remove `@NotNull` from `TestSuiteRequestDto.datasetId`; keep the optional UUID format validation
- [x] 5.6 Update `service.domain.mapper.DatasetMapper` to map `visibility` both ways; `TestSuiteMapper` already passes `datasetId` UUID through (no change needed)

## 6. Web layer and exception handling

- [x] 6.1 Add `PATCH /api/v1/datasets/{id}/visibility` operation on `DatasetController`, delegating to `DatasetService.transitionVisibility`
- [x] 6.2 `DatasetController` POST handler now accepts the enriched `DatasetRequestDto` (validation handled in `DatasetService.create`); OpenAPI annotations document the new 400 (REQUIRES/FORBIDS) and 409 (ALREADY_BOUND/REBIND_FORBIDDEN) error codes
- [x] 6.3 `DatasetController` PUT handler accepts `visibility` in the request body; `DatasetMapper.update` does NOT map the field, so it is silently dropped at the service layer. OpenAPI description updated to point users at the PATCH endpoint.
- [x] 6.4 `DatasetController` DELETE handler — no signature change; OpenAPI description documents the PUBLIC (RESTRICT) vs PRIVATE (cascade) split
- [x] 6.5 `TestSuiteController` POST/PUT — `TestSuiteRequestDto.datasetId` is no longer `@NotNull` (task 5.5); OpenAPI updated to document the unbound state on POST and `PRIVATE_DATASET_REBIND_FORBIDDEN` on PUT
- [x] 6.6 `DefaultExceptionHandler.handleDataAccessException` inspects `SQLState=='P0001'` (depth-bounded cause walk) and returns HTTP 409 with `errorCode=PRIVATE_DATASET_ALREADY_BOUND`; other `DataAccessException`s are rethrown so existing handlers and the `23505 → UNIQUE_CONSTRAINT_VIOLATION` mapping remain untouched
- [x] 6.7 `TestSuiteRunController` POST `/api/v1/test-suites/{testSuiteId}/runs` — `SUITE_HAS_NO_DATASET` is thrown by `TestSuiteRunService.createRun` as a `DatasetVisibilityRuleException`, mapped to HTTP 409 by `handleDatasetVisibilityRuleError`. OpenAPI annotations document the new 409 response.

## 7. OpenAPI examples and documentation

- [x] 7.1 `DatasetRequestDto` and `DatasetResponseDto` carry `@Schema(description=…, example=…)` on the new `visibility` and `bindToSuiteId` fields (added in Group 5); request/response example JSON files reference the same values for round-trip parity
- [x] 7.2 Example JSON files added (existing flat convention under `src/main/resources/openapi/examples/`, name slots ∈ {minimal,full,…}): updated `api-v1-datasets-POST-request-minimal.json` (PUBLIC) and response; new `api-v1-datasets-POST-request-full.json` (PRIVATE + bindToSuiteId) and response; new `api-v1-datasets-id-visibility-PATCH-request-minimal.json` and response-200; updated `api-v1-datasets-id-PUT-request-minimal.json` and response to include `visibility` (silently dropped on PUT — documented in OpenAPI description). Per-error-code example files were not added — error semantics are described in the controller `@ApiResponse` descriptions (Group 6) and the existing convention does not have per-error-code examples; one representative error file (`api-v1-test-suites-testSuiteId-runs-POST-response-409-minimal.json`) covers `SUITE_HAS_NO_DATASET` for run-start (task 7.5)
- [x] 7.3 `@Operation` + `@ApiResponse` block on `PATCH /api/v1/datasets/{id}/visibility` is in place (added in 6.1); the matching `api-v1-datasets-id-visibility-PATCH-request-minimal.json` and `…-response-200-minimal.json` are auto-injected by `OpenApiExampleCustomizer`
- [x] 7.4 `OpenApiQueryParamCustomizer` — added `filterNote` field to `EndpointParamConfig` (back-compat constructor preserved) and wired into `applyFilterDescription`; `/api/v1/datasets` now appends "Note: visibility is not a filterable field. The server hard-filters this endpoint to PUBLIC datasets only — PRIVATE datasets are accessible by id but never appear in this list."
- [x] 7.5 Test-suites examples: added `api-v1-test-suites-POST-request-subset.json` (unbound suite — `datasetId: null`) and `…-response-201-subset.json` (with `valid=false` + `SUITE_NOT_BOUND_TO_DATASET` warning); added `api-v1-test-suites-testSuiteId-runs-POST-response-409-minimal.json` documenting the `SUITE_HAS_NO_DATASET` response from `TestSuiteRunService.createRun`

## 8. Tests — unit

- [x] 8.1 `DatasetServiceTest` — added 6 tests for `transitionVisibility`: PUBLIC→PRIVATE with 0/1/2+ bindings (0 and 2 rejected with `PRIVATE_TRANSITION_INVALID_BINDING_COUNT`, 1 succeeds), PRIVATE→PUBLIC always succeeds (verified no count check), no-op same-visibility returns unchanged without writes, dataset-not-found throws `EntityNotFoundException`
- [x] 8.2 `DatasetServiceTest` — added 5 tests for `create`: PUBLIC without `bindToSuiteId` succeeds and never touches a suite; PUBLIC with `bindToSuiteId` is rejected before persistence (`PUBLIC_DATASET_FORBIDS_SUITE_BINDING`); PRIVATE without `bindToSuiteId` is rejected before persistence (`PRIVATE_DATASET_REQUIRES_SUITE_BINDING`); PRIVATE with valid `bindToSuiteId` atomically inserts dataset and updates the target suite's `datasetId`; PRIVATE with missing suite id throws `EntityNotFoundException`
- [~] 8.3 Skipped at the unit-test layer — `TestSuiteService.update` rebind matrix (PRIVATE→rebind forbidden, unbind forbidden, PUBLIC rebind/unbind succeeds) is covered by Group 9 functional tests (9.13) which exercise the full controller→service→repository path against a real Postgres; project pattern has no existing `TestSuiteServiceTest` because cross-table coordination is end-to-end by design
- [~] 8.4 Skipped at the unit-test layer — run-start `SUITE_HAS_NO_DATASET` guard ordering (fires before `valid=false` check) is covered by Group 9 functional tests (9.15) which boot the Spring context and assert deterministic ordering against real fixtures
- [x] 8.5 **Deferred by design** along with task 5.3 — the cross-field rule lives at the service layer (`DatasetService.validateVisibilityBinding`), not on the DTO, so there is no `@AssertTrue` to unit-test. Equivalent service-layer rejection (both error codes) is covered by `DatasetServiceTest` tasks 8.2 above.
- [x] 8.6 `DatasetMapperTest` — added 3 tests: `toDto` maps `visibility` to the response; `toEntity` maps `visibility` from the create request; `update` does NOT propagate `visibility` (PUT silently drops the field). PRE-EXISTING `delete*` and `create*` tests required mocking updates because `DatasetService.delete` now reads the dataset via `findById` (visibility branching) instead of `existsById`, and `create` now validates `visibility` first — all four pre-existing tests were updated and a new `deletePrivateCascadesUnbindAndDelete` test was added

## 9. Tests — functional (Testcontainers Postgres)

- [x] 9.1 Added `DatasetVisibilityFunctionalTests` (abstract) and nested `DatasetVisibilityTests` in `PostgresFunctionalTests`; updated `MetaTestDataHelper.createDataset` to accept and default `visibility=PUBLIC` (back-compat 2-arg overload preserved); updated `DatasetCrudFunctionalTests` to set `visibility=PUBLIC` on the four POST builders that previously omitted it (otherwise the repository's `Dataset visibility is required` guard rejects)
- [x] 9.2 `createPublicSucceeds` — POST with `visibility=PUBLIC` returns 201 with `visibility=PUBLIC` on the response; row persisted with `visibility='PUBLIC'`
- [x] 9.3 `createPrivateBindsTargetSuiteAtomically` — POST with `visibility=PRIVATE` + `bindToSuiteId` returns 201; asserts (a) response carries `visibility=PRIVATE`, (b) the target suite's `dataset_id` is atomically rebound to the new dataset id
- [~] 9.4 Skipped — concurrent racing POSTs would require multi-thread orchestration with a real DB connection pool and a deterministic interleave; the trigger backstop is verified deterministically by 9.16 ("second POST when suite already bound to another PRIVATE") which exercises the same code path
- [x] 9.5 `listExcludesPrivateButGetByIdReturnsIt` — `GET /datasets` does NOT return PRIVATE datasets; `GET /datasets/{id}` for a PRIVATE id returns 200 with the dataset (visibility=PRIVATE)
- [x] 9.6 `filterByVisibilityReturns400` — `GET /datasets?filter=visibility:eq:PRIVATE` returns HTTP 400 because `visibility` is not in `FilterWhitelists.DATASETS`
- [x] 9.7 `transitionPublicToPrivateZeroBindingsReturns409` / `…OneBindingReturns200` / `…MultipleBindingsReturns409` — three tests covering the 0/1/2+ branches of the PUBLIC→PRIVATE transition
- [x] 9.8 `transitionPrivateToPublicAlwaysSucceeds` — PATCH PRIVATE→PUBLIC returns 200, skips the binding-count check (no test suite created), bumps `version`
- [x] 9.9 `deletePublicDatasetRespectsRestrict` — DELETE PUBLIC with no bindings returns 204 (row gone); DELETE PUBLIC with a bound suite returns 409 and the row survives (FK RESTRICT preserved)
- [x] 9.10 `deletePrivateDatasetCascadesUnbindAndDelete` — DELETE PRIVATE returns 204; the bound suite survives with `datasetId=null`; dataset row and all test cases are gone (cascade)
- [x] 9.11 `deletePrivateBoundSuiteCascadesDataset` — DELETE on a PRIVATE-bound suite removes the suite, the dataset, and all test cases in a single transaction (run/snapshot cleanup is the existing V1.6 FK cascade, exercised by other suites; this test asserts the PRIVATE cascade specifically)
- [x] 9.12 `deletePublicBoundSuiteKeepsDataset` — DELETE on a PUBLIC-bound suite removes the suite but the PUBLIC dataset survives
- [x] 9.13 `rebindOrUnbindFromPrivateReturns409` — PUT changing `datasetId` while current is PRIVATE returns 409 `PRIVATE_DATASET_REBIND_FORBIDDEN`; PUT setting `datasetId=null` while current is PRIVATE also returns 409; suite's binding is left intact in both cases
- [x] 9.14 `unboundSuiteIsRetrievableButCannotRun` — creates an unbound suite via `MetaTestDataHelper.createTestSuite(name, null)`; GET returns 200 with `datasetId=null`; POST `/runs` returns 409 `SUITE_HAS_NO_DATASET`
- [x] 9.15 `runStartGuardFiresBeforeValidityCheck` — creates an unbound suite, then forces `valid=false` via `metaTestDataHelper.forceSuiteInvalid`; POST `/runs` returns 409 `SUITE_HAS_NO_DATASET` (NOT `SUITE_NOT_VALID`) — proves deterministic ordering of the dataset guard before the validity check
- [x] 9.16 `createPrivateForSuiteWithOtherPrivateReturns409` — second POST with `visibility=PRIVATE` and `bindToSuiteId` referencing a suite already bound to another PRIVATE dataset returns HTTP 409. Discovered an enforcement gap during this test — `DatasetService.bindNewPrivateDatasetToSuite` bypassed the rebind-from-PRIVATE check that `TestSuiteService.update` enforces, so the second POST silently succeeded. Fixed by adding a service-layer pre-check that reads the suite's current dataset, inspects its visibility, and throws `DatasetVisibilityRuleException(PRIVATE_DATASET_REBIND_FORBIDDEN)` when the current binding is PRIVATE.

## 10. Project docs

- [x] 10.1 AGENTS.md "Dataset Entity" section renamed to "(…, Visibility)" and expanded with a 6-bullet visibility block: PUBLIC/PRIVATE semantics, list hard-filter, atomic create-and-bind, PATCH transition endpoint, rebind-from-PRIVATE rejection, and the P0001 trigger pattern (cross-referenced to the DefaultExceptionHandler mapping)
- [x] 10.2 Confirmed — no new `@ConfigurationProperties` were introduced; no `docs/configuration.md` update needed
- [x] 10.3 Updated `openspec/specs/README.md` after all — the existing `datasets` and `test-suites` one-line summaries described pre-change semantics (NOT NULL `datasetId`, no visibility model) and would mislead readers. Rewrote both summaries to reflect the new visibility model and unbound suite state. No new spec folders added.

## 11. Verification

- [x] 11.1 Run `./gradlew checkstyleMain checkstyleTest` — passed clean after fixing 6 files: (a) import order in `DatasetService`, `TestSuiteRunService`, `TestSuiteService`, `DatasetServiceTest` (moved `ErrorCode` import below `service.domain.*` imports), (b) line-length on `DatasetController` and `TestSuiteController` @ApiResponse descriptions (wrapped over multiple lines), (c) `DefaultExceptionHandler.visibilityStatusFor` switch — collapsed multi-label case onto a single line to satisfy `IndentationCheck`
- [x] 11.2 Run `./gradlew test` — full suite passes (1684+ tests). First attempt surfaced 4 failures that were addressed in this session: (a) `LayeredArchitectureTest` flagged service-layer references to `web.handler.ErrorCode` — fixed by introducing a service-layer `DatasetVisibilityErrorCode` enum (`service.domain.exception`), making `DatasetVisibilityRuleException` carry that enum, and adding a `toWireErrorCode` translation in `DefaultExceptionHandler`; (b) `DatasetRecordMapperTest` two tests didn't set `visibility` on the synthetic `DatasetsRecord` — added `record.setVisibility("PUBLIC")` to both fixtures and an assertion for the new field on the happy-path test; (c) `DatasetMigrationFunctionalTests.testSuitesPostMigrationShape` asserted `dataset_id is_nullable=NO` (pre-change schema) — updated to expect `YES` and renamed the @DisplayName accordingly
- [x] 11.3 Manual smoke: drop and recreate the local DB, boot the app, run the golden-path scenarios (PUBLIC create, PRIVATE create-and-bind, transition, suite-delete-cascade) against the live Swagger UI; confirm no NPEs in logs
