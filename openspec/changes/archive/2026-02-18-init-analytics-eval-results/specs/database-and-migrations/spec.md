# Database and Migrations (Delta)

## Purpose
Delta spec for the `init-analytics-eval-results` change. Extends the database and migrations setup to support symmetric dual Flyway configuration, moves meta migrations to a dedicated subfolder, and adds startup validation.

Status: **Modified**

## MODIFIED Requirements

### Requirement: Apply schema changes via Flyway migrations
**Base:** The existing Flyway setup uses Spring Boot auto-config with a single migration path at `classpath:db/migration/${datasource.vendor}/`.

#### Scenario: Both Flyway instances manually configured
- **WHEN** the application starts
- **THEN** Spring Boot Flyway auto-config SHALL be disabled (`spring.flyway.enabled=false`). Both meta and analytics Flyway beans SHALL be manually configured using the same approach.

#### Scenario: Meta migrations moved to dedicated subfolder
- **WHEN** the application starts
- **THEN** the meta Flyway SHALL execute migrations from `classpath:db/migration/meta/${datasource.meta.vendor}/` (moved from `classpath:db/migration/${datasource.vendor}/` for symmetric layout with analytics)

#### Scenario: Analytics migration path
- **WHEN** the application starts with `datasource.analytics.vendor` configured
- **THEN** the analytics Flyway SHALL execute migrations from `classpath:db/migration/analytics/${datasource.analytics.vendor}/`

#### Scenario: Default migration history table for both
- **WHEN** both Flyway instances run
- **THEN** both SHALL use the default `flyway_schema_history` table name. Since meta and analytics always run in separate databases/schemas (enforced by startup validation), there is no conflict.

#### Scenario: Independent version numbering
- **WHEN** migrations are added to meta or analytics
- **THEN** each SHALL use its own version numbering independently. Both follow the same naming convention: `V<version>__<Description>.sql`.

### Requirement: Startup validation — meta and analytics must be different databases
The service SHALL validate at startup that meta and analytics datasources point to different databases.

#### Scenario: Different databases
- **WHEN** the application starts and meta and analytics JDBC URLs resolve to different databases
- **THEN** startup SHALL proceed normally

#### Scenario: Same database and same schema detected
- **WHEN** the application starts and meta and analytics JDBC URLs resolve to the same database AND the configured schemas are the same (or both default to `public`)
- **THEN** the application SHALL fail to start with a descriptive error message indicating that meta and analytics must use separate databases or separate schemas to avoid table name collisions and Flyway history conflicts. URL comparison SHALL parse the JDBC URL to extract host, port, and database name rather than comparing raw strings (syntactic normalization only — DNS-level equivalences like `localhost` vs `127.0.0.1` are NOT resolved).

#### Scenario: Same database with different schemas allowed
- **WHEN** the application starts and meta and analytics JDBC URLs resolve to the same database but `postgres.meta.datasource.schema` and `postgres.analytics.datasource.schema` are different
- **THEN** startup SHALL proceed normally. Flyway SHALL configure `defaultSchema` per datasource to isolate migration histories and tables.

### Requirement: Migration naming convention
**Base:** Both meta and analytics use the same naming convention.

#### Scenario: Symmetric directory layout
- **WHEN** a schema change is introduced
- **THEN** meta migrations SHALL be placed under `src/main/resources/db/migration/meta/POSTGRES/` and analytics migrations under `src/main/resources/db/migration/analytics/POSTGRES/` (or the appropriate vendor subdirectory)

#### Scenario: First analytics migration
- **WHEN** the `init-analytics-eval-results` change is applied
- **THEN** the first analytics migration SHALL be `V1.1__CreateTestCaseRunResultsTable.sql` creating the `test_case_run_results` table with composite primary key `(created_at_ms, id)` for future partitioning support

#### Scenario: No meta migration needed
- **WHEN** the `init-analytics-eval-results` change is applied
- **THEN** no meta migration is required. The existing `test_suite_runs.created_at_ms` column provides the timestamp used by all results for a given run (see design D8). No additional time range columns are needed.

## Implementation Notes
- Design reference: `design.md` decisions D1, D3.
- Disable Spring Boot Flyway auto-config: `spring.flyway.enabled=false` in `application.yml`
- Meta Flyway: Manually configured bean in `MetaFlywayConfiguration` using `metaDataSource`, migration path `classpath:db/migration/meta/${datasource.meta.vendor}/`
- Analytics Flyway: Manually configured bean in `AnalyticsFlywayConfiguration` using `analyticsDataSource`, migration path `classpath:db/migration/analytics/${datasource.analytics.vendor}/`
- **Startup validation ordering:** Implement validation in a dedicated `@Configuration` class (e.g., `DatasourceValidationConfiguration`) that produces a marker bean (e.g., `DatasourceValidationResult`) after all validations pass. Both Flyway `@Bean` methods SHALL declare this marker as a parameter, creating a hard bean dependency that guarantees validation completes before migration runs. Note: `@Import` alone does NOT guarantee `@PostConstruct` ordering — an explicit bean dependency is required.
- **Flyway settings preservation:** Both manually configured Flyway beans SHALL set `baselineOnMigrate(true)` and `validateMigrationNaming(true)` — these were previously handled by Spring Boot auto-config (`spring.flyway.*` in `application.yml`) and must not be lost when switching to manual configuration.
- Schema support: Configure `defaultSchema` on each Flyway instance per `postgres.*.datasource.schema` properties. When using same-database-different-schema, `flyway_schema_history` tables are isolated in their respective schemas.
- Existing meta migration files must be moved from `db/migration/POSTGRES/` to `db/migration/meta/POSTGRES/` (file contents unchanged, Flyway checksums preserved)
- Analytics migration `V1.1__CreateTestCaseRunResultsTable.sql` SHALL include composite PK `(created_at_ms, id)`, `UNIQUE (test_suite_run_id, test_case_id, run_index, created_at_ms)` constraint, and standalone index on `(id)` for efficient `findById` lookups. No separate `(created_at_ms DESC, id DESC)` index — covered by PK backward scan. Column comment on `created_at_ms`: "Run creation timestamp from meta DB — all results for a run share this value"
- No meta migration needed — the existing `test_suite_runs.created_at_ms` is reused as the timestamp for all results
