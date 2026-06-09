# AI DIAL Admin Evaluation Framework Backend

A Spring Boot backend service for the AI DIAL Admin Evaluation Framework. This service manages the lifecycle of LLM/AI model evaluations, including test suite authoring, execution tracking, and metrics collection.

## Features

### Core Functionality
- **Test Suite Management**: Full CRUD operations for Test Suites and Test Cases
- **Datasets**: Dataset CRUD with file upload/download and background revalidation tasks
- **Evaluation Runs**: Track test suite execution and results, with real-time status streaming (SSE)
- **Metrics System**: Metric declarations (versioned), per-suite metric definitions, and metric aggregation
- **Analytics**: Test-case run results, evaluation summaries, and run-metric snapshots with keyset pagination
- **CSV / ZIP Import & Export**: Bulk import/export of test cases and evaluation summaries
- **Try-It-Out**: Execute test cases live against DIAL Core deployments
- **Template Variables**: Extraction, resolution, and type inference from test case templates
- **Pagination & Filtering**: Built-in pagination, filtering, and sorting for list endpoints

### Technical Features
- **Security**: OIDC/JWT-based authentication with multi-issuer support
- **Database**: PostgreSQL with Flyway migrations — JDBC-based (no JPA), queries via the typed jOOQ DSL
- **Dual Datasource**: Separate **meta** (domain) and **analytics** datasources/schemas
- **DIAL Core Integration**: Model/deployment discovery, deployment invocation, and file storage
- **MCP**: Model Context Protocol tool invocation via the DIAL Core MCP proxy
- **API Documentation**: OpenAPI/Swagger UI
- **Health Checks**: Custom health indicators for database and dependencies (liveness/readiness probes)
- **Correlation ID**: Request correlation for distributed tracing
- **Observability**: OpenTelemetry instrumentation and Prometheus metrics
- **Dynamic Logging**: Runtime log level configuration
- **Transaction Timestamps**: Consistent timestamps within transactions

## Quick Start

### Prerequisites

- Java 25
- Docker (for local development with PostgreSQL)
- Gradle 9.5.1 (a wrapper is included — use `./gradlew`)

### Running Locally with Docker Compose

The Compose file lives under `local_env/`. It starts a single PostgreSQL container that
initializes **both** databases — `evaluation_db` (meta) and `evaluation_analytics_db`
(analytics, created by `local_env/init-db.sql`) — and runs the app with
`CONFIG_REST_SECURITY_MODE=none` for local use.

```bash
# Start PostgreSQL and the application
docker compose -f local_env/docker-compose.yml up -d

# Or start only PostgreSQL
docker compose -f local_env/docker-compose.yml up -d postgres
```

### Running the Application

```bash
# Build the project
./gradlew clean build

# Run the application
./gradlew bootRun
```

### Running Tests

```bash
# Run all tests
./gradlew test

# Run tests with test containers
./gradlew test --info
```

## API Endpoints

Once running, access:
- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **API Docs**: http://localhost:8080/v3/api-docs
- **Health Check**: http://localhost:8080/api/v1/health (plus `/api/v1/health/ready` and `/api/v1/health/live`)

> Swagger UI / `/v3/api-docs` is the authoritative, always-current API contract. The tables below
> are an overview; refer to Swagger for the full set of endpoints, parameters, and schemas.

### Endpoint Groups

| Group | Base Path | Purpose |
|-------|-----------|---------|
| Test Suites | `/api/v1/test-suites` | CRUD + clone |
| Test Suite Runs | `/api/v1/test-suite-runs` | Create/list/get/patch/delete runs; SSE status stream |
| Test Cases | `/api/v1/datasets/{datasetId}/test-cases` | CRUD, CSV import/preview, bulk patch, try-it-out |
| Datasets | `/api/v1/datasets` | CRUD, file upload/download, revalidation tasks |
| Metric Declarations | `/api/v1/metric-declarations` | List/get declarations and versions |
| Metric Definitions | `/api/v1/test-suites/{testSuiteId}/metric-definitions` | Per-suite metric definitions + aggregation |
| Analytics | `/api/v1/analytics/...` | Test-case results, eval summaries (+ CSV export), run-metric snapshots |
| Deployments | `/api/v1/deployments` | Discover DIAL Core deployments, models, and tools |
| Files | `/api/v1/test-suites/{suiteId}/files` | Suite file upload/download |
| Health | `/api/v1/health` | Health, readiness, liveness |

