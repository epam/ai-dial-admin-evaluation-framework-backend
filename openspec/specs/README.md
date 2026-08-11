# OpenSpec Main Specifications

This folder contains the **main (baseline) specifications** for the Evaluation Framework Backend.

Changes are proposed and implemented via `openspec/changes/<change-name>/...` and can be synced back into these main specs.

Coding standards and architectural conventions live in `openspec/config.yaml` (not in specs).

## Spec Index

### Core Domain

Specs defining the primary business entities and their APIs.

- **[datasets](datasets/spec.md)** — Implemented
  Dataset CRUD — central owner of `testCaseSchema` and test cases. Carries `visibility` (PUBLIC / PRIVATE): PUBLIC datasets appear in the list endpoint and may be shared across suites; PRIVATE datasets are hidden from the list, bound to exactly one suite, and cascade-delete with it. Atomic create-and-bind for PRIVATE via `bindToSuiteId`; transitions go through `PATCH /api/v1/datasets/{id}/visibility`. Publish endpoint (`POST /api/v1/datasets/{id}/publish`) promotes a PRIVATE dataset to PUBLIC with optional name/description update in a single atomic operation (no-op when already PUBLIC with no changes). PL/pgSQL trigger `tg_test_suites_private_binding_guard` enforces single-binding for PRIVATE at the DB layer (P0001 → HTTP 409). Hosts schema-change revalidation (Phase 1 dataset-rooted, Phase 2 per-suite). Dependent-suites sub-resource (`GET /api/v1/datasets/{id}/test-suites`) returns `{id, name, description}` summaries of all bound suites; non-paginated, visibility-agnostic, backed by a selective-column projection. Related: test-suites, test-cases, test-suite-runs.
- **[test-suites](test-suites/spec.md)** — Implemented
  TestSuite CRUD with suite type discriminator (DEPLOYMENT, MCP_TOOL). DEPLOYMENT suites: deploymentRef, endpointRef, requestTemplate, inputBindings. MCP_TOOL suites: mcpDeploymentRef, toolRef, argumentTemplate. Suite binds to a dataset via optional `datasetId` (suites with `datasetId=null` are "unbound" — retrievable and updatable but cannot run). Rebinding off a PRIVATE dataset is forbidden (409 PRIVATE_DATASET_REBIND_FORBIDDEN). Per-suite `disabledTestCaseIds` (JSONB array, capped at 10000) excludes specific dataset rows from runs. Suite validity (`isValid`) is config-only (template + bindings + endpoint schema); a bound suite with zero runnable test cases is `isValid=true`. Run creation enforces a run-time guard: zero runnable test cases → 409 `INVALID_OPERATION`. Optional `testCaseFilter` further narrows the runnable subset (see `suite-test-case-filter`). Related: datasets, request-template, test-cases, mcp-tool-invocation, suite-test-case-filter.
- **[suite-test-case-filter](suite-test-case-filter/spec.md)** — Implemented
  Per-suite `testCaseFilter` — an optional Structured Query DSL filter over the bound dataset's test-case fields that narrows the runnable subset. AND-combined with `is_valid` and `disabledTestCaseIds`; applied consistently at run-creation's zero-runnable guard (409 `INVALID_OPERATION` when no match) and at snapshot materialization. Reuses the Structured Query DSL translation via the `service.domain.job.RunnableTestCaseSelector` interface inversion (no `service` → `experimental.query` dependency). Related: test-suites, query-schema-discovery, structured-query-model, suite-run-snapshot, test-suite-runs.
