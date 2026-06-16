## Context

`EvalSummary` rows store metric-enriched evaluation outputs in the analytics datasource (`eval_summary` table, append-only, versioned by `computation_id`). The current API surface (`EvalSummaryController` under `/api/v1/analytics/eval-summaries`) returns nested JSON with `testCaseData`, `extractedColumns`, `metricValues`, `metricInfos`, and optional `requestBody`/`responseBody` payloads via the JOIN to `test_case_run_results`. Each `TestSuiteRun` carries a frozen `SuiteSnapshotDto` (in `test_suite_runs.suite_snapshot` JSONB) that describes the test-case schema and response-column definitions *as they were when the run executed*; this is the authoritative source for what columns belonged to that run. Metric output shapes for a specific computation are pinned by `run_metric_snapshots` rows (`computation_id` + `output_schema`).

The existing TestCase CSV export (`TestCaseController.exportCsv` + `CsvExportService`/`ZipExportService`) is the reference pattern for streaming CSV through Apache Commons CSV `CSVPrinter` and serializing per-row cells. The EvalSummary export reuses that streaming/serialization shape but introduces dynamic, run-specific column derivation and an explicit column-subset selector.

## Goals / Non-Goals

**Goals:**
- Export one `(runId, computationId)` of `EvalSummary` rows as flat CSV ready for downstream analysis tools (Excel, pandas, BI).
- Derive the column set from the run's frozen `SuiteSnapshotDto` plus the resolved computation's `RunMetricSnapshot` entries — never from the *live* `TestSuite`, so an export of an old run is deterministic and faithful.
- Flatten `testCaseData` (one column per schema field), `extractedColumns` (one column per response column), and `metricValues` (one column per `<metric>.<field>`) into separate CSV columns.
- Keep `metricInfos`, `extractionWarnings`, and any non-flattenable JSON as single CSV cells holding compact JSON.
- Support inclusion of `requestBody`/`responseBody` (which live in `test_case_run_results` and require a JOIN projection) via explicit naming in the `columns` array — no separate `detailed` flag on the API surface.
- Support **column subsetting** so callers can request only the columns they want — passed in the request body to avoid URL-length limits when there are many schema fields and metric fields.
- Provide a **preview endpoint** that returns the full column manifest plus the first 10 rows so a UI can drive column selection before the full POST export.
- Handle legacy runs (`suite_snapshot IS NULL`) by synthesizing a transient snapshot via `SuiteSnapshotBuilder.build(liveSuite)`; only fail with `HTTP 422` (code `SNAPSHOT_SUITE_MISSING`) when the live suite is also missing. Snapshots with unsupported `snapshotVersion` still fail with `HTTP 422` (code `UNSUPPORTED_SNAPSHOT_VERSION`).

**Non-Goals (V1):**
- ZIP archive output that materializes FILE-typed cells into downloadable files. V1 emits raw DIAL refs verbatim. The column planner is structured to make this an additive follow-up (the FILE-cell value-extractor is the only place that needs to swap).
- Asynchronous / job-based / S3-staged export. V1 is synchronous streaming.
- Cross-run, cross-suite, or cross-computation export. V1 is one `(runId, computationId)`.
- Customizing CSV quoting, line-ending, or character-set policies beyond the existing project defaults.

## Decisions

### D1. POST for the full export, GET for the preview

Full export takes a potentially large `columns` list (a run with dozens of `testCaseSchema` fields plus several metric definitions each with multiple output fields can blow past common URL-length limits). It is therefore `POST /api/v1/analytics/eval-summaries/export.csv` with an `EvalSummaryExportRequestDto` JSON body. The endpoint is read-only in effect; the verb choice is driven by payload size, not semantics. The preview endpoint has no `columns` selection and stays `GET /api/v1/analytics/eval-summaries/export/preview` with query params, keeping it browser-friendly. The preview path drops the `.csv` suffix from the export path because the preview response is JSON (`application/json`), not CSV — the URL should not lie about the content type.

Alternative considered: hybrid (query params for small fields + body for `columns`). Rejected as awkward — mixing parameter sources across one resource is harder to document and validate than a single JSON body.

