# Database Schema Reference

> **Status**: Synchronized with Flyway migrations
> **Last sync**: 2026-08-17 (meta V1.29, analytics V1.18)
> **Databases**: Meta (PostgreSQL) + Analytics (PostgreSQL)

This document describes the current database schema as implemented by Flyway migrations.

---

## Tables Overview

### Meta Database

| Table | Description | Primary Key |
|-------|-------------|-------------|
| `datasets` | Central owner of `testCaseSchema` and test cases; multiple suites may bind to one dataset | `id` (VARCHAR(36)) |
| `test_suites` | Evaluation test suite definitions; optionally binds to a dataset via NULLABLE `dataset_id` (unbound suites cannot run) | `id` (VARCHAR(36)) |
| `test_cases` | Individual test cases within a dataset | `id` (VARCHAR(36)) |
| `test_suite_runs` | Async test suite run tracking | `id` (VARCHAR(36)) |
| `test_case_run_inputs` | Snapshot of test case data for a run (written at async phase start) | `(run_id, position)` (composite) |
| `revalidation_tasks` | Async dataset revalidation task tracking | `id` (VARCHAR(36)) |
| `metric_declarations` | Metric declarations (catalog; synced from metric providers) | `id` (VARCHAR(36)) |
| `metric_declaration_versions` | Schema versions per metric declaration | `id` (VARCHAR(36)) |
| `test_suite_metric_definitions` | Metric applications within a test suite | `id` (VARCHAR(36)) |

### Analytics Database

| Table | Description | Primary Key |
|-------|-------------|-------------|
| `test_case_run_results` | Test case execution results | `(created_at_ms, id)` (composite) |
| `test_case_eval_summaries` | Metric-enriched test case results (denormalized) | `(created_at_ms, id)` (composite) |
| `run_metric_snapshots` | Metric definition snapshots per computation batch | `id` (VARCHAR(36)) |

---

## Table: `datasets`

Central owner of `testCaseSchema` and the test-case table. Multiple test suites may bind to the same dataset via `test_suites.dataset_id`. Introduced by V1.22; each pre-existing suite was migrated into a paired dataset named `'DATASET_' || suite.name`.

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | VARCHAR(36) | NOT NULL | - | Primary key (UUID) |
| `name` | VARCHAR(263) | NOT NULL | - | Dataset name (unique, case-insensitive). Width is 255 + 8 to fit the `DATASET_` prefix applied to suite names during the V1.22 backfill. |
| `description` | VARCHAR(2000) | NULL | - | Optional description |
| `test_case_schema` | JSONB | NOT NULL | `'[]'::jsonb` | Column definitions (List of FieldDefinitionDto) |
| `is_valid` | BOOLEAN | NOT NULL | TRUE | Dataset-level validation status |
| `validation_warnings` | JSONB | NOT NULL | `'[]'::jsonb` | Structured validation warnings |
| `visibility` | VARCHAR(16) | NOT NULL | - | `'PUBLIC'` or `'PRIVATE'`. CHECK constraint `ck_datasets_visibility` enforces the enum. PUBLIC datasets are listed in `GET /api/v1/datasets`; PRIVATE datasets are catalogue-hidden and bind to exactly one suite (enforced by the `tg_test_suites_private_binding_guard` trigger on `test_suites`). Backfilled to `'PRIVATE'` for all V1.22 rows; default dropped after backfill so future inserts must supply visibility explicitly. |
| `version` | BIGINT | NOT NULL | 0 | Optimistic locking version |
| `created_by` | VARCHAR(255) | NOT NULL | - | User who created the dataset |
| `created_at_ms` | BIGINT | NOT NULL | - | Creation timestamp (epoch ms) |
| `updated_at_ms` | BIGINT | NOT NULL | - | Last update timestamp (epoch ms) |

### Indexes

| Index Name | Columns | Type | Notes |
|------------|---------|------|-------|
| `uq_datasets_name` | `LOWER(name)` | UNIQUE (BTREE) | Case-insensitive unique constraint on dataset name |
| `idx_datasets_created_at_ms` | `created_at_ms DESC` | BTREE | |

### Constraints

| Constraint Name | Definition |
|-----------------|------------|
| `ck_datasets_visibility` | `CHECK (visibility IN ('PUBLIC', 'PRIVATE'))` |

### JSONB Column Schemas

**`test_case_schema`** (List of FieldDefinitionDto):
```json
[
  {
    "name": "string",
    "type": "STRING|INTEGER|NUMBER|BOOLEAN|OBJECT|ARRAY|FILE",
    "required": true,
    "description": "string"
  }
]
```

---

## Table: `test_suites`

