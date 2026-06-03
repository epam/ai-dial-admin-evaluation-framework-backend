## Context

This change addresses a set of small but impactful fixes accumulated from production observation. The most critical is a live 500 error on `GET /api/v1/deployments` caused by a mismatch between the assumed DIAL Core API response shape and the actual response. The remaining fixes promote type safety (String → enum) and close gaps in the eval summary data model.

**Current state of each area:**

1. **Deployment listing bug**: `DialCoreDeploymentListResponseDto` wraps a `data` list expecting `{"data":[...]}`, but DIAL `/v1/deployments` returns a bare array `[...]`. Additionally, `DialCoreDeploymentDto.type` never gets populated because the actual discriminator field in DIAL is `"object"` (values: `"model"`, `"application"`, `"toolset"`).

2. **EvalSummary fields gap**: `test_case_eval_summaries` table has `extracted_columns` but not `extraction_warnings`. The `test_case_run_results` table has `request_body`, `response_body`, and `extraction_warnings`. The `EvalSummaryDetailResponseDto` exposes none of these extra fields.

3. **suiteType as String**: `TestSuite.suiteType` in the data model is `String`; `SuiteType` enum exists at the DTO layer but is not used in the data model or RowMapper. `SuiteType.isMcpTool(String)` is the current branching helper.

4. **Transport/interfaces as String**: `DialCoreToolsetDto.transport`, `DialCoreDeploymentDto` (no transport field yet), `McpDeploymentReferenceDto.transport`, `ToolsetInfoDto.transport`, and `DialCoreDeploymentDto.interfaces` are all untyped strings.

## Goals / Non-Goals

**Goals:**
- Fix live 500 error on deployment listing
- Expose `extractionWarnings`, `requestBody`, `responseBody` in eval summary detail endpoint
- Promote `suiteType` to typed enum in data model layer
- Introduce `DialTransport` and `McpTransport` enums; change `interfaces` to `List<InterfaceType>`
- Fail fast on unrecognized enum values from DIAL API responses

**Non-Goals:**
- Adding MCP transport filtering or routing logic
- Changing the DIAL API contract or MCP proxy behavior
- Making `suiteType` required in `TestSuiteRequestDto` (already optional with DB default)
- Exposing `requestBody`/`responseBody` in eval summary list queries (performance concern)

## Decisions

### D1: Deserialize `/v1/deployments` as `List<DialCoreDeploymentDto>`
DIAL returns a bare array. Change `DialCoreClient.getDeployments()` to deserialize as `List<DialCoreDeploymentDto>` directly. Remove `DialCoreDeploymentListResponseDto` entirely (it has no other usages). Update `DeploymentService` to remove the now-unnecessary `response.getData()` null guard.

### D2: Rename `type` → `object` in `DialCoreDeploymentDto`
The discriminator in DIAL's response is the `"object"` field. Rename the Java field to `object` to match. `@JsonIgnoreProperties(ignoreUnknown = true)` already handles the per-type extra fields (`"model"`, `"toolset"` etc. that appear at the same level). Add `transport` field (with `DialTransport` enum type) to capture toolset transport in the unified list.

### D3: `extractionWarnings` denormalized into `test_case_eval_summaries`
`extractionWarnings` is written once per test case run and is tightly coupled to the computed result. Denormalizing avoids a JOIN on the hot list query path. A new Flyway migration V1.7 adds the column with `DEFAULT '[]'` so existing rows are not broken.

### D4: `requestBody`/`responseBody` joined in `findById` only
These fields are large JSONB blobs not needed for list/aggregation queries. The `getById` query joins `test_case_run_results` on `test_case_run_result_id` to fetch them. This follows the same TOAST-avoidance pattern as `SELECT_LIST_COLUMNS` vs `SELECT_ALL_COLUMNS` in `PostgresEvalSummaryRepository`.

### D5: Fail fast on unknown enum values
`DialTransport`, `McpTransport`, and `InterfaceType` all use `@JsonCreator` with `IllegalArgumentException` on unrecognized values. This matches the user's decision for issues #4 and #6. If DIAL adds a new transport or interface, the application fails loudly rather than silently ignoring it.

### D6: Two distinct transport enums
`DialTransport { HTTP }` lives in `client.dialcore.dto` — it represents the DIAL API's transport concept (how the toolset is reachable via DIAL). `McpTransport { STREAMABLE_HTTP("streamable-http") }` lives in `service.domain.dto` — it represents the client-facing transport type used when configuring MCP suites. They have different value sets and different layers.

`DeploymentMapper` SHALL convert `DialTransport.HTTP` → `McpTransport.STREAMABLE_HTTP` when mapping `DialCoreToolsetDto` or `DialCoreDeploymentDto` to `ToolsetInfoDto`. "HTTP" in DIAL's terminology refers to the same protocol as "streamable-http" in the MCP SDK — both mean HTTP-based Streamable MCP transport. Since `DialTransport` fails fast on unknown values at deserialization time (D5), the only runtime value is `HTTP` — no WARN logging is needed in the mapper.

### D7: `TestSuite.suiteType` promoted to `SuiteType` enum
The Java field type on `TestSuite` SHALL change from `String` to `SuiteType`; the RowMapper converts the raw DB string to the enum via `SuiteType.fromValue()`. The RowMapper calls `SuiteType.fromValue(rs.getString("suite_type"))`. This is safe because the DB column is `NOT NULL` with default `'DEPLOYMENT'` and all existing values are valid enum members. `SuiteType.isMcpTool(String)` is retired; callers compare directly against `SuiteType.MCP_TOOL`.

## Risks / Trade-offs

- **[Risk] Fail-fast on unknown enum values** → If DIAL introduces a new `object` type, `transport` value, or `interface` value, the deployment listing endpoint will 500 again. Mitigation: monitor logs; the fix is adding a new enum value. Accepted per user decision.

- **[Risk] V1.7 migration adds NOT NULL column with DEFAULT** → Safe for existing rows (DEFAULT `'[]'`). The batch-insert SQL must include `extraction_warnings` or inserts will fail. Ensure the `EvaluationWorker`'s batch-write path passes the value before deploying.

- **[Trade-off] JOIN in getById only** → Eval summary list returns `requestBody`/`responseBody` as `null`. Clients needing these fields must call the detail endpoint. This is intentional to avoid TOAST decompression on bulk queries.

## Migration Plan

1. Deploy Flyway V1.7 (`extraction_warnings JSONB NOT NULL DEFAULT '[]'`) alongside code changes.
2. No rollback required for enum changes (backward-compatible; enums serialize to the same string values).
3. For V1.7: if rollback needed, drop column (data loss acceptable — it mirrors data already in `test_case_run_results`).