- **[test-suite-clone](test-suite-clone/spec.md)** — Implemented
  Clone endpoint (`POST /api/v1/test-suites/{id}/clone`) — deep-copies a test suite and its metric definitions (TSMDs) under a new name; cloned suite references the source's dataset by default (test cases are NOT duplicated — they belong to the dataset). Exception: cloning a suite bound to a PRIVATE dataset (no `datasetId` override) also clones the dataset — a new PRIVATE dataset with copied test cases (new ids), remapped `disabledTestCaseIds`, and copied dataset-scoped files; redirecting such a clone to a different dataset is forbidden (409 PRIVATE_DATASET_REBIND_FORBIDDEN). Optional overrides: description, deploymentRef, endpointRef, responseColumns, requestTemplate, inputBindings, mcpDeploymentRef, toolRef, argumentTemplate, datasetId, disabledTestCaseIds. suiteType is always inherited. File references in suite/TSMD JSONB rewritten to the new suite scope; post-clone validation is synchronous only.
- **[detach-dataset](detach-dataset/spec.md)** — Implemented
  Detach-dataset endpoint (`POST /api/v1/test-suites/{id}/detach-dataset`) — forks a suite's bound PUBLIC dataset into a new PRIVATE clone for exclusive use by that suite; the original PUBLIC dataset is preserved. Test cases are copied with fresh IDs and file-ref rewrites; `disabledTestCaseIds` are remapped. Optional `name` field in the request body; derived automatically when omitted. 409 when suite has no dataset (SUITE_HAS_NO_DATASET) or bound dataset is already PRIVATE (PRIVATE_DATASET_REBIND_FORBIDDEN).
- **[dataset-clone](dataset-clone/spec.md)** — Implemented
  Standalone dataset clone endpoint (`POST /api/v1/datasets/{id}/clone`) — deep-copies a PUBLIC dataset (row + all test cases with fresh IDs and `@ef/datasets/{id}/` file-ref rewrites) into a new unbound PUBLIC dataset; the source is never modified. Cloning a PRIVATE dataset is rejected with 400 (PRIVATE_DATASET_REQUIRES_SUITE_BINDING) — an unbound PRIVATE dataset is invalid; clone the owning suite instead. Optional `name` (auto-derived `"<source> (clone)"` when omitted) and `description` (copied from source when omitted) overrides; 404 when source missing, 409 on duplicate name. Reuses `DatasetCloneService.cloneRowAndTestCases` (visibility/description threaded as parameters). Related: datasets, test-suite-clone, detach-dataset.
- **[test-cases](test-cases/spec.md)** — Implemented
  TestCase CRUD, PATCH, CSV export/import, schema validation, re-validation. Test cases are dataset-scoped — endpoints live under `/api/v1/datasets/{datasetId}/test-cases/*`. Related: datasets, test-suites.
- **[multi-turn-test-case](multi-turn-test-case/spec.md)** — Implemented
  Multi-turn test cases as a single test case carrying an ordered `multiTurnData` array of per-turn data maps; `data` (shared, test-case-level fields) and `multiTurnData` (per-turn fields) coexist (no mutual exclusivity). Field scope is declared per field on the dataset schema (`FieldDefinitionDto.perTurn`); a turn's effective view merges shared `data` with the turn map and feeds template resolution, conditions, and metrics. Scope-aware validation (misplacement → 400), configurable turn-count cap (`test-case.multi-turn.max-turns`, default 10). Turn count `N` is driven by whether the suite's bindings reference a `perTurn: true` field (`multiTurnData.length` if so, else `N = 1` collapse); the turn loop is JSONata-frame-driven — no hardcoded `messages`/`choices[0].message` path, history is whatever the author's request-template JSONata expression builds from prior turns' response columns — streams each turn, and fails fast. Flat CSV multiplication (reserved `turnIndex` column, shared columns repeated, contiguous-run assembly on import), and MCP-suite rejection at run creation. Related: datasets, test-cases, request-template, response-columns, eval-execution-engine, suite-run-snapshot, suite-test-case-filter, conditional-metric-execution.
- **[test-case-bulk-delete-by-ids](test-case-bulk-delete-by-ids/spec.md)** — Implemented
  Bulk deletion by explicit UUID list (`DELETE /api/v1/datasets/{datasetId}/test-cases:bulk`) with partial-success semantics — deleted and not-found IDs returned separately, configurable cap via `test-case.bulk.max-delete-ids`. Related: test-cases.
