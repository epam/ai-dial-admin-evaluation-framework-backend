# Tasks: Rename MetricDefinition to MetricDeclaration

## 1. Database

- [x] 1.1 Add Flyway migration V1.7__RenameMetricDefinitionsToMetricDeclarations.sql in meta POSTGRES: ALTER TABLE metric_definitions RENAME TO metric_declarations

## 2. Data layer

- [x] 2.1 Rename model class and file MetricDefinition to MetricDeclaration in data.db.model
- [x] 2.2 Rename MetricDefinitionRowMapper to MetricDeclarationRowMapper and map to MetricDeclaration
- [x] 2.3 Rename MetricDefinitionRepository and PostgresMetricDefinitionRepository to MetricDeclarationRepository and PostgresMetricDeclarationRepository; update all SQL to use table metric_declarations
- [x] 2.4 In FilterWhitelists rename METRIC_DEFINITIONS to METRIC_DECLARATIONS; in SortWhitelists rename METRIC_DEFINITIONS to METRIC_DECLARATIONS; update Postgres repository to use new constant names

## 3. Service layer

- [x] 3.1 Rename MetricDefinitionResponseDto to MetricDeclarationResponseDto (class and file)
- [x] 3.2 Rename MetricDefinitionMapper to MetricDeclarationMapper; map MetricDeclaration to MetricDeclarationResponseDto
- [x] 3.3 Rename MetricDefinitionService to MetricDeclarationService; use MetricDeclarationRepository, MetricDeclarationMapper, MetricDeclarationResponseDto; update log messages to MetricDeclaration

## 4. Web layer

- [x] 4.1 Rename MetricDefinitionController to MetricDeclarationController; set RequestMapping to /api/v1/metric-declarations; update Tag and all Operation/ApiResponse/Parameter text to metric declaration(s)
- [x] 4.2 Update controller to inject MetricDeclarationService and use MetricDeclarationResponseDto in method signatures and Schema

## 5. Tests

- [x] 5.1 Rename MetricDefinitionFunctionalTests to MetricDeclarationFunctionalTests; replace MetricDefinitionResponseDto with MetricDeclarationResponseDto and all URLs metric-definitions with metric-declarations; update DisplayName
- [x] 5.2 In PostgresFunctionalTests rename inner class MetricDefinitionTests to MetricDeclarationTests extending MetricDeclarationFunctionalTests
- [x] 5.3 In NoSecurityStartupSmokeTest and OidcSecurityStartupSmokeTest change apiUrl from metric-definitions to metric-declarations

## 6. OpenAPI examples

- [x] 6.1 Rename the four example JSON files under src/main/resources/openapi/examples from api-v1-metric-definitions-* to api-v1-metric-declarations-* (GET response minimal/full, id-GET response minimal/full)

## 7. Documentation

- [x] 7.1 Update docs/database-schema.md: rename section to Table metric_declarations, update description to Metric declarations catalog; update table list at top if it references metric_definitions
- [x] 7.2 Sync delta specs to main specs: update openspec/specs/metrics-system/spec.md, entity-filtering/spec.md, test-cases/spec.md with wording and URLs from change specs; update openspec/specs/README.md metrics summary if it says metric definitions
