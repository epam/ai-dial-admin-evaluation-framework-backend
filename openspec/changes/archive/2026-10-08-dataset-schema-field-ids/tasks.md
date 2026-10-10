## 1. Field id model and backfill

- [x] 1.1 Add nullable `String id` with the OpenAPI `@Schema` description from design D1, and `@Builder(toBuilder = true)`, to `evaluation-runner-core/.../runner/dto/FieldDefinitionDto.java`; verify `./gradlew :evaluation-runner-core:test` and `./gradlew compileJava compileTestJava` pass
- [x] 1.2 Add `src/main/resources/db/migration/meta/POSTGRES/V1.36__AddIdsToDatasetSchemaFields.sql` per design D8 (fills missing ids, preserves order, idempotent, no version bump); verify with a functional test that seeds schemas through `MetaTestDataHelper` (fields without `id`, with `"id": null`, with an existing id; plus a non-array schema value), runs the migration script through a helper method that executes the classpath SQL file (no raw SQL in the test body), and asserts every field has a string `id`, order is preserved, existing ids are untouched, the non-array row is unchanged, and a second run changes nothing
- [x] 1.3 Document the `id` key of the `datasets.test_case_schema` JSONB shape and V1.36 in `docs/database-schema.md`; verify the doc lists `id` alongside `name`/`type`/`perTurn`

## 2. Schema field id resolution

- [x] 2.1 Create `service.domain.DatasetSchemaFieldIdResolver` (`@Component`, `@LogExecution`) and the `SchemaFieldResolution` record with `resolveForCreate` / `resolveForUpdate` per design D2 (400 via `ValidationException` for id-on-create, unknown id, duplicate id, case-insensitive duplicate name; claims over current fields — explicit ids first, then exact-name fallback onto unclaimed fields, including stored fields without an id; fresh UUIDs; returns copies, never mutates input); verify it compiles
- [x] 2.2 Add `DatasetSchemaFieldIdResolverTest` covering: create assigns ids / rejects ids; update keeps ids; name fallback; claimed-id guard (`[{id:A,name:b},{name:a}]`); rename detection; swap (`a↔b`) and chain (`a→b,b→c`) produce the right `renames`; removed names exclude renamed fields; stored id-less field matched by name gets an id and is not removed; unknown id, duplicate id, duplicate name (`x`/`X`) → `ValidationException`; verify `./gradlew :test --tests "*.DatasetSchemaFieldIdResolverTest"` passes

## 3. Rename data move

- [x] 3.1 Add `renameDataFields(UUID datasetId, Map<String, String> renames)` to `TestCaseRepository` and implement it in `PostgresTestCaseRepository` per design D5 (single UPDATE restricted to rows holding an old key, bound array params, non-shadowing aliases, simultaneous mapping from the original object, `data` + element-wise `multi_turn_data` under the same shape guard as `removeDataFields`, no-op on empty map); verify it compiles and `JooqSchemaDriftTest` / `JdbcTemplateFenceTest` still pass
- [x] 3.2 Add pass-through `TestCaseService.renameDataFields(UUID, Map<String, String>)` (meta transaction, empty-map short-circuit); verify via `TestCaseServiceTest`
- [x] 3.3 Add a functional repository test for `renameDataFields`: plain rename, swap, chain, per-turn values in every turn, explicit JSON `null` value preserved, absent key stays absent, non-array / non-object `multi_turn_data` shapes untouched, rows without any old key not rewritten, other datasets untouched; verify it passes

## 4. Dataset create/update