- **[request-template](request-template/spec.md)** — Implemented
  Request template system — `${{variable}}` and `${{variable|type}}` placeholder syntax with optional type hints, input bindings, template variable extraction (`declaredType`/`effectiveType`), resolved-request preview. Every `application/json` request body (Map-authored `content` or JSONata-authored `jsonataContent`, mutually exclusive) is unconditionally JSONata-evaluated after placeholder preprocessing; the evaluated result must be a JSON object or the request errors out. Related: test-suites, response-columns, multi-turn-test-case.
- **[test-suite-metric-definitions](test-suite-metric-definitions/spec.md)** — Implemented
  TSMD CRUD — materialized metric configurations within a test suite, polymorphic parameter bindings (TestCase, Response, Constant), client-supplied metric declaration version with ownership validation, `enabled` flag, `valid` / `validationWarnings` state. Related: test-suites, metrics-system, tsmd-validation.
- **[tsmd-validation](tsmd-validation/spec.md)** — Implemented
  TSMD soft validation — `MetricDefinitionValidationService` with 6 checks (INVALID_OUTPUT_SCHEMA for missing/malformed output schemas, UNRESOLVED_REFERENCE for TestCase/Response column refs, REQUIRED for missing/null-constant required properties, ADDITIONAL for unknown properties), synchronous auto-revalidation on suite schema update, manual revalidation endpoint side effect, `enabled` flag management.
- **[aggregated-metric-definition](aggregated-metric-definition/spec.md)** — Implemented
  Read-only aggregated endpoint returning a TSMD enriched with full metric declaration and version details (schemas) in a single response. Related: test-suite-metric-definitions, metrics-system.

### Polymorphic Request Body

- **[polymorphic-request-body](polymorphic-request-body/spec.md)** — Implemented
  Polymorphic body type hierarchy for request templates (JSON, multipart/form-data, URL-encoded), endpoint schemas, resolved bodies, and pluggable RequestBodySerializer strategy.

### DIAL File Storage

- **[dial-file-storage](dial-file-storage/spec.md)** — Implemented
  DIAL Core file storage integration — DialFileClient, EF service key/bucket management, file reference resolution (@ef alias → real bucket), file upload/download proxy, suite-scoped file lifecycle, streaming file retrieval, configurable size/count limits.
- **[dataset-file-storage](dataset-file-storage/spec.md)** — Implemented
  Dataset-scoped file management REST API mirroring the suite-scoped endpoints; files stored under `@ef/datasets/{datasetId}/`. Cascade cleanup on PUBLIC dataset delete and PRIVATE suite-cascade delete. Configurable `max-files-per-dataset`.
- **[dial-file-ref](dial-file-ref/spec.md)** — Implemented
  DIAL file reference model — short format (`@ef/...`, `public/...`), DialFileRefResolver for alias-to-bucket translation (`resolveToRealPath`) and DIAL payload embedding (`resolveToDialRef`), FILE-typed placeholder resolution in ResolvedRequestService, prefix whitelist validation, `buildDatasetEfRef` for dataset-shaped refs, cross-suite/dataset reference warnings.
- **[file-ref-validation](file-ref-validation/spec.md)** — Implemented
  Centralized file reference format and ownership validation via FileRefValidator — short-format rules, dual-ownership entry points (`validateSuiteOwnership` / `validateDatasetOwnership`), legacy suite-shaped refs in test-case data tolerated, delegation from SuiteValidationService / BindingValidator / TestCaseValidationService.
- **[blob-storage](blob-storage/spec.md)** — Superseded (replaced by `dial-file-storage` + `dial-file-ref`)
  Original PostgreSQL Large Objects implementation. All requirements removed; see `dial-file-storage` for the replacement.

### Integration

Specs for external service integrations.

