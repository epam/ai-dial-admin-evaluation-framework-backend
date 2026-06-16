## Why

Current TestSuite management is a temporary stub and does not support real-world evaluation authoring needs (deployment/endpoint binding, large test case datasets, CSV workflows, or schema-aware handling needed for later metric binding, endpoint invocation, and analytics).

We need to introduce a production-ready TestSuite authoring model now so upcoming work (runs/jobs, metric binding, analytics table generation) has a stable, schema-driven foundation.

## What Changes

- **BREAKING**: Replace the current TestSuite REST contract (DTO shape) with a business-driven TestSuite aggregate that embeds `deploymentRef` and `endpointRef`.
- Add a dedicated API for **TestCase** management under a specific TestSuite (nested under TestSuite), including CRUD + pagination/sorting/filtering.
- Add CSV workflows for test cases:
  - Export TestCasesDefinition dataset to CSV
  - Bulk upload CSV into TestCasesDefinition with **auto schema detection** (columns -> parameters/facts; type inference)
- Store schema/data documents in Postgres as **`jsonb`** (endpointRef contract, test case schema, test case parameters/facts).
- TestCasesDefinition properties (schema/columns/metadata) are managed as an embedded part of TestSuite (no separate `/test-case-definitions` API).
- Avoid duplicating endpoint contract: persist `endpointRef` only on TestSuite and use it for schema detection/validation.
- Introduce a reusable list filtering spec used across entity list endpoints (repeatable structured filters via `filter=<field>:<op>:<value>` with whitelisted fields/operators).
- Add a small JDBC helper `JsonbSqlParameter` to consistently write/read `jsonb` values without sprinkling casts across repositories.
- Metric definitions: expose only a **list endpoint stub** for now (full metric management is deferred).

## Capabilities

### New Capabilities

- `test-cases`: Manage test case datasets and cases via dedicated API.
  - Covers TestCasesDefinition schema, TestCase CRUD, partial updates (PATCH), and CSV import/export with auto schema detection.
- `entity-filtering`: Standardized filtering/search for list endpoints.
  - Defines a safe, reusable `filter` DSL backed by per-entity whitelists.

### Modified Capabilities

- `test-suites`: Evolve TestSuite from a simple entity to a real authoring aggregate.
  - Includes embedded `deploymentRef` + `endpointRef` (OpenAPI 3.1 operation contract), references to test case datasets, and metric binding placeholders.
  - **BREAKING** DTO/API contract changes; no backward compatibility required.
- `metrics-system`: Minor adjustment to support discovery via a list-only stub endpoint (full metric definition/versioning remains planned).

## Impact

- **APIs**:
  - Existing `GET/POST/PUT/DELETE /api/v1/test-suites` remain as routes but their payload schema changes (**BREAKING**).
  - New routes for TestCasesDefinition/TestCase management and CSV import/export.
  - New list-only route for metric definitions (stub).
  - All list endpoints adopt shared filtering/search semantics (`q`, `filter`, plus pagination/sorting).
  - Note: for v1 we will start with structured `filter` only (no `q` free-text search).
- **Database/migrations**:
  - Extend/replace `test_suites` schema and introduce new tables for test case definitions/cases and metric bindings.
  - Introduce `jsonb` columns for endpointRef, schemas, and case payloads.
- **Implementation**:
  - New JDBC repositories, RowMappers, MapStruct DTO mappers, services, and controllers following existing conventions.
  - Add `JsonbSqlParameter` helper for consistent `jsonb` parameter binding.
- **Follow-up / open question (future task)**:
  - Refine filtering DSL (OR groups, richer operators, jsonb-path filters, or adopting a standard like RSQL/OData) once UI and usage patterns are clearer.

