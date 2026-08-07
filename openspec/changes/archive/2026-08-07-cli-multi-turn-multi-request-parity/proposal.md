## Why

`eval-cli` reuses the shared Phase 1 execution engine (`evaluation-runner-core`), which is fully multi-turn and multi-request capable — but the CLI's wiring and the CSV→import boundary predate both features, so they are silently broken end-to-end:

1. **Multi-turn never activates in the CLI.** `EvaluationContextFactory` never sets `snapshotTestCaseSchema`, and the CLI never fetches the dataset schema at all (`FetchService` fetches suite + test cases only; `TestSuiteResponseDto` carries no schema, and `perTurn` lives on the dataset's `FieldDefinitionDto`). `PerTurnBindingDetector.referencesPerTurnField(bindings, null)` therefore always answers `false` → every multi-turn case runs as N = 1 with shared `data` only. Wrong results, no error.
2. **Row identity is destroyed at the CSV/import boundary, silently.** `CsvResultBatchWriter` omits `requestIndex`/`totalRequests`/`turnIndex`/`totalTurns` (all four exist on the runner's `TestCaseRunResult`), and the backend's `EvalResultsCsvParser.RESERVED_COLUMNS` has no such columns either — so every imported row is persisted pinned to `0`/`1`/`0`/`1` no matter where it actually sat in the chain or turn sequence. Conditional metrics pinned to a chain or turn position (`request.name`, `request.last`, `turn.last`) therefore can never match on an imported run. The corruption is silent rather than loud: `CsvResultBatchWriter` also omits `testCaseId`, and the parser synthesizes a **fresh random UUID per row** when that column is absent, so `EvalResultsImportService.validateBatch`'s duplicate key `(testCaseId-or-testCaseName, runIndex)` never collides for a CLI-produced file. A multi-request or multi-turn CLI run imports "successfully" and yields a run whose rows each claim to be an unrelated single-request, single-turn test case. A CSV that *does* carry `testCaseId` — a hand-produced one — instead hits the opposite failure: the duplicate key collides and the whole batch is rejected as "Duplicate result".
3. **Imported rows are not identity-equivalent to live-run rows.** A live run emits every `(request, turn)` row of one test-case repetition under a single shared `test_case_id`. The per-row random UUID breaks that grouping, which the eval-summary natural key `(run, test_case_id, run_index, request_index, turn_index, computation_id, created_at_ms)` depends on — imported results cannot be grouped back into the repetition that produced them.
4. **Import-time `testCaseData` schema validation is scope-blind.** `SchemaValidationService.buildFieldSchema` flattens every dataset field — per-turn and shared alike — into one `properties` map and every `required` field into one `required` list, and `EvalResultsCsvParser` validates each row against it. Shared-only rows are legitimate, though: a chain position that binds no per-turn field executes once from shared data, and a single-turn case in a per-turn dataset persists shared-only `testCaseData`. A dataset with a required per-turn field therefore rejects the whole import with HTTP 400.

Imported runs already capture a real suite snapshot (`executeSnapshotPhase` runs on the import path too, populating `requestName`/`additionalRequests`/`testCaseSchema`), so Phase 2 resolves request labels correctly as soon as rows carry their true `requestIndex` — no backend metadata work is needed, and design.md Decision 5 asserts that with a functional test.

## What Changes

- **Backend — import contract extension** (`EvalResultsCsvParser`, `EvalResultsImportService`):
  - Add four **optional** reserved CSV columns: `requestIndex`, `totalRequests`, `turnIndex`, `totalTurns`. Absent/blank → current defaults (`0`/`1`/`0`/`1`), keeping every existing CSV byte-compatible (non-breaking).
  - Parser populates the stubs and validates per-row sanity (non-negative integers; `totalRequests`/`totalTurns` at least 1; `requestIndex < totalRequests`; `turnIndex < totalTurns`).
  - `validateBatch` duplicate key extends to `(identity, runIndex, requestIndex, turnIndex)`.
  - No DB schema change: `request_index`/`total_requests`/`turn_index`/`total_turns` already exist on `test_case_run_results` (multi-request/multi-turn changes); the import path merely stops discarding them.
- **Backend — stable per-case identity for id-less CSVs**: when a row has no `testCaseId`, the parser synthesizes **one** UUID per distinct `testCaseName` within the file instead of one per row, so all `(request, turn)` rows of a repetition share a `test_case_id` exactly as a live run's do.
- **Backend — scope-aware import validation**: `testCaseData` required-ness is enforced only for shared-scope fields; per-turn fields are type-checked when present and never treated as required, so a shared-only row in a per-turn dataset validates.
- **Backend — Phase 2 verification (test only)**: a functional test asserts that a `request.name`-pinned conditional metric works on an imported multi-request run — imported runs already capture a real suite snapshot, so the identity columns are the only missing piece (design.md Decision 5).
- **CLI — multi-turn activation**: new `DatasetApiClient` in `.client.source` (`GET /api/v1/datasets/{id}`), dataset schema persisted in `SuiteFetchBundle`, `EvaluationContextFactory` sets `.snapshotTestCaseSchema(...)`.
- **CLI — CSV export of row identity**: `CsvResultBatchWriter` emits the four new columns (and its Javadoc note claiming they are "not part of RESERVED_COLUMNS" is corrected).
- **CLI — MCP guard parity**: `RunOrchestrationService` fails fast when `suiteType == MCP_TOOL` and any fetched test case carries `multiTurnData`, mirroring the backend's 409 run-creation guard.
- **Docs**: the import endpoint's OpenAPI examples and `@Operation` description brought in line with the full reserved-column contract; `eval-cli/README.md` updated (fetch now includes the dataset schema; new bundle field; deployment order).

Everything here modifies **Implemented** capabilities; no Planned/Vision surface is touched. No Flyway migration, no new configuration properties (hence no `docs/configuration.md` change) are anticipated.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `eval-results-import`: import contract gains four optional reserved columns carrying per-row `(request_index, turn_index)` identity; id-less rows sharing a `testCaseName` share one synthesized `test_case_id`; in-batch duplicate detection keys on `(identity, runIndex, requestIndex, turnIndex)`; `testCaseData` validation becomes scope-aware; imported runs' request/turn context reaches Phase 2 conditional metrics (enabled by the identity columns — no metadata code change, behavior asserted by test).
- `eval-cli`: `fetch` also retrieves the bound dataset's schema (bundle format extended); `run` activates the shared engine's per-turn detection via the fetched schema, produces one result per `(request, turn)` pair, writes row identity columns to the results CSV, and rejects MCP_TOOL suites with multi-turn cases pre-flight.

(`multi-turn-test-case` / `multi-request-suite` requirements are unchanged — this change makes existing consumers honor them, it does not alter run semantics.)

## Impact

- **Backend (root module)**: `service.domain.analytics.EvalResultsCsvParser`, `EvalResultsImportService`. API: `POST /api/v1/test-suites/{id}/runs/import` accepts (does not require) four new columns, and stops rejecting shared-only rows in per-turn datasets. No changes to `TestSuiteRunService`/`TestSuiteEvaluationJob`/`MetricEvaluationContext`.
- **eval-cli**: new `client.source.DatasetApiClient`; `model.SuiteFetchBundle` (new field — persisted bundle JSON grows; old bundles without the field remain loadable), `service.FetchService`, `service.EvaluationContextFactory`, `service.RunOrchestrationService`, `csv.CsvResultBatchWriter`.
- **evaluation-runner-core**: no changes expected (engine already supports both features).
- **Deployment order**: the backend's import-contract extension MUST ship no later than the CLI change. A CLI running ahead of its backend fails silently, not loudly — an old backend ignores the four unknown headers and persists `0`/`1`/`0`/`1` with per-row random ids, exactly the corruption this change removes.
- **Risk**: bundle-format and CSV-format evolution must stay backward-compatible; multi-row-per-case runs multiply row counts against `analytics.results.batch.max-items` (documented, not resized here). Verification item: cloned datasets must preserve `perTurn` flags (adjacent known issue GH #120 is a different path — CSV OVERRIDE import — and stays out of scope).
- **Tests**: backend unit + functional tests for identity columns, stable per-name identity, scope-aware validation, and a legacy 15-column regression; CLI unit tests for schema fetch wiring, CSV columns, and the two guards.
