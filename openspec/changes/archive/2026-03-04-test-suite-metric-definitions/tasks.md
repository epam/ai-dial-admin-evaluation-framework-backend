## 1. Database Migration

- [x] 1.1 Create Flyway migration `V1.13__CreateTestSuiteMetricDefinitionsTable.sql` in `db/migration/meta/POSTGRES/` — table `test_suite_metric_definitions` with columns: `id` VARCHAR(36) PK, `test_suite_id` VARCHAR(36) FK → test_suites ON DELETE CASCADE, `metric_declaration_id` VARCHAR(36) FK → metric_declarations, `metric_declaration_version_id` VARCHAR(36) FK → metric_declaration_versions, `name` VARCHAR(255) NOT NULL, `config_bindings` JSONB NOT NULL DEFAULT '[]', `input_bindings` JSONB NOT NULL DEFAULT '[]', `created_at_ms` BIGINT NOT NULL, `updated_at_ms` BIGINT NOT NULL. Add UNIQUE constraint on `(test_suite_id, LOWER(name))`, index on `test_suite_id`, index on `metric_declaration_id`.

## 2. Data Layer (Model, RowMapper, Repository)

- [x] 2.1 Create `TestSuiteMetricDefinition` model in `data.db.model` — fields: id (UUID), testSuiteId (UUID), metricDeclarationId (UUID), metricDeclarationVersionId (UUID), name (String), configBindings (String — raw JSONB), inputBindings (String — raw JSONB), createdAt (Long), updatedAt (Long)
- [x] 2.2 Create `TestSuiteMetricDefinitionRowMapper` in `data.db.mapper` — maps ResultSet to model, parsing UUID fields from VARCHAR, JSONB columns as String
- [x] 2.3 Create `TestSuiteMetricDefinitionRepository` interface in `data.db.repository` — methods: save, findById, findByIdAndTestSuiteId, findAll (paginated with filters/sorts), update, deleteById, deleteByTestSuiteId, count
- [x] 2.4 Create `PostgresTestSuiteMetricDefinitionRepository` implementation in `data.db.repository` — NamedParameterJdbcTemplate queries using text blocks, WhereBuilder for filtering, OrderByBuilder for sorting, TransactionTimestampContext for timestamps
- [x] 2.5 Add `METRIC_DEFINITIONS` filter whitelist entry in `FilterWhitelists` — field `name` with EQ, NE, CONTAINS operators
- [x] 2.6 Add `METRIC_DEFINITIONS` sort whitelist entry in `SortWhitelists` — fields: `name` → `name`, `createdAt` → `created_at_ms`; default sort: `createdAt,desc`

## 3. DTOs and Binding Types

- [x] 3.1 Create `MetricBindingSourceDto` as a polymorphic base with `@JsonTypeInfo(use = NAME, property = "$type")` and `@JsonSubTypes` for three source types
- [x] 3.2 Create `TestCaseBindingSourceDto` (`$type = "TestCase"`) with `columnName` (String, @NotBlank)
- [x] 3.3 Create `ResponseBindingSourceDto` (`$type = "Response"`) with `columnName` (String, @NotBlank)
- [x] 3.4 Create `ConstantBindingSourceDto` (`$type = "Constant"`) with `value` (Object, @NotNull — null excluded due to global NON_NULL Jackson serialization)
- [x] 3.5 Create `MetricParameterBindingDto` with `property` (String, @NotBlank) and `source` (@NotNull @Valid MetricBindingSourceDto)
- [x] 3.6 Create `TestSuiteMetricDefinitionRequestDto` — fields: `name` (@NotBlank, @Size max 255), `metricDeclarationId` (@NotNull UUID), `configBindings` (@Valid List), `inputBindings` (@Valid List). Add OpenAPI `@Schema` annotations.
- [x] 3.7 Create `TestSuiteMetricDefinitionResponseDto` — fields: id, testSuiteId, metricDeclarationId, metricDeclarationVersionId, name, configBindings (List of MetricParameterBindingDto), inputBindings (List of MetricParameterBindingDto), createdAt, updatedAt. Add OpenAPI `@Schema` annotations.

## 4. MapStruct Mapper and JSONB Conversion

- [x] 4.1 Create `TestSuiteMetricDefinitionMapper` (MapStruct interface) — map between request DTO ↔ model, model ↔ response DTO. Use `JsonbMapper` for converting `List<MetricParameterBindingDto>` ↔ String (JSONB). configBindings and inputBindings fields need custom mapping via JsonbMapper.
- [x] 4.2 Add serialization/deserialization methods in `JsonbMapper` for `List<MetricParameterBindingDto>` ↔ String (or extend existing pattern). Ensure `$type` discriminator is preserved through serialization round-trip.

## 5. Service Layer

