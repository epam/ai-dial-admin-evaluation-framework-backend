## 1. Data Layer

- [x] 1.1 Add `updateVisibilityAndMetadata(UUID id, DatasetVisibility visibility, String name, String description, long updatedAt)` to `DatasetRepository` interface with Javadoc (done: method declared in `data.db.repository.DatasetRepository`)
- [x] 1.2 Implement `updateVisibilityAndMetadata` in `PostgresDatasetRepository` using jOOQ typed DSL — single `UPDATE` setting `VISIBILITY`, `NAME`, `DESCRIPTION`, `VERSION + 1`, `UPDATED_AT_MS` where `ID = id` (done: method compiles, no SQL text blocks)

## 2. DTO

- [x] 2.1 Create `DatasetPublishRequestDto` in `service.domain.dto` — Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor`, optional `name` (`@Size(max = ValidationConstants.MAX_DATASET_NAME_LENGTH)`), optional `description` (`@Size(max = 2000)`), OpenAPI `@Schema` annotations on both fields (done: class exists, no magic numbers)

## 3. Service

- [x] 3.1 Add `publish(UUID id, DatasetPublishRequestDto dto)` to `DatasetService` — `@Transactional("metaTransactionManager")`, acquires `findByIdForUpdate`, computes effective name/description, no-op guard, calls `updateVisibilityAndMetadata`, catches `DataIntegrityViolationException` → 409, re-fetches and returns (done: method compiles, no-op path verified in unit test)

## 4. Controller

- [x] 4.1 Add `@PostMapping("/{id}/publish")` handler in `DatasetController` — `@LogExecution` at class level already present, `@Valid @RequestBody DatasetPublishRequestDto`, delegates to `datasetService.publish()`, returns `ResponseEntity.ok(result)`, OpenAPI `@Operation` summary and `@ExampleObject` for request body (done: endpoint reachable at `/api/v1/datasets/{id}/publish`)

## 5. Tests

- [x] 5.1 Add `shouldPublishPrivateDatasetWithoutMetadataUpdate` to `DatasetVisibilityFunctionalTests` — verifies `visibility=PUBLIC`, name unchanged, version bumped, DB state confirmed via `datasetRepository.findById` (done: test passes)
- [x] 5.2 Add `shouldPublishPrivateDatasetWithNameAndDescription` — verifies name/description updated atomically alongside visibility, version bumped (done: test passes)
- [x] 5.3 Add `shouldPublishAlreadyPublicDatasetIsNoOp` — verifies `version` unchanged on empty-body publish of already-PUBLIC dataset (done: test passes)
- [x] 5.4 Add `shouldPublishAlreadyPublicDatasetWithNewNameUpdatesMetadata` — verifies name updated and version bumped when only name changes on already-PUBLIC dataset (done: test passes)
- [x] 5.5 Add `shouldPublishWithDuplicateNameReturns409` — verifies `UNIQUE_CONSTRAINT_VIOLATION` error code (done: test passes)
- [x] 5.6 Add `shouldPublishNonExistentDatasetReturns404` — verifies `NOT_FOUND` error code (done: test passes)
- [x] 5.7 Run `./gradlew test --tests "com.epam.aidial.evaluation.functional.tests.*DatasetVisibilityFunctionalTests"` — all tests green (done: no failures)

## 6. Quality

- [x] 6.1 Run `./gradlew spotlessApply` then `./gradlew checkstyleMain checkstyleTest` — zero violations (done: build output clean)
- [x] 6.2 Update `openspec/specs/datasets/spec.md` by syncing the delta spec from this change (done: `## ADDED Requirements` block merged into main spec)
