## 1. Database migrations (meta)

- [x] 1.1 Add Flyway migration to create `metric_declaration_versions` table (id, metric_declaration_id FK, schema_version, config_schema JSONB, input_schema JSONB, output_schema JSONB, description TEXT, created_at_ms), with index on metric_declaration_id and index on (metric_declaration_id, schema_version) for latest-version lookups
- [x] 1.2 Add Flyway migration to add `provider_id` VARCHAR(255) NOT NULL to `metric_declarations`, add UNIQUE(provider_id, name), and DELETE all existing rows (remove seeded stubs)

## 2. Data layer (models, mappers, repositories)

- [x] 2.1 Add MetricDeclarationVersion model and MetricDeclarationVersionRowMapper; add provider_id to MetricDeclaration model and update MetricDeclarationRowMapper
- [x] 2.2 Add MetricDeclarationVersionRepository interface and PostgresMetricDeclarationVersionRepository (save, findByMetricDeclarationIdOrderBySchemaVersionDesc for latest)
- [x] 2.3 Update MetricDeclarationRepository and PostgresMetricDeclarationRepository: add provider_id to all SQL and support findById, findByProviderIdAndName; add providerId to FilterWhitelists.METRIC_DECLARATIONS (STRING, EQ operator, maps to provider_id column) so the existing generic filter mechanism handles provider filtering; use meta transaction manager and TransactionTimestampContext

## 3. Configuration

- [x] 3.1 Add @ConfigurationProperties for metric providers (list of id, base-url, connect-timeout-ms, read-timeout-ms) and sync (enabled, cron or fixed-delay); add defaults in application.yml and document in docs/configuration.md

## 4. Metric provider client

- [x] 4.1 Add DTOs for provider API (MetricsResponse, MetricsDescription) matching GET /metrics contract
- [x] 4.2 Add metric provider RestClient factory or configuration that builds one RestClient per configured provider (baseUrl, timeouts); no user token propagation
- [x] 4.3 Add MetricProviderClient (or per-provider invoker) that calls GET /metrics and returns parsed DTOs; handle non-2xx and timeout by throwing so caller can log and continue

## 5. Sync service and job

- [x] 5.1 Add MetricProviderSyncService: syncOne(providerId, baseUrl/client) fetches GET /metrics, then in one meta transaction upserts MetricDeclarations by (provider_id, name) and inserts new MetricDeclarationVersion when config/input/output schema or description differ from latest; use structural JSON comparison (Jackson JsonNode.equals) for schema fields to avoid spurious versions from formatting differences
- [x] 5.2 Add MetricProviderSyncJob (or equivalent) that is @Async and listens for ApplicationReadyEvent to trigger the first after-startup sync; use @Scheduled (cron or fixed delay) for subsequent runs. Iterate configured providers, call syncOne per provider, catch exceptions per provider and log without failing the job
- [x] 5.3 Wire sync job to run only when sync.enabled is true and providers list is non-empty; ensure startup is not blocked by sync

## 6. MetricDeclaration service and API

- [x] 6.1 Update MetricDeclarationService and MetricDeclarationMapper for provider_id and latest-version description; providerId filtering is handled by the existing generic filter infrastructure (FilterWhitelists, no dedicated query param needed); ensure list returns only provider-synced declarations (no seeded data after migration)
- [x] 6.2 Update MetricDeclarationResponseDto to include providerId; optionally add latest version schemas (config_schema, input_schema, output_schema) per design/spec
- [x] 6.3 Update MetricDeclarationController: no dedicated providerId query parameter needed (use existing generic filter=providerId:eq:<id>); update OpenAPI description to mention providerId as a supported filter field; update examples; update docs/database-schema.md for new table and column
- [x] 6.4 Add GET /api/v1/metric-declarations/{id}/latest: response DTO for latest version (id, metric_declaration_id, schema_version, config_schema, input_schema, output_schema, description, createdAt); service method to resolve declaration by id and return latest version; controller endpoint returning 404 when declaration or latest version missing; OpenAPI and examples

## 7. Documentation and tests

- [x] 7.1 Update docs/database-schema.md with metric_declaration_versions table and metric_declarations.provider_id column
- [x] 7.2 Add functional tests for GET /api/v1/metric-declarations (empty catalog, with providerId filter after sync) and for GET /api/v1/metric-declarations/{id}/latest (success, 404 when declaration missing, 404 when no versions); add unit tests for MetricProviderSyncService (upsert and version creation logic)
- [x] 7.3 Add functional or integration test that sync job runs (e.g. with test provider or wire mock) and catalog is populated without blocking startup
