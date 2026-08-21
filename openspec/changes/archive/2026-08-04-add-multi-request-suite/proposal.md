## Why

A test suite today defines exactly **one** request per test case: the suite-level `deploymentRef` + `endpointRef` + `requestTemplate` + `responseColumns` + `inputBindings` (or the MCP triad for `MCP_TOOL` suites). Real evaluation scenarios need a **chain** of requests against the same deployment — e.g. request #0 updates the deployment's configuration, request #1 runs the actual (single- or multi-turn) test against that configuration; or a setup call produces an id/token that the test call must reference.

Today authors have no way to express this. They either pre-provision state out-of-band (untracked, not reproducible per run) or fold everything into one request body (impossible when the calls hit different endpoints or must be ordered).

This change introduces **multi-request test suites**: a suite may define additional requests that execute, in order, after the existing suite-level request. The 1-to-1 suite↔deployment relationship is preserved (`deploymentRef` stays suite-level).

## What Changes

**Chain model (new capability)**
- The existing suite-level request fields become **request #0** of the chain — always first, always present.
- New suite field `additionalRequests: List<RequestDefinitionDto>` — each entry carries the **full** per-request shape: optional `name` label (≤255), `endpointRef`, `requestTemplate`, `responseColumns`, `inputBindings`. They execute after request #0, in list order.
- New optional suite-level `requestName` (≤255) labels request #0, so every request in the chain is user-labellable.
- Chain length cap: `ValidationConstants.MAX_ADDITIONAL_REQUESTS = 10`.

**Shared flat response-column namespace**
- Response columns across request #0 and all additional requests form **one flat namespace**. Global name uniqueness is enforced at write time (duplicate across any two requests → HTTP 400). No `response::<request>::` prefixing anywhere.
- Frame bindings **accumulate** along the chain: request `i+1`'s JSONata sees every column extracted by requests `0..i`; within a multi-turn request each turn's extraction merges into the accumulated frame.
- Each persisted result row's `extracted_columns` is the **accumulated union** at that point in the chain.
- The 50-column cap becomes a **suite-wide union** cap.

**Multi-turn stays emergent, now per request**
- `PerTurnBindingDetector` runs on **each request's own** `inputBindings`. Any number of requests in a chain may be multi-turn; a request with no per-turn binding executes exactly once.

**New `request` analytics dimension**
- Every HTTP call in the chain persists a `TestCaseRunResult` row. Metrics run on **all** rows (authors gate per-request scoring with conditional metrics).
- New `request_index INT NOT NULL DEFAULT 0` / `total_requests INT NOT NULL DEFAULT 1` on **both** `test_case_run_results` and `test_case_eval_summaries`; every uniqueness key that currently includes `turn_index` is extended to include `request_index`.
- Indices are stamped only when chain length > 1, so a single-request suite's rows stay byte-identical to today's.

**Conditional metric execution gains a `request` namespace**
- The TSMD condition dictionary gains `request: {index, total, last, name}` mirroring the existing `turn` object, so a condition can pin a metric to a specific request by index, position or label.

**MCP: model-ready, implementation deferred**
- `additionalRequests` non-empty on a `suiteType=MCP_TOOL` suite → HTTP 400. MCP chaining needs a JSONata-capable argument template and is a follow-up change.

**Fail-fast across the chain**
- A failing turn aborts the remaining turns of that request **and** the remaining requests. Completed calls' rows persist; the failing call persists as `ERROR`/`FAILED`/`TIMEOUT`.

Everything above is **Planned** (nothing in this change is implemented yet). Every existing single-request suite is unaffected: no data migration, all new columns additive with defaults.

## Capabilities

### New Capabilities
- `multi-request-suite`: the request-chain model — `additionalRequests` / `requestName` on a suite, ordered execution semantics, the shared flat response-column namespace and frame accumulation, per-request multi-turn emergence, the `request_index`/`total_requests` analytics dimension, chain-wide fail-fast, and the `MCP_TOOL` rejection guard.