### Test Suites API

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/test-suites` | Create a new test suite |
| GET | `/api/v1/test-suites` | List all test suites (paginated) |
| GET | `/api/v1/test-suites/{id}` | Get a test suite by ID |
| PUT | `/api/v1/test-suites/{id}` | Update a test suite |
| DELETE | `/api/v1/test-suites/{id}` | Delete a test suite |
| POST | `/api/v1/test-suites/{id}/clone` | Clone a test suite |

## Configuration

See [docs/configuration.md](docs/configuration.md) for detailed configuration options.

### Key Environment Variables

The service uses two datasources — **meta** (domain entities) and **analytics** (run results
and metrics) — each with its own connection variables.

| Variable | Description | Default |
|----------|-------------|---------|
| `POSTGRES_META_DATASOURCE_URL` | Meta DB connection URL | `jdbc:postgresql://localhost:5432/evaluation_db` |
| `POSTGRES_META_DATASOURCE_USERNAME` | Meta DB username | `postgres` |
| `POSTGRES_META_DATASOURCE_PASSWORD` | Meta DB password | `postgres` |
| `POSTGRES_ANALYTICS_DATASOURCE_URL` | Analytics DB connection URL | `jdbc:postgresql://localhost:5432/evaluation_analytics_db` |
| `POSTGRES_ANALYTICS_DATASOURCE_USERNAME` | Analytics DB username | `postgres` |
| `POSTGRES_ANALYTICS_DATASOURCE_PASSWORD` | Analytics DB password | `postgres` |
| `CONFIG_REST_SECURITY_MODE` | Security mode (`oidc` or `none`) | `oidc` |

> DIAL Core, file storage, MCP, metric-provider, and observability settings have their own
> environment variables — see [docs/configuration.md](docs/configuration.md) for the complete list.

## Project Structure

```
src/
├── main/
│   ├── java/com/epam/aidial/evaluation/
│   │   ├── client/                 # External clients (dialcore, mcp, metricprovider)
│   │   ├── configuration/          # Spring configurations
│   │   │   ├── datasource/         # Meta + analytics DataSource setup
│   │   │   ├── logging/            # Logging, correlation IDs
│   │   │   ├── properties/         # Config properties
│   │   │   └── security/           # Security configurations
│   │   ├── constants/              # Bounded-context constants (Security, Validation, ...)
│   │   ├── data/
│   │   │   └── db/                 # Database layer (jOOQ typed DSL, no JPA)
│   │   │       ├── analytics/      # Analytics mappers, models, repositories, cursor pagination
│   │   │       ├── exception/      # DB-specific exceptions
│   │   │       ├── mapper/         # Record → domain RecordMappers (meta)
│   │   │       ├── model/          # Domain models (+ filter/, pagination/)
│   │   │       ├── repository/     # Meta data access (+ sql/ builders, sql/json/)
│   │   │       └── transaction/    # Transaction utilities (+ timestamp/)
│   │   ├── service/
│   │   │   ├── domain/             # Business logic
│   │   │   │   ├── analytics/      # Analytics services, CursorCodec
│   │   │   │   ├── csv/            # CSV processing
│   │   │   │   ├── dto/            # DTOs with validation
│   │   │   │   ├── exception/      # Custom exceptions
│   │   │   │   ├── filter/         # Filter parsing & execution
│   │   │   │   ├── job/            # Job execution / SSE parsing
│   │   │   │   ├── mapper/         # MapStruct mappers
│   │   │   │   └── sort/           # Sort parsing & execution
│   │   │   └── infrastructure/     # Cross-cutting concerns
│   │   │       ├── health/         # Health indicators
│   │   │       ├── logger/         # Dynamic logging
│   │   │       └── transaction/    # Transaction aspects
│   │   ├── utils/                  # Utility classes
│   │   └── web/
│   │       ├── controller/         # REST controllers
│   │       ├── filter/             # Servlet filters
│   │       ├── handler/            # Exception handlers
│   │       ├── pagination/         # Pagination/filter param resolvers
│   │       └── security/           # JWT/OIDC security
│   ├── java-generated/             # jOOQ-generated sources (data/db/jooq/{meta,analytics}) — do not edit
│   └── resources/
│       ├── db/migration/meta/POSTGRES/       # Meta Flyway migrations
│       ├── db/migration/analytics/POSTGRES/  # Analytics Flyway migrations
│       ├── openapi/                # OpenAPI examples
│       ├── schemas/                # JSON schemas
│       ├── application.yml         # Application configuration
│       ├── application-local.yml   # Local profile overrides
│       └── log4j2.xml              # Logging configuration
└── test/
    └── java/com/epam/aidial/evaluation/
        ├── architectural/          # Architecture tests (ArchUnit)
        └── functional/             # Functional tests with Testcontainers
```

## Design Documentation

Architecture and behavior are specified with [OpenSpec](https://github.com/Fission-AI/OpenSpec).
The authoritative description of each capability — entities, APIs, and design decisions — lives in
the spec files under `openspec/`:

- [OpenSpec Specifications](openspec/specs/README.md) - Indexed, per-capability baseline specs (datasets, test suites, runs, analytics, metrics, DIAL Core integration, security, and more) — the source of truth for system behavior and design
- [Configuration Reference](docs/configuration.md) - Detailed configuration options
- [Database Schema Reference](docs/database-schema.md) - Tables, columns, indexes, and JSONB schemas

### OpenSpec Workflow

Changes are proposed and implemented as spec deltas, then synced back into the baseline specs:

- `openspec/specs/` — main (baseline) specifications, one folder per capability
- `openspec/changes/` — in-flight change proposals (proposal, design, tasks, delta specs)
- `openspec/config.yaml` — coding standards and architectural conventions

See [AGENTS.md](AGENTS.md) for the contributor workflow and project conventions.

## Building Docker Image

```bash
# Build the application
./gradlew clean bootJar

# Build Docker image
docker build -t ai-dial-admin-evaluation-framework-backend .
```

## License

Apache License 2.0
