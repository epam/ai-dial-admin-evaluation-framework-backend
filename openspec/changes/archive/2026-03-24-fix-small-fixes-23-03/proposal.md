## Why

A live 500 error crashes all `/api/v1/deployments` requests because `DialCoreDeploymentListResponseDto` expects a wrapped `{"data":[...]}` response but DIAL Core `/v1/deployments` returns a bare JSON array, and the discriminator field is `"object"` not `"type"`. Additionally, eval summaries are missing `extractionWarnings`/`requestBody`/`responseBody` fields, several domain concepts use stringly-typed strings instead of enums (`suiteType` in the model layer, MCP transport, DIAL interfaces), and `InterfaceType` / `McpTransport` have no typed representation in the places that consume them.

## What Changes

- **BREAKING BUG FIX**: Fix `DialCoreClient.getDeployments()` — deserialize DIAL response as `List<DialCoreDeploymentDto>` (bare array), remove unused `DialCoreDeploymentListResponseDto` wrapper
- Fix `DialCoreDeploymentDto` — rename `type` field to `object` (actual DIAL discriminator); add `transport` field (present on toolset entries in unified list)
- Fix `DeploymentMapper.toDeploymentInfoDto()` — branch on `object` instead of `type`; update `DeploymentService` call site accordingly
- Change `DialCoreDeploymentDto.interfaces` from `List<String>` to `List<InterfaceType>` (enum already exists; fail-fast on unknown values)
- Create `DialTransport` enum (`HTTP`) in `client.dialcore.dto`; apply to `DialCoreToolsetDto.transport` and `DialCoreDeploymentDto.transport`
- Create `McpTransport` enum (`STREAMABLE_HTTP("streamable-http")`) in `service.domain.dto`; apply to `McpDeploymentReferenceDto.transport` and `ToolsetInfoDto.transport`
- Add `extraction_warnings JSONB NOT NULL DEFAULT '[]'` column to `test_case_eval_summaries` (Flyway V1.7); propagate to `EvalSummary` model, RowMapper, batch-insert SQL, and `EvalSummaryDetailResponseDto`
- Add `requestBody`/`responseBody` to `EvalSummaryDetailResponseDto`; populate via LEFT JOIN on `test_case_run_results` in the `findById` query only (not in list queries)
- Change `TestSuite.suiteType` from `String` to `SuiteType` enum in the data model; update `TestSuiteRowMapper`; replace `SuiteType.isMcpTool(String)` usages with direct enum comparison

## Capabilities

### New Capabilities
<!-- None — all changes modify existing capabilities -->

### Modified Capabilities
- `dial-core-client`: Fix response format for `/v1/deployments` (bare array, not wrapped); fix discriminator field (`object` not `type`); add `transport` to unified DTO; introduce `DialTransport` enum; change `interfaces` to `List<InterfaceType>`
- `analytics-eval-results`: Add `extractionWarnings` to `test_case_eval_summaries` table and eval summary response; expose `requestBody`/`responseBody` in detail view via JOIN
- `test-suites`: Promote `suiteType` from `String` to `SuiteType` enum in data model layer
- `mcp-tool-invocation`: Introduce `McpTransport` enum; apply to `McpDeploymentReferenceDto.transport` and `ToolsetInfoDto.transport`

## Impact

- **Deployment listing** (`GET /api/v1/deployments`, `DeploymentController`, `DeploymentService`, `DialCoreClient`, `DialCoreDeploymentDto`, `DialCoreDeploymentListResponseDto`, `DeploymentMapper`) — fixes live 500 error
- **Analytics eval summaries** (`EvalSummary`, `PostgresEvalSummaryRepository`, `EvalSummaryRowMapper`, `EvalSummaryDetailResponseDto`, `EvalSummaryService`, Flyway V1.7)
- **Test suite model layer** (`TestSuite`, `TestSuiteRowMapper`, `EvaluationWorker`, `SuiteValidationService`, `TestSuiteService`)
- **MCP transport typing** (`McpDeploymentReferenceDto`, `ToolsetInfoDto`, `McpDeploymentMapper` if present)
- **DIAL Core client DTOs** (`DialCoreToolsetDto`, `DialCoreDeploymentDto`, new `DialTransport` enum)
- No DB migration needed for enum changes; one analytics migration (V1.7) for `extraction_warnings`
- `docs/database-schema.md` must be updated for V1.7
