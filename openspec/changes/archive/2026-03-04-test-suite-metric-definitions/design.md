## Context

Metric declarations (MetricDeclaration + MetricDeclarationVersion) already exist as a synced catalog of available metrics with versioned config/input/output JSON schemas. Test suites exist as evaluation specifications with test case schemas, response columns, request templates, and input bindings. There is currently no link between these two domains — users cannot specify which metrics should be applied to a test suite's results.

Test Suite Metric Definitions (TSMD) bridge this gap by materializing the selection and configuration of metrics within a test suite. Each TSMD references a metric declaration and its version, and defines parameter bindings that map metric input/config properties to concrete data sources (test case columns, response columns, or constant values).

The existing codebase follows a consistent layered pattern for entities: model → RowMapper → repository → service → controller → DTOs → MapStruct mapper → Flyway migration → functional tests. TSMD will follow this same pattern.

## Goals / Non-Goals

**Goals:**
- Introduce a `test_suite_metric_definitions` table in the meta database
- Provide full CRUD REST API nested under test suites (`/api/v1/test-suites/{suiteId}/metric-definitions`)
- Support paginated listing with filtering by `name` and sorting by `name`, `createdAt`
- Store parameter bindings as JSONB with polymorphic source types (`TestCase`, `Response`, `Constant`)
- Resolve `metric_declaration_version_id` server-side to latest version at creation time
- Update documentation (database schema, ER model)

**Non-Goals:**
- Validation of bindings against metric declaration schemas (future work)
- Staleness detection / out-of-date version warnings (future work)
- Validation that multiple TSMDs referencing the same metric have different bindings (explicitly out of scope)
- Metric result computation or execution integration
- `created_by` field on TSMD (only `test_suites` has this currently)
- Optimistic locking / `version` field on TSMD

## Decisions

### D1: Separate table, not embedded JSONB on test_suites

**Decision**: TSMD is a separate `test_suite_metric_definitions` table with its own PK, not a JSONB array field on `test_suites`.

**Rationale**: MetricResult (future) needs a stable FK to `tsmd_id` for analytics. A separate table enables independent CRUD, querying, filtering, and sorting. It also keeps the test_suites row from growing unboundedly.

**Alternatives considered**: Embedding as JSONB on test_suites — simpler CRUD but no stable FK target, harder to query independently, and test_suites row size grows with metric count.

### D2: Required FK to metric_declaration_version_id, resolved server-side

**Decision**: `metric_declaration_version_id` is a required NOT NULL FK to `metric_declaration_versions`. On create, the backend resolves it to the latest `MetricDeclarationVersion` (by `schema_version DESC`) for the referenced `metric_declaration_id`. On update, re-resolve to latest.

**Rationale**: Provides a clear audit trail for future staleness detection without burdening the API consumer. The client only needs to supply `metricDeclarationId`; the version is resolved automatically.

**Alternatives considered**: Nullable FK (null = "use latest") — simpler but weaker for staleness detection. Plain `schema_version` int — requires a lookup to resolve the actual version record.

### D3: Polymorphic binding sources via `$type` discriminator

**Decision**: `config_bindings` and `input_bindings` are JSONB arrays where each element has a `property` (target schema key) and a `source` object with a `$type` discriminator field. Three source types: `TestCase` (with `columnName`), `Response` (with `columnName`), `Constant` (with `value` as any non-null JSON value — null is excluded due to global `NON_NULL` Jackson serialization).

**Rationale**: Clean polymorphic model that maps well to Jackson `@JsonTypeInfo`/`@JsonSubTypes`. The `$type` prefix avoids collision with schema property names. Three source types cover the immediate use cases.

**Alternatives considered**: Flat structure with mutually exclusive fields (like current `InputBindingDto`) — works but less extensible and harder to validate. Separate tables for bindings — over-normalized for what are essentially configuration details.

### D4: Flat property targeting (top-level schema keys only)

**Decision**: The `property` field in each binding refers to a top-level key in the metric's `config_schema` or `input_schema`. No nested path support.

**Rationale**: Metric schemas are expected to keep input/config properties flat. Flat targeting keeps binding resolution trivially simple. Nested path support can be added later if schemas evolve.

### D5: UNIQUE(test_suite_id, LOWER(name)) constraint

**Decision**: TSMD names must be unique within a test suite (case-insensitive), matching the existing pattern used by test_suites and test_cases.

**Rationale**: The name is the user-facing label for this metric application. Uniqueness prevents confusion when multiple instances of the same metric are used with different bindings.

### D6: No optimistic locking, no created_by

**Decision**: TSMD does not have a `version` field for optimistic locking or a `created_by` field.

**Rationale**: `created_by` exists only on `test_suites` in the current codebase — no other child entity has it. Optimistic locking is not needed for initial scope since TSMDs are simple configuration entities without concurrent editing concerns.

### D7: API follows test-case nesting pattern

**Decision**: REST endpoints are nested under test suites: `/api/v1/test-suites/{suiteId}/metric-definitions/{id}`. Full CRUD with paginated list, filter by `name`, sort by `name` and `createdAt`.

**Rationale**: Consistent with how test cases relate to test suites in the existing API.

### D8: CASCADE delete from test_suites

**Decision**: `ON DELETE CASCADE` on the `test_suite_id` FK. When a test suite is deleted, all its TSMDs are automatically removed.

**Rationale**: Consistent with test_cases and test_suite_runs behavior. TSMDs are meaningless without their parent suite.

## Risks / Trade-offs

- **[No binding validation]** → Bindings are stored without validating that `columnName` exists in test_case_schema or responseColumns, or that `property` exists in the metric schema. Mitigation: this is explicitly out of scope; future validation work will address this. Invalid bindings won't cause runtime failures until metric execution is implemented.
- **[Version drift]** → The stored `metric_declaration_version_id` may become stale if the metric declaration is updated. Mitigation: future staleness detection feature will compare stored version against latest. For now, re-resolving on update keeps the version current when users actively edit TSMDs.
- **[No cascade from metric_declarations]** → Deleting a metric declaration will fail if TSMDs reference it (FK constraint). Mitigation: metric declarations are synced from providers and not user-deletable in the current system. If deletion is needed in the future, we can add cascade or validation logic.

## Migration Plan

- Single Flyway migration `V1.13__CreateTestSuiteMetricDefinitionsTable.sql` in `db/migration/meta/POSTGRES/`
- Additive change — no modification to existing tables
- No data migration needed (new table starts empty)
- Rollback: drop the table (no dependencies on it from existing entities)
