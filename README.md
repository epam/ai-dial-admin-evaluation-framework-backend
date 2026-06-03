# AI DIAL Admin Evaluation Framework Backend

A Spring Boot backend service for the AI DIAL Admin Evaluation Framework. This service manages the lifecycle of LLM/AI model evaluations, including test suite authoring, execution tracking, and metrics collection.

## Features

### Core Functionality
- **Test Suite Management**: Full CRUD operations for Test Suites and Test Cases
- **Evaluation Runs**: Track test suite execution and results
- **Metrics System**: Support for metric declarations, versioning, and result storage
- **Pagination**: Built-in pagination support for list endpoints

### Technical Features
- **Security**: OIDC/JWT-based authentication with multi-issuer support
- **Database**: PostgreSQL with Flyway migrations (JDBC-only, no JPA)
- **API Documentation**: OpenAPI/Swagger UI
- **Health Checks**: Custom health indicators for database and dependencies
- **Correlation ID**: Request correlation for distributed tracing
- **Dynamic Logging**: Runtime log level configuration
- **Transaction Timestamps**: Consistent timestamps within transactions

## Quick Start

### Prerequisites

- Java 25
- Docker (for local development with PostgreSQL)
- Gradle 8.13+

### Running Locally with Docker Compose

```bash
# Start PostgreSQL and the application
docker-compose up -d

# Or start only PostgreSQL
docker-compose up -d postgres
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
- **Health Check**: http://localhost:8080/api/v1/health

### Test Suites API

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/test-suites` | Create a new test suite |
| GET | `/api/v1/test-suites` | List all test suites (paginated) |
| GET | `/api/v1/test-suites/{id}` | Get a test suite by ID |
| PUT | `/api/v1/test-suites/{id}` | Update a test suite |
| DELETE | `/api/v1/test-suites/{id}` | Delete a test suite |

## Configuration

See [docs/configuration.md](docs/configuration.md) for detailed configuration options.

### Key Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `POSTGRES_DATASOURCE_URL` | PostgreSQL connection URL | `jdbc:postgresql://localhost:5432/evaluation_db` |
| `POSTGRES_DATASOURCE_USERNAME` | Database username | `postgres` |
| `POSTGRES_DATASOURCE_PASSWORD` | Database password | `postgres` |
| `CONFIG_REST_SECURITY_MODE` | Security mode (`oidc` or `none`) | `oidc` |

## Project Structure

```
src/
├── main/
│   ├── java/com/epam/aidial/evaluation/
│   │   ├── configuration/          # Spring configurations
│   │   │   ├── datasource/         # DataSource setup
│   │   │   ├── logging/            # Logging, correlation IDs
│   │   │   ├── properties/         # Config properties
│   │   │   └── security/           # Security configurations
│   │   ├── data/
│   │   │   └── db/                 # Database layer
│   │   │       ├── mapper/         # JDBC RowMappers
│   │   │       ├── model/          # Domain models + pagination
│   │   │       ├── repository/     # Data access (interface + impl)
│   │   │       └── transaction/    # Transaction utilities
│   │   ├── service/
│   │   │   ├── domain/             # Business logic
│   │   │   │   ├── dto/            # DTOs with validation
│   │   │   │   ├── exception/      # Custom exceptions
│   │   │   │   └── mapper/         # MapStruct mappers
│   │   │   └── infrastructure/     # Cross-cutting concerns
│   │   │       ├── health/         # Health indicators
│   │   │       ├── logger/         # Dynamic logging
│   │   │       └── transaction/    # Transaction aspects
│   │   ├── utils/                  # Utility classes
│   │   └── web/
│   │       ├── controller/         # REST controllers
│   │       ├── handler/            # Exception handlers
│   │       └── security/           # JWT/OIDC security
│   └── resources/
│       ├── db/migration/POSTGRES/  # Flyway migrations
│       ├── application.yml         # Application configuration
│       └── log4j2.xml              # Logging configuration
└── test/
    └── java/com/epam/aidial/evaluation/
        ├── architectural/          # Architecture tests (ArchUnit)
        └── functional/             # Functional tests with Testcontainers
```

## Design Documentation

For detailed architecture and design decisions, see:

- [Entity-Relationship Model](docs/design/entity-relationship-model.md) - Data model design, entity catalog, and technology decisions
- [Infrastructure Architecture](docs/design/infrastructure-architecture.md) - Component architecture, deployment model, and data flows
- [Configuration Reference](docs/configuration.md) - Detailed configuration options

## Building Docker Image

```bash
# Build the application
./gradlew clean bootJar

# Build Docker image
docker build -t ai-dial-admin-evaluation-framework-backend .
```

## License

Apache License 2.0
