# AI DIAL Admin Evaluation Framework Backend

A Spring Boot backend for the AI DIAL Admin Evaluation Framework. It manages the lifecycle of LLM/AI
evaluations: datasets and test cases, test suites, run execution against DIAL Core deployments,
metric computation, and analytics.

## Features

- **Datasets & test cases**: schema-driven datasets, multi-turn test cases, file attachments, CSV / ZIP import & export
- **Test suites**: HTTP (deployment / route, multi-request chains, JSONata templates) and MCP-tool suites; clone, try-it-out
- **Runs**: async execution with cancellation, SSE status streaming, result import
- **Metrics**: versioned metric declarations synced from metric providers, per-suite metric definitions, conditional
  execution, overall scores
- **Analytics**: test-case results, eval summaries, metric scores, costs; keyset pagination; per-run metric snapshots
- **Query DSL**: structured query API (`/api/v1/queries`) over analytics and meta entities
- **MCP server** (opt-in): exposes evaluation setup and execution as MCP tools at `/mcp`
- **DIAL App mode** (opt-in): runs as a DIAL Application Route for long-running evaluations
- **Platform**: OIDC/JWT multi-issuer + API-key auth, PostgreSQL (meta + analytics datasources, jOOQ, Flyway),
  OpenTelemetry tracing, Prometheus metrics, health probes

> ⚠️ **Authorization:** authenticated users are currently fully trusted — no per-user/per-tenant authorization yet.

## Modules

| Module                   | Purpose                                                                              |
|--------------------------|--------------------------------------------------------------------------------------|
| root                     | Main Spring Boot application (REST API, MCP server, persistence)                     |
| `evaluation-runner-core` | DB-free execution engine library, shared by the app and the CLI                      |
| `eval-cli`               | Standalone CLI that runs suites against a target deployment — see [eval-cli/README.md](eval-cli/README.md) |

## Quick Start

### Prerequisites

- Java 25
- Docker (PostgreSQL for local runs; Testcontainers for tests)
- Gradle wrapper included — use `./gradlew`

### Run with Docker Compose

`local_env/docker-compose.yml` starts PostgreSQL (initializing both `evaluation_db` and
`evaluation_analytics_db`) and the app with security disabled (`CONFIG_REST_SECURITY_MODE=none`).

```bash
docker compose -f local_env/docker-compose.yml up -d           # PostgreSQL + app
docker compose -f local_env/docker-compose.yml up -d postgres  # PostgreSQL only
```

Optional environment variables passed through by Compose (unset ones fall back to the `application.yml` defaults):

| Name                              | Purpose                                                |
|-----------------------------------|--------------------------------------------------------|
| `METRIC_PROVIDERS_DIAL_BASE_URL`  | Metric provider URL (default `http://localhost:8086`)  |
| `METRIC_PROVIDERS_DIAL_ENABLED`   | Enable the `dial` metric provider (default `true`)     |
| `METRIC_PROVIDERS_SYNC_ENABLED`   | Sync metric declarations from providers (default off)  |
| `METRIC_PROVIDERS_SYNC_CRON`      | Sync schedule, e.g. `0 */1 * * * *` (default `-`, none) |
| `METRIC_PROVIDERS_EXTRA_ENABLED`  | Enable the second (`extra`) metric provider            |
| `METRIC_PROVIDERS_EXTRA_BASE_URL` | Second provider URL (default `http://localhost:8087`)  |
| `DIAL_EF_API_KEY`                 | API key used for DIAL Core calls                       |

### Build, run, test

```bash
./gradlew clean build   # compile, tests, checkstyle, spotlessCheck
./gradlew bootRun       # run the app
./gradlew test          # all tests (Testcontainers)
./gradlew spotlessApply # format code
```

### Docker image

```bash
docker build -t ai-dial-admin-evaluation-framework-backend .
```

## API

- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **OpenAPI JSON**: http://localhost:8080/v3/api-docs
- **Health**: `/api/v1/health`, `/api/v1/health/ready`, `/api/v1/health/live`
- **Query console** (local dev UI for the Query DSL): see [local_env/query-console/README.md](local_env/query-console/README.md)

Swagger is the authoritative API contract. See also [docs/api-documentation.md](docs/api-documentation.md).

## Configuration

All properties and environment variables: [docs/configuration.md](docs/configuration.md). Most common:

| Variable                                                     | Description                       | Default                                                    |
|--------------------------------------------------------------|-----------------------------------|------------------------------------------------------------|
| `POSTGRES_META_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD`   | Meta DB connection                | `jdbc:postgresql://localhost:5432/evaluation_db`           |
| `POSTGRES_ANALYTICS_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | Analytics DB connection        | `jdbc:postgresql://localhost:5432/evaluation_analytics_db` |
| `CONFIG_REST_SECURITY_MODE`                                  | `oidc` or `none`                  | `oidc`                                                     |
| `DIAL_CORE_URL`                                              | DIAL Core base URL                | `http://localhost:8085`                                    |
| `DIAL_EF_API_KEY`                                            | API key for DIAL Core             | —                                                          |
| `SPRING_AI_MCP_SERVER_ENABLED`                               | Enable the inbound MCP server     | `false`                                                    |
| `DIAL_APP_PROXY_ENABLED`                                     | Enable DIAL App mode              | `false`                                                    |

## Documentation

- [AGENTS.md](AGENTS.md) — architecture, conventions, contributor workflow
- [OpenSpec specifications](openspec/specs/README.md) — per-capability source of truth for behavior and design
  (changes live in `openspec/changes/`)
- [Patterns](docs/patterns/README.md) — non-obvious implementation patterns
- [Key packages](docs/key-packages.md) — package map of all modules
- [Database schema](docs/database-schema.md) · [Entity-relationship model](docs/design/entity-relationship-model.md) ·
  [Infrastructure architecture](docs/design/infrastructure-architecture.md)
- [CONTRIBUTING.md](CONTRIBUTING.md) · [SECURITY.md](SECURITY.md)

## License

Apache License 2.0
