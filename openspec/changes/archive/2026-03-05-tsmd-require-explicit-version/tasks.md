## 1. Repository Layer

- [x] 1.1 Add `existsByIdAndMetricDeclarationId(UUID id, UUID metricDeclarationId)` method to `MetricDeclarationVersionRepository` interface
- [x] 1.2 Implement `existsByIdAndMetricDeclarationId` in `PostgresMetricDeclarationVersionRepository` with a `SELECT 1 ... WHERE id = :id AND metric_declaration_id = :metricDeclarationId` query

## 2. Request DTO

- [x] 2.1 Add `@NotNull UUID metricDeclarationVersionId` field to `TestSuiteMetricDefinitionRequestDto` with `@Schema` annotation (example UUID)

## 3. Service Layer

- [x] 3.1 Replace `resolveLatestVersion()` and `validateMetricDeclarationExists()` calls in `create()` with a single `validateVersionBelongsToDeclaration(dto.getMetricDeclarationVersionId(), dto.getMetricDeclarationId())` validation that calls `existsByIdAndMetricDeclarationId` and throws `EntityNotFoundException` on mismatch
- [x] 3.2 Replace `resolveLatestVersion()` and `validateMetricDeclarationExists()` calls in `update()` with the same `validateVersionBelongsToDeclaration` validation
- [x] 3.3 Remove the now-unused `resolveLatestVersion()` and `validateMetricDeclarationExists()` private methods from `TestSuiteMetricDefinitionService`
- [x] 3.4 Remove the `MetricDeclarationRepository` dependency from `TestSuiteMetricDefinitionService` if no other usage remains

## 4. Mapper Layer

- [x] 4.1 Update `TestSuiteMetricDefinitionMapper.toEntity()` to take `metricDeclarationVersionId` from the DTO instead of as a separate parameter
- [x] 4.2 Update `TestSuiteMetricDefinitionMapper.update()` similarly — take version ID from the DTO
- [x] 4.3 Update service call sites in `create()` and `update()` to pass the simplified mapper arguments

## 5. Tests

- [x] 5.1 Update functional tests for TSMD create to include `metricDeclarationVersionId` in request bodies
- [x] 5.2 Update functional tests for TSMD update to include `metricDeclarationVersionId` in request bodies
- [x] 5.3 Add functional test: create with version that doesn't belong to the declaration returns HTTP 404
- [x] 5.4 Add functional test: update with version that doesn't belong to the declaration returns HTTP 404
- [x] 5.5 Add functional test: create/update without `metricDeclarationVersionId` returns HTTP 400

## 6. OpenAPI

- [x] 6.1 Update OpenAPI request examples for TSMD create/update endpoints to include `metricDeclarationVersionId` field