- **[dial-core-client](dial-core-client/spec.md)** — Implemented
  DIAL Core API proxy — unified deployment listing (models + applications + toolsets via `/v1/deployments`), type/interface query param filtering, toolset detail retrieval, JWT propagation, upstream error mapping, deployment invocation.
- **[app-schema-route-resolution](app-schema-route-resolution/spec.md)** — Implemented
  Application route resolution inherited from app type schemas via DIAL Core schema API, schema route DTOs, merge behavior.
- **[mcp-tool-invocation](mcp-tool-invocation/spec.md)** — Implemented
  MCP SDK client integration — McpToolInvoker (tool call execution, tool discovery via `tools/list`), McpRequestResolver (argument template resolution), McpResponseSerializer (CallToolResult → JSON), configurable timeouts, error mapping.
- **[toolset-listing](toolset-listing/spec.md)** — Implemented
  Toolset deployment type extension — ToolsetInfoDto, `type`/`interface` query param filtering on deployment listing, tool discovery endpoint (`GET /deployments/tools?deploymentId=&transport=`), InterfaceType enum, DeploymentType extended for toolsets.

### Try It Out

- **[try-it-out](try-it-out/spec.md)** — Implemented
  Endpoints for sending a single resolved request to a DIAL Core deployment or MCP tool call and proxying the response. Covers test-case-based and variables-based modes for both HTTP and MCP suites, URL routing, timeout configuration, error proxying, type-aware validation rules, and SSE streaming response handling (`streaming=true`, `events` list, `{"events":[...]}` body envelope).

### SSE Streaming

- **[sse-event-parsing](sse-event-parsing/spec.md)** — Implemented
  Injectable `SseEventParser` component for RFC-compliant SSE wire format parsing. Produces `SseParseResult` with typed `SseEvent` records (event type + JSON/string data), deadline enforcement via `Clock`, and byte-size limiting. Used by both the evaluation engine and TryItOut path.

### Grafana Integration

- **[grafana-deep-links](grafana-deep-links/spec.md)** — Implemented
  Grafana Explore URL generation — configurable deep links in API responses for one-click navigation to Grafana Tempo traces. Per-trace URLs on execution results and try-it-out, run-scoped TraceQL URLs on test suite runs, per-test-case aggregate TraceQL URLs on eval summaries.

### Cross-cutting Concerns

Specs for behaviors that apply across multiple domain areas.

- **[sorting](sorting/spec.md)** — Implemented
  Multi-column sorting for list endpoints (whitelist, tie-breaker, SQL injection prevention).
- **[entity-filtering](entity-filtering/spec.md)** — Implemented
  Pagination and structured `filter` (whitelist, AND/`in` operators, HTTP 400 validation) on list endpoints.
- **[security](security/spec.md)** — Implemented
  OIDC/JWT multi-issuer authentication + configurable security modes; DIAL API-Key authentication via DIAL Core introspection as an alternative to bearer tokens.
- **[openapi-examples](openapi-examples/spec.md)** — Implemented
  OpenAPI request/response examples (minimal + full), resource-based JSON, OpenApiExampleCustomizer.
- **[openapi-query-param-docs](openapi-query-param-docs/spec.md)** — Implemented
  Auto-generated OpenAPI query parameter descriptions from FilterWhitelists, SortWhitelists, and PaginationProperties. Covers field-operator matrices, type hints, pagination defaults, and parameter examples.