- [x] 4.1 Add `DATASET_FIELD_RENAME_FORBIDDEN` to `DatasetVisibilityErrorCode` and wire `ErrorCode`, and map it to HTTP 409 in `DefaultExceptionHandler.visibilityStatusFor` / `toWireErrorCode`; verify compile and an existing handler unit test (or a new case) asserts 409 + wire code
- [x] 4.2 Wire `DatasetSchemaFieldIdResolver` into `DatasetService.create` (reject ids, assign fresh ids before mapping); verify `DatasetServiceTest` create cases, including a new "id on create → 400" case
- [x] 4.3 Rework `DatasetService.update` per design D3 (no explicit lock): version check; resolve ids; `schemaChanged` on the resolved schema; save; rename guard **after** the save (`PUBLIC` + `testSuiteService.countReferencingDataset > 0` → `DATASET_FIELD_RENAME_FORBIDDEN`); prune `removedNames` then `renameDataFields`; revalidation when changed. Remove `computeRemovedFields`. Add `getByIdForUpdate(UUID)` (meta tx, `MANDATORY` propagation) per design D6. Verify `DatasetServiceTest` updated/extended: guard rejects only with renames on bound PUBLIC and runs after save, prune-then-rename order, id-less unchanged schema is not a change, `getByIdForUpdate` uses `findByIdForUpdate`
- [x] 4.4 Add dataset functional tests in `PostgresFunctionalTests`: rename keeps data (`column2→column3`); swap; rename + type change coerced after PUT; rename + `perTurn` flip leaves value in place and the case reports misplacement; PRIVATE rename refreshes its suite's `isValid` before the PUT returns; bound PUBLIC rename → 409 with nothing changed; unbound PUBLIC rename succeeds; unknown id / duplicate id / duplicate name / id on create → 400; id-less client update keeps ids; two concurrent schema PUTs with the same version → one succeeds, one 409 `VERSION_CONFLICT`; a run snapshot's `testCaseSchema` carries the field ids; verify the nested test class passes
- [x] 4.5 Update dataset OpenAPI examples (`@Schema` examples / `src/main/resources/openapi/examples/` dataset request/response files) to show `id` on response schema fields and on update requests, and document the new 409 on `PUT /api/v1/datasets/{id}`; verify `/v3/api-docs` contains the updated examples (existing OpenAPI example test, or extend it)

## 5. CSV/ZIP import through DatasetService

- [x] 5.1 Replace `DatasetRepository` and `DatasetSchemaProvider` in `CsvImportService` with `DatasetService`: `getByIdForUpdate` before any test-case write, schema persisted via `update(datasetId, request, lockedVersion)` built from the locked read per design D6; verify compile and no remaining `datasetRepository` / `datasetSchemaProvider` references in the class
- [x] 5.2 Move the schema persist after `fixupTestCases` and remove the explicit `revalidationService.startDatasetRevalidation` call (and the `RevalidationService` dependency if now unused); verify `CsvImportServiceTest` (adjusted mocks) asserts one `datasetService.update` call on schema-changing imports and none on APPEND-with-existing-schema / MERGE-without-new-fields
- [x] 5.3 Strip field ids in `ZipManifestSerializer` on both `write` and `read` per design D7; verify `ZipManifestSerializerTest` cases: exported manifest has no `id`, parsed manifest fields have `id == null`
- [x] 5.4 Add/extend import functional tests: OVERRIDE keeps ids of surviving columns and drops the rest; MERGE keeps existing ids and adds new ones; OVERRIDE into a bound PUBLIC dataset dropping a column succeeds (no 409); exactly one revalidation task per schema-changing import and none for an OVERRIDE reproducing the current schema; a PUT carrying the pre-import version after the import → 409 `VERSION_CONFLICT`; MERGE after a committed rename keeps the renamed field and its id; ZIP import of a manifest carrying foreign ids succeeds; ZIP export manifest has no ids; verify the nested test classes pass

## 6. Revalidation sync cleanup

- [x] 6.1 Remove the ineffective `@Async` from `RevalidationService`, rename `runDatasetRevalidationAsync` → `runDatasetRevalidation`, correct javadocs (`RevalidationService`, `SuiteValidationService`); verified: compile + `RevalidationServiceTest` + `DatasetServiceTest` pass

## 7. Docs and final verification

- [x] 7.1 Add a "Field identity and rename" section to `docs/patterns/dataset-entity.md` (id is per-schema, name stays the key, rename guard, prune-then-rename, imports resolve by name, manifests strip ids); verify the section exists and is linked from the doc's table of contents if it has one
- [x] 7.2 Run `./gradlew spotlessApply checkstyleMain checkstyleTest` and the full `./gradlew build`; verify both succeed
