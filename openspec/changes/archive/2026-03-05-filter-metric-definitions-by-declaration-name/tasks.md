## 1. Data Layer

- [x] 1.1 Add `metricDeclarationName` field to `TestSuiteMetricDefinition` model (`data.db.model`). (done: field exists, Lombok generates getter/setter/builder)
- [x] 1.2 Update `TestSuiteMetricDefinitionRowMapper` to extract `metric_declaration_name` from result set and populate the new field. (done: `rs.getString("metric_declaration_name")` mapped)
- [x] 1.3 Update all SELECT SQL in `PostgresTestSuiteMetricDefinitionRepository` — add `JOIN metric_declarations metric_declaration ON metric_definition.metric_declaration_id = metric_declaration.id`, alias main table as `metric_definition`, select `metric_declaration.name AS metric_declaration_name`, table-qualify all column references in SELECT, WHERE, COUNT. (done: `findAll`, `findById`, `findByIdAndTestSuiteId`, `count` queries all use JOIN + aliases)
- [x] 1.4 Update `FilterWhitelists.METRIC_DEFINITIONS` — change existing `name` column from `"name"` to `"metric_definition.name"`, add `"metricDeclarationName"` entry mapped to `"metric_declaration.name"` with STRING type and EQ/NE/CONTAINS operators. (done: whitelist has both entries)
- [x] 1.5 Update `SortWhitelists.METRIC_DEFINITIONS` — table-qualify column values: `"name"` → `"metric_definition.name"`, `"created_at_ms"` → `"metric_definition.created_at_ms"`. (done: sort columns qualified)

## 2. Service / DTO Layer

- [x] 2.1 Add `metricDeclarationName` field to `TestSuiteMetricDefinitionResponseDto` with `@Schema(example = "Accuracy")`. (done: field exists in DTO)
- [x] 2.2 Update `TestSuiteMetricDefinitionMapper.toDto()` to map `entity.getMetricDeclarationName()` to the response DTO. (done: field mapped)
- [x] 2.3 Update `TestSuiteMetricDefinitionService` create/update methods — after save/update, re-fetch the entity via `findByIdAndTestSuiteId` (which now JOINs) so the response includes `metricDeclarationName`. (done: create and update return populated field)

## 3. Functional Tests

- [x] 3.1 Add functional tests for filtering by `metricDeclarationName` (EQ, NE, CONTAINS) in `TestSuiteMetricDefinitionFunctionalTests`. (done: tests pass, verify correct filtering)
- [x] 3.2 Add/update functional tests verifying `metricDeclarationName` is present in GET, POST, PUT responses. (done: assertions on the new field in create/update/get/list responses)

## 4. Build Verification

- [x] 4.1 Run `./gradlew clean build` — all tests pass, checkstyle clean. (done: green build)