- **[structured-query-model](structured-query-model/spec.md)** — Implemented
  Body-delivered structured query wire contract (v7), its request-side object model, and body-delivered execution at `POST /api/v1/queries/execute`. Envelope (`entity`/`filter`/`mode`/`select`/`group_by`/`aggregate`/`having`/`sort`/`page`), CQL2-JSON filter tree (`op`/`args`), uniform expression grammar discriminated by `type` (`field`/`value`/`param`/`fn`/`array`), aggregation/sort/offset pagination. Implemented under `experimental.query.*`: the record model (sealed `Expr`/`FilterNode`/`PageSpec`, `FilterNodeDeserializer`, `@JsonValue` wire codes) plus schema-driven validation, jOOQ SQL translation, and a `{rows, totalCount}` response — narrower than the original vision (no per-field capability flags, no `page`/`next_cursor` envelope, cursor paging rejected). Parameter binding via `ParamExpr` expression substitution: a single pre-pass (`QueryParameterResolver`) rewrites a query into a parameter-free form before translation; unbound/cyclic/param-to-param bindings rejected with HTTP 400; public endpoint is parameterless. Registry-driven function catalog (`QueryFunction` SPI, `QueryFunctionRegistry`): each function is a separate component collected at startup; duplicate names rejected. `co`/`nc` translate to case-insensitive LIKE on scalar fields but to JSONB array-element containment (`?` / `@>`) when the left operand is a bare `ARRAY`-typed field. Related: query-schema-discovery, entity-filtering, sorting, suite-test-case-filter.
- **[query-schema-discovery](query-schema-discovery/spec.md)** — Implemented
  Discovery of queryable entities and their flat field schemas for the structured query DSL (`GET /api/v1/queries/entities`, base schema, and instance-specific detailed schema). Entity catalog with `complex`/`schemaIdField`, jOOQ-derived base schema (JSONB fields listed as-is), per-instance JSONB flattening for `eval_summaries` derived from a test suite run snapshot, per-dataset JSONB flattening for the complex `test_cases` entity (keyed by `dataset_id`, `data::<field>` typed from the dataset schema), a `QueryableEntitySchemaProvider` SPI + registry, and the 404/400 error contract. Related: structured-query-model, suite-test-case-filter.

### Analytics

Specs for the analytics datasource and result storage.

- **[analytics-datasource](analytics-datasource/spec.md)** — Implemented
  Dual datasource configuration — separate analytics DB alongside meta DB. Symmetric property paths (`datasource.meta.*` / `datasource.analytics.*`), qualified JdbcTemplate beans, separate Flyway migration path, startup validation.
- **[analytics-eval-results](analytics-eval-results/spec.md)** — Implemented
  Test case run result storage and retrieval. Batch write API (envelope with testSuiteId/testSuiteRunId, JDBC batch insert, idempotent), keyset-paginated read API with JSONB path filtering on `testCaseData`, append-only flat data model with `run_index` for multi-run suites.
- **[eval-execution-engine](eval-execution-engine/spec.md)** — Implemented
  In-process evaluation execution engine — virtual thread executor with semaphore-bounded concurrency, streaming SSE response handling (OpenAI delta collapse or `{"events":[...]}` envelope via `SseEventParser`), retry with exponential backoff, rate limiting, header blacklist, response size limiting, cancellation support. MCP suite support: suite type branching, McpRequestResolver → McpToolInvoker → McpResponseSerializer flow. Snapshot-driven execution: inputs read from `test_case_run_inputs` table (populated at snapshot phase); legacy runs fall back to live test case queries. **Implementation home**: the core execution classes (`EvaluationWorker`, `TurnLoopExecutor`, `DeploymentTurnInvoker`, `RequestResolver`, `SseEventParser`, `ResponseColumnExtractor`, the DIAL Core/MCP clients, etc.) now live in the `evaluation-runner-core` Gradle subproject (`com.epam.aidial.evaluation.runner.*`); the EF backend depends on it. No requirement/behavior change — see `evaluation-runner-core-module`.
- **[evaluation-runner-core-module](evaluation-runner-core-module/spec.md)** — Implemented
  The `evaluation-runner-core` Gradle subproject — a DB-free (no JDBC/jOOQ/Flyway), reusable library module under root package `com.epam.aidial.evaluation.runner` housing the Phase 1 execution engine, its DIAL Core/MCP clients, request/response pipeline, and supporting utilities, so a future standalone CI runner can share exact execution parity with the EF backend. Contributes all shared beans via Spring Boot autoconfiguration (`EvaluationRunnerAutoConfiguration`, `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`) — the EF backend needs no manual `@Import`/`@ComponentScan`. Enforces its own boundary contract via `RunnerModuleConstraintsTest` (no JDBC/jOOQ/Flyway, no dependency back on the EF backend, `client` does not depend on `job`, every Spring component carries `@LogExecution`). Related: eval-execution-engine.
