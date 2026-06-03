## Why

Today there is no way for users to extract a `TestSuiteRun`'s metric-enriched results out of the system for downstream analysis. The existing `/api/v1/analytics/eval-summaries` endpoints return paginated JSON suitable for UIs but not for spreadsheet/BI tooling, and `EvalSummary.testCaseData`, `extractedColumns`, and `metricValues` are nested JSON payloads that are awkward to flatten by hand. Users currently dump JSON and post-process it themselves, which loses the run's *frozen* schema (the live `TestSuite` may have evolved since the run executed) and is error-prone.

This change adds a first-class CSV export so users can pull one run × one metric computation into a flat, analysis-ready table, with the columns derived from the run's frozen `SuiteSnapshotDto`. A companion JSON preview endpoint surfaces the full column manifest plus a 10-row sample (as a JSON array-of-arrays — first array is the headers, subsequent arrays are data rows with parsed cell values) so the UI can drive column selection before the (potentially large) full download.

## What Changes

- **Add `POST /api/v1/analytics/eval-summaries/export.csv`** — streaming CSV export for a single `(runId, computationId)` pair.
  - Request body carries `runId`, optional `computation` (UUID or `latest`), `columns` (ordered subset to emit; empty = default set), `filter`, `delimiter`.
  - Column set is computed from the run's frozen `SuiteSnapshotDto.testCaseSchema` + `responseColumns` + the resolved computation's `RunMetricSnapshot` entries — no reliance on the *live* `TestSuite`.
  - `testCaseData` is inlined as one column per schema field (prefix `data.`); `extractedColumns` is inlined per response column (prefix `response.`); `metricValues` is inlined as `<metricName>.<fieldName>` numeric columns.
  - `metricInfos` and `extractionWarnings` are JSON-stringified into single CSV cells. Keys present in `extractedColumns` that are not in the run's frozen `responseColumns` are dropped (no overflow column — the run-frozen column-set rule guarantees this set is empty in well-formed runs).
  - `requestBody` / `responseBody` are excluded from the **default** column set (the set emitted when `columns` is empty/omitted); they SHALL be included only when the caller explicitly names them in `columns`. Naming either body column implicitly turns on the `test_case_run_results` JOIN projection. Callers wanting a full dump including bodies discover the complete manifest via `GET /export/preview` and then POST the full `columns` list — one extra round-trip for the heaviest case, accepted as a clean simplification over carrying a separate "detailed" flag.
  - FILE-typed cells (both schema-side and response-column-side) emit the raw DIAL ref string as-is. ZIP materialization is **out of scope for V1** — the column planner is structured so a later change can swap FILE cell values for ZIP-relative paths without touching the column-discovery code.
  - Legacy runs (`suite_snapshot IS NULL`) synthesize a transient snapshot via `SuiteSnapshotBuilder.build(liveSuite)` (mirroring the baseline AGENTS.md "Legacy runs" policy); only when the live suite is also missing does the request fail with `HTTP 422` (`SNAPSHOT_SUITE_MISSING`). Unsupported `snapshotVersion` still fails with `HTTP 422` (`UNSUPPORTED_SNAPSHOT_VERSION`). The 422 mappings are wired by extracting `SnapshotSuiteMissingException` and `UnsupportedSnapshotVersionException` (currently package-private inside `TestSuiteEvaluationJob`) to top-level classes under `service.domain.exception` and adding handlers to `DefaultExceptionHandler`.
  - Non-terminal runs (`status = PENDING | RUNNING`) are rejected with `HTTP 409` (`RUN_NOT_TERMINAL`) — cursor pagination over a moving target produces inconsistent CSV; the export only operates on `COMPLETED | FAILED | CANCELLED` runs. The same guard applies to the preview endpoint.
- **Add `GET /api/v1/analytics/eval-summaries/export/preview`** — returns the full column manifest plus at most 10 sample rows as JSON. Response body is a top-level array-of-arrays: the first element is the headers array; each subsequent element is one data row whose cells preserve parsed JSON types (numbers stay numbers, nested objects/arrays are emitted as JSON, `null` stays `null`). The headers array always includes `requestBody` and `responseBody` so the UI can decide whether to include them in the subsequent POST. Same `runId`/`computation`/`filter` semantics; does NOT accept `columns` or `delimiter` — header is always the complete column set, and the JSON response has no CSV delimiter concept.
- **Introduce a column-planner / column-selector pair** as injectable `@Component`s under `service.domain.analytics` so the column-discovery and subsetting logic is isolated and unit-testable.
- **Extend `PostgresEvalSummaryRepository`** with a cursor-paginated read that accepts the JOIN-on-`test_case_run_results` projection on demand (the existing `SELECT_BY_ID_DETAIL_SQL` shape, lifted into a list query) when a request's `columns` array names `requestBody` or `responseBody`.
- **OpenAPI**: add request/response examples for both endpoints; register the preview endpoint's `filter` param with `OpenApiQueryParamCustomizer` (the POST takes its inputs in the body and needs no customizer entry).
- **Filter whitelisting**: extend (or add) `FilterWhitelists.EVAL_SUMMARIES` to cover the columns we permit filtering on for export.
- **AGENTS.md / docs**: note the new endpoints under the analytics surface.

## Capabilities

### New Capabilities
- `eval-summary-export`: CSV export and preview for `EvalSummary` results scoped to a single run × computation, with run-frozen column derivation from `SuiteSnapshotDto`, optional column subsetting, and opt-in inclusion of request/response bodies via explicit naming in the `columns` array.

### Modified Capabilities
*None.* The export consumes data already specified by `analytics-eval-results`, `metric-evaluation`, `response-columns`, and `suite-run-snapshot` without changing their requirements; the export is purely a new read-side surface.

## Impact

- **Code**:
  - New controller methods on `EvalSummaryController` (`POST /export.csv`, `GET /export/preview`).
  - New services: `EvalSummaryExportService`, `EvalSummaryExportColumnPlanner`, `EvalSummaryExportColumnSelector` (all under `service.domain.analytics`).
  - New DTO: `EvalSummaryExportRequestDto` under `service.domain.dto.analytics`.
  - New repository read path on `PostgresEvalSummaryRepository` (JOIN-on-`test_case_run_results` cursor query).
- **API**: two additive endpoints; no breaking changes to existing routes.
- **Database**: no schema changes, no migrations.
- **Configuration**: no new properties expected. (If a constant such as preview row count or max columns is exposed, it goes in `ValidationConstants`, not `application.yml`.)
- **Security**: same auth/authz as the existing list endpoint — no new roles or scopes.
- **Dependencies**: reuses Apache Commons CSV (`org.apache.commons.csv.CSVPrinter`) already present in the project.
- **Docs**: `openspec/specs/README.md` gets a new entry for `eval-summary-export`. No change to `docs/database-schema.md` or `docs/configuration.md`.
- **Out of scope (deferred to follow-up changes)**: ZIP materialization of FILE refs, asynchronous/job-based export, cross-run export.