### Modified Capabilities
- `test-suites`: create/update/get accept and return `additionalRequests` + `requestName`; new hard-400 validation rules (global response-column uniqueness, suite-wide 50-column union cap, chain length cap, MCP guard); suite-level soft validation runs per request with indexed warning paths; clone rewrites suite-scoped file refs inside the `additional_requests` blob.
- `request-template`: a suite carries one request template **per request in the chain**, not a single one; template size / bindings-count / duplicate-`templateVariable` limits apply per request; the resolved-request preview endpoint gains an optional `requestIndex`.
- `response-columns`: response columns are defined per request but share one flat suite-wide namespace with global uniqueness; the frame available to a column's expression accumulates prior requests' columns; persisted `extracted_columns` is the accumulated union.
- `multi-turn-test-case`: drop the "the suite's single `requestTemplate`/`inputBindings`" wording — turn count is decided per request from that request's own bindings.
- `suite-run-snapshot`: `SuiteSnapshotDto` gains `additionalRequests` + `requestName` additively; `snapshotVersion` stays `"2"`.
- `conditional-metric-execution`: the condition dictionary gains the `request: {index, total, last, name}` namespace.
- `analytics-eval-results`: `test_case_run_results` gains `request_index` / `total_requests`; its natural-key uniqueness constraint is extended.
- `metrics-storage`: `test_case_eval_summaries` gains `request_index` / `total_requests`; its natural-key unique index is extended.
- `run-comparison-metric-scores`: the matched-row key becomes `lower(test_case_name)` + `run_index` + `request_index` + `turn_index`.
- `eval-summary-export`: the CSV manifest gains `requestIndex` **and** `turnIndex` identity columns immediately after `runIndex` (the `turnIndex` addition intentionally fixes the pre-existing gap where a multi-turn run's rows are indistinguishable in an export); `response::<column>` columns are derived from the suite-wide union.
- `query-schema-discovery`: `eval_summaries` detailed-schema `response::*` fields are derived from the suite-wide union of the snapshot's response columns.
- `tsmd-validation`: TSMD reference resolution and auto-revalidation resolve `Response` bindings against the suite-wide union of response columns.

## Impact

**API (backward compatible, additive)**
- `POST /api/v1/test-suites`, `PUT /api/v1/test-suites/{id}`, `GET /api/v1/test-suites`, `GET /api/v1/test-suites/{id}` — new optional `additionalRequests`, `requestName` fields.
- `GET /api/v1/test-suite-runs/{id}` — `suiteSnapshot` echo gains the two new fields.
- `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/resolved-request` — new optional `requestIndex` query param (default `0`, bounds-checked → 400 out of range).
- `POST /api/v1/analytics/eval-summaries/export.csv` + `.../export/preview` — two new manifest columns, `requestIndex` and `turnIndex`, inserted after `runIndex`. Name-based column selection is unaffected; a consumer that indexes the header row positionally sees two extra columns.
- `GET /api/v1/analytics/metric-scores/comparison` — unchanged contract, changed matching semantics.
- New HTTP 400 (`VALIDATION_ERROR`) causes: duplicate response-column name across requests, suite-wide union > 50 columns, `additionalRequests.size() > 10`, `additionalRequests` non-empty on an MCP suite, per-request template/body/schema violations.

**Data model — meta DB** (Flyway, next free version `V1.29`; current head `V1.28__AddMultiTurnDataToTestCaseRunInputs.sql`)
- `V1.29__AddAdditionalRequestsToTestSuites.sql`: `ALTER TABLE test_suites ADD COLUMN additional_requests JSONB NOT NULL DEFAULT '[]'::jsonb`, `ADD COLUMN request_name VARCHAR(255)` (nullable). No data migration, no index changes.

**Data model — analytics DB** (Flyway, next free versions `V1.16`/`V1.17`; current head `V1.15__AddEvalSummariesRunComputedAtIndex.sql`)
- `V1.16__AddRequestColumnsToTestCaseRunResults.sql`: add `request_index INTEGER NOT NULL DEFAULT 0`, `total_requests INTEGER NOT NULL DEFAULT 1`; drop and re-create `uq_results_run_case_index` as `UNIQUE (test_suite_run_id, test_case_id, run_index, request_index, turn_index, created_at_ms)`.
- `V1.17__AddRequestColumnsToEvalSummaries.sql`: same two columns; drop and re-create unique index `uq_eval_summaries_natural_key` as `(test_suite_run_id, test_case_id, run_index, request_index, turn_index, computation_id, created_at_ms)`.
- These are the **only** two DDL objects in the analytics schema that reference `turn_index` (verified by grep over `src/main/resources/db/migration/analytics/POSTGRES/`).
- `./gradlew generateJooq` must be run and the regenerated sources under `src/main/java-generated/` committed.
- `docs/database-schema.md` must be updated (both tables' column lists + index lists + migration history).

**Configuration**
- No new configuration properties. `docs/configuration.md` needs no change. The chain-length cap is a compile-time constant (`ValidationConstants.MAX_ADDITIONAL_REQUESTS`) because it is consumed by a `@Size` annotation.

**New classes / packages** (no new packages)
- `com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto` — new shared DTO in the existing `evaluation-runner-core` `runner.dto` package.
- `com.epam.aidial.evaluation.runner.job.RequestChainExecutor` — new orchestrator in the existing `runner.job` package.
- New constant `ValidationConstants.MAX_ADDITIONAL_REQUESTS`; new constant `ValidationConstants.MAX_RESPONSE_COLUMNS = 50` extracted from the current hardcoded literal.

**Modified classes (verified to exist)**
- Meta/API: `TestSuiteRequestDto`, `TestSuiteResponseDto`, `SuiteSnapshotDto`, `TestSuite`, `TestSuiteRecordMapper`, `TestSuiteMapper` (incl. `toCloneEntity` file-ref rewrite), `JsonbMapper`, `RunnerJsonbMapper`, `TestSuiteRequestValidator`, `SuiteValidationService` (both overloads), `TestSuiteService` (`normalizeRequest`, `isResponseColumnsChanged`), `MetricDefinitionValidationService`, `SuiteSnapshotBuilder`.
- Runner: `EvaluationContext`, `EvaluationWorker`, `TurnLoopExecutor`, `runner.model.TestCaseRunResult`.
- Analytics: `EvalSummary`, `TestCaseRunResultRecordMapper`, `EvalSummaryRecordMapper` (all four map methods), `PostgresTestCaseRunResultRepository.saveAll`, `PostgresEvalSummaryRepository` (`saveAll`, `matchCondition`, `otherRunKeys`, `findUnmatchedIds`).
- Downstream: `ConditionContext`, `ConditionExpressionEvaluator`, `InProcessMetricEvaluationExecutor`, `EvalSummariesSchemaProvider`, `EvalSummaryExportColumnPlanner`, `RunComparisonService`, `ResolvedRequestController`, `ResolvedRequestService`.

**Risks**
- Two analytics uniqueness keys are dropped and re-created — brief write-blocking DDL on large tables (see design).
- `PostgresResultBatchWriter` / `saveAll` `ON CONFLICT` targets must be updated in lockstep with the DDL, or idempotent re-writes silently degrade.
- Metrics now run on every chain row by default, multiplying provider calls for chained suites; authors must gate with `condition`.

**Rollout**
- Additive columns with defaults + additive DTO fields; no feature flag. Existing suites keep `additional_requests = '[]'`, produce a one-element chain, and stamp no request indices — byte-identical rows.
- No backfill. Old `suiteSnapshot` blobs deserialize unchanged (`@JsonIgnoreProperties(ignoreUnknown = true)`, `snapshotVersion "2"` retained).

**Test plan**
- Unit: chain ordering, frame accumulation, fail-fast, index stamping, per-request turn detection; validator (cross-request duplicate column → 400, union cap → 400, MCP guard → 400, chain cap → 400); `request` condition namespace; comparison match key; export manifest.
- Functional (`@PostgresFunctionalTests`): suite CRUD round-trip with `additionalRequests`; a 2-request chain run (setup + multi-turn test) asserting per-row `request_index`/`turn_index`, accumulated `extracted_columns` and condition-gated metrics; legacy single-request suite produces rows with indices at defaults.
- `./gradlew spotlessApply checkstyleMain checkstyleTest`, `:evaluation-runner-core:test`, full `./gradlew clean build`.