- **[suite-run-snapshot](suite-run-snapshot/spec.md)** — Implemented
  Suite snapshot phase — `SuiteSnapshotDto` (versioned, `@JsonIgnoreProperties(ignoreUnknown = true)`), `SuiteSnapshotBuilder` component, `test_case_run_inputs` table for per-run test case snapshots, `suite_snapshot` JSONB column on `test_suite_runs`, snapshot phase with `ISOLATION_REPEATABLE_READ` + retry on `40001` + idempotent cleanup, inconsistent-snapshot guard, two-tier column selection (list excludes `suite_snapshot`; detail includes it), daily retention cleanup job (`TestCaseRunInputsRetentionJob`) with configurable retention duration.
- **[metrics-storage](metrics-storage/spec.md)** — Implemented
  Eval summary storage layer — denormalized analytics table for metric-enriched test case results, run metric snapshots, computation versioning, JSONB metric filtering and aggregation.
- **[metric-evaluation](metric-evaluation/spec.md)** — Implemented
  In-process metric evaluation engine — Phase 2 of test suite run lifecycle. Evaluates configured TSMDs against test case results by calling metric provider `/evaluate` endpoints with resolved bindings, writes results as EvalSummary records. Runs for any TSMD count including zero: a metric-less run still writes one EvalSummary per result row (`metric_values = {}`, no `metric_infos`, no RunMetricSnapshots), so its responses and extracted columns stay readable. Provider-bounded concurrency, retry with exponential backoff, RunMetricSnapshot capture.
- **[conditional-metric-execution](conditional-metric-execution/spec.md)** — Implemented
  Optional per-TSMD `condition` (JSONata, max 2000 chars) deciding per result row (per turn) whether a metric runs. Evaluated over a namespaced dictionary `{data, response, turn:{index,total,last}}`; clean `true` runs the metric, clean `false` omits it, a throw/non-boolean/null yields a wholesale metric-level error (`metricError::<name>`) while the row stays SUCCESS. Validated as JSONata at write time (400 on bad syntax). Related: test-suite-metric-definitions, tsmd-validation, metric-evaluation, multi-turn-test-case.
- **[metric-score-statistics](metric-score-statistics/spec.md)** — Implemented
  Code-defined per-metric aggregate statistics (AVG/P10/P90/MIN/MAX) computed as Phase-3 of a test suite run via the structured-query DSL, stored in `metric_score_results` analytics table, readable only through the unified Query DSL entity `metric_score_results`. Per-suite `overall_score` is a typed, sealed `OverallScoreDefinition` (`mean`/`weighted_mean`/`custom_function`), snapshotted per run and resolved to a `StructuredQuery` at Phase-3 time; default `overall` is the single metric's average for single-metric runs when the column is null. No management API; no CRUD endpoint over stored results — a derived, never-persisted matched-row comparison endpoint does exist, see run-comparison-metric-scores.
- **[run-comparison-metric-scores](run-comparison-metric-scores/spec.md)** — Implemented
  Matched-row comparison of two runs of one suite — `GET /api/v1/analytics/metric-scores/comparison?runIds=<a>,<b>`. Recomputes the five per-metric statistics, `overall` and the average execution duration over only the eval-summary rows whose match key (`lower(test_case_name)` + `run_index` + `turn_index`) occurs in both runs, so the two runs' numbers describe one population. Per-side anti-join with no collapse (duplicate keys all match, so `matchedRowCount` may differ between the runs); returns the **non-matching** `test_case_eval_summaries.id`s for the FE's follow-up `not`-wrapped `in` exclusion filter, capped by `analytics.comparison.max-unmatched-rows`. Values are never persisted. Implemented in `experimental.query.service.metricscore` behind the `service.domain.analytics.RunComparisonProvider` interface inversion, so the stable `web` layer does not reach experimental code. Related: metric-score-statistics, metrics-storage, structured-query-model, suite-run-snapshot.