Test suite definitions that bind to a dataset for their test cases and schema.

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | VARCHAR(36) | NOT NULL | - | Primary key (UUID) |
| `name` | VARCHAR(255) | NOT NULL | - | Suite name |
| `description` | VARCHAR(2000) | NULL | - | Optional description |
| `created_by` | VARCHAR(255) | NOT NULL | - | User who created the suite |
| `suite_type` | VARCHAR(20) | NOT NULL | `'DEPLOYMENT'` | Suite type discriminator: `DEPLOYMENT` or `MCP_TOOL` |
| `dataset_id` | VARCHAR(36) | NULL | - | Optional FK to `datasets.id` (RESTRICT on delete; PRIVATE-bound suites are cascade-deleted via service-layer logic, see `tg_test_suites_private_binding_guard` for binding-uniqueness). When NULL the suite is in the **unbound** state — editable but `POST /api/v1/test-suites/{id}/runs` returns HTTP 409 `SUITE_HAS_NO_DATASET`. The `tg_test_suites_private_binding_guard` BEFORE INSERT OR UPDATE OF `dataset_id` trigger rejects a second concurrent binding to any PRIVATE dataset (raises `ERRCODE='P0001'` MESSAGE `'PRIVATE_DATASET_ALREADY_BOUND'` → HTTP 409). |
| `disabled_test_case_ids` | JSONB | NOT NULL | `'[]'::jsonb` | Legacy per-suite exclude list (array of UUIDs). Retained in the schema for backward compatibility but **no longer read or written by application code** — the runnable set (run-creation count, snapshot materialization) is defined solely by `is_valid` and `test_case_filter`. Inserts omit the column and rely on the DDL default; existing rows keep whatever stale content they had. Column removal is a pending follow-up change. |
| `deployment_ref` | JSONB | NULL | - | Deployment reference (DeploymentReferenceDto) — HTTP suites |
| `endpoint_ref` | JSONB | NULL | - | Endpoint contract definition (EndpointContractDto) — HTTP suites |
| `response_columns` | JSONB | NOT NULL | `'[]'::jsonb` | Response column definitions (List of ResponseColumnDefinitionDto) |
| `request_template` | JSONB | NULL | - | Postman-style request template (RequestTemplateDto) — HTTP suites |
| `input_bindings` | JSONB | NOT NULL | `'[]'::jsonb` | Bindings from template variables to data fields (List of InputBindingDto) |
| `mcp_deployment_ref` | JSONB | NULL | - | MCP deployment reference (McpDeploymentReferenceDto) — MCP suites |
| `tool_ref` | JSONB | NULL | - | MCP tool reference with schema (ToolReferenceDto) — MCP suites |
| `argument_template` | JSONB | NULL | - | MCP argument template with bindings (ArgumentTemplateDto) — MCP suites |
| `overall_score` | JSONB | NULL | - | Per-suite `overall` metric-score definition — a serialized `StructuredQuery` expression. Settable and readable via the suite API (`overallScore` on `POST`/`PUT`/`GET /api/v1/test-suites`); references configured metric columns by their flattened name `metric::<metricName>::<outputField>`. NULL = system default (the single metric's average — `avg(:metricField)` — computed only when the run has exactly one numeric metric field). Captured verbatim into the suite snapshot per run; Phase 3 honors a non-null value for any metric count. See V1.23. |
| `test_case_filter` | JSONB | NULL | - | Per-suite test-case selection filter — a serialized Structured Query DSL `filter` subtree authored over the dataset's test-case fields (base columns and flattened `data::<field>` fields). Settable and readable via the suite API (`testCaseFilter` on `POST`/`PUT`/`GET /api/v1/test-suites`); validated at write time against the bound dataset's test-case schema (unknown field/type/malformed → HTTP 400). NULL = no filter (run every valid test case). When set, it is AND-combined with `is_valid` to select the runnable test cases at run-creation count and snapshot. Does not affect suite validity. See V1.24. |
| `overall_score_threshold` | DOUBLE PRECISION | NULL | - | Optional per-suite threshold, same numeric type as the computed run-level `overall` metric score result (`metric_score_result.value`). Settable and readable via the suite API (`overallScoreThreshold` on `POST`/`PUT`/`GET /api/v1/test-suites`); validated at write time to be within `[0.0, 1.0]` inclusive (HTTP 400 `VALIDATION_ERROR` otherwise). NULL = no threshold configured. Not compared server-side against a run's computed score — comparison is a client-side concern. Not captured in the suite snapshot. See V1.25. |
| `additional_requests` | JSONB | NOT NULL | `'[]'::jsonb` | Ordered chain of requests 1..N executed after request #0 (the suite's own `endpoint_ref`/`request_template`/`response_columns`/`input_bindings`), against the same `deployment_ref` (List of RequestDefinitionDto). Response columns across request #0 and every additional request share one flat, globally-unique namespace capped at `RunnerValidationConstants.MAX_RESPONSE_COLUMNS` (50) in total; chain length capped at `RunnerValidationConstants.MAX_ADDITIONAL_REQUESTS` (10). Rejected (400) when non-empty on an `MCP_TOOL` suite. Settable and readable via the suite API (`additionalRequests` on `POST`/`PUT`/`GET /api/v1/test-suites`); rewritten (`@ef/suites/{id}/` prefix) on clone. Captured into the suite snapshot per run. See V1.29. |
| `request_name` | VARCHAR(255) | NULL | - | Optional user-facing label for request #0, mirroring `RequestDefinitionDto.name` on each additional request, so every request in the chain is labellable (e.g. for a metric `condition`'s `request.name`). NULL = request #0 unlabelled. Settable and readable via the suite API (`requestName` on `POST`/`PUT`/`GET /api/v1/test-suites`); captured into the suite snapshot per run. See V1.29. |
| `is_valid` | BOOLEAN | NOT NULL | TRUE | Suite-level validation status |
| `validation_warnings` | JSONB | NOT NULL | `'[]'::jsonb` | Structured validation warnings |
| `version` | BIGINT | NOT NULL | 0 | Optimistic locking version |
| `created_at_ms` | BIGINT | NOT NULL | - | Creation timestamp (epoch ms) |
| `updated_at_ms` | BIGINT | NOT NULL | - | Last update timestamp (epoch ms) |

### Indexes

| Index Name | Columns | Type | Notes |
|------------|---------|------|-------|
| `uq_test_suites_name` | `LOWER(name)` | UNIQUE (BTREE) | Case-insensitive unique constraint on suite name |
| `idx_test_suites_dataset_id` | `dataset_id` | BTREE | Lookup for suites bound to a dataset |
| `idx_test_suites_created_at_ms` | `created_at_ms DESC` | BTREE | |

### Foreign Keys

| Column | References | On Delete |
|--------|------------|-----------|
| `dataset_id` | `datasets(id)` | RESTRICT |

### JSONB Column Schemas

**`deployment_ref`** (DeploymentReferenceDto):
```json
{
  "id": "string",
  "name": "string",
  "version": "string",
  "type": "string (optional, e.g. dial-model / dial-application)"
}
```

**`endpoint_ref`** (EndpointContractDto):
```json
{
  "relativeUrlPattern": "string",
  "method": "GET|POST|PUT|DELETE|PATCH",
  "operationId": "string",
  "parameters": [
    {
      "name": "string",
      "in": "PATH|QUERY|HEADER",
      "required": "boolean",
      "schema": { /* JSON Schema Draft-07 */ }
    }
  ],
  "requestBodySchema": { /* polymorphic — see RequestBodySchemaDto variants below */ },
  "responseBodySchema": { /* JSON Schema Draft-07 */ }
}
```

**`disabled_test_case_ids`** (array of UUIDs; retained but unused by application code — see the `test_suites` table description above):
```json
["11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222"]
```

**`response_columns`** (List of ResponseColumnDefinitionDto):
```json
[
  {
    "name": "string",
    "displayName": "string (optional)",
    "expression": "string (JSONata expression)",
    "type": "STRING|INTEGER|NUMBER|BOOLEAN|OBJECT|ARRAY (optional, defaults to STRING)"
  }
]
```

**`request_template`** (RequestTemplateDto):
```json
{
  "urlTemplate": "string",
  "queryParams": [{"key": "string", "value": "string"}],
  "headers": [{"key": "string", "value": "string"}],
  "body": { /* polymorphic — see RequestBodyDto variants below */ }
}
```

**`input_bindings`** (List of InputBindingDto):
```json
[
  {
    "templateVariable": "string",
    "dataField": "string (mutually exclusive with constantValue)",
    "constantValue": "string (mutually exclusive with dataField)"
  }
]
```

#### Polymorphic RequestBodyDto Variants

The `request_template.body` field uses a `contentType` discriminator. Variants:

**JSON body** (`contentType: "application/json"`):
```json
{
  "contentType": "application/json",
  "content": { /* any JSON object with ${{variable}} placeholders */ }
}
```

**Multipart form-data body** (`contentType: "multipart/form-data"`):
```json
{
  "contentType": "multipart/form-data",
  "content": [
    {
      "name": "string",
      "type": "text|file",
      "value": "string (with ${{variable}} placeholders)",
      "filename": "string (optional, for file parts)"
    }
  ]
}
```

**URL-encoded body** (`contentType: "application/x-www-form-urlencoded"`):
```json
{
  "contentType": "application/x-www-form-urlencoded",
  "content": [
    { "key": "string", "value": "string (with ${{variable}} placeholders)" }
  ]
}
```

#### Polymorphic RequestBodySchemaDto Variants

The `endpoint_ref.requestBodySchema` field uses a `contentType` discriminator. Variants:

**JSON schema** (`contentType: "application/json"`):
```json
{
  "contentType": "application/json",
  "schema": { /* JSON Schema Draft-07 */ }
}
```

**Multipart form-data schema** (`contentType: "multipart/form-data"`):
```json
{
  "contentType": "multipart/form-data",
  "parts": [
    {
      "name": "string",
      "type": "text|file",
      "required": "boolean",
      "schema": { /* JSON Schema Draft-07, for text parts */ },
      "allowedContentTypes": ["string"],
      "maxSizeBytes": "number"
    }
  ]
}
```

**URL-encoded schema** (`contentType: "application/x-www-form-urlencoded"`):
```json
{
  "contentType": "application/x-www-form-urlencoded",
  "schema": { /* JSON Schema Draft-07 */ }
}
```

**`additional_requests`** (List of RequestDefinitionDto — requests 1..N of the chain; request #0 is the suite's own `endpoint_ref`/`request_template`/`response_columns`/`input_bindings`):
```json
[
  {
    "name": "string (optional, max 255 — user-facing label)",
    "endpointRef": { /* EndpointContractDto — same shape as endpoint_ref above */ },
    "requestTemplate": { /* RequestTemplateDto — same shape as request_template above */ },
    "responseColumns": [ /* List of ResponseColumnDefinitionDto — same shape as response_columns above */ ],
    "inputBindings": [ /* List of InputBindingDto — same shape as input_bindings above */ ]
  }
]
```
Deliberately excludes `deploymentRef`, MCP fields, `testCaseFilter` and `overallScore` — those stay suite-level (1-to-1 suite↔deployment relationship). Response-column names across `response_columns` and every `additional_requests[*].responseColumns` form one flat, globally-unique namespace (no per-request prefixing).

**`mcp_deployment_ref`** (McpDeploymentReferenceDto):
```json
{
  "id": "string (required — deployment ID)",
  "type": "string (required — 'dial-toolset' or 'dial-application')",
  "name": "string (optional — display name)",
  "transport": "string (optional — transport type)"
}
```

**`tool_ref`** (ToolReferenceDto):
```json
{
  "name": "string (required — tool name)",
  "description": "string (optional)",
  "inputSchema": { /* JSON Schema (Map) — required */ },
  "outputSchema": { /* JSON Schema (Map) — nullable */ }
}
```

**`argument_template`** (ArgumentTemplateDto):
```json
{
  "arguments": {
    "argName": "${{variableName}} or constant value"
  }
}
```

---

## Table: `test_cases`

Individual test cases belonging to a dataset. Per-suite run selection is controlled by `test_suites.test_case_filter` (combined with `is_valid`) rather than a column on this table; `test_suites.disabled_test_case_ids` is a retained-but-unused legacy column (see the `test_suites` table).

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | VARCHAR(36) | NOT NULL | - | Primary key (UUID) |
| `dataset_id` | VARCHAR(36) | NOT NULL | - | FK to `datasets.id` (CASCADE on delete). Renamed from `test_suite_id` in V1.22. |
| `test_case_name` | VARCHAR(255) | NOT NULL | - | Display name |
| `data` | JSONB | NOT NULL | `'{}'::jsonb` | Unified test case data map (single-turn). Empty `'{}'` for a multi-turn case. |
| `multi_turn_data` | JSONB | NULL | - | Ordered array of per-turn data maps for a multi-turn test case (V1.27); NULL for single-turn (the discriminator). MAY coexist with a populated `data`: `data` carries the case's shared (test-case-level) fields and each turn map carries the per-turn fields. Field scope is declared per field in `datasets.test_case_schema` (`FieldDefinitionDto.perTurn`). No mutual-exclusivity CHECK constraint. |
| `is_valid` | BOOLEAN | NOT NULL | - | Validation status |
| `validation_warnings` | JSONB | NOT NULL | `'[]'::jsonb` | Structured validation warnings |
| `created_at_ms` | BIGINT | NOT NULL | - | Creation timestamp (epoch ms) |
| `updated_at_ms` | BIGINT | NOT NULL | - | Last update timestamp (epoch ms) |

### Indexes

| Index Name | Columns | Type | Notes |
|------------|---------|------|-------|
| `uq_test_cases_dataset_name` | `dataset_id`, `LOWER(test_case_name)` | UNIQUE (BTREE) | Case-insensitive unique constraint: one name per dataset |
| `idx_test_cases_dataset_id` | `dataset_id` | BTREE | |
| `idx_test_cases_created_at_ms` | `created_at_ms DESC` | BTREE | |
| `idx_test_cases_data` | `data` | GIN | |

### Foreign Keys

| Column | References | On Delete |
|--------|------------|-----------|
| `dataset_id` | `datasets(id)` | CASCADE |

### JSONB Column Schemas

**`validation_warnings`** (List of ValidationWarningDto):
```json
[
  {
    "fieldName": "string",
    "path": "string",
    "message": "string",
    "code": "REQUIRED|TYPE|FORMAT|PATTERN|ENUM|ADDITIONAL|UNRESOLVED_REFERENCE|INVALID_OUTPUT_SCHEMA|REQUEST_BODY_EVALUATION_ERROR|UNKNOWN|INVALID_INPUT|INVALID_SCOPE",
    "turnIndex": "number (optional, 0-based; multi-turn cases only)"
  }
]
```

---

## Table: `test_suite_runs`

Tracks async test suite evaluation runs.

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | VARCHAR(36) | NOT NULL | - | Primary key (UUID) |
| `test_suite_id` | VARCHAR(36) | NOT NULL | - | FK to test_suites.id |
| `test_run_name` | VARCHAR(255) | NOT NULL | - | Display name (auto-generated or user-provided) |
| `status` | VARCHAR(20) | NOT NULL | - | PENDING, RUNNING, COMPLETED, FAILED, CANCELLED |
| `run_config` | JSONB | NOT NULL | - | Run configuration (RunConfigDto) |
| `number_of_test_cases` | INTEGER | NOT NULL | - | Preview count at creation; finalized at snapshot phase to match `COUNT(test_case_run_inputs)` |
| `suite_snapshot` | JSONB | NULL | - | Execution-relevant suite configuration captured at snapshot phase (SuiteSnapshotDto) |
| `started_at_ms` | BIGINT | NULL | - | Run start timestamp (epoch ms) |
| `completed_at_ms` | BIGINT | NULL | - | Run completion timestamp (epoch ms) |
| `error_message` | TEXT | NULL | - | Error message if failed |
| `error_details` | JSONB | NULL | - | Structured error details (RunErrorDetailsDto) |
| `created_at_ms` | BIGINT | NOT NULL | - | Creation timestamp (epoch ms) |
| `updated_at_ms` | BIGINT | NOT NULL | - | Last update timestamp (epoch ms) |

### Indexes

| Index Name | Columns | Type | Notes |
|------------|---------|------|-------|
| `idx_test_suite_runs_test_suite_id` | `test_suite_id` | BTREE | |
| `idx_test_suite_runs_status` | `status` | BTREE | |
| `idx_test_suite_runs_created_at_ms` | `created_at_ms DESC` | BTREE | |
| `uq_test_suite_runs_suite_name` | `test_suite_id`, `test_run_name` | UNIQUE (BTREE) | One name per suite |

### Foreign Keys

| Column | References | On Delete |
|--------|------------|-----------|
| `test_suite_id` | `test_suites(id)` | CASCADE |

### Sequences

| Sequence Name | Start | Increment | Description |
|---------------|-------|-----------|-------------|
| `test_suite_run_name_seq` | 1 | 1 | Auto-generated run name sequence ("Run #N") |

### JSONB Column Schemas

**`run_config`** (RunConfigDto):
```json
{
  "numberOfRuns": 1,
  "testRunName": "string (optional)",
  "execution": {
    "concurrencyLevel": 1,
    "requestTimeoutMs": 30000,
    "rateLimitRps": 5.0
  },
  "retry": {
    "maxRetries": 0,
    "retryDelayMs": 1000,
    "retryBackoffMultiplier": 2.0
  }
}
```

**`error_details`** (RunErrorDetailsDto):
```json
{
  "code": "string",
  "category": "VALIDATION|TIMEOUT|RESOURCE_LIMIT|TEST_SUITE_ERROR|INTERNAL",
  "message": "string",
  "details": { /* optional key-value map */ }
}
```

**`suite_snapshot`** (SuiteSnapshotDto):
```json
{
  "snapshotVersion": "2",
  "suiteType": "DEPLOYMENT|MCP_TOOL",
  "datasetRef": { "id": "uuid", "version": 0, "name": "string" },
  "deploymentRef": { "id": "string", "name": "string", "version": "string", "type": "string (optional)" },
  "endpointRef": { /* EndpointContractDto */ },
  "requestTemplate": { /* RequestTemplateDto */ },
  "inputBindings": [ /* List of InputBindingDto */ ],
  "responseColumns": [ /* List of ResponseColumnDefinitionDto */ ],
  "additionalRequests": [ /* List of RequestDefinitionDto — requests 1..N of the chain, defaults to [] */ ],
  "requestName": "string (optional — label for request #0, null when unlabelled)",
  "testCaseSchema": [ /* List of FieldDefinitionDto — inlined from dataset at snapshot time */ ],
  "mcpDeploymentRef": { /* McpDeploymentReferenceDto — MCP suites only */ },
  "toolRef": { /* ToolReferenceDto — MCP suites only */ },
  "argumentTemplate": { /* ArgumentTemplateDto — MCP suites only */ }
}
```

`snapshotVersion = "2"` is the current shape (introduced by V1.22). Legacy `"1"` snapshots have no `datasetRef` and are rejected at run-resume time with `UnsupportedSnapshotVersionException`.

---

## Table: `test_case_run_inputs`

Snapshot of test case data for a run; written at async phase start under a REPEATABLE READ transaction. Acts as the executor's source of truth for the run duration. Rows are automatically deleted when the parent run row is deleted (FK CASCADE). A scheduled retention job additionally purges rows for completed/failed runs older than the configured retention window.

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `run_id` | VARCHAR(36) | NOT NULL | - | FK to `test_suite_runs.id` (CASCADE DELETE) |
| `position` | INTEGER | NOT NULL | - | Zero-based order matching `findValidByDatasetId` sort order |
| `test_case_id` | VARCHAR(36) | NOT NULL | - | Loose reference to the test case (no FK — snapshot is independent of live `test_cases`) |
| `test_case_name` | VARCHAR(255) | NOT NULL | - | Test case display name at snapshot time |
| `test_case_data` | JSONB | NOT NULL | - | Unified test case data map at snapshot time (single-turn) |
| `multi_turn_data` | JSONB | NULL | - | Frozen ordered array of per-turn data maps for a multi-turn test case (V1.28); NULL for a single-turn input |
| `request_template_override` | JSONB | NULL | - | Legacy per-case request template override at snapshot time. Always NULL for runs created after V1.22 (the override surface was removed with the dataset migration); kept for backward compatibility with in-flight pre-V1.22 runs. |
| `input_bindings_override` | JSONB | NULL | - | Legacy per-case input bindings override at snapshot time. Always NULL for runs created after V1.22; same backward-compatibility reason as above. |

### Primary Key

Composite: `(run_id, position)`

### Foreign Keys

| Column | References | On Delete |
|--------|------------|-----------|
| `run_id` | `test_suite_runs(id)` | CASCADE |

### Indexes

| Index Name | Columns | Type | Notes |
|------------|---------|------|-------|
| `idx_test_case_run_inputs_run_id` | `run_id` | BTREE | Efficient pagination by run |

---

## Table: `revalidation_tasks`

Tracks async dataset revalidation task progress (Phase 1 dataset-rooted, Phase 2 per-suite fan-out).

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | VARCHAR(36) | NOT NULL | - | Primary key (UUID) |
| `dataset_id` | VARCHAR(36) | NOT NULL | - | FK to `datasets.id` (CASCADE). Renamed from `test_suite_id` in V1.22. |
| `status` | VARCHAR(20) | NOT NULL | - | PENDING, RUNNING, COMPLETED, FAILED, TIMED_OUT |
| `total_cases` | INTEGER | NOT NULL | 0 | Total test cases to process |
| `processed_cases` | INTEGER | NOT NULL | 0 | Test cases processed so far |
| `valid_count` | INTEGER | NOT NULL | 0 | Count of valid test cases |
| `invalid_count` | INTEGER | NOT NULL | 0 | Count of invalid test cases |
| `started_at_ms` | BIGINT | NULL | - | Task start timestamp (epoch ms) |
| `completed_at_ms` | BIGINT | NULL | - | Task completion timestamp (epoch ms) |
| `error_message` | TEXT | NULL | - | Error message if failed. In-flight tasks aborted by the V1.22 migration carry the literal prefix `Aborted by introduce-dataset-entity migration:`. |
| `coerced_cell_count` | BIGINT | NOT NULL | 0 | Total (row, field) cells auto-coerced by `SchemaChangeCoercer` during this revalidation |

### Indexes

| Index Name | Columns | Type | Notes |
|------------|---------|------|-------|
| `idx_revalidation_tasks_dataset_id` | `dataset_id` | BTREE | |

### Foreign Keys

| Column | References | On Delete |
|--------|------------|-----------|
| `dataset_id` | `datasets(id)` | CASCADE |

---

## Table: `metric_declarations`

Metric declarations catalog; populated by sync from configured metric providers (GET /metrics). No seeded data.

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | VARCHAR(36) | NOT NULL | - | Primary key (UUID) |
| `provider_id` | VARCHAR(255) | NOT NULL | - | Provider id from metric-providers configuration |
| `name` | VARCHAR(255) | NOT NULL | - | Metric name (unique per provider) |
| `display_name` | TEXT | NULL | - | Latest version display name (denormalized) |
| `description` | VARCHAR(2000) | NULL | - | Latest version description (denormalized) |
| `created_at_ms` | BIGINT | NOT NULL | - | Creation timestamp (epoch ms) |

### Indexes

| Index Name | Columns | Type | Notes |
|------------|---------|------|-------|
| `uq_metric_declarations_provider_name` | `(provider_id, name)` | UNIQUE (BTREE) | One name per provider |

---

## Table: `metric_declaration_versions`

Schema versions for each metric declaration. New version is inserted when config/input/output schema or description changes (structural comparison).

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | VARCHAR(36) | NOT NULL | - | Primary key (UUID) |
| `metric_declaration_id` | VARCHAR(36) | NOT NULL | - | FK to metric_declarations.id |
| `schema_version` | INTEGER | NOT NULL | - | Monotonically increasing per declaration |
| `config_schema` | JSONB | NULL | - | JSON schema for metric config |
| `input_schema` | JSONB | NULL | - | JSON schema for metric input |
| `output_schema` | JSONB | NULL | - | JSON schema for metric output |
| `display_name` | TEXT | NULL | - | Display name at this version snapshot |
| `description` | TEXT | NULL | - | Version description |
| `created_at_ms` | BIGINT | NOT NULL | - | Creation timestamp (epoch ms) |

### Foreign Keys

| Column | References | On Delete |
|--------|------------|-----------|
| `metric_declaration_id` | `metric_declarations(id)` | CASCADE |

### Indexes

| Index Name | Columns | Type | Notes |
|------------|---------|------|-------|
| `idx_metric_declaration_versions_declaration_id` | `metric_declaration_id` | BTREE | Lookup by declaration |
| `idx_metric_declaration_versions_declaration_version` | `(metric_declaration_id, schema_version DESC)` | BTREE | Latest-version lookup |

---

## Table: `test_suite_metric_definitions`

Metric applications within a test suite. Each row binds a metric declaration (with server-resolved latest version) to a test suite, with parameter bindings stored as JSONB.

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | VARCHAR(36) | NOT NULL | - | Primary key (UUID) |
| `test_suite_id` | VARCHAR(36) | NOT NULL | - | FK to test_suites.id |
| `metric_declaration_id` | VARCHAR(36) | NOT NULL | - | FK to metric_declarations.id |
| `metric_declaration_version_id` | VARCHAR(36) | NOT NULL | - | FK to metric_declaration_versions.id (server-resolved to latest) |
| `name` | VARCHAR(255) | NOT NULL | - | Metric definition name (unique per suite, case-insensitive) |
| `config_bindings` | JSONB | NOT NULL | `'[]'::jsonb` | Config parameter bindings (List of MetricParameterBindingDto) |
| `input_bindings` | JSONB | NOT NULL | `'[]'::jsonb` | Input parameter bindings (List of MetricParameterBindingDto) |
| `is_enabled` | BOOLEAN | NOT NULL | `TRUE` | Whether this TSMD participates in metric evaluation |
| `condition` | VARCHAR(2000) | NULL | - | Optional JSONata expression (V1.26) gating whether this metric runs per result row (per turn), evaluated over `{data, response, turn}`. NULL/blank ⇒ always run. Validated as syntactically valid JSONata at write time. |
| `is_valid` | BOOLEAN | NOT NULL | `TRUE` | Whether the last soft validation passed |
| `validation_warnings` | JSONB | NOT NULL | `'[]'::jsonb` | Soft validation warnings (List of ValidationWarningDto) |
| `created_at_ms` | BIGINT | NOT NULL | - | Creation timestamp (epoch ms) |
| `updated_at_ms` | BIGINT | NOT NULL | - | Last update timestamp (epoch ms) |

### Foreign Keys

| Column | References | On Delete |
|--------|------------|-----------|
| `test_suite_id` | `test_suites(id)` | CASCADE |
| `metric_declaration_id` | `metric_declarations(id)` | RESTRICT (default) |
| `metric_declaration_version_id` | `metric_declaration_versions(id)` | RESTRICT (default) |

### Indexes

| Index Name | Columns | Type | Notes |
|------------|---------|------|-------|
| `idx_tsmd_test_suite_id` | `test_suite_id` | BTREE | Lookup by suite |
| `idx_tsmd_metric_declaration_id` | `metric_declaration_id` | BTREE | Lookup by declaration |
| `uq_tsmd_suite_name` | `test_suite_id`, `LOWER(name)` | UNIQUE (BTREE) | Case-insensitive unique name per suite |

### JSONB Column Schemas

**`config_bindings`** / **`input_bindings`** (List of MetricParameterBindingDto):
```json
[
  {
    "property": "string (metric parameter name)",
    "source": {
      "$type": "TestCase|Response|Constant",
      "columnName": "string (for TestCase and Response types)",
      "value": "any (for Constant type)"
    }
  }
]
```

The `source` field is polymorphic, discriminated by `$type`:
- **`TestCase`**: Binds to a test case column. Fields: `columnName` (String).
- **`Response`**: Binds to a response column. Fields: `columnName` (String).
- **`Constant`**: Binds to a constant value. Fields: `value` (any JSON value).

---

## Table: `test_case_run_results` (Analytics DB)

Test case execution results stored in the analytics database. Each row represents one test case execution within a test suite run.

> **Note:** This table resides in the **analytics database** (separate from the meta database). The `created_at_ms` column is the run's creation timestamp from the meta DB — all results for a run share this value.

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | VARCHAR(36) | NOT NULL | - | Result ID (UUID) |
| `test_suite_run_id` | VARCHAR(36) | NOT NULL | - | Run ID (references meta DB `test_suite_runs.id`) |
| `test_suite_id` | VARCHAR(36) | NOT NULL | - | Suite ID (denormalized for filtering) |
| `test_case_id` | VARCHAR(36) | NOT NULL | - | Test case ID |
| `test_case_name` | VARCHAR(255) | NOT NULL | - | Test case display name |
| `run_index` | INTEGER | NOT NULL | - | Run iteration index (0-based) |
| `request_index` | INTEGER | NOT NULL | 0 | 0-based position of this row's request within the suite's request chain (V1.17); 0 for a single-request suite |
| `total_requests` | INTEGER | NOT NULL | 1 | Length of the suite's request chain (V1.17); 1 for a single-request suite |
| `turn_index` | INTEGER | NOT NULL | 0 | 0-based turn position within a multi-turn test case (V1.13); 0 for single-turn |
| `total_turns` | INTEGER | NOT NULL | 1 | Planned turn count of the test case (V1.13); 1 for single-turn |
| `test_case_data` | JSONB | NOT NULL | - | Test case input data (that turn's data for a multi-turn row) |
| `request_body` | JSONB | NULL | - | HTTP request body sent to endpoint (full accumulated history for a multi-turn turn) |
| `response_body` | JSONB | NULL | - | HTTP response body received |
| `response_status_code` | INTEGER | NULL | - | HTTP response status code |
| `execution_status` | VARCHAR(20) | NOT NULL | - | SUCCESS, FAILED, TIMEOUT, ERROR |
| `exec_started_at_ms` | BIGINT | NOT NULL | - | Execution start timestamp (epoch ms) |
| `exec_completed_at_ms` | BIGINT | NOT NULL | - | Execution end timestamp (epoch ms) |
| `exec_duration_ms` | BIGINT | NOT NULL | - | Execution duration (computed: completedAt - startedAt) |
| `trace_id` | VARCHAR(128) | NULL | - | Distributed trace ID |
| `extracted_columns` | JSONB | NOT NULL | `'{}'::jsonb` | Extracted column values keyed by column name |
| `extraction_warnings` | JSONB | NOT NULL | `'[]'::jsonb` | Extraction warning entries (List of ExtractionWarningDto) |
| `retry_count` | INTEGER | NOT NULL | 0 | Number of retry attempts before final outcome (0 = no retries) |
| `log_details` | JSONB | NULL | - | Structured retry attempt log (populated only when retryCount > 0) |
| `created_at_ms` | BIGINT | NOT NULL | - | Run creation timestamp from meta DB (epoch ms) |

### Primary Key

Composite: `(created_at_ms, id)` — `created_at_ms` as leading column for future time-based partitioning.

### Constraints

| Constraint Name | Type | Columns | Notes |
|-----------------|------|---------|-------|
| `uq_results_run_case_index` | UNIQUE | `(test_suite_run_id, test_case_id, run_index, request_index, turn_index, created_at_ms)` | Idempotent writes (ON CONFLICT DO NOTHING). Extended with `turn_index` in V1.13 so each turn is uniquely keyed, and with `request_index` in V1.17 (inserted before `turn_index`, matching the request-then-turn nesting of the execution model) so each chain position is uniquely keyed. Includes `created_at_ms` for future partitioning. |

### Indexes

| Index Name | Columns | Type | Notes |
|------------|---------|------|-------|
| `idx_results_suite_run_case` | `(test_suite_id, test_suite_run_id, test_case_name)` | BTREE | Composite index for suite/run/case filtering |
| `idx_results_id` | `(id)` | BTREE | Standalone index for efficient `findById` lookups (PK has `created_at_ms` as leading column) |

### JSONB Column Schemas

**`test_case_data`** (arbitrary JSON object):
```json
{
  "prompt": "Hello, world!",
  "category": "greeting"
}
```

**`request_body`** (arbitrary JSON — typically the HTTP request payload):
```json
{
  "messages": [{"role": "user", "content": "Hello, world!"}]
}
```

**`response_body`** (arbitrary JSON — typically the HTTP response payload):
```json
{
  "choices": [{"message": {"content": "Hi there!"}}]
}
```

**`extracted_columns`** (arbitrary JSON object — keyed by response column name):
```json
{
  "answer": "Hi there!",
  "total_tokens": 25
}
```

**`extraction_warnings`** (List of ExtractionWarningDto):
```json
[
  {
    "column": "string",
    "expression": "string",
    "error": "string"
  }
]
```

**`log_details`** (structured retry attempt log, nullable):
```json
{
  "retryAttempts": [
    {
      "attemptIndex": 1,
      "statusCode": 500,
      "errorType": "HTTP_ERROR",
      "durationMs": 1200
    }
  ]
}
```

---

## Table: `test_case_eval_summaries` (Analytics DB)

Metric-enriched test case results stored in the analytics database. Each row represents one test case execution enriched with metric computation outputs, denormalized from `test_case_run_results`.

> **Note:** This table resides in the **analytics database**. Foreign key references to meta DB entities (test suites, runs, test cases) are soft FKs — no physical constraint.

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | VARCHAR(36) | NOT NULL | - | Unique identifier (UUID) |
| `test_suite_id` | VARCHAR(36) | NOT NULL | - | Reference to test suite (soft FK) |
| `test_suite_run_id` | VARCHAR(36) | NOT NULL | - | Reference to test suite run (soft FK) |
| `test_case_run_result_id` | VARCHAR(36) | NOT NULL | - | Reference to test case run result (soft FK) |
| `test_case_id` | VARCHAR(36) | NOT NULL | - | Reference to test case (soft FK) |
| `test_case_name` | VARCHAR(255) | NOT NULL | - | Test case name at execution time |
| `run_index` | INTEGER | NOT NULL | - | Run iteration index |
| `request_index` | INTEGER | NOT NULL | 0 | 0-based position of this row's request within the suite's request chain (V1.18); 0 for a single-request suite |
| `total_requests` | INTEGER | NOT NULL | 1 | Length of the suite's request chain (V1.18); 1 for a single-request suite |
| `turn_index` | INTEGER | NOT NULL | 0 | 0-based turn position within a multi-turn test case (V1.14); 0 for single-turn |
| `total_turns` | INTEGER | NOT NULL | 1 | Planned turn count of the test case (V1.14); 1 for single-turn |
| `computation_id` | VARCHAR(36) | NOT NULL | - | Metric computation batch identifier |
| `test_case_data` | JSONB | NOT NULL | - | Test case input data (denormalized from test_case_run_results) |
| `extracted_columns` | JSONB | NOT NULL | `'{}'::jsonb` | Extracted column values (denormalized) |
| `execution_status` | VARCHAR(20) | NOT NULL | - | Execution status (SUCCESS, FAILED, TIMEOUT, ERROR) |
| `exec_duration_ms` | BIGINT | NOT NULL | - | Execution duration in milliseconds |
| `metric_eval_duration_ms` | BIGINT | NOT NULL | 0 | Sum of latency (ms) across the TSMD provider `/evaluate` calls dispatched for this row's computation (V1.16); excludes TSMDs with a condition error (no call made) |
| `response_status_code` | INTEGER | NULL | - | HTTP response status code |
| `metric_values` | JSONB | NOT NULL | `'{}'::jsonb` | Compact metric output values (keyed by metric name, nested by output name) |
| `metric_infos` | JSONB | NULL | - | Detailed metric output info/metadata (lazy-loaded) |
| `extraction_warnings` | JSONB | NOT NULL | `'[]'::jsonb` | Warnings produced during column extraction (denormalized from test_case_run_results) |
| `created_at_ms` | BIGINT | NOT NULL | - | Row creation timestamp (from run's createdAt) |
| `computed_at_ms` | BIGINT | NOT NULL | - | Metric computation timestamp |

### Primary Key

Composite: `(created_at_ms, id)` — `created_at_ms` as leading column for future time-based partitioning.

### Constraints

| Constraint Name | Type | Columns | Notes |
|-----------------|------|---------|-------|
| `uq_eval_summaries_natural_key` | UNIQUE (INDEX) | `(test_suite_run_id, test_case_id, run_index, request_index, turn_index, computation_id, created_at_ms)` | Idempotent writes. Extended with `turn_index` in V1.14 so each turn's summary is uniquely keyed per computation, and with `request_index` in V1.18 (inserted before `turn_index`) so each chain position's summary is uniquely keyed. Includes `created_at_ms` for future partitioning. |

### Indexes

| Index Name | Columns | Type | Notes |
|------------|---------|------|-------|
| `idx_eval_summaries_run_computation` | `(test_suite_run_id, computation_id)` | BTREE | Lookup by run and computation batch |
| `idx_eval_summaries_computation` | `(computation_id)` | BTREE | Lookup by computation batch |
| `idx_eval_summaries_id` | `(id)` | BTREE | Standalone index for efficient `findById` lookups (PK has `created_at_ms` as leading column) |
| `idx_eval_summaries_run_computed_at` | `(test_suite_run_id, computed_at_ms DESC, computation_id)` | BTREE | Latest-computation resolution (V1.15): serves `WHERE test_suite_run_id = ? ORDER BY computed_at_ms DESC LIMIT 1` as a top-1 descent with `computation_id` available from the index tuple |

### JSONB Column Schemas

**`metric_values`** (compact metric output values):
```json
{
  "Accuracy": {"score": 0.85, "confidence": 0.92},
  "Relevance": {"score": 0.78}
}
```
Values are numeric or null, keyed by metric name and nested by output name. TSMD entries always use real output field names from the metric's output schema — the synthetic `"error"` key is never used here (transport failures produce `{fieldName: null}` per output field, or `{}` when the output schema has no fields).

**`metric_infos`** (detailed metric output info/metadata):
```json
{
  "Accuracy": {"score": {"explanation": "High match", "reasoning": "..."}, ...},
  "Relevance": {"score": {"explanation": "Partial match"}}
}
```
Arbitrary JSON detail objects, keyed by metric name and nested by output name.

---

## Table: `run_metric_snapshots` (Analytics DB)

Metric definition snapshots captured at computation time. Each row records the metric declaration version, bindings, and output schema used for a specific metric computation batch.

> **Note:** This table resides in the **analytics database**. Foreign key references to meta DB entities (test suite runs, TSMDs, metric declarations) are soft FKs — no physical constraint.

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | VARCHAR(36) | NOT NULL | - | Primary key (UUID) |
| `computation_id` | VARCHAR(36) | NOT NULL | - | Metric computation batch identifier |
| `test_suite_run_id` | VARCHAR(36) | NOT NULL | - | Reference to test suite run (soft FK) |
| `tsmd_id` | VARCHAR(36) | NOT NULL | - | Reference to test suite metric definition (soft FK) |
| `tsmd_name` | VARCHAR(255) | NOT NULL | - | TSMD name at computation time |
| `metric_declaration_id` | VARCHAR(36) | NOT NULL | - | Reference to metric declaration (soft FK) |
| `metric_declaration_version_id` | VARCHAR(36) | NOT NULL | - | Reference to metric declaration version (soft FK) |
| `config_bindings` | JSONB | NOT NULL | `'[]'::jsonb` | Config binding snapshot |
| `input_bindings` | JSONB | NOT NULL | `'[]'::jsonb` | Input binding snapshot |
| `output_schema` | JSONB | NOT NULL | `'{}'::jsonb` | Output schema snapshot |
| `computed_at_ms` | BIGINT | NOT NULL | - | Metric computation timestamp |

### Constraints

| Constraint Name | Type | Columns | Notes |
|-----------------|------|---------|-------|
| `uq_run_metric_snapshots_comp_tsmd` | UNIQUE | `(computation_id, tsmd_id)` | One snapshot per metric definition per computation batch |

### Indexes

| Index Name | Columns | Type | Notes |
|------------|---------|------|-------|
| `idx_run_metric_snapshots_run` | `(test_suite_run_id)` | BTREE | Lookup by test suite run |

### JSONB Column Schemas

**`config_bindings`** / **`input_bindings`** (List of MetricParameterBindingDto):
```json
[
  {
    "property": "string (metric parameter name)",
    "source": {
      "$type": "TestCase|Response|Constant",
      "columnName": "string (for TestCase and Response types)",
      "value": "any (for Constant type)"
    }
  }
]
```

**`output_schema`** (JSON Schema):
```json
{
  "type": "object",
  "properties": {
    "score": {"type": "number"},
    "explanation": {"type": "string"}
  }
}
```

---

## Metric-score statistics (code-defined)

The per-metric statistics (AVG, P10, P90, MIN, MAX) are defined in code as typed `StructuredQuery` objects in `BuiltInMetricStatistics` (package `query.service.metricscore`), each a single-`value` aggregate over `eval_summaries` parameterized with `:runId`/`:computationId` plus `:metricField`. The run-level **`overall`** is a per-suite property (`test_suites.overall_score`), snapshotted per run; when unset, Phase 3 uses the built-in default — the single metric's average (`avg(:metricField)`), computed only for single-metric runs. Phase-3 computation runs these queries via `StructuredQueryService` and writes results to the analytics `metric_score_result` table below.

---

## Table: `metric_score_result` (Analytics DB)

Computed aggregated metric statistics per run, append-only per computation. One row per (run, computation, statistic, metric field). Written by the run job's metric-score phase (Phase 3), reusing the run's metric-evaluation `computation_id`.

> **Note:** This table resides in the **analytics database**. `test_suite_run_id` is a soft FK — no physical constraint.

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | VARCHAR(36) | NOT NULL | - | Primary key (UUID) |
| `test_suite_run_id` | VARCHAR(36) | NOT NULL | - | Reference to test suite run (soft FK) |
| `test_suite_id` | VARCHAR(36) | NOT NULL | - | Reference to the run's owning test suite (soft FK, denormalized for suite-scoped queries) |
| `computation_id` | VARCHAR(36) | NOT NULL | - | Metric computation batch identifier |
| `metric_score_name` | VARCHAR(255) | NOT NULL | - | Statistic / definition name (e.g. `AVG`, `P90`, `overall`) |
| `metric_name` | VARCHAR(255) | NOT NULL | - | Metric output field as `<metricName>.<outputField>` |
| `value` | DOUBLE PRECISION | NULL | - | Computed numeric value |
| `computed_at_ms` | BIGINT | NOT NULL | - | Epoch-millisecond compute timestamp; shared by all results of a computation |

### Primary Key

`id`

### Constraints

| Constraint Name | Type | Columns | Notes |
|-----------------|------|---------|-------|
| `uq_metric_score_result_natural_key` | UNIQUE | `(test_suite_run_id, computation_id, metric_score_name, metric_name)` | One result per statistic per metric field per computation (append-only) |

### Indexes

| Index Name | Columns | Type | Notes |
|------------|---------|------|-------|
| `idx_metric_score_result_run_computation` | `(test_suite_run_id, computation_id)` | BTREE | Lookup results for a run's computation |
| `idx_metric_score_result_suite_computed` | `(test_suite_id, computed_at_ms)` | BTREE | Suite-scoped, time-ordered retrieval (latest N results for a suite) |

---

## Stored Function: `roc_auc_score` (Analytics DB)

`roc_auc_score(y double precision[], p double precision[]) RETURNS double precision` — computes the ROC AUC score (rank-sum / Mann-Whitney formulation) for a binary classifier. `y` holds the actual class (0/1) and `p` the predicted probability, paired positionally by array index (both arrays must be built from the same row scan, e.g. `array_agg(y)`/`array_agg(p)` in the same `SELECT`). Returns `NULL` when either class is absent (no positive/negative pair to rank). Introduced in `V1.11__CreateRocAucScoreFunction.sql`; invoked from the Query DSL's `roc_auc(label, probability)` function (`query.service.translate.function.BuiltInQueryFunctions`), usable anywhere a `FnExpr` is valid, including a suite's custom `overallScore` expression.

---

## Migration History

### Meta Database (`db/migration/meta/POSTGRES/`)

| Version | File | Description |
|---------|------|-------------|
| V1.1 | `V1.1__InitTestSuitesTable.sql` | Initial test_suites table |
| V1.2 | `V1.2__TestSuiteAggregateTables.sql` | Added aggregate model, test_cases, revalidation_tasks, metric_definitions |
| V1.3 | `V1.3__TestCaseValidationWarningsJsonb.sql` | Changed validation_warnings from TEXT[] to JSONB |
| V1.5 | `V1.5__RequestTemplateRestructure.sql` | Request template restructure: drop old columns, add test_case_schema, request_template, input_bindings, data, overrides |
| V1.6 | `V1.6__CreateTestSuiteRunsTable.sql` | Added test_suite_runs table, indexes, unique constraint, run name sequence |
| V1.7 | `V1.7__RenameMetricDefinitionsToMetricDeclarations.sql` | Renamed metric_definitions to metric_declarations |
| V1.8 | `V1.8__AddResponseColumnsToTestSuites.sql` | Added response_columns JSONB to test_suites |
| V1.9 | `V1.9__CreateMetricDeclarationVersionsTable.sql` | Created metric_declaration_versions table |
| V1.10 | `V1.10__AddProviderIdToMetricDeclarations.sql` | Added provider_id to metric_declarations; UNIQUE(provider_id, name); removed seeded data |
| V1.11 | `V1.11__CreateBlobsTable.sql` | Created blobs table for file blob metadata (LO references) |
| V1.12 | `V1.12__WrapPolymorphicRequestBody.sql` | Wrapped existing body/requestBodySchema JSONB fields with polymorphic contentType discriminator |
| V1.13 | `V1.13__CreateTestSuiteMetricDefinitionsTable.sql` | Created test_suite_metric_definitions table with FK to test_suites, metric_declarations, metric_declaration_versions |
| V1.14 | `V1.14__DropBlobsTable.sql` | Dropped blobs table; file storage migrated to DIAL Core file storage |
| V1.15 | `V1.15__AddMcpFieldsToTestSuites.sql` | Added suite_type, mcp_deployment_ref, tool_ref, argument_template columns to test_suites for MCP tool evaluation support |
| V1.16 | `V1.16__AddTsmdValidationAndEnabledColumns.sql` | Added is_enabled, is_valid, validation_warnings columns to test_suite_metric_definitions |
| V1.17 | `V1.17__AddSuiteSnapshotToTestSuiteRuns.sql` | Added nullable suite_snapshot JSONB column to test_suite_runs |
| V1.18 | `V1.18__CreateTestCaseRunInputsTable.sql` | Created test_case_run_inputs table with composite PK (run_id, position), FK to test_suite_runs with CASCADE DELETE, and run_id index |
| V1.19 | `V1.19__AddDisplayNameToMetricDeclarations.sql` | Added nullable display_name TEXT column to metric_declarations |
| V1.20 | `V1.20__AddDisplayNameToMetricDeclarationVersions.sql` | Added nullable display_name TEXT column to metric_declaration_versions |
| V1.21 | `V1.21__AddCoercedCellCountToRevalidationTasks.sql` | Added coerced_cell_count column to revalidation_tasks |
| V1.22 | `V1.22__IntroduceDataset.sql` | Introduced datasets table; rebound test_cases and revalidation_tasks FKs from test_suites to datasets; backfilled per-suite datasets; relaxed `test_suites.dataset_id` to nullable (unbound state); added `datasets.visibility` (`'PUBLIC'`/`'PRIVATE'`) with CHECK constraint; added `tg_test_suites_private_binding_guard` trigger raising `ERRCODE='P0001'` MESSAGE `'PRIVATE_DATASET_ALREADY_BOUND'` for concurrent PRIVATE-binding violations; backfilled `suite_snapshot` JSON to v2 (`snapshotVersion`, `datasetRef`) |
| V1.23 | `V1.23__AddOverallScoreToTestSuites.sql` | Added nullable `overall_score` JSONB column to test_suites (per-suite `overall` metric-score definition; NULL = system default, computed only for single-metric runs) |
| V1.24 | `V1.24__AddTestCaseFilterToTestSuites.sql` | Added nullable `test_case_filter` JSONB column to test_suites (per-suite Structured Query DSL filter selecting runnable test cases; NULL = no filter; validated at suite write time; AND-combined with `is_valid` at run-creation count and snapshot) |
| V1.25 | `V1.25__AddOverallScoreThresholdToTestSuites.sql` | Added nullable `overall_score_threshold` DOUBLE PRECISION column to test_suites (per-suite threshold compared against the computed run-level `overall` metric score, same numeric type as the result; NULL = no threshold configured) |
| V1.26 | `V1.26__AddConditionToTestSuiteMetricDefinitions.sql` | Added nullable `condition` VARCHAR(2000) to test_suite_metric_definitions (optional JSONata gating whether the metric runs per result row/turn; NULL ⇒ always run) |
| V1.27 | `V1.27__AddMultiTurnDataToTestCases.sql` | Added nullable `multi_turn_data` JSONB to test_cases (ordered array of per-turn data maps). Coexists with `data` (shared vs per-turn fields, scoped in `test_case_schema`); no mutual-exclusivity CHECK constraint. |
| V1.28 | `V1.28__AddMultiTurnDataToTestCaseRunInputs.sql` | Added nullable `multi_turn_data` JSONB to test_case_run_inputs (frozen multi-turn snapshot; one input row per case) |
| V1.29 | `V1.29__AddAdditionalRequestsToTestSuites.sql` | Added `additional_requests` JSONB NOT NULL DEFAULT `'[]'::jsonb` (ordered chain of requests 1..N, List of RequestDefinitionDto) and nullable `request_name` VARCHAR(255) (label for request #0) to test_suites, for multi-request suites |

### Analytics Database (`db/migration/analytics/POSTGRES/`)

| Version | File | Description |
|---------|------|-------------|
| V1.1 | `V1.1__CreateTestCaseRunResultsTable.sql` | Initial test_case_run_results table with composite PK, unique constraint, indexes |
| V1.2 | `V1.2__AddExtractedColumnsToTestCaseRunResults.sql` | Added extracted_columns and extraction_warnings JSONB to test_case_run_results |
| V1.3 | `V1.3__AddStreamingTimingToTestCaseRunResults.sql` | Added time_to_first_token_ms and time_to_last_token_ms nullable BIGINT columns |
| V1.4 | `V1.4__DropTimingAddRetryColumns.sql` | Dropped time_to_first_token_ms and time_to_last_token_ms; added retry_count (INTEGER NOT NULL DEFAULT 0) and log_details (JSONB nullable) |
| V1.5 | `V1.5__CreateTestCaseEvalSummariesTable.sql` | Created test_case_eval_summaries table with composite PK, unique constraint, indexes |
| V1.6 | `V1.6__CreateRunMetricSnapshotsTable.sql` | Created run_metric_snapshots table with unique constraint on (computation_id, tsmd_id) |
| V1.7 | `V1.7__AddExtractionWarningsToEvalSummaries.sql` | Added extraction_warnings JSONB NOT NULL DEFAULT '[]' to test_case_eval_summaries |
| V1.8 | `V1.8__NormalizeErrorShapedMetricValues.sql` | Normalized transport-failure metric_values from synthetic `{"error": null}` to real output field names; updated corresponding metric_infos entries |
| V1.10 | `V1.10__CreateMetricScoreResultTable.sql` | Created metric_score_result table (`id` PK, natural-key unique constraint, append-only per computation) |
| V1.11 | `V1.11__CreateRocAucScoreFunction.sql` | Created `roc_auc_score(double precision[], double precision[])` SQL function computing the rank-sum ROC AUC score over paired label/probability arrays |
| V1.12 | `V1.12__AddSuiteAndTimestampToMetricScoreResult.sql` | Added `test_suite_id` and `computed_at_ms` to metric_score_result (backfilled, NOT NULL) with index `idx_metric_score_result_suite_computed` for suite-scoped latest-N retrieval |
| V1.13 | `V1.13__AddTurnColumnsToTestCaseRunResults.sql` | Added `turn_index`/`total_turns` (NOT NULL DEFAULT 0/1) to test_case_run_results; extended `uq_results_run_case_index` with `turn_index` |
| V1.14 | `V1.14__AddTurnColumnsToEvalSummaries.sql` | Added `turn_index`/`total_turns` (NOT NULL DEFAULT 0/1) to test_case_eval_summaries; extended `uq_eval_summaries_natural_key` with `turn_index` |
| V1.15 | `V1.15__AddEvalSummariesRunComputedAtIndex.sql` | Added index `idx_eval_summaries_run_computed_at` on test_case_eval_summaries `(test_suite_run_id, computed_at_ms DESC, computation_id)` for latest-computation resolution off the fact table |
| V1.16 | `V1.16__AddMetricEvalDurationToEvalSummaries.sql` | Added `metric_eval_duration_ms` (BIGINT NOT NULL DEFAULT 0) to test_case_eval_summaries |
| V1.17 | `V1.17__AddRequestColumnsToTestCaseRunResults.sql` | Added `request_index`/`total_requests` (NOT NULL DEFAULT 0/1) to test_case_run_results; dropped and re-created `uq_results_run_case_index` as `(test_suite_run_id, test_case_id, run_index, request_index, turn_index, created_at_ms)` |
| V1.18 | `V1.18__AddRequestColumnsToEvalSummaries.sql` | Added `request_index`/`total_requests` (NOT NULL DEFAULT 0/1) to test_case_eval_summaries; dropped and re-created unique index `uq_eval_summaries_natural_key` as `(test_suite_run_id, test_case_id, run_index, request_index, turn_index, computation_id, created_at_ms)` |

---

## Conventions

- **UUIDs**: Stored as `VARCHAR(36)` strings
- **Timestamps**: Stored as `BIGINT` epoch milliseconds
- **JSON data**: Stored as `JSONB` for indexing and querying
- **Boolean columns**: Named with `is_` prefix (e.g., `is_enabled`, `is_valid`)
- **Foreign keys**: Use `ON DELETE CASCADE` for child tables

---

## Related Documentation

- [Entity-Relationship Model](design/entity-relationship-model.md) - Conceptual data model design
- [Configuration Reference](configuration.md) - Application configuration
- [AGENTS.md](../AGENTS.md) - Development guidelines and code templates
