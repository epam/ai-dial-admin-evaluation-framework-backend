## 1. Clone primitive — thread visibility

- [x] 1.1 In `service/domain/DatasetCloneService.java`, add a `String description` parameter and a trailing `DatasetVisibility visibility` parameter to `cloneRowAndTestCases(...)`; use them in the `Dataset.builder().description(...).visibility(...)` instead of the internal `source.getDescription()` and the hardcoded `DatasetVisibility.PRIVATE`; update the method Javadoc to state description and visibility are caller-supplied (done: signature + builder + Javadoc updated, compiles).
- [x] 1.2 Update caller `service/domain/TestSuiteService.java` (`performDetachTransaction`) to pass `source.getDescription()` and `DatasetVisibility.PRIVATE` (done: call site updated, behavior unchanged).
- [x] 1.3 Update caller `service/domain/TestSuiteCloneService.java` (`executeDbWrites`) to pass `source.getDescription()` and `DatasetVisibility.PRIVATE` (done: call site updated, behavior unchanged).

## 2. Request DTO

- [x] 2.1 Create `service/domain/dto/DatasetCloneRequestDto.java` (`@Data @Builder @NoArgsConstructor @AllArgsConstructor`) with two optional fields: `name` annotated `@Size(max = ValidationConstants.MAX_DATASET_NAME_LENGTH)` (no `@NotBlank`) and `description` annotated `@Size(max = 2000)`, each with a `@Schema(example = "...")` (done: DTO compiles, null name + null description allowed).

## 3. Service orchestration

- [x] 3.1 In `service/domain/DatasetService.java`, inject the collaborators not already present: `DatasetCloneService` (`FileService`, `metaTransactionManager`, and `transactionTimestampContext` were already present — used the in-class `transactionTimestampContext` convention instead of a separate `Clock`) (done: field added via constructor injection, context still wires).
- [x] 3.2 Add `DatasetResponseDto clone(UUID id, DatasetCloneRequestDto dto, Jwt jwt)`: load source (→ `EntityNotFoundException`/404 if absent); resolve `newDatasetId`, `timestamp = clock.millis()`, `createdBy = authorResolver.getCreatedBy(jwt)`, `name` (explicit or `deriveCloneName`), and `description` (explicit or `source.getDescription()`) (done: method drafted, resolves inputs).
- [x] 3.3 Implement the pre-TX → TX flow mirroring `TestSuiteService.detachDataset`: pre-TX `datasetCloneService.copyDatasetFiles`; `TransactionTemplate(metaTransactionManager)` with `TRANSACTION_TIMESTAMP_KEY` bind/unbind guard running `cloneRowAndTestCases(source, newId, name, description, createdBy, timestamp, source.getVisibility())` (discard the id-map); `catch (DataIntegrityViolationException)` → `UniqueConstraintViolationDetector.rethrowIfUniqueViolation(...)` (409); best-effort `fileService.deleteAllByDatasetId(newDatasetId)` cleanup on TX failure; then load the new dataset and return `datasetMapper.toDto(...)` (done: flow implemented).

## 4. Controller + OpenAPI

- [x] 4.1 In `web/controller/DatasetController.java`, add `clone` handler: `@PostMapping("/{id}/clone")`, `@ResponseStatus(HttpStatus.CREATED)`, params `@PathVariable UUID id`, `@Valid @RequestBody DatasetCloneRequestDto dto`, `@AuthenticationPrincipal Jwt jwt`; return `ResponseEntity.status(CREATED).eTag(etag(result.getVersion())).body(result)` (done: endpoint mapped).
- [x] 4.2 Add OpenAPI annotations (`@Operation`, `@ApiResponse` for 201/400/404/409) and request examples (`@ExampleObject` minimal `{}` + full name/description) per the `openapi-examples` spec (done: annotations + examples present on the handler).

## 5. Tests

- [x] 5.1 Unit-test `DatasetService.clone` (use `Clock.fixed(...)`, mock `DatasetCloneService`/`FileService`/tx manager): name override vs auto-derive, description override vs source-inherit, visibility passed equals source visibility, `copyDatasetFiles` invoked before the TX write, file cleanup invoked on TX failure, 404 when source missing (done: `./gradlew test --tests "...DatasetServiceTest"` passes).
- [x] 5.2 Add functional tests: new `functional/tests/DatasetCloneFunctionalTests.java` (extends `BaseFunctionalTest`) + nested `class DatasetCloneTests extends DatasetCloneFunctionalTests {}` in `PostgresFunctionalTests`; use `MetaTestDataHelper` for fixtures and repositories for assertions. Cover: clone PUBLIC w/ test cases → 201 + new id + inherited visibility + copied cases + ETag; explicit name; derived name; explicit description; inherited description; collision "(clone 2)"; 404; (done: `./gradlew test --tests "...PostgresFunctionalTests\$DatasetCloneTests"` passes — boots the context, confirming the new `@Qualifier` wiring).
- [x] 5.3 Run the existing suite-clone/detach functional + unit tests to confirm the primitive signature change is behavior-preserving (done: `DatasetDetachFunctionalTests` and TestSuiteClone tests pass).

## 6. Quality gates & docs

- [x] 6.1 Run `./gradlew spotlessApply` then `./gradlew checkstyleMain checkstyleTest` (done: formatting + checkstyle clean).
- [x] 6.2 Updated `openspec/specs/README.md` per Spec Index Maintenance Policy — added the `dataset-clone` entry (done: index lists the new `dataset-clone` spec). The delta spec syncs to `openspec/specs/dataset-clone/spec.md` at archive time.