- **[eval-summary-export](eval-summary-export/spec.md)** — Implemented
  CSV export and JSON preview for eval summaries — `POST /api/v1/analytics/eval-summaries/export.csv` (streaming CSV) and `GET /api/v1/analytics/eval-summaries/export/preview` (typed array-of-arrays JSON for column discovery). Snapshot-driven column manifest (identity → timestamps → execution → `data::<field>` → `response::<column>` → `metric::<metric>::<field>` → `metricInfo::<metric>::<field>` → `metricError::<metric>` → `extractionWarnings` → bodies; `::` is the family-separator, dots inside identifiers are preserved verbatim), `requestBody`/`responseBody` opt-in via explicit `columns`, run-state guard (terminal runs only), run-scoping filter injection, per-page `TransactionTemplate` streaming. A run with no metric snapshots exports a metric-free manifest rather than 404-ing; computation existence is decided by eval-summary rows. Related: metrics-storage, suite-run-snapshot.

### Infrastructure

Specs for database, observability, and operational concerns.

- **[build-tooling](build-tooling/spec.md)** — Implemented
  Gradle wrapper version pin (9.5+ on the 9.x line, `-bin` distribution), JDK 25 toolchain declaration, zero-deprecation-warning build invariant, jOOQ codegen output stability across wrapper bumps, and single-source-of-truth binding between wrapper, `AGENTS.md`, `openspec/config.yaml`, and `Dockerfile`.
- **[typed-sql-dsl](typed-sql-dsl/spec.md)** — Implemented
  jOOQ 3.20 typed DSL replacing NamedParameterJdbcTemplate across all repositories. Zonky EmbeddedPostgres codegen pipeline (`./gradlew generateJooq`), committed generated sources, schema-drift guard test, DSLContext beans with TransactionAwareDataSourceProxy and exception translation, RecordMapper pattern, FilterWhitelists/SortWhitelists with typed Field references, ArchUnit fence enforcing JdbcTemplate usage limits.
- **[database-and-migrations](database-and-migrations/spec.md)** — Implemented
  PostgreSQL JDBC + Flyway migration conventions.
- **[observability-and-logging](observability-and-logging/spec.md)** — Implemented
  Correlation IDs, request logging, dynamic log levels, OTel distributed tracing (W3C traceparent propagation, OTLP export, span attributes on eval and metric spans for Grafana Tempo navigation, and OTel Baggage propagation of eval run/suite id on run-scoped outbound calls for downstream analytics grouping).
- **[health](health/spec.md)** — Implemented
  Health endpoint surface.
- **[configuration-docs](configuration-docs/spec.md)** — Implemented
  Structure, schema, and maintenance rules for `docs/configuration.md` — six-column property table schema, four-term `Required` vocabulary (`Yes`/`No`/`Conditional`/`Recommended`), nine top-level groups, `Applied when` expression grammar, and the rule that every configurable property has a documented row.

### Testing

Specs for functional test conventions and test infrastructure.

- **[testing-conventions](testing-conventions/spec.md)** — Implemented
  Rules for functional test setup: use `MetaTestDataHelper`/`AnalyticsTestDataHelper` for fixtures and cleanup, no raw SQL in test methods, back-door state via named helper methods only.

### Architectural Conventions

Formal versioned requirements for project-wide architectural rules. Quick-reference inline conventions live in [AGENTS.md](../../AGENTS.md); the authoritative layering principle lives in [openspec/config.yaml](../config.yaml).