Alternative considered for the preview shape: returning a CSV body for parity with the export endpoint. Rejected because the preview's sole consumer is a column-picker UI, which then has to parse CSV in the browser; an array-of-arrays JSON (first row = headers, remaining rows = data) is trivially consumable by JS and preserves typed values (numbers as numbers, nulls as nulls) for nicer rendering. The full export remains CSV because CSV is the native format for downstream analysis tools (Excel, pandas, BI) — that audience is distinct from the in-browser preview audience.

### D2. Column set is run-frozen, not live-suite-derived

The column planner reads:
- `SuiteSnapshotDto.testCaseSchema` → inlined `data.<fieldName>` columns,
- `SuiteSnapshotDto.responseColumns` → inlined `response.<columnName>` columns,
- `RunMetricSnapshot.{tsmdName, outputSchema}` for the resolved `computation_id` → inlined `<metricName>.<fieldName>` numeric columns. (`tsmdName` on the snapshot is the metric's display name copied from `TestSuiteMetricDefinition` at run time; it is the value used as the CSV column-name prefix and is referred to as `<metricName>` in spec/headers.)

It never touches `TestSuite` or `TestSuiteMetricDefinition` from the meta DB for runs that have a snapshot. This guarantees a re-export of an old run produces the same column set it would have produced right after the run finished, even if the suite or metric definitions have since been edited.

Legacy runs (`suite_snapshot IS NULL`) are handled by synthesizing a transient snapshot via `SuiteSnapshotBuilder.build(liveSuite)`, mirroring the baseline policy documented in AGENTS.md → "Suite Run Snapshot Phase" → "Legacy runs". Only when the live suite is also missing does the service fail with `HTTP 422` (code `SNAPSHOT_SUITE_MISSING`). Snapshots whose `snapshotVersion` is unsupported still fail with `HTTP 422` (code `UNSUPPORTED_SNAPSHOT_VERSION`).

**Shared exception types and 422 mapping**: `SnapshotSuiteMissingException` and `UnsupportedSnapshotVersionException` currently exist as **package-private nested classes** inside `TestSuiteEvaluationJob.java` (see lines 411–421). They are extracted to **top-level public classes** under `com.epam.aidial.evaluation.service.domain.exception` (alongside `ValidationException`, `EntityNotFoundException`, etc.), so that both `TestSuiteEvaluationJob` and the new `EvalSummaryExportService` (in a different package, `service.domain.analytics`) can throw and import them from a single shared location. `DefaultExceptionHandler` is extended with two new `@ExceptionHandler` methods that map each exception to `HTTP 422 Unprocessable Entity` with the corresponding `ErrorCode.SNAPSHOT_SUITE_MISSING` / `ErrorCode.UNSUPPORTED_SNAPSHOT_VERSION` entry (newly added to `ErrorCode`).

**Run state guard (terminal-only)**: cursor pagination over `test_case_eval_summaries` requires a stable snapshot of the underlying table; exporting a run whose `status` is non-terminal (`PENDING`, `RUNNING`) can produce skipped or duplicated rows as new summaries are inserted mid-stream. After loading the `TestSuiteRun`, the service rejects requests targeting a non-terminal run with `HTTP 409 Conflict` and error code `RUN_NOT_TERMINAL` (well-formed request, resource state forbids the operation — 409 is more accurate than 422 here). `RunStatus.isTerminal()` already exists on the enum and is reused. The same guard applies to the preview endpoint for symmetry (the preview also walks rows from the live table).

Non-goal: keys present in `extractedColumns` that are not in `SuiteSnapshotDto.responseColumns` are dropped from the CSV; the run-frozen column-set rule guarantees this set is empty in well-formed runs, so no "overflow" column is emitted.

### D3. Column-name namespacing prevents collisions

Inlined columns are prefixed so they cannot collide with identity/execution columns or with each other:
- `data.<fieldName>` for testCaseSchema fields,
- `response.<columnName>` for response columns,
- `<metricName>.<fieldName>` for flattened metric values (the metric name is already a unique identifier within a computation, so it does not need an additional prefix).

Identity columns (`id`, `testSuiteId`, `testSuiteRunId`, `testCaseRunResultId`, `testCaseId`, `testCaseName`, `runIndex`, `computationId`), timestamps (`createdAt`, `computedAt`), execution columns (`executionStatus`, `execDurationMs`, `responseStatusCode`), JSON-blob cells (`metricInfos`, `extractionWarnings`), and body columns (`requestBody`, `responseBody`) use their bare property names. These bare names are the canonical IDs callers pass via `columns`.

### D4. Bodies are opt-in via explicit `columns`

The API surface carries no `detailed` flag. `requestBody` and `responseBody` are excluded from the default column set and are emitted **only** when the caller explicitly names them in `columns`. The decision is therefore single-axis (the contents of `columns`), not the mix of two parameters it used to be:

- When `columns` is empty/omitted, the effective column set is the full manifest **minus** `requestBody` and `responseBody`. Repository projection: non-JOIN.
- When `columns` is non-empty, the effective column set is exactly the requested columns in the requested order. Repository projection: JOIN iff any requested column is a body column.

This means the heaviest path ("give me everything including bodies") is no longer reachable as a single POST against the export endpoint. Callers wanting a full dump including bodies first call `GET /export/preview` (whose manifest always contains the body columns — the preview is the discovery surface) and then POST with the full `columns` list. The extra round-trip is accepted as a clean simplification: the UI was already going to call preview for its column-picker, and programmatic dumpers can cache the manifest.

**Why the planner stays oblivious to this rule (option B)**: The planner is a pure function of `(SuiteSnapshotDto, List<RunMetricSnapshot>)` and always emits the **full** manifest including the two body descriptors. The "default omits bodies" rule lives in exactly one place — the `EvalSummaryExportColumnSelector`'s empty-input branch — so a future change that adds or removes a "default-excluded" column class touches one method. The selector's signature, conceptually:

```
List<ColumnDescriptor> subset(List<ColumnDescriptor> all, List<String> requested):
  if requested.isEmpty():
    return all.filter(d -> !d.isBodyColumn())          // default set
  else:
    return requested.map(name -> all.findByName(name)) // explicit subset
                    .raiseIfAnyUnknown()
```

`ColumnDescriptor` gains an `isBodyColumn()` predicate (true only for the two body descriptors; equivalent to "is in the set {`requestBody`, `responseBody`}"). The repository-projection picker downstream of the selector remains unchanged: it inspects the *effective* descriptor list for any `requiresJoinProjection=true` entry and picks the JOIN variant when found.

**Preview endpoint**: the preview always emits the full manifest (no subsetting, no body filtering) and always uses the JOIN projection, so body cells are populated in the ≤10-row sample. This is the discovery surface the export depends on.

### D5. Repository projection tiers

`PostgresEvalSummaryRepository` today offers `SELECT_LIST_COLUMNS` (excludes `metric_infos`, `extraction_warnings`), `SELECT_ALL_COLUMNS` (adds `metric_infos` only — `extraction_warnings` is **not** included), and `SELECT_BY_ID_DETAIL_SQL` (single-row LEFT JOIN that includes `extraction_warnings`, `request_body`, `response_body`). The export service:
- Always needs `metric_infos` **and** `extraction_warnings` (both may be selected columns and `extraction_warnings` participates in the spec's empty-cell semantics for failed `response.*` extractions), so it never uses the slim list-only projection and cannot use today's `SELECT_ALL_COLUMNS` either.
- **Identity-column availability note**: `test_case_run_result_id` is a column on `test_case_eval_summaries` directly (soft FK to `test_case_run_results`), not a column that lives only on the joined `test_case_run_results` row. It is present in `SELECT_LIST_COLUMNS`, `SELECT_ALL_COLUMNS`, and `SELECT_EXPORT_COLUMNS`. The spec's identity-column list (which includes `testCaseRunResultId`) therefore does NOT force the JOIN; the no-bodies path emits that cell from the `eval_summary` row alone.
- Introduces a new repository projection constant `SELECT_EXPORT_COLUMNS` = `SELECT_ALL_COLUMNS` + `extraction_warnings`. Used by the export's no-bodies (non-JOIN) cursor read path. (Alternative considered: add `extraction_warnings` to `SELECT_ALL_COLUMNS` directly. Rejected because other callers of `SELECT_ALL_COLUMNS` should keep their current TOAST-light projection; the export is the only caller that needs `extraction_warnings` in a list query.)
- Introduces a new cursor-paginated `findAll` variant that lifts the JOIN from `SELECT_BY_ID_DETAIL_SQL` into a list query when at least one of `requestBody`/`responseBody` is requested. The JOIN variant already projects `extraction_warnings`. The variant accepts the same `(List<FilterCondition> filters, UUID computationId, Long runCreatedAtMs, Cursor cursor, int size)` contract as the existing `findAll`. The keyset is `(created_at_ms, id)` (per AGENTS.md "CursorCodec and Keyset Pagination"); `runCreatedAtMs` is a per-run **filter predicate**, not part of the keyset. `EvalSummaryExportService` resolves `runCreatedAtMs` once via `runRepository.findById(runId).getCreatedAt()` (already loaded for snapshot resolution) and passes it through to the repository.

**Run-scoping invariant**: `EvalSummary.createdAtMs` is set from `TestSuiteRun.createdAt` at batch-create time (see `EvalSummaryService.batchCreate`). Two runs created in the same millisecond would share the same `createdAtMs`, so the `runCreatedAtMs = :ts` predicate alone is **not** sufficient to scope rows to a single run. The existing list endpoint sidesteps this via `validateRequiredRunIdFilter`, which forces the caller to supply a `runId:eq:<id>` filter that `WhereBuilder` translates into a SQL predicate. The export service MUST follow the same contract: before invoking the repository, `EvalSummaryExportService` SHALL prepend an internal `FilterCondition` equivalent to `runId:eq:<request.runId>` to the user-supplied `filter` list (the user's filter list does not need to contain it — the service injects it on every request). This is a **named invariant** of the export pipeline and is covered by a dedicated functional test (two runs at the same millisecond → disjoint exports). A cleaner long-term design — adding an explicit `UUID runId` parameter to the repository signature — is deferred as a follow-up; for this change we keep the existing filter-list contract.

This keeps TOAST decompression cost off the read path when the user is not asking for the heavy columns. The existing `EvalSummaryRowMapper` `hasColumn`-guarded mapping covers all three projections; if `extraction_warnings` is not already mapped via `hasColumn`, the rowmapper is extended.

### D6. Component layout in `service.domain.analytics`

Three injectable `@Component`s:

- `EvalSummaryExportColumnPlanner` — pure function: given `SuiteSnapshotDto` and `List<RunMetricSnapshot>` (resolved computation), returns an ordered `List<ColumnDescriptor>` where each descriptor holds `{ name, isBodyColumn: boolean, valueExtractor: Function<EvalSummaryExportRow, Object> }` plus a derived `requiresJoinProjection()` accessor (computed `return isBodyColumn;` in V1 — the body payloads are the only columns that live on the joined `test_case_run_results` table). The planner takes no `detailed` flag and always emits the full manifest including the two body descriptors (`requestBody`, `responseBody`) at the tail of the manifest, immediately after the JSON-blob cells. `isBodyColumn` is true only for those two descriptors. The selector consumes `isBodyColumn` to strip bodies on the empty-input branch; the projection-picker consumes `requiresJoinProjection()` to decide JOIN-vs-non-JOIN. Keeping the two predicates as named accessors preserves the two-concept API even though they share one backing field in V1; if a future column ever needs the JOIN without being a body column (e.g. a trace-metadata field on `test_case_run_results`) or vice versa, splitting `requiresJoinProjection` back into an independent record component is a one-line change with call sites unchanged. For `<metric>.<field>` derivation, the planner SHALL inject the existing `com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor` `@Component` (per `metric-evaluation` spec, which mandates a single shared extractor reused across the metric-evaluation executor and TSMD validation) and call `extractFieldNames(snapshot.outputSchema())` to obtain field names in `properties` insertion order. The planner SHALL NOT parse `outputSchema` JSON itself — duplicating that logic would violate AGENTS.md "use specialized, injectable components" and `best-practices` spec, and creates a maintenance hazard (any Jackson config change affecting ordering would have to be coordinated across two parsers). The metric-field column ordering contract therefore lives in `OutputSchemaFieldExtractor`'s tests, not in the planner's. Trivially unit-testable.
- `EvalSummaryExportColumnSelector` — given the full planned list plus an optional user-supplied `columns` list, returns the effective ordered list. **Empty input branch**: returns the planner output filtered to drop descriptors where `isBodyColumn=true` (the default-set rule from D4 lives here). **Non-empty input branch**: returns the user-ordered subset, raising `ValidationException` listing every unknown name. The selector is the single place that distinguishes "default" from "explicit subset" — the planner and projection-picker stay branch-free.
- `EvalSummaryExportService` — orchestrates **both** paths:
  - **CSV export** (`exportToCsv`): resolves `computationId` via a new shared `ComputationResolver` `@Component`, loads run + snapshot (rejects legacy 422), loads `RunMetricSnapshot`s for the computation, asks the planner for the full descriptor manifest, asks the selector for the effective subset (which strips body columns when `request.columns()` is empty), picks repository projection based on whether any effective descriptor has `requiresJoinProjection=true`, opens `CSVPrinter` on the response `OutputStream`, cursor-loops the repository, writes one row per `EvalSummary` by walking the descriptor extractors and applying the CSV cell-serialization rules.
  - **JSON preview** (`previewAsJson`): same input resolution; planner produces the full manifest (always); **the selector is bypassed** (preview never subsets — its job is to expose the full manifest); repository projection is always the JOIN variant (so `requestBody`/`responseBody` cells are populated); a single repository call with `size=10` is issued, the cursor is discarded; the method builds and returns a `List<List<Object>>` (first inner list = headers, ≤10 subsequent inner lists = rows whose cells are the descriptor extractors' raw `Object` values without CSV stringification). Spring's default Jackson `HttpMessageConverter` serializes the structure as JSON `application/json`, naturally preserving the typed cell values declared in the spec.

**Note on `ComputationResolver`**: `EvalSummaryService.resolveComputationId` is currently `private` in `service.domain.analytics.EvalSummaryService`. Rather than widen its visibility (which would expose an internal helper as part of the service's public API), we extract the resolution rule into a new injectable `@Component ComputationResolver` under `service.domain.analytics` (per AGENTS.md "specialized, injectable components for conversion/validation logic instead of private/inner methods to facilitate reuse and testing"). `EvalSummaryService` is refactored to delegate to it, and `EvalSummaryExportService` injects the same component. `ComputationResolver` is a **thin delegator** to `RunMetricSnapshotRepository` and SHALL NOT be annotated `@Transactional`. `RunMetricSnapshotRepository` / `PostgresRunMetricSnapshotRepository` do **not** carry `@Transactional` annotations of their own; the existing `EvalSummaryService.resolveComputationId` works only because its callers (the service's public methods) open a `@Transactional("analyticsTransactionManager")` scope, and Spring's default `REQUIRED` propagation lets the resolver participate in that caller-supplied transaction. `ComputationResolver` MUST preserve that contract: it carries no `@Transactional` of its own, and every caller (`EvalSummaryService` public methods today, `EvalSummaryExportService` after this change) is responsible for opening an analytics-datasource transaction before invoking it. Concretely, `EvalSummaryExportService.exportToCsv` and `previewAsJson` MUST scope their **setup phase** (run + snapshot + computation + metric-snapshot lookups) inside a `@Transactional(value = "analyticsTransactionManager", readOnly = true)` boundary (or an equivalent programmatic `TransactionTemplate` — see D9.1 below for the dual-datasource streaming-phase concern). Keeping the resolver annotation-free preserves the existing propagation semantics exactly.

**Error mapping**: the resolver translates lookup failures into structured errors as follows: a **well-formed UUID** that does not match any `run_metric_snapshots` row for the run — and `"latest"` against a run with zero snapshots — both surface as `HTTP 404` with code `NOT_FOUND`. A **malformed** `computation` string (neither a valid UUID nor the literal `"latest"`) surfaces as `HTTP 400` with code `VALIDATION_ERROR`. (Aligning the existing `GET /api/v1/analytics/eval-summaries` list endpoint's behavior with this same mapping is **out of scope** for this change and is tracked as a follow-up.)

### D7. Request body DTO

`EvalSummaryExportRequestDto` (under `service.domain.dto.analytics`):
- `runId: UUID` (`@NotNull`)
- `computation: String` (optional; defaults to `latest`)
- `columns: List<String>` (optional; `@Size(max = ValidationConstants.MAX_EXPORT_COLUMNS)` to bound headers; ordered, may be empty)
- `filter: List<String>` (optional; size-capped to mirror the existing list-controller `@Size` on filter)
- `delimiter: String` (optional; defaults to `","`; validated as single ASCII char by the controller — same pattern as `TestCaseController.parseDelimiter`).

No `detailed` flag: per D4, `requestBody`/`responseBody` are reached only by explicit naming in `columns`. The OpenAPI examples on this DTO MUST include one example whose `columns` list contains both body columns, so the surface advertises the path even though the field that previously hinted at it is gone.

A new constant `ValidationConstants.MAX_EXPORT_COLUMNS` caps the column-subset size (proposal: 512). Filters reuse the existing `MAX_LIST_FILTER_PARAMS` cap. `MAX_EXPORT_COLUMNS` SHALL be enforced **twice**: (1) at request binding via the `@Size(max = …)` on `EvalSummaryExportRequestDto.columns` (rejects oversized client-supplied subsets); and (2) inside `EvalSummaryExportService` against the **planner's output**, immediately after planning and **before** selector subsetting. Without (2) a caller could trivially bypass the cap by supplying a small `columns` array against a run whose snapshot + metric snapshots produce more than 512 derived columns, exposing the CSV writer to unbounded per-row work. The post-plan check throws `ValidationException` whose message includes both the offending count and the cap.

The DTO's `filter` field SHALL carry a `@Schema(description = …)` that embeds the allowed filter fields drawn from `FilterWhitelists.EVAL_SUMMARIES`. `OpenApiQueryParamCustomizer` enriches query params only, so it does not reach request body fields; the `@Schema` description is the equivalent body-side surface and explicitly fills the gap (cross-reference `openapi-query-param-docs` spec).

### D8. CSV cell serialization rules

Reuse the rules from `CsvExportService`:
- `null` → empty string.
- Empty `Map`, empty `List`, or empty Jackson container (`ObjectNode`/`ArrayNode` with no entries) → empty string (so `metricInfos = {}` renders as an empty cell rather than the literal `"{}"`).
- `String` / `Number` / `Boolean` → `toString()`.
- Non-empty `Map` / `List` / Jackson `JsonNode` → compact JSON via the shared `ObjectMapper`.
- DIAL file refs (FILE-typed schema or response columns) → raw string verbatim — V1 does not materialize.

These rules apply uniformly across `data.*`, `response.*`, `<metric>.<field>`, and the JSON-blob cells **on the CSV export path only**. The JSON preview path emits the descriptor extractors' raw `Object` values directly into the response array — Jackson then serializes each value as its natural JSON type (numbers stay numbers, parsed `Map`/`List`/`JsonNode` become nested JSON, `null` stays `null`, empty `{}`/`[]` are preserved). The preview deliberately diverges from the CSV "empty Map/List → empty string" rule because preserving empty containers in JSON is type-honest and the UI can render them as it sees fit; collapsing them to `null` would lose the signal that the column exists but is empty.

`EvalSummary`'s JSONB-backed fields (`testCaseData`, `extractedColumns`, `metricValues`, `metricInfos`, `extractionWarnings`) are persisted as raw `String` on the model. The export pipeline SHALL parse each such string once per row into a `Map<String, Object>` (or `JsonNode`) via the shared `ObjectMapper` and reuse the parsed value across every descriptor that reads from it. Parsed values are stored on a transient per-row working object so the planner's `valueExtractor`s never re-parse. Parse failures SHALL be logged with the caught exception passed as the last SLF4J argument (per AGENTS.md logging rule) and the affected cell(s) SHALL be rendered as empty — the export does not abort on a single malformed row.

### D9. Streaming, but bounded

The CSV export uses cursor pagination (`PostgresEvalSummaryRepository.findAll(…, cursor, size)`) and writes each batch through `CSVPrinter` to the response `OutputStream` so memory stays bounded regardless of row count. The JSON preview is a single `findAll(size=10)` with the cursor discarded; because the response is capped at 10 rows it is built in memory as a `List<List<Object>>` (no streaming needed) and returned to Spring for Jackson serialization.

Setting `HttpServletResponse` headers for the CSV export (`Content-Type: text/csv; charset=UTF-8`, `Content-Disposition: attachment; filename="eval-summary-<runId>-<computationId>.csv"`) happens before the first byte is written; errors after the first write degrade gracefully into a truncated CSV — acceptable for V1, will be flagged in the verification plan. The JSON preview returns a typed value (the `List<List<Object>>` above) and lets Spring manage `Content-Type: application/json` — failures before serialization surface as the standard structured error body, with no partial-write concerns.

### D9.1 Transaction boundaries during streaming export

The export reads from **both** datasources: meta (`TestSuiteRun`, and the live `TestSuite` for legacy-snapshot synthesis) and analytics (`RunMetricSnapshot`s, `EvalSummary` cursor pages, `test_case_run_results` JOIN). A single class-level `@Transactional` is therefore inappropriate because (a) `@Transactional` is single-datasource, and (b) holding either transaction open across the HTTP response write is an anti-pattern (long-lived tx, connection-pool starvation, no commit until the client finishes downloading). The streaming export uses a **two-phase** transactional design, both phases completing **before** the first byte of CSV is written (with a per-page analytics tx in the streaming loop):

- **Phase A — setup, single short tx per datasource, before any bytes are streamed.**
  1. Open a short **meta** read-only tx (programmatic `TransactionTemplate` bound to the `metaTransactionManager` bean) to read `TestSuiteRun` and, when needed, the live `TestSuite` for legacy-snapshot synthesis. Commit.
  2. Open a short **analytics** read-only tx (programmatic `TransactionTemplate` bound to the `analyticsTransactionManager` bean) for `ComputationResolver` resolution + `RunMetricSnapshot`s lookup + column-planner construction. Commit.
  3. Set `HttpServletResponse` headers (`Content-Type`, `Content-Disposition`).
- **Phase B — streaming, one tx per cursor page.** Wrap each `repository.findAll(..., cursor, size)` invocation in its own short analytics read-only `TransactionTemplate` callback. The callback fetches one page, the surrounding loop writes those rows to `CSVPrinter`, and the tx commits before the next page's tx opens. Do NOT hold a single analytics tx across all pages.
- **Why programmatic `TransactionTemplate` over class-level `@Transactional`**: Phase A spans two datasources (impossible with a single `@Transactional` qualifier). Phase B requires per-page commit granularity (impossible inside a `@Transactional` method whose scope is the method body). Using `TransactionTemplate` consistently for both phases keeps the service implementation uniform and easy to follow.
- **`TransactionTimestampAspect` interaction**: meta `TransactionTemplate` callbacks do not need `TransactionTimestampContext` (read-only, no inserts); analytics `TransactionTemplate` callbacks are explicitly out of scope for the aspect (per AGENTS.md "`TransactionTimestampAspect` is scoped to meta transactions only").
- **Preview endpoint**: the preview is bounded (≤10 rows, single repository call). The same dual-datasource concern still applies, so the preview also uses two short `TransactionTemplate` callbacks — one meta, one analytics — and assembles the in-memory response after both commit. No streaming phase needed.

`EvalSummaryExportService` therefore injects `@Qualifier("metaTransactionManager")` and `@Qualifier("analyticsTransactionManager")` `PlatformTransactionManager` beans (with Lombok's `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier` already configured per AGENTS.md), wraps each in a `TransactionTemplate` with `readOnly=true`, and uses them at the two boundary points described above.

## Risks / Trade-offs

- **Risk**: A run with very wide schemas + many metric definitions produces an unwieldy CSV (hundreds of columns). **Mitigation**: column subsetting is a first-class feature; the preview endpoint surfaces the full manifest so callers can self-trim. `MAX_EXPORT_COLUMNS` caps **both** the client-supplied `columns` array (request-binding `@Size`) **and** the planner-computed manifest width (post-plan, pre-subset check inside `EvalSummaryExportService`), so neither path can produce an unbounded CSV.
- **Risk**: a `columns` array that names `requestBody` and/or `responseBody` pulls large JSONB blobs through the JOIN, slow for large runs. **Mitigation**: lazy projection switching keeps the heavy path opt-in (only the explicit naming triggers the JOIN); documented in the spec.
- **Risk**: Future ZIP-materialization change must be additive without breaking V1 callers. **Mitigation**: column-name catalog is the contract; FILE cells already hold DIAL refs which is a valid value for both raw and materialized modes. The follow-up adds a query/body param (e.g. `materializeFiles`) and a different `Content-Type`; existing callers continue to work unchanged.
- **Trade-off**: POST for a read-only operation breaks `<a href>` semantics for the browser-driven path. Accepted — the preview endpoint is GET and serves the "click a link to peek" use case; the full export is expected to be invoked programmatically (UI fetch + saveAs).
- **Risk**: Status-code propagation after partial CSV writes (HTTP headers already flushed). **Mitigation**: validate all inputs and resolve the snapshot **before** opening the `CSVPrinter`; once writing starts, downstream failures are logged with stacktraces (per project logging convention) and surface as truncated CSV rather than a structured error body. Documented as a known limitation in V1.
