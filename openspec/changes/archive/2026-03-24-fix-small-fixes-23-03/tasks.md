## 1. Fix Deployment Listing Bug (Issues #1 + #5)

> **Task ordering**: Tasks within this group have sequential dependencies: 1.1 → 1.2 and 1.3 → 1.4 → 1.5 → 1.6.

- [x] 1.1 Create `DialTransport` enum in `client.dialcore.dto` with `HTTP("HTTP")`, `@JsonCreator`/`@JsonValue` (fail-fast on unknown)
- [x] 1.2 In `DialCoreDeploymentDto`: rename `type` field to `object`; add `transport` field (type `DialTransport`); change `interfaces` from `List<String>` to `List<InterfaceType>`
- [x] 1.3 Apply `DialTransport` to `DialCoreToolsetDto.transport` (change from `String`)
- [x] 1.4 Change `DialCoreClient.getDeployments()` return type from `DialCoreDeploymentListResponseDto` to `List<DialCoreDeploymentDto>`; deserialize response as `List<DialCoreDeploymentDto>`; grep across **all** sources (main + test) for `DialCoreDeploymentListResponseDto`; update test mocks in `DeploymentFunctionalTests`, `McpDeploymentFunctionalTests`, `DeploymentServiceTokenPropagationTest` (change `when(...).thenReturn(DialCoreDeploymentListResponseDto.builder().data(...).build())` to `when(...).thenReturn(List.of(...))`); then delete the class and remove its import from `DialCoreClient`
- [x] 1.5 Update `DeploymentMapper.toDeploymentInfoDto()` to branch on `source.getObject()` instead of `source.getType()`; pass `source.getTransport()` when building `ToolsetInfoDto` (Requires tasks 2.1 and 2.3 to be completed first — implement transport field assignment as `McpTransport.STREAMABLE_HTTP` directly or leave null to be filled in by task 2.4.)
- [x] 1.6 Update `DeploymentService.getAllDeployments()` to consume `List<DialCoreDeploymentDto>` directly (remove `response.getData()` null guard)

## 2. McpTransport Enum (Issue #4)

> **Cross-group dependency**: Tasks 2.1 and 2.3 are prerequisites for completing task 1.5.

- [x] 2.1 Create `McpTransport` enum in `service.domain.dto` with `STREAMABLE_HTTP("streamable-http")`, `@JsonCreator`/`@JsonValue` (fail-fast on unknown)
- [x] 2.2 Apply `McpTransport` to `McpDeploymentReferenceDto.transport`; remove the `@Pattern` regex validator; update `@Schema` example
- [x] 2.3 Apply `McpTransport` to `ToolsetInfoDto.transport`; update `@Schema` example
- [x] 2.4 In `DeploymentMapper.toToolsetInfoDto()`, add explicit transport conversion using a simple `switch` or named conversion method: `DialTransport.HTTP` → `McpTransport.STREAMABLE_HTTP`

## 3. SuiteType Enum in Data Model (Issue #3)

- [x] 3.1 Change `TestSuite.suiteType` from `String` to `SuiteType`
- [x] 3.2 Update `TestSuiteRowMapper` to call `SuiteType.fromValue(rs.getString("suite_type"))`
- [x] 3.3 Replace all usages of `SuiteType.isMcpTool(String)` with direct enum comparison (`testSuite.getSuiteType() == SuiteType.MCP_TOOL`). Known call sites:
  - `TryItOutService.java:193` — `SuiteType.isMcpTool(suite.getSuiteType())`
  - `EvaluationWorker.java:94` — `SuiteType.isMcpTool(context.getSuiteType())`
  - `TestSuiteEvaluationJob.java:194,196,198` — three consecutive `SuiteType.isMcpTool(suite.getSuiteType())` calls
- [x] 3.4 Remove `SuiteType.isMcpTool(String)` helper method from `SuiteType` enum

## 4. EvalSummary — extractionWarnings (Issue #2, part A)