- **[best-practices](best-practices/spec.md)** — Implemented (phase 1)
  Cross-domain access through services, not foreign repositories. A domain service injects only its own domain's repository; cross-domain reads and writes go through the owning domain's service. Phase 1 covers `DatasetService`; phases 2/3 extend the rule to ~13 other services flagged in the audit.

### CLI Tools

Specs for standalone command-line tools that consume the EF backend's public REST API.

- **[eval-cli](eval-cli/spec.md)** — Implemented
  Standalone Spring Boot CLI (`eval-cli` Gradle subproject, root package `com.epam.aidial.evaluation.cli`) that clones "standard" test suites from a source EF instance, fetches their configuration and test cases, executes them against a CLI-configured target deployment using `evaluation-runner-core`'s existing batch execution path, and imports the results via the source EF's `runs/import` endpoint — enabling cross-environment evaluation without a second EF deployment. Exposes `clone`, `fetch`, `run`, `import`, and `evaluate` picocli subcommands; DB-free; static Api-Key auth against both source EF and target DIAL Core. Related: eval-results-import, test-suite-clone, evaluation-runner-core-module.

- **[eval-cli-distribution](eval-cli-distribution/spec.md)** — Implemented
  Packaging of `eval-cli` as a locally buildable Docker image: `eval-cli/Dockerfile` (multi-stage build targeting `:eval-cli:bootJar`, no JDK required at runtime) plus a dedicated entrypoint forwarding CLI arguments. Deliberately **not** published by CI — this monorepo's release tooling assumes one image per repo, so consumers clone this repo at a pinned ref and build the image themselves (`docker build -f eval-cli/Dockerfile -t eval-cli:local .` from the repo root) rather than pulling from a registry. Related: eval-cli.

### Vision / Planned

Specs documented but not yet fully implemented.

- **[metrics-system](metrics-system/spec.md)** — Partial
  Metric declarations/versioning and metric results storage. Stub implemented: list metric declarations (paginated/sorted/filtered, seeded Accuracy/Latency/Relevance).
- **[metric-provider-sync](metric-provider-sync/spec.md)** — Implemented
  Scheduled sync of metric declarations and versions from external metric provider services via GET /metrics API.
- **[response-columns](response-columns/spec.md)** — Implemented
  User-defined response column definitions scoped to TestSuite. JSONata expressions to extract named values from response bodies, stored as JSONB, evaluated at run time with an additive `$_request`/`$_response` frame; column names must not collide with a JSONata built-in function name or the reserved `_request`/`_response` names (plain `request`/`response` are allowed column names). `FILE` type supported as a display hint for DIAL file reference columns. Failed extractions feed forward to the next multi-turn turn's frame as an explicit JSONata null, not undefined. Related: request-template, multi-turn-test-case.
- **[test-suite-runs](test-suite-runs/spec.md)** — Implemented
  Foundational infrastructure for async test suite execution: run lifecycle (PENDING→RUNNING→COMPLETED/FAILED/CANCELLED), CRUD with filtering/sorting/pagination, SSE status streaming, startup reconciliation, configurable concurrency limits. Uses in-process evaluation engine with virtual threads, streaming SSE support, retry/rate-limiting, and configurable execution settings. `numberOfTestCases` finalized at snapshot phase (not immutable at creation). `suiteSnapshot` field in API response (detail only; omitted from list).
- **[eval-results-import](eval-results-import/spec.md)** — Implemented
  `POST /api/v1/test-suites/{testSuiteId}/runs/import` — imports a batch of already-produced eval results for an existing, dataset-bound suite in one request (creates the run, persists results, extracts response columns) and asynchronously runs Phase 2 (metric evaluation) + Phase 3 (score computation) against them, skipping deployment invocation entirely. Related: test-suite-runs, response-columns, analytics-results.
- **[evaluation-runs](evaluation-runs/spec.md)** — Planned
  Running suites, storing run history and per-case results.
- **[runner-and-jobs](runner-and-jobs/spec.md)** — Partial
  In-process evaluation executor (implemented). Kubernetes job runner (planned).
