## 1. Data Layer

- [x] 1.1 Create `AggregatedMetricDefinition` model in `data.db.model` with all fields from the 3-way JOIN (metric definition + declaration + declaration version) (done: model compiles, uses Lombok @Data/@Builder/@NoArgsConstructor/@AllArgsConstructor)
- [x] 1.2 Create `AggregatedMetricDefinitionRowMapper` in `data.db.mapper` mapping the 3-way JOIN result set to `AggregatedMetricDefinition` (done: row mapper compiles, maps all columns including aliased ones)
- [x] 1.3 Add `findAggregatedByIdAndTestSuiteId(UUID id, UUID testSuiteId)` to `TestSuiteMetricDefinitionRepository` interface returning `Optional<AggregatedMetricDefinition>` (done: interface compiles)
- [x] 1.4 Implement `findAggregatedByIdAndTestSuiteId` in `PostgresTestSuiteMetricDefinitionRepository` with the 3-way JOIN SQL query (done: implementation compiles, uses `AggregatedMetricDefinitionRowMapper`)

## 2. Service & Mapper Layer

- [x] 2.1 Create `AggregatedMetricDefinitionResponseDto` in `service.domain.dto` with top-level metric definition fields + nested `MetricDeclarationResponseDto` and `MetricDeclarationVersionResponseDto`, including OpenAPI `@Schema` annotations (done: DTO compiles)
- [x] 2.2 Add `toAggregatedDto(AggregatedMetricDefinition)` method to `TestSuiteMetricDefinitionMapper` composing the aggregated response DTO with nested objects (done: mapper compiles, uses `JsonbMapper` for bindings and schema conversion)
- [x] 2.3 Add `getAggregatedById(UUID testSuiteId, UUID id)` method to `TestSuiteMetricDefinitionService` with `@Transactional(value = "metaTransactionManager", readOnly = true)` (done: service method compiles, throws `EntityNotFoundException` when not found)

## 3. Web Layer

- [x] 3.1 Add `GET /{id}/aggregated` endpoint to `TestSuiteMetricDefinitionController` with OpenAPI annotations (`@Operation`, `@ApiResponse` for 200/404) (done: endpoint compiles, returns `AggregatedMetricDefinitionResponseDto`)
- [x] 3.2 Add OpenAPI example JSON file under `src/main/resources/openapi/examples/` for the aggregated response (done: example file exists)

## 4. Testing

- [x] 4.1 Add functional tests for the aggregated endpoint in `TestSuiteMetricDefinitionFunctionalTests`: successful retrieval, 404 for non-existent definition, 404 for wrong test suite (done: tests pass with `./gradlew test`)

## 5. Verification & Docs

- [x] 5.1 Run `./gradlew checkstyleMain checkstyleTest` and fix any violations (done: checkstyle passes)
- [x] 5.2 Run `./gradlew test` and fix any failures (done: all tests pass)
- [x] 5.3 Create new spec `openspec/specs/aggregated-metric-definition/spec.md` by syncing from the change's delta spec (done: main spec exists with status Implemented)
- [x] 5.4 Update `openspec/specs/README.md` per Spec Index Maintenance Policy (done: index reflects new `aggregated-metric-definition` spec)