> **Execution order**: tasks 4.1 → 4.2 → 4.3 → 4.4 MUST be completed before starting 4.5 → 4.6 → 4.7 → 4.8 (the DB schema and model changes are prerequisites for the service/DTO layer).

- [x] 4.1 Write Flyway migration `analytics/POSTGRES/V1.7__AddExtractionWarningsToEvalSummaries.sql`: `ALTER TABLE test_case_eval_summaries ADD COLUMN extraction_warnings JSONB NOT NULL DEFAULT '[]'::jsonb`
- [x] 4.2 Add `extractionWarnings` (`String`, JSON-serialized) field to `EvalSummary` model
- [x] 4.3 Update `EvalSummaryRowMapper` to map `rs.getString("extraction_warnings")` → `extractionWarnings`
- [x] 4.4 Update batch-insert SQL in `PostgresEvalSummaryRepository` to include `extraction_warnings` column and `:extractionWarnings` parameter
- [x] 4.5 Add `extractionWarnings` (nullable `JsonNode`) to `EvalSummaryBatchWriteItemDto`
- [x] 4.6 In `InProcessMetricEvaluationExecutor.buildItem()`, populate `.extractionWarnings(parseJsonNode(result.getExtractionWarnings()))`
- [x] 4.7 Update `EvalSummaryMapper.toEntity()`: add `extractionWarnings` source mapping; expand the existing `defaultExtractedColumns()` `@AfterMapping` method to also default `extractionWarnings` to `"[]"` if null (MapStruct supports multiple `@AfterMapping` methods, but grouping related defaults together is preferred)
- [x] 4.8 Add `extractionWarnings` (as `JsonNode`) to `EvalSummaryDetailResponseDto` with `@JsonInclude(NON_NULL)` and update `EvalSummaryMapper.toDetailDto()`

## 5. EvalSummary — requestBody/responseBody in Detail View (Issue #2, part B)

- [x] 5.1 Add `requestBody` and `responseBody` (nullable `String`) to `EvalSummary` model (not mapped from DB in list queries — null by default)
- [x] 5.2 Update the existing `findById()` query in `PostgresEvalSummaryRepository` (currently uses the `SELECT_BY_ID_SQL` constant) to LEFT JOIN `test_case_run_results` on `test_case_run_result_id` and select `request_body`, `response_body`; rename `SELECT_BY_ID_SQL` to `SELECT_BY_ID_DETAIL_SQL` (since this constant is separate from `SELECT_LIST_COLUMNS` and `SELECT_ALL_COLUMNS`, which are used for list/bulk queries)
- [x] 5.3 Update `EvalSummaryRowMapper` to conditionally map `request_body`/`response_body` when the columns are present (use `hasColumn()` check)
- [x] 5.4 Add `requestBody`/`responseBody` (nullable `JsonNode`) to `EvalSummaryDetailResponseDto` with `@JsonInclude(NON_NULL)`
- [x] 5.5 Update `EvalSummaryMapper.toDetailDto()` to map `requestBody`/`responseBody` via `JacksonMapper` (String → JsonNode) (This updates the same `toDetailDto()` method as task 4.8 — if implementing sequentially, apply both sets of mappings together in a single method edit.)

> **Note**: `EvalSummaryService.getById()` requires no changes — the new fields flow automatically through the `EvalSummary` model from repository to mapper to DTO.

## 6. Docs and Tests

- [x] 6.1 Update `docs/database-schema.md` with the V1.7 `extraction_warnings` column
- [x] 6.2 Update OpenAPI examples for deployment listing: check `src/main/resources/openapi/examples/` for any deployment-related example JSON files and update them to use `object` discriminator instead of `type`; also update `@Schema` annotations on `DialCoreDeploymentDto` if applicable
- [x] 6.3 Add/update functional test for `GET /api/v1/deployments` to assert it returns a non-error response (covers the deserialization bug)
- [x] 6.4 Add/update functional test for `GET /api/v1/eval-summaries/{id}` asserting `extractionWarnings`, `requestBody`, `responseBody` are present in detail response and absent in list response