- [x] 5.1 Create `TestSuiteMetricDefinitionService` in `service.domain` — inject repository, MetricDeclarationRepository, MetricDeclarationVersionRepository, TestSuiteRepository, mapper, Clock. Use `@Transactional("metaTransactionManager")` for writes, `readOnly = true` for reads.
- [x] 5.2 Implement `create` — validate test suite exists, validate metric declaration exists, resolve latest MetricDeclarationVersion (throw 404 if none), map DTO to model, save, return response DTO
- [x] 5.3 Implement `getById` — find by id and testSuiteId, throw 404 if not found, return response DTO
- [x] 5.4 Implement `list` — delegate to repository with page request, filters, sorts scoped to testSuiteId, return paginated response
- [x] 5.5 Implement `update` — find existing (404 if not found), validate metric declaration exists, re-resolve latest version, update fields, save, return response DTO
- [x] 5.6 Implement `delete` — find by id and testSuiteId (404 if not found), delete, return 204

## 6. Controller

- [x] 6.1 Create `TestSuiteMetricDefinitionController` in `web.controller` — inject service, PaginationParamResolver. Add `@Tag`, `@Validated`. Map to `/api/v1/test-suites/{testSuiteId}/metric-definitions`.
- [x] 6.2 Implement `POST` endpoint — `@Operation` annotations, `@Valid @RequestBody`, return 201 with created DTO
- [x] 6.3 Implement `GET /{id}` endpoint — return 200 with single DTO
- [x] 6.4 Implement `GET` (list) endpoint — `@Size(max=32)` on filter/sort params, PaginationParamResolver for page/size, return paginated response
- [x] 6.5 Implement `PUT /{id}` endpoint — `@Valid @RequestBody`, return 200 with updated DTO
- [x] 6.6 Implement `DELETE /{id}` endpoint — return 204
- [x] 6.7 Register TSMD endpoints in `OpenApiQueryParamCustomizer` for auto-generated filter/sort/pagination query param docs

## 7. Test Data Helpers

- [x] 7.1 Add TSMD helper methods to `MetaTestDataHelper` — `createTestSuiteMetricDefinition(testSuiteId, metricDeclarationId, name)` and cleanup support. Inject `TestSuiteMetricDefinitionRepository`.
- [x] 7.2 Add metric declaration + version fixture helpers to `MetaTestDataHelper` if not already present (needed as prerequisites for TSMD tests)

## 8. Functional Tests

- [x] 8.1 Create `TestSuiteMetricDefinitionFunctionalTests` — test class annotated with `@PostgresFunctionalTests`, inject `MetaTestDataHelper`
- [x] 8.2 Test: `shouldCreateMetricDefinition` — POST with valid body, assert 201, verify fields in response including resolved versionId
- [x] 8.3 Test: `shouldReturn404_whenTestSuiteNotFound` — POST with non-existent suiteId
- [x] 8.4 Test: `shouldReturn404_whenMetricDeclarationNotFound` — POST with non-existent metricDeclarationId
- [x] 8.5 Test: `shouldReturn409_whenDuplicateName` — POST with duplicate name (case-insensitive)
- [x] 8.6 Test: `shouldGetMetricDefinitionById` — GET by id, assert 200 with full response
- [x] 8.7 Test: `shouldReturn404_whenMetricDefinitionNotFound` — GET with non-existent id
- [x] 8.8 Test: `shouldListMetricDefinitions` — GET list with pagination, verify page structure
- [x] 8.9 Test: `shouldFilterByName` — GET list with `filter=name:contains:...`
- [x] 8.10 Test: `shouldSortByName` — GET list with `sort=name,asc`
- [x] 8.11 Test: `shouldUpdateMetricDefinition` — PUT with changed name/bindings, assert 200
- [x] 8.12 Test: `shouldReturn404_whenMetricDeclarationNotFoundOnUpdate` — PUT with non-existent metricDeclarationId
- [x] 8.13 Test: `shouldReturn404_whenMetricDeclarationHasNoVersionsOnUpdate` — PUT with metricDeclarationId that exists but has no versions
- [x] 8.14 Test: `shouldDeleteMetricDefinition` — DELETE, assert 204, verify removed
- [x] 8.15 Test: `shouldCascadeDeleteWithTestSuite` — delete parent suite, verify TSMDs are gone
- [x] 8.16 Test: `shouldPersistAndReturnBindings` — create with all three binding source types (TestCase, Response, Constant), verify round-trip

## 9. Documentation

- [x] 9.1 Update `docs/database-schema.md` — add `test_suite_metric_definitions` table section with columns, indexes, FK, JSONB schemas for config_bindings and input_bindings
- [x] 9.2 Update `docs/design/entity-relationship-model.md` — add TSMD entity to ER diagram and entity catalog, update relationships matrix
