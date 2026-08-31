# Configuration

This document is the operator-facing reference for every configurable property of the AI DIAL Admin Evaluation Framework Backend. Every property table in this document uses the same six-column schema — `Property | Environment Variable | Default | Required | Applied when | Description` — and every `Required` value is drawn from a fixed four-term vocabulary (`Yes`, `No`, `Conditional`, `Recommended`).

## Table of Contents

1. [Overview](#1-overview)
2. [Spring Framework Configuration](#2-spring-framework-configuration)
   - [Server](#21-server)
   - [Actuator](#22-actuator)
   - [OpenAPI](#23-openapi)
   - [Logging](#24-logging)
3. [Security](#3-security)
   - [Mode](#31-mode)
   - [Identity Providers](#32-identity-providers)
   - [JWT Claim Resolution](#33-jwt-claim-resolution)
   - [DIAL API-Key Authentication](#34-dial-api-key-authentication)
4. [Data Layer](#4-data-layer)
   - [Meta Datasource](#41-meta-datasource)
   - [Analytics Datasource](#42-analytics-datasource)
   - [Azure AD Authentication](#43-azure-ad-authentication)
   - [Flyway](#44-flyway)
   - [Datasource Validation](#45-datasource-validation)
5. [DIAL Integration](#5-dial-integration)
   - [DIAL Core Client](#51-dial-core-client)
   - [DIAL API Key](#52-dial-api-key)
   - [DIAL File Storage](#53-dial-file-storage)
   - [DIAL MCP Client](#54-dial-mcp-client)
   - [DIAL ADAS Client](#55-dial-adas-client)
6. [Evaluation Engine](#6-evaluation-engine)
   - [Test Suite Run — Executor](#61-test-suite-run--executor)
   - [Test Suite Run — SSE](#62-test-suite-run--sse)
   - [Test Suite Run — Execution Settings](#63-test-suite-run--execution-settings)
   - [Test Suite Run — Retry Settings](#64-test-suite-run--retry-settings)
   - [Test Suite Run — Run Config](#65-test-suite-run--run-config)
   - [Test Suite Run — Concurrency Limits](#66-test-suite-run--concurrency-limits)
   - [Test Suite Run — Run Inputs](#67-test-suite-run--run-inputs)
   - [Analytics Results Batch Write](#68-analytics-results-batch-write)
   - [Analytics Eval Summaries Batch Write](#69-analytics-eval-summaries-batch-write)
   - [Metric Providers](#610-metric-providers)
   - [Metric Evaluation](#611-metric-evaluation)
   - [SSE Event Processing](#612-sse-event-processing)
   - [Analytics Run Comparison](#613-analytics-run-comparison)
   - [JSONata Evaluation](#614-jsonata-evaluation)
7. [Data Management](#7-data-management)
   - [Pagination](#71-pagination)
   - [CSV Export](#72-csv-export)
   - [CSV Import](#73-csv-import)
   - [Validation](#74-validation)
   - [Test Case Batch](#75-test-case-batch)
8. [Observability](#8-observability)
   - [Grafana Integration](#81-grafana-integration)
9. [Notes](#9-notes)

---

## 1. Overview

### Configuration precedence

Spring Boot resolves each property in this order, first match wins:

1. **Environment variables** — convert the property key to uppercase and replace `.` and `-` with `_`. Example: `postgres.meta.datasource.url` → `POSTGRES_META_DATASOURCE_URL`, `dial.file-storage.bucket-alias` → `DIAL_FILE_STORAGE_BUCKET_ALIAS`.
2. **Values in `application.yml`** (including profile-specific overrides such as `application-dev.yml`).
3. **Defaults declared in `application.yml`** via `${ENV_NAME:default}` expressions.

The `Environment Variable` column below records either the trivial uppercase-dot-to-underscore conversion or the deliberately aliased name the application binds to (e.g. `dial.api-key` → `DIAL_EF_API_KEY`).

### Startup validation

Every `@ConfigurationProperties` class is validated at startup using Bean Validation annotations (`@NotNull`, `@NotBlank`, `@Min`, `@Max`, `@Valid`, …). Invalid values cause the application to fail fast with a clear error message — an operator sees the misconfiguration at boot, not in production traffic.

### Governance

The structure of this document — the six-column schema, the four-term `Required` vocabulary, and the nine top-level groups — is codified in the `configuration-docs` spec: [`openspec/specs/configuration-docs/spec.md`](../openspec/specs/configuration-docs/spec.md). Every new configuration property must update this document with a compliant row in the same PR.

---

## 2. Spring Framework Configuration

### 2.1 Server

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `server.port` | `SERVER_PORT` | `8080` | No | - | HTTP port the application listens on. |
| `server.tomcat.accesslog.enabled` | `SERVER_TOMCAT_ACCESSLOG_ENABLED` | `true` | No | - | Enables Tomcat access logging. |
| `server.tomcat.accesslog.pattern` | `SERVER_TOMCAT_ACCESSLOG_PATTERN` | `%{X-Correlation-Id}i %h %l %u %t "%r" %s %b %D` | No | - | Tomcat access log format. Correlation ID is captured first for log correlation with application logs. |
| `server.tomcat.max-http-post-size` | `SERVER_TOMCAT_MAX_HTTP_POST_SIZE` | `10485760` | No | - | Tomcat-level cap on POST body size in bytes (10 MB). Also enforced by application-level `analytics.*.batch.max-request-size-bytes`. |

### 2.2 Actuator

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `management.endpoints.web.exposure.include` | `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health,info,prometheus,metrics` | No | - | Comma-separated list of Spring Boot Actuator endpoints exposed over HTTP. |
| `management.endpoint.health.show-details` | `MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS` | `when_authorized` | No | - | Visibility of detailed health data (`never`, `when_authorized`, `always`). |

**Available endpoints** (when exposed): `GET /actuator/health` (application readiness — includes `dialFileStorage`, `database`, `analyticsDatabase` health indicators), `GET /actuator/info`, `GET /actuator/prometheus`, `GET /actuator/metrics`.

### 2.3 OpenAPI

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `springdoc.api-docs.enabled` | `SPRINGDOC_API_DOCS_ENABLED` | `true` | No | - | Enables OpenAPI document generation. |
| `springdoc.api-docs.path` | `SPRINGDOC_API_DOCS_PATH` | `/v3/api-docs` | No | - | HTTP path serving the generated OpenAPI document. |
| `springdoc.swagger-ui.enabled` | `SPRINGDOC_SWAGGER_UI_ENABLED` | `true` | No | - | Enables the Swagger UI console. |
| `springdoc.swagger-ui.path` | `SPRINGDOC_SWAGGER_UI_PATH` | `/swagger-ui.html` | No | - | HTTP path where Swagger UI is served. |

Swagger UI is available at `http://<host>:<server.port>/swagger-ui.html` when enabled.

### 2.4 Logging

#### 2.4.1 General

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `logging.level.root` | `LOGGING_LEVEL_ROOT` | `INFO` | No | - | Root logger level. |
| `logging.level.com.epam.aidial` | `LOGGING_LEVEL_COM_EPAM_AIDIAL` | `INFO` | No | - | Application package logger level. Profile `dev` raises this to `TRACE`. |
| `logging.request-response.enabled` | `LOGGING_REQUEST_RESPONSE_ENABLED` | `false` | No | - | When `true`, every HTTP request/response is logged with body. Profile `dev` enables this by default. |

#### 2.4.2 Dynamic Log Level

The application can adjust log levels at runtime by re-reading a JSON file on disk — no restart required.

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `logger.configuration.interval` | `LOGGER_CONFIGURATION_INTERVAL` | `30` | No | - | Poll interval in seconds for re-reading the logger configuration file. |
| `logger.configuration.path` | `LOGGER_CONFIGURATION_PATH` | `/app/log-config/logging.levels.json` | No | - | Absolute path to the logger configuration file. |

Example `logging.levels.json`:

```json
{
  "loggerLevels": {
    "com.epam.aidial.evaluation": {
      "defaultLevel": "DEBUG",
      "configuredLevel": "TRACE",
      "validTill": 1735689600000
    }
  }
}
```

`configuredLevel` is applied until `validTill` (epoch millis); after that the logger reverts to `defaultLevel`.

#### 2.4.3 Correlation ID

Every HTTP request is assigned a correlation ID that appears in access logs, application logs, and the response headers. If the inbound request carries a valid `X-Correlation-Id` header (16–32 alphanumeric characters) it is reused; otherwise a new ID is generated from the OpenTelemetry trace ID when present, or randomly when not.

---

## 3. Security

### 3.1 Mode

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `config.rest.security.mode` | `CONFIG_REST_SECURITY_MODE` | `oidc` | No | - | Security mode. `oidc` enforces JWT authentication; `none` disables authentication (local development and functional tests only). |
| `config.rest.security.disable-swagger-authorization` | `CONFIG_REST_SECURITY_DISABLE_SWAGGER_AUTHORIZATION` | `true` | No | - | When `true`, Swagger UI is reachable without a JWT even in `oidc` mode. |
| `config.rest.security.default.allowedRoles` | `CONFIG_REST_SECURITY_DEFAULT_ALLOWEDROLES` | `admin` | No | - | Default role(s) allowed to call secured endpoints when a provider does not supply its own `allowedRoles`. |

### 3.2 Identity Providers

Identity providers are configured under `providers.<id>.*` as a map keyed by provider id. Each entry defines how one OIDC issuer's JWTs are validated.

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `providers.<id>.issuer` | `PROVIDERS_<ID>_ISSUER` | - | Yes | `config.rest.security.mode=oidc` | Expected `iss` claim value. Tenant id or full issuer URL. |
| `providers.<id>.jwkSetUri` | `PROVIDERS_<ID>_JWKSETURI` | - | Yes | `config.rest.security.mode=oidc` | JWKS endpoint used to verify token signatures. |
| `providers.<id>.audiences` | `PROVIDERS_<ID>_AUDIENCES` | - | Yes | `config.rest.security.mode=oidc` | Accepted `aud` claim values. |
| `providers.<id>.roleClaims` | `PROVIDERS_<ID>_ROLECLAIMS` | - | No | `config.rest.security.mode=oidc` | JWT claim names to look up role values in (first non-empty wins). |
| `providers.<id>.principalClaim` | `PROVIDERS_<ID>_PRINCIPALCLAIM` | - | Yes | `config.rest.security.mode=oidc` | JWT claim used for the authenticated principal name. |
| `providers.<id>.aliases` | `PROVIDERS_<ID>_ALIASES` | - | No | `config.rest.security.mode=oidc` | Alternate `iss` values also accepted for this provider (e.g. Azure tenant aliases). |
| `providers.<id>.allowedRoles` | `PROVIDERS_<ID>_ALLOWEDROLES` | - | No | `config.rest.security.mode=oidc` | Provider-scoped override of `config.rest.security.default.allowedRoles`. |

Example provider entry:

```yaml
providers:
  azure:
    issuer: <tenant-id>                       # or full issuer URL
    jwkSetUri: https://login.microsoftonline.com/<tenant-id>/discovery/v2.0/keys
    audiences:
      - <client-id>
    roleClaims:
      - roles
    principalClaim: sub
    aliases:
      - login.microsoftonline.com
      - sts.windows.net
    allowedRoles:
      - admin
```

### 3.3 JWT Claim Resolution

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `security.jwt.user-claim` | `SECURITY_JWT_USER_CLAIM` | `sub` | No | `config.rest.security.mode=oidc` | JWT claim used by `AuthorResolver` to populate `createdBy`/`updatedBy` on domain entities. Falls back to `"anonymous"` when `config.rest.security.mode=none`. |

### 3.4 DIAL API-Key Authentication

An alternative to OIDC/JWT bearer tokens: a caller may authenticate with an `Api-Key` header instead, validated by delegating to DIAL Core's `GET /v1/user/info`. Only active when `config.rest.security.mode=oidc`.

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `config.rest.security.api-key.enabled` | `API_KEY_ENABLED` | `false` | No | `config.rest.security.mode=oidc` | Enables DIAL API-Key authentication via the `Api-Key` request header. |
| `config.rest.security.api-key.core-url` | `API_KEY_CORE_URL` | - | Yes | `config.rest.security.api-key.enabled=true` | Base URL of the DIAL Core instance used to introspect API keys via `GET /v1/user/info`. |
| `config.rest.security.api-key.cache-ttl-seconds` | `API_KEY_CACHE_TTL_SECONDS` | `60` | No | `config.rest.security.api-key.enabled=true` | Time-to-live, in seconds, for cached successful introspection results. |
| `config.rest.security.api-key.cache-max-size` | `API_KEY_CACHE_MAX_SIZE` | `10000` | No | `config.rest.security.api-key.enabled=true` | Maximum number of cached introspection results. |
| `config.rest.security.api-key.request-timeout-ms` | `API_KEY_REQUEST_TIMEOUT_MS` | `3000` | No | `config.rest.security.api-key.enabled=true` | Connect/read timeout, in milliseconds, for the introspection call to DIAL Core. |
| `config.rest.security.api-key.roles-mapping` | `API_KEY_ROLES_MAPPING` | - (empty) | Conditional | `config.rest.security.api-key.enabled=true` | JSON object mapping DIAL Core project-key role names to lists of this service's authority strings. Required (with `default-roles-mapping`) that at least one of the two mappings is non-empty. |
| `config.rest.security.api-key.default-roles-mapping` | `API_KEY_DEFAULT_ROLES_MAPPING` | - (empty) | Conditional | `config.rest.security.api-key.enabled=true` | JSON object mapping DIAL Core roles from the JWT-rooted per-request-key response shape (`userClaims`) to lists of this service's authority strings. |
| `config.rest.security.api-key.user-claims-role-claim` | `API_KEY_USER_CLAIMS_ROLE_CLAIM` | `roles` | No | `config.rest.security.api-key.enabled=true` | Claim name read out of the introspection response's `userClaims` object to obtain the caller's raw roles. |
| `config.rest.security.api-key.startup-probe` | `API_KEY_STARTUP_PROBE` | `true` | No | `config.rest.security.api-key.enabled=true` | When `true`, the service calls DIAL Core's `/v1/user/info` at startup to verify connectivity and fails to start if Core is unreachable or misconfigured. |

---

## 4. Data Layer

The application uses a **dual datasource architecture**: a **meta** database for domain entities (test suites, test cases, runs) and an **analytics** database for test case execution results and metric summaries.

### 4.1 Meta Datasource

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `datasource.meta.vendor` | `DATASOURCE_META_VENDOR` | `POSTGRES` | No | - | Meta database vendor. Only `POSTGRES` is currently supported. |
| `datasource.meta.auth.type` | `DATASOURCE_META_AUTH_TYPE` | `basic` | No | - | Meta authentication strategy. `basic` uses the username/password pair; `azure` obtains short-lived Azure AD tokens. |
| `postgres.meta.datasource.url` | `POSTGRES_META_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/evaluation_db` | Recommended | - | Meta JDBC base URL without query parameters. Override in every non-local environment. |
| `postgres.meta.datasource.connection-params` | `POSTGRES_META_DATASOURCE_CONNECTION_PARAMS` | `-` | No | - | JDBC query parameters appended to the URL (e.g. `reWriteBatchedInserts=true`). Empty by default. |
| `postgres.meta.datasource.driver-class-name` | `POSTGRES_META_DATASOURCE_DRIVER_CLASS_NAME` | `org.postgresql.Driver` | No | - | JDBC driver class. |
| `postgres.meta.datasource.username` | `POSTGRES_META_DATASOURCE_USERNAME` | `postgres` | Conditional | `datasource.meta.auth.type=azure` | Database username. For `basic` auth the default is safe for local development; for `azure` auth this MUST be the Azure AD identity username. |
| `postgres.meta.datasource.password` | `POSTGRES_META_DATASOURCE_PASSWORD` | `postgres` | Recommended | `datasource.meta.auth.type=basic` | Database password. The default is intended for local development only. Override via environment variable in every non-local environment. Unused when `datasource.meta.auth.type=azure`. |
| `postgres.meta.datasource.schema` | `POSTGRES_META_DATASOURCE_SCHEMA` | `public` | No | - | Database schema for meta entities and Flyway migrations. |

### 4.2 Analytics Datasource

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `datasource.analytics.vendor` | `DATASOURCE_ANALYTICS_VENDOR` | `POSTGRES` | No | - | Analytics database vendor. `POSTGRES` or `CLICKHOUSE`. |
| `datasource.analytics.auth.type` | `DATASOURCE_ANALYTICS_AUTH_TYPE` | `basic` | No | - | Analytics authentication strategy. Same semantics as `datasource.meta.auth.type`. |
| `postgres.analytics.datasource.url` | `POSTGRES_ANALYTICS_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/evaluation_analytics_db` | Recommended | - | Analytics JDBC base URL without query parameters. Override in every non-local environment. |
| `postgres.analytics.datasource.connection-params` | `POSTGRES_ANALYTICS_DATASOURCE_CONNECTION_PARAMS` | `reWriteBatchedInserts=true` | No | - | JDBC query parameters appended to the URL. The default enables batched-insert rewriting for the analytics write path. |
| `postgres.analytics.datasource.driver-class-name` | `POSTGRES_ANALYTICS_DATASOURCE_DRIVER_CLASS_NAME` | `org.postgresql.Driver` | No | - | JDBC driver class. |
| `postgres.analytics.datasource.username` | `POSTGRES_ANALYTICS_DATASOURCE_USERNAME` | `postgres` | Conditional | `datasource.analytics.auth.type=azure` | Database username. For `azure` auth this MUST be the Azure AD identity username. |
| `postgres.analytics.datasource.password` | `POSTGRES_ANALYTICS_DATASOURCE_PASSWORD` | `postgres` | Recommended | `datasource.analytics.auth.type=basic` | Database password. The default is intended for local development only. Unused when `datasource.analytics.auth.type=azure`. |
| `postgres.analytics.datasource.schema` | `POSTGRES_ANALYTICS_DATASOURCE_SCHEMA` | `public` | No | - | Database schema for analytics entities and Flyway migrations. |

#### 4.2.1 ClickHouse Analytics Datasource

Applies only when `datasource.analytics.vendor=CLICKHOUSE`. ClickHouse has no schemas in the PostgreSQL sense; `clickhouse.analytics.datasource.database` plays the equivalent role (it is Flyway's `defaultSchema` for this vendor) and must match the database segment of `clickhouse.analytics.datasource.url`. `datasource.analytics.auth.type=azure` is not supported for this vendor.

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `clickhouse.analytics.datasource.url` | `CLICKHOUSE_ANALYTICS_DATASOURCE_URL` | `jdbc:ch://localhost:8123/evaluation_analytics` | Recommended | `datasource.analytics.vendor=CLICKHOUSE` | Analytics JDBC base URL without query parameters. Accepts `jdbc:ch://` or `jdbc:clickhouse://`. Override in every non-local environment. |
| `clickhouse.analytics.datasource.connection-params` | `CLICKHOUSE_ANALYTICS_DATASOURCE_CONNECTION_PARAMS` | `-` | No | `datasource.analytics.vendor=CLICKHOUSE` | JDBC query parameters appended to the URL. Empty by default. |
| `clickhouse.analytics.datasource.driver-class-name` | `CLICKHOUSE_ANALYTICS_DATASOURCE_DRIVER_CLASS_NAME` | `com.clickhouse.jdbc.ClickHouseDriver` | No | `datasource.analytics.vendor=CLICKHOUSE` | JDBC driver class (ClickHouse V2 JDBC driver). |
| `clickhouse.analytics.datasource.username` | `CLICKHOUSE_ANALYTICS_DATASOURCE_USERNAME` | `clickhouse` | Recommended | `datasource.analytics.vendor=CLICKHOUSE` | Database username. |
| `clickhouse.analytics.datasource.password` | `CLICKHOUSE_ANALYTICS_DATASOURCE_PASSWORD` | `clickhouse` | Recommended | `datasource.analytics.vendor=CLICKHOUSE` | Database password. The default is intended for local development only. Override via environment variable in every non-local environment. |
| `clickhouse.analytics.datasource.database` | `CLICKHOUSE_ANALYTICS_DATASOURCE_DATABASE` | `evaluation_analytics` | No | `datasource.analytics.vendor=CLICKHOUSE` | ClickHouse database name; used as Flyway's `defaultSchema` and as the migration location's vendor segment (`db/migration/analytics/CLICKHOUSE`). |

On this vendor, `analyticsTransactionManager` is a no-op (`ClickHouseNoOpTransactionManager`) — ClickHouse has no transactions, and analytics writes are idempotent append-only batches deduplicated at read time by `ReplacingMergeTree` (session-wide `SET final = 1`, configured on the connection pool). See [Database Schema Reference — ClickHouse analytics schema](database-schema.md#clickhouse-analytics-schema-vendorclickhouse).

### 4.3 Azure AD Authentication

For Azure-managed PostgreSQL, set `datasource.meta.auth.type=azure` and/or `datasource.analytics.auth.type=azure` and configure Azure credential resolution.

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `auth.azure.type` | `AUTH_AZURE_TYPE` | `none` | Conditional | `datasource.meta.auth.type=azure OR datasource.analytics.auth.type=azure` | Azure credential type. `managed` creates an Azure `TokenCredential` bean automatically. Leave at `none` only when you supply your own `TokenCredential` bean via custom configuration. |

Minimal example:

```yaml
datasource:
  meta:
    auth:
      type: azure
  analytics:
    auth:
      type: azure
auth:
  azure:
    type: managed
postgres:
  meta:
    datasource:
      username: <azure-ad-identity-username>
  analytics:
    datasource:
      username: <azure-ad-identity-username>
```

The application automatically obtains and refreshes Azure AD tokens for database authentication; operators do not manage token lifetimes.

### 4.4 Flyway

Spring Boot's Flyway auto-config is disabled (`spring.flyway.enabled=false`); Flyway is configured manually against both datasources. Migration files are loaded from `classpath:db/migration/meta/${datasource.meta.vendor}/` (meta) and `classpath:db/migration/analytics/${datasource.analytics.vendor}/` (analytics). Both Flyway runners use `baselineOnMigrate=true` and `validateMigrationNaming=true`, and both wait on datasource validation before applying migrations.

Flyway itself has no operator-facing tunables in this service beyond the datasource connection settings above and the `datasource.*.vendor` selector that determines the migration path.

### 4.5 Datasource Validation

At startup the application validates that the meta and analytics datasources do not point to the same `(database, schema)` pair. Sharing a database with different schemas is allowed. The application also verifies that the configured `datasource.analytics.vendor` has an available repository implementation. Validation failures abort startup.

---

## 5. DIAL Integration

### 5.1 DIAL Core Client

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `dial.components.core.base-url` | `DIAL_CORE_URL` | `http://localhost:8085` | No | - | Base URL for DIAL Core API. |
| `dial.components.core.connect-timeout-ms` | `DIAL_CORE_CONNECT_TIMEOUT_MS` | `5000` | No | - | Connection timeout in milliseconds. |
| `dial.components.core.read-timeout-ms` | `DIAL_CORE_READ_TIMEOUT_MS` | `30000` | No | - | Read timeout in milliseconds for metadata calls. |
| `dial.components.core.retry.max-attempts` | `DIAL_CORE_RETRY_MAX_ATTEMPTS` | `3` | No | - | Maximum retry attempts for transient failures. |
| `dial.components.core.retry.delay-ms` | `DIAL_CORE_RETRY_DELAY_MS` | `1000` | No | - | Initial retry delay in milliseconds. |
| `dial.components.core.retry.multiplier` | `DIAL_CORE_RETRY_MULTIPLIER` | `2.0` | No | - | Exponential backoff multiplier. |
| `dial.components.core.try-out.read-timeout-ms` | `DIAL_CORE_TRY_OUT_READ_TIMEOUT_MS` | `120000` | No | - | Read timeout for try-it-out deployment invocations. Higher than the metadata read timeout because LLM inference latency is typically 30–120 s. |

### 5.2 DIAL API Key

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `dial.api-key` | `DIAL_EF_API_KEY` | - | Yes | - | DIAL Core API key for the Evaluation Framework service account. Validated via `@NotBlank`; the application fails fast at startup when unset. Used for deployment metadata calls, try-it-out invocations, MCP proxy calls, and file storage operations. |

### 5.3 DIAL File Storage

Files referenced by test cases with `FILE`-typed schema fields are stored in DIAL Core's file storage API, accessed via the shared `dial.api-key`.

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `dial.file-storage.bucket-alias` | `DIAL_FILE_STORAGE_BUCKET_ALIAS` | `@ef` | No | - | Client-facing bucket alias used in file references (e.g. `@ef/suites/{suiteId}/{filename}`). The real DIAL bucket is discovered at runtime on the first file operation. |
| `dial.file-storage.max-file-size-bytes` | `DIAL_FILE_STORAGE_MAX_FILE_SIZE_BYTES` | `52428800` | No | - | Maximum size per uploaded file in bytes (50 MB default). |
| `dial.file-storage.max-files-per-suite` | `DIAL_FILE_STORAGE_MAX_FILES_PER_SUITE` | `100` | No | - | Maximum number of files stored per test suite. |
| `dial.file-storage.max-files-per-dataset` | `DIAL_FILE_STORAGE_MAX_FILES_PER_DATASET` | `100` | No | - | Maximum number of files stored per dataset (counts uploads under `@ef/datasets/{datasetId}/`). |
| `dial.file-storage.connect-timeout-ms` | `DIAL_FILE_STORAGE_CONNECT_TIMEOUT_MS` | `5000` | No | - | HTTP connection timeout for DIAL file operations. |
| `dial.file-storage.read-timeout-ms` | `DIAL_FILE_STORAGE_READ_TIMEOUT_MS` | `30000` | No | - | HTTP read timeout for DIAL file operations. |

Spring multipart upload limits must be sized to accept the configured per-file cap plus multipart envelope overhead:

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `spring.servlet.multipart.max-file-size` | `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` | `50MB` | No | - | Spring-level maximum size of a single multipart part. Must be greater than or equal to `dial.file-storage.max-file-size-bytes`. |
| `spring.servlet.multipart.max-request-size` | `SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE` | `55MB` | No | - | Spring-level maximum total multipart request size. |

When `dial.file-storage.*` is configured and the bucket is successfully discovered, the `/actuator/health` readiness group reports the `dialFileStorage` indicator as `UP`; otherwise it reports `DOWN` and blocks readiness.

### 5.4 DIAL MCP Client

Configuration for MCP (Model Context Protocol) tool invocations routed through DIAL Core's MCP proxy.

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `dial.mcp.connect-timeout-ms` | `DIAL_MCP_CONNECT_TIMEOUT_MS` | `5000` | No | - | Connection timeout in milliseconds. |
| `dial.mcp.read-timeout-ms` | `DIAL_MCP_READ_TIMEOUT_MS` | `120000` | No | - | Read timeout in milliseconds. Higher than the DIAL Core metadata client because MCP tool execution latency can be significant. |

### 5.5 DIAL ADAS Client

Configuration for dial-adas, an external analytics service queried for `GET /api/v1/test-suite-runs/{id}/costs` (average test-case execution cost and average metric-evaluation cost, computed from `dial_usage_log` aggregate queries correlated by the run's OTel baggage).

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `dial.adas.base-url` | `DIAL_ADAS_URL` | `http://localhost:8087` | No | - | Base URL for the dial-adas query-execute API. |
| `dial.adas.connect-timeout-ms` | `DIAL_ADAS_CONNECT_TIMEOUT_MS` | `5000` | No | - | Connection timeout in milliseconds. |
| `dial.adas.read-timeout-ms` | `DIAL_ADAS_READ_TIMEOUT_MS` | `30000` | No | - | Read timeout in milliseconds. |

---

## 6. Evaluation Engine

### 6.1 Test Suite Run — Executor

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `test-suite-run.executor.core-pool-size` | `TEST_SUITE_RUN_EXECUTOR_CORE_POOL_SIZE` | `5` | No | - | Core thread pool size for async run execution. |
| `test-suite-run.executor.max-pool-size` | `TEST_SUITE_RUN_EXECUTOR_MAX_POOL_SIZE` | `10` | No | - | Maximum thread pool size. |
| `test-suite-run.executor.queue-capacity` | `TEST_SUITE_RUN_EXECUTOR_QUEUE_CAPACITY` | `50` | No | - | Queue capacity before new submissions are rejected. |

### 6.2 Test Suite Run — SSE

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `test-suite-run.sse.timeout-minutes` | `TEST_SUITE_RUN_SSE_TIMEOUT_MINUTES` | `30` | No | - | SSE client connection timeout in minutes. |
| `test-suite-run.sse.cleanup-interval-ms` | `TEST_SUITE_RUN_SSE_CLEANUP_INTERVAL_MS` | `300000` | No | - | Interval at which stale SSE emitters are pruned, in milliseconds. |

### 6.3 Test Suite Run — Execution Settings

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `test-suite-run.execution.default-concurrency-level` | `TEST_SUITE_RUN_EXECUTION_DEFAULT_CONCURRENCY_LEVEL` | `1` | No | - | Default number of parallel test case executions per run. |
| `test-suite-run.execution.max-concurrency-level` | `TEST_SUITE_RUN_EXECUTION_MAX_CONCURRENCY_LEVEL` | `50` | No | - | Upper bound on concurrency per run. |
| `test-suite-run.execution.default-request-timeout-ms` | `TEST_SUITE_RUN_EXECUTION_DEFAULT_REQUEST_TIMEOUT_MS` | `30000` | No | - | Default per-request timeout in milliseconds. |
| `test-suite-run.execution.max-request-timeout-ms` | `TEST_SUITE_RUN_EXECUTION_MAX_REQUEST_TIMEOUT_MS` | `600000` | No | - | Upper bound on per-request timeout. |
| `test-suite-run.execution.result-batch-size` | `TEST_SUITE_RUN_EXECUTION_RESULT_BATCH_SIZE` | `100` | No | - | Number of results buffered before flushing to the analytics database. |
| `test-suite-run.execution.max-response-size-bytes` | `TEST_SUITE_RUN_EXECUTION_MAX_RESPONSE_SIZE_BYTES` | `5242880` | No | - | Maximum captured response body size in bytes before truncation (5 MB default). |
| `test-suite-run.execution.cancellation-grace-period-ms` | `TEST_SUITE_RUN_EXECUTION_CANCELLATION_GRACE_PERIOD_MS` | `30000` | No | - | Time in milliseconds to wait for in-flight calls to drain AFTER a run is cancelled, before calling `shutdownNow()` to interrupt remaining workers. Applies ONLY when cancellation is requested; it is NOT an overall evaluation timeout. A run that takes longer than this value without being cancelled continues to completion. Per-call wall-clock bounding is the responsibility of `request-timeout-ms`. |
| `test-suite-run.execution.header-blacklist` | `TEST_SUITE_RUN_EXECUTION_HEADER_BLACKLIST` | `[Authorization, Host, Content-Length, Transfer-Encoding, Connection, traceparent, tracestate]` | No | - | HTTP headers silently stripped from evaluation requests before they are forwarded. |

### 6.4 Test Suite Run — Retry Settings

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `test-suite-run.retry.default-max-retries` | `TEST_SUITE_RUN_RETRY_DEFAULT_MAX_RETRIES` | `0` | No | - | Default maximum retry count per call. `0` disables retries. |
| `test-suite-run.retry.max-max-retries` | `TEST_SUITE_RUN_RETRY_MAX_MAX_RETRIES` | `10` | No | - | Upper bound on per-call retry count. |
| `test-suite-run.retry.default-retry-delay-ms` | `TEST_SUITE_RUN_RETRY_DEFAULT_RETRY_DELAY_MS` | `1000` | No | - | Default base delay between retries in milliseconds. |
| `test-suite-run.retry.max-retry-delay-ms` | `TEST_SUITE_RUN_RETRY_MAX_RETRY_DELAY_MS` | `60000` | No | - | Upper bound on retry delay in milliseconds. |
| `test-suite-run.retry.default-retry-backoff-multiplier` | `TEST_SUITE_RUN_RETRY_DEFAULT_RETRY_BACKOFF_MULTIPLIER` | `2.0` | No | - | Default exponential backoff multiplier. |
| `test-suite-run.retry.max-retry-backoff-multiplier` | `TEST_SUITE_RUN_RETRY_MAX_RETRY_BACKOFF_MULTIPLIER` | `10.0` | No | - | Upper bound on backoff multiplier. |

### 6.5 Test Suite Run — Run Config

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `test-suite-run.run-config.max-number-of-runs` | `TEST_SUITE_RUN_RUN_CONFIG_MAX_NUMBER_OF_RUNS` | `64` | No | - | Maximum value accepted for the `numberOfRuns` field of a test suite run request. |

### 6.6 Test Suite Run — Concurrency Limits

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `test-suite-run.limits.max-concurrent-runs-global` | `TEST_SUITE_RUN_LIMITS_MAX_CONCURRENT_RUNS_GLOBAL` | `20` | No | - | Maximum test suite runs that may execute concurrently across all suites. |
| `test-suite-run.limits.max-concurrent-runs-per-suite` | `TEST_SUITE_RUN_LIMITS_MAX_CONCURRENT_RUNS_PER_SUITE` | `5` | No | - | Maximum concurrent runs for a single test suite. |

### 6.7 Test Suite Run — Run Inputs

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `test-suite-run.run-inputs.retention-days` | `TEST_SUITE_RUN_RUN_INPUTS_RETENTION_DAYS` | `1` | No | - | Number of days to retain `test_case_run_inputs` rows after the parent run reaches a terminal state (COMPLETED or FAILED). Rows older than this threshold are deleted by the daily retention cleanup job. |

### 6.8 Analytics Results Batch Write

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `analytics.results.batch.max-items` | `ANALYTICS_RESULTS_BATCH_MAX_ITEMS` | `10000` | No | - | Maximum number of result items per batch write request. |
| `analytics.results.batch.max-request-size-bytes` | `ANALYTICS_RESULTS_BATCH_MAX_REQUEST_SIZE_BYTES` | `10485760` | No | - | Maximum request body size in bytes for a batch write (10 MB). Also enforced by `server.tomcat.max-http-post-size`. |
| `analytics.results.csv-import.max-file-size` | `ANALYTICS_RESULTS_CSV_IMPORT_MAX_FILE_SIZE` | `10MB` | No | - | Maximum CSV file size for the eval-results import endpoint (`POST /api/v1/test-suites/{id}/runs/import`). Requests exceeding this limit are rejected with HTTP 400 before parsing begins. |

### 6.9 Analytics Eval Summaries Batch Write

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `analytics.eval-summaries.batch.max-items` | `ANALYTICS_EVAL_SUMMARIES_BATCH_MAX_ITEMS` | `10000` | No | - | Maximum number of items per eval summary batch write request. |
| `analytics.eval-summaries.batch.max-request-size-bytes` | `ANALYTICS_EVAL_SUMMARIES_BATCH_MAX_REQUEST_SIZE_BYTES` | `10485760` | No | - | Maximum request body size in bytes for a batch write (10 MB). Also enforced by `server.tomcat.max-http-post-size`. |

### 6.10 Metric Providers

Metric declarations can be synced from one or more external metric provider services (each exposing `GET /metrics`). Providers are configured as a map keyed by provider id.

#### Sync schedule

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `metric-providers.sync.enabled` | `METRIC_PROVIDERS_SYNC_ENABLED` | `false` | Recommended | - | Enables the scheduled metric sync job. Default `false` is intended for local development only; operators SHOULD set this to `true` in any environment that needs to pick up metric declarations from a metric provider service. |
| `metric-providers.sync.cron` | `METRIC_PROVIDERS_SYNC_CRON` | `-` | No | `metric-providers.sync.enabled=true` | Cron expression for recurring sync (e.g. `0 */5 * * * *` for every five minutes). `-` disables recurring sync; the job still runs once at startup when `enabled=true`. |
| `metric-providers.sync.fixed-delay-ms` | `METRIC_PROVIDERS_SYNC_FIXED_DELAY_MS` | `0` | No | `metric-providers.sync.enabled=true AND metric-providers.sync.cron=-` | Fixed delay in milliseconds between sync runs. Used only when no cron expression is set. |

#### Provider map

Each entry under `metric-providers.providers.<id>` defines one provider. The map key is the provider id (recorded as `provider_id` on synced metric declarations). The default configuration ships with two entries: `dial` (enabled by default) and `extra` (disabled by default, a ready-to-use slot for a second provider service).

Each entry carries its own `enabled` flag. Disabling an entry excludes it from the **sync job** only — metric declarations already synced from that provider stay in the catalog and are still evaluated via the provider's `/evaluate` endpoint during test suite runs. To stop all sync, use `metric-providers.sync.enabled=false`.

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `metric-providers.providers.<id>.enabled` | `METRIC_PROVIDERS_<ID>_ENABLED` | - | Yes | - | Whether the sync job processes this provider entry. `@NotNull` — every entry present in configuration MUST define it (there is no Java-side default), so a provider added purely via environment variables MUST also set `METRIC_PROVIDERS_<ID>_ENABLED`. Stock defaults: `dial` = `true`, `extra` = `false`. `false` skips the entry during sync but leaves its already-synced declarations usable for metric evaluation. |
| `metric-providers.providers.<id>.base-url` | `METRIC_PROVIDERS_<ID>_BASE_URL` | - | Conditional | `metric-providers.sync.enabled=true` | Base URL of the provider (e.g. `http://metric-service:8080`). `GET /metrics` is called against this. Validated via `@NotBlank` when a provider entry is present. The default configuration for id `dial` resolves from `METRIC_PROVIDERS_DIAL_BASE_URL` with yaml fallback `http://localhost:8086`. |
| `metric-providers.providers.<id>.connect-timeout-ms` | `METRIC_PROVIDERS_<ID>_CONNECT_TIMEOUT_MS` | `5000` | No | `metric-providers.sync.enabled=true` | HTTP connection timeout in milliseconds. |
| `metric-providers.providers.<id>.read-timeout-ms` | `METRIC_PROVIDERS_<ID>_READ_TIMEOUT_MS` | `150000` | No | `metric-providers.sync.enabled=true` | HTTP read timeout in milliseconds. `150000` is the value shipped in `application.yml` for the stock `dial` and `extra` entries; an entry declared only via environment variables falls back to the binding default of `30000`. |
| `metric-providers.providers` | `-` | `{dial: {...}, extra: {...}}` | No | - | Top-level provider map. An empty map disables sync for all providers. See the rows above for the fields each entry accepts. |

Example:

```yaml
metric-providers:
  providers:
    my-metrics:
      enabled: true
      base-url: http://metric-provider:8080
      connect-timeout-ms: 5000
      read-timeout-ms: 150000
    legacy-metrics:
      enabled: false          # kept in config, skipped by the sync job
      base-url: http://legacy-metric-provider:8080
      connect-timeout-ms: 5000
      read-timeout-ms: 150000
  sync:
    enabled: true
    cron: "0 */5 * * * *"
```

#### Per-provider environment variable override

Entries under the `providers` map are fully addressable via environment variables using Spring Boot's relaxed binding. The pattern is `METRIC_PROVIDERS_<UPPER_ID>_<PROPERTY>` where `<UPPER_ID>` is the provider id in upper case and `<PROPERTY>` is the field name with `.` and `-` converted to `_`. For the stock `dial` entry the following env vars are available without editing YAML:

| Environment variable | Overrides | Notes |
|---|---|---|
| `METRIC_PROVIDERS_DIAL_ENABLED` | `metric-providers.providers.dial.enabled` | Default `true`. Set `false` to keep the entry configured but skip it during sync. |
| `METRIC_PROVIDERS_DIAL_BASE_URL` | `metric-providers.providers.dial.base-url` | Required in yaml as `@NotBlank`. |
| `METRIC_PROVIDERS_DIAL_CONNECT_TIMEOUT_MS` | `metric-providers.providers.dial.connect-timeout-ms` | - |
| `METRIC_PROVIDERS_DIAL_READ_TIMEOUT_MS` | `metric-providers.providers.dial.read-timeout-ms` | - |
| `METRIC_PROVIDERS_EXTRA_ENABLED` | `metric-providers.providers.extra.enabled` | Default `false`. Set `true` to activate the stock second provider slot. |
| `METRIC_PROVIDERS_EXTRA_BASE_URL` | `metric-providers.providers.extra.base-url` | Yaml fallback `http://localhost:8087`. Override before enabling the entry. |
| `METRIC_PROVIDERS_EXTRA_CONNECT_TIMEOUT_MS` | `metric-providers.providers.extra.connect-timeout-ms` | - |
| `METRIC_PROVIDERS_EXTRA_READ_TIMEOUT_MS` | `metric-providers.providers.extra.read-timeout-ms` | - |

To register a third or further provider without touching YAML, use env vars keyed by upper-cased provider id — both `METRIC_PROVIDERS_CUSTOM_BASE_URL` **and** `METRIC_PROVIDERS_CUSTOM_ENABLED` for a provider keyed `custom` (`enabled` is `@NotNull`, so omitting it fails startup validation).

### 6.11 Metric Evaluation

Configuration for the in-process metric evaluation phase of test suite runs. After deployment evaluation completes, the system calls each configured metric provider's `/evaluate` endpoint.

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `metric-evaluation.default-concurrency-per-provider` | `METRIC_EVALUATION_DEFAULT_CONCURRENCY_PER_PROVIDER` | `5` | No | - | Maximum concurrent `/evaluate` calls per metric provider (semaphore permits). |
| `metric-evaluation.batch-size` | `METRIC_EVALUATION_BATCH_SIZE` | `100` | No | - | Number of EvalSummary records buffered before flushing to the analytics DB. |
| `metric-evaluation.per-result-timeout-ms` | `METRIC_EVAL_PER_RESULT_TIMEOUT_MS` | `150000` | No | - | Max wall time in milliseconds to wait for all metric futures on a single TestCaseRunResult before cancelling remaining futures and marking timed-out metric definitions as FAILED. |
| `metric-evaluation.retry.max-retries` | `METRIC_EVALUATION_RETRY_MAX_RETRIES` | `0` | No | - | Maximum retries per `/evaluate` call. `0` disables retry. |
| `metric-evaluation.retry.retry-delay-ms` | `METRIC_EVALUATION_RETRY_RETRY_DELAY_MS` | `1000` | No | - | Initial retry delay in milliseconds. |
| `metric-evaluation.retry.retry-backoff-multiplier` | `METRIC_EVALUATION_RETRY_RETRY_BACKOFF_MULTIPLIER` | `2.0` | No | - | Exponential backoff multiplier. Must be ≥ 1.0. |
| `metric-evaluation.retry.max-retry-delay-ms` | `METRIC_EVALUATION_RETRY_MAX_RETRY_DELAY_MS` | `60000` | No | - | Upper bound on retry delay in milliseconds. |

---

### 6.12 SSE Event Processing

Global, path-agnostic cap for SSE stream parsing. The per-path idle (inactivity) timeout — `requestTimeoutMs` on the evaluation path and `dial.components.core.try-out.read-timeout-ms` on the Try It Out path — bounds gaps between lines; this absolute cap bounds the total stream duration so a server that heartbeats forever still terminates. Shared by both streaming paths.

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `sse-event-processing.max-total-duration-ms` | `SSE_EVENT_PROCESSING_MAX_TOTAL_DURATION_MS` | `3600000` | No | - | Absolute maximum wall-clock time in milliseconds to spend parsing a single SSE stream, regardless of activity. Crossing it stops parsing with `TIMEOUT` and returns the events accumulated so far. Set high (default 1 hour) so it acts as a safety ceiling, not a working timeout. Minimum `1000`. |

### 6.13 Analytics Run Comparison

Bound for `GET /api/v1/analytics/metric-scores/comparison`, which recomputes metric-score statistics over only the eval-summary rows two runs have in common and returns the ids of the rows that did **not** match, so a client can reproduce that population by excluding them.

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `analytics.comparison.max-unmatched-rows` | `ANALYTICS_COMPARISON_MAX_UNMATCHED_ROWS` | `5000` | No | - | Maximum number of non-matching eval-summary rows a single run comparison may report **per run**; exceeding it fails the request with HTTP 409 naming both the count and this limit. Bounds the returned exclusion id list, the `IN` bind count (an overflow of the database parameter ceiling would otherwise surface as HTTP 500) and the worst-case response size — about 0.35 MB at the default, and reached only at *low* overlap, since two runs that match completely report an empty exclusion list. Minimum `1`. |

### 6.14 JSONata Evaluation

Runtime bounds applied to every JSONata expression evaluation (request-template body evaluation and response-column/condition evaluation) via `Frame.setRuntimeBounds`, protecting worker threads from a runaway or unbounded-recursion JSONata expression.

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `jsonata.evaluation-timeout-ms` | `JSONATA_EVALUATION_TIMEOUT_MS` | `10000` | No | - | Maximum wall-clock time in milliseconds a single JSONata expression evaluation may run before it is aborted. Minimum `1`. |
| `jsonata.max-recursion-depth` | `JSONATA_MAX_RECURSION_DEPTH` | `1000` | No | - | Maximum call-stack recursion depth a single JSONata expression evaluation may reach before it is aborted. Minimum `1`. |

---

## 7. Data Management

### 7.1 Pagination

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `pagination.default-size` | `PAGINATION_DEFAULT_SIZE` | `100` | No | - | Default `size` applied to list endpoints when the client does not supply one. |
| `pagination.max-size` | `PAGINATION_MAX_SIZE` | `1000` | No | - | Upper bound on `size`. Requests above this are rejected with HTTP 400. |

On list endpoints, missing `page` defaults to `0` and missing `size` to `pagination.default-size`. `size` is validated to be within `[1, pagination.max-size]`.

### 7.2 CSV Export

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `csv.export.page-size` | `CSV_EXPORT_PAGE_SIZE` | `500` | No | - | Page size used for iterative database fetches when streaming a CSV export. Capped by `pagination.max-size`. |

### 7.3 CSV Import

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `csv.import.max-file-size` | `CSV_IMPORT_MAX_FILE_SIZE` | `10MB` | No | - | Maximum CSV file size. Parsed via Spring's `DataSize` support; use a value with unit (`10MB`, `1GB`). |
| `csv.import.max-rows` | `CSV_IMPORT_MAX_ROWS` | `100000` | No | - | Maximum number of rows accepted per import. |
| `csv.import.batch-size` | `CSV_IMPORT_BATCH_SIZE` | `1000` | No | - | Number of rows inserted per JDBC batch. |

### 7.4 Validation

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `validation.max-warnings-per-case` | `VALIDATION_MAX_WARNINGS_PER_CASE` | `5` | No | - | Maximum validation warnings stored per test case. |
| `validation.revalidation.batch-size` | `VALIDATION_REVALIDATION_BATCH_SIZE` | `500` | No | - | Batch size used by async revalidation. |
| `validation.revalidation.timeout-minutes` | `VALIDATION_REVALIDATION_TIMEOUT_MINUTES` | `5` | No | - | Upper bound on revalidation task runtime, in minutes. |
| `validation.max-template-size-bytes` | `VALIDATION_MAX_TEMPLATE_SIZE_BYTES` | `65536` | No | - | Maximum serialized size of `requestTemplate` or `requestTemplateOverride` (64 KB). |
| `validation.max-bindings-count` | `VALIDATION_MAX_BINDINGS_COUNT` | `64` | No | - | Maximum number of `inputBindings` or `inputBindingsOverride` entries. |

#### Fixed (non-configurable) limits

The following limits are enforced via Bean Validation (`@Size`) in DTOs and controllers and are not tunable at runtime: list endpoints accept at most 32 `filter` parameters and 32 `sort` parameters; `TestCasesDefinitionDto.factFields` accepts at most 128 elements; CSV delimiter must be a single ASCII character.

### 7.5 Test Case Batch

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `test-case.batch.max-items` | `TEST_CASE_BATCH_MAX_ITEMS` | `256` | No | - | Maximum number of items accepted per batch PUT or PATCH request on the test cases endpoint. |
| `test-case.bulk.max-operations` | `TEST_CASE_BULK_MAX_OPERATIONS` | `512` | No | - | Maximum combined count of `bulkOperations` + `itemOperations` accepted per `PATCH /test-cases:bulk` request. Must be ≥ `test-case.bulk.max-item-operations`. |
| `test-case.bulk.max-ids-per-selector` | `TEST_CASE_BULK_MAX_IDS_PER_SELECTOR` | `10000` | No | - | Maximum `selector.ids.length` per bulk operation. Also enforced as the upper bound on the id-set materialised from a `filter` selector. |
| `test-case.bulk.max-item-operations` | `TEST_CASE_BULK_MAX_ITEM_OPERATIONS` | `500` | No | - | Maximum number of heterogeneous per-row operations (`itemOperations`) accepted per `PATCH /test-cases:bulk` request. |
| `test-case.bulk.max-delete-ids` | `TEST_CASE_BULK_MAX_DELETE_IDS` | `10000` | No | - | Maximum number of IDs accepted in a single bulk-delete-by-IDs request (`DELETE /test-cases:bulk`). |
| `test-case.multi-turn.max-turns` | `TEST_CASE_MULTI_TURN_MAX_TURNS` | `10` | No | - | Maximum number of turns a multi-turn test case (`multiTurnData`) may carry. A case exceeding this cap is persisted but marked `is_valid=false` with an invalidating warning (not rejected), so it is excluded from runnable selection. |

---

## 8. Observability

### 8.1 Grafana Integration

Optional Grafana Explore deep link generation. When enabled, API responses include ready-to-click URLs that open traces in Grafana Tempo. Feature is disabled by default (empty `base-url`).

| Property | Environment Variable | Default | Required | Applied when | Description |
|---|---|---|---|---|---|
| `app.grafana.base-url` | `GRAFANA_BASE_URL` | - | Recommended | - | Grafana base URL (e.g. `http://grafana:3000`). When blank or absent, deep link generation is disabled and no URL fields appear in responses. |
| `app.grafana.tempo-datasource-uid` | `GRAFANA_TEMPO_DATASOURCE_UID` | `tempo` | Conditional | `app.grafana.base-url is set` | Grafana Tempo datasource UID as configured in Grafana's datasource settings. |
| `app.grafana.org-id` | `GRAFANA_ORG_ID` | `1` | Conditional | `app.grafana.base-url is set` | Grafana organization ID used in Explore URLs. Required for multi-org Grafana deployments. |

When enabled, the following response fields are populated with deep-link URLs:

- `ExecutionInfoResponseDto.grafanaTraceUrl` — Grafana Explore URL for a single test case trace.
- `TryItOutResponseDto.grafanaTraceUrl` — Grafana Explore URL for a try-it-out trace.
- `TestSuiteRunResponseDto.grafanaExploreUrl` — Grafana Explore TraceQL URL for all traces in a run (populated only once the run has started).

---

## 9. Notes

### Docker run example

```bash
docker run -d \
  -p 8080:8080 \
  -e DIAL_EF_API_KEY=<your-dial-api-key> \
  -e POSTGRES_META_DATASOURCE_URL=jdbc:postgresql://db:5432/evaluation_db \
  -e POSTGRES_META_DATASOURCE_USERNAME=app_user \
  -e POSTGRES_META_DATASOURCE_PASSWORD=<secret> \
  -e POSTGRES_ANALYTICS_DATASOURCE_URL=jdbc:postgresql://db:5432/evaluation_analytics_db \
  -e POSTGRES_ANALYTICS_DATASOURCE_USERNAME=app_user \
  -e POSTGRES_ANALYTICS_DATASOURCE_PASSWORD=<secret> \
  -v /path/to/logging.levels.json:/app/log-config/logging.levels.json:ro \
  ai-dial-admin-evaluation-framework-backend
```

### Production deployment reminders

- **Always override** `postgres.meta.datasource.password`, `postgres.analytics.datasource.password`, `postgres.meta.datasource.url`, and `postgres.analytics.datasource.url` — the stock defaults are for local development only.
- **Always set** `DIAL_EF_API_KEY`; the application refuses to boot without it.
- **Enable metric provider sync** in every environment that needs metric declarations kept in sync with a metric provider service: set `METRIC_PROVIDERS_SYNC_ENABLED=true` and either a `METRIC_PROVIDERS_SYNC_CRON` expression or a `METRIC_PROVIDERS_SYNC_FIXED_DELAY_MS` value. The default (`false`) is intended for local development only.
- **Point each enabled provider entry at a real service.** The stock `extra` entry is disabled (`METRIC_PROVIDERS_EXTRA_ENABLED=false`) and points at `http://localhost:8087`; set `METRIC_PROVIDERS_EXTRA_BASE_URL` before flipping it to `true`.
- JDBC query parameters (`connection-params`) have sensible defaults in `application.yml`; override only when the database requires additional driver options (e.g. `sslmode=require`).
- When enabling Azure AD authentication for PostgreSQL, verify the managed identity assigned to the pod has been granted the relevant database role before rolling the deployment.
