## Why

Today, getting metric scores for a test suite requires running the full evaluation pipeline: snapshot → Phase 1 (actual deployment invocation) → Phase 2 (metric evaluation) → Phase 3 (score computation). There is no way to bring in test-case data and *already-produced* model responses (from an external run, a different system, or historical data) and have EF score them — the only path today is to re-invoke a deployment through EF to get a `TestSuiteRun` populated. Users who already have response data (e.g. exported from another tool, or produced by a system EF cannot invoke directly) need a way to import it and get the same metric evaluation and score computation EF already provides for live runs.

## What Changes

- Add a single new endpoint, `POST /api/v1/test-suites/{testSuiteId}/runs/import`, that accepts a batch of already-produced eval results (raw response bodies, one per test case) for an existing, dataset-bound suite. In one request it creates a `TestSuiteRun`, persists the imported results as `TestCaseRunResult` rows, and asynchronously triggers Phase 2 (metric evaluation) + Phase 3 (score computation) — Phase 1 (deployment invocation) is skipped entirely, replaced by the caller-supplied responses.
- Response-column (JSONata) extraction, previously only performed inline during live Phase 1 execution, is invoked directly against the imported response bodies at import time via the existing `ResponseColumnExtractor`.
- New validation: each imported item is caller-supplied and trusted for identity — `testCaseId`/`testCaseName`/`testCaseData` are never looked up or resolved against any existing `TestCase` row (no `TestCaseRepository` dependency in the import path); no two imported items may share the same test-case identity and `runIndex`; `completedAt >= startedAt` per item; batch-size cap; standard run guards (suite not-found/unbound/invalid, concurrency limits, run-name uniqueness).
- Test-case *data* schema validation IS performed by the new endpoint, at import time, directly against the suite's bound dataset schema — via `SchemaValidationService`/`DatasetSchemaProvider` inside `EvalResultsImportService.validateBatch` — rather than by reusing the CSV import path.
- No schema/migration changes: the new endpoint reuses the existing `TestSuiteRun` and `TestCaseRunResult` tables/columns as-is (a `run_source`/kind column was considered and deliberately rejected — nothing in the pipeline needs to branch on it, since the new Phase-2/3-only job method is invoked directly rather than dispatched by inspecting the run).

## Capabilities

### New Capabilities
- `eval-results-import`: Importing already-produced eval results (raw model responses) for an existing test suite's test cases, and running metric evaluation + score computation against the imported data without invoking a deployment.

### Modified Capabilities
(none — this is a new, additive capability; no existing spec's requirements change)

## Impact

- **API**: one new endpoint on the existing test-suite-run resource, `POST /api/v1/test-suites/{testSuiteId}/runs/import`, returning `202 Accepted` with a `TestSuiteRunResponseDto` (status `PENDING`, transitioning to `RUNNING`/`COMPLETED`/`FAILED` asynchronously like a normal run).
- **New DTOs** (`service.domain.dto.analytics`): `EvalResultsImportRequestDto`, `EvalResultsImportItemDto`.
- **New/changed service code**: `TestSuiteRunService` gains an orchestrating `importResultsAndEvaluate` entry point (composing a meta-transactional run-creation step and an analytics-transactional result-persistence step, per the project's dual-datasource constraints — no 2PC across datasources); `TestSuiteEvaluationJob` gains a Phase-2/3-only async method; `TestCaseRunResultMapper` gains an overload that sources `testCaseName`/`testCaseData` directly from the request item (never resolved from a `TestCase` row), synthesizing `testCaseId` via `UUID.randomUUID()` when the caller supplies only `testCaseName`, and sources `extractedColumns`/`extractionWarnings` from server-side `ResponseColumnExtractor` output.
- **No DB schema changes.**
- **No new configuration properties** — batch-size limits reuse the existing `analytics.results.batch.max-items` property (`AnalyticsResultsProperties`).
- **Testing**: new unit tests for the import service's validation/guard logic and the job's Phase-2/3-only method; new functional test coverage (`@PostgresFunctionalTests`) exercising import → async evaluation → `EvalSummary`/`RunMetricSnapshot`/`MetricScoreResult` verification.
