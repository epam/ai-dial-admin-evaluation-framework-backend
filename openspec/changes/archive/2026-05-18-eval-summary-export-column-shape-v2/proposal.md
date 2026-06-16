## Why

The `eval-summary-export` CSV is consumed primarily by external analysis tooling (pandas/polars notebooks). The current column-naming convention has two ergonomic problems that block clean analysis workflows: the `.` separator collides with metric and field names that may legitimately contain dots (e.g. `precision@5`, `bert.score`), and the single `metricInfos` JSON blob column forces analysts to parse JSON at row time to access per-metric details. Pythonistas also can't reach `.`-separated columns via attribute access on a DataFrame.

## What Changes

- **BREAKING**: Replace the `.` column-name separator with `:` everywhere it is currently used by the export — `data.<f>` → `data:<f>`, `response.<c>` → `response:<c>`, `<metric>.<field>` → see next bullet.
- **BREAKING**: Prefix metric value columns with `metric:` for consistency with `data:` and `response:`. `<metric>.<field>` → `metric:<metric>:<field>`.
- **BREAKING**: Remove the single `metricInfos` JSON-blob column from the CSV header and the preview headers manifest.
- **NEW**: For every top-level property in each metric's `output_schema.properties`, emit a per-field detail column `metricInfo:<metric>:<field>` carrying the JSON details object (or, on per-field failure, the `{type:"error", message:"..."}` envelope produced by the `oneOf [Field, MetricError]` discriminator in the schema).
- **NEW**: For every metric in the resolved computation, emit a per-metric error column `metricError:<metric>`. Always present in the header; populated only when `metricInfos[<metric>]` cannot be interpreted as a per-field map (the wholesale-failure case where metric evaluation never produced per-field structure). Routing rule: if `metricInfos[<metric>]` is a JSON object whose top-level keys include at least one key from the metric's `output_schema.properties`, it is treated as the per-field map and `metricError:<metric>` stays empty; otherwise the whole payload routes to `metricError:<metric>`.
- Identity/execution columns (`id`, `testSuiteId`, …, `responseStatusCode`) and the `extractionWarnings` JSON-blob column keep their existing camelCase names — they are not derived from snapshot/metric data, so the separator convention does not apply.
- No back-compat shim. Per AGENTS.md ("Avoid backwards-compatibility hacks"), downstream consumers must update to the new header shape. The preview endpoint's headers manifest remains the discovery mechanism, so consumers that read it dynamically need no further work.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `eval-summary-export`: column-naming requirements change for inlined `testCaseData`, inlined `extractedColumns`, and flattened metric value columns (new separator `:` and the `metric:` prefix). The JSON-blob columns requirement is rewritten: the single `metricInfos` column is removed; two new families of columns are added (`metricInfo:<m>:<f>` per output-schema field, `metricError:<m>` per metric). CSV cell-serialization rules and the run-frozen-column-set rule are unchanged.

## Impact

**Code**

- `service/domain/analytics/EvalSummaryExportColumnPlanner` — replace three `"."` literals; introduce a separator constant (most likely in a new `ExportColumnConstants` or `EvalSummaryExportConstants` class under `constants/`, per the constants-per-bounded-context rule); add the `metric:` prefix; emit the new per-field `metricInfo:<m>:<f>` descriptor loop and the per-metric `metricError:<m>` descriptor; remove the single `metricInfos` plain descriptor.
- `data/db/analytics/model/EvalSummaryExportRow` (or wherever per-row accessors live alongside the existing `metricInfos()` accessor) — add `metricInfo(metric, field)` and `metricWholesaleError(metric)` accessors implementing the routing rule from "What Changes".
- `service/domain/analytics/EvalSummaryExportColumnSelector` — no logic change; new column names flow through the existing exact-name lookup. The `MAX_EXPORT_COLUMNS` planner-output cap continues to protect against unbounded manifests (which are now somewhat wider for metric-heavy runs).
- `service/domain/dto/analytics/EvalSummaryExportRequestDto` — refresh `@Schema(example=…)` snippets that reference `data.foo` / `Accuracy.score` / `metricInfos`.
- `web/controller/EvalSummaryController` — refresh `@ExampleObject` JSON for both `exportCsv` and `previewExport`.
- `src/main/resources/openapi/examples/` — refresh any preview/export response files that hardcode the old column shape.
- Tests — `EvalSummaryExportColumnPlannerTest`, `EvalSummaryExportServiceTest`, `PostgresFunctionalTests$EvalSummaryExportFunctionalTests` updated for new header assertions and new structural cases (per-field success, per-field error, wholesale error).

**APIs**

- `POST /api/v1/analytics/eval-summaries/export.csv` — CSV header line changes shape; `text/csv` response Content-Type unchanged.
- `GET /api/v1/analytics/eval-summaries/export/preview` — headers manifest (first element of the response JSON array) changes shape; response Content-Type unchanged.
- Request bodies (`EvalSummaryExportRequestDto`) — schema unchanged, only `@Schema` example strings refresh.
- Error codes — unchanged.

**Data / DB**

- No schema migrations. `metricValues` / `metricInfos` storage in `test_case_eval_summaries` and `run_metric_snapshots.output_schema` are unchanged; the change is purely in how those columns are projected into the exported manifest.

**Configuration**

- No new configuration properties. No update to `docs/configuration.md`.

**Documentation**

- `openspec/specs/eval-summary-export/spec.md` — delta sync after archival: rewrite the requirements named above and refresh their scenarios.
- No update to `docs/database-schema.md` (no schema change).
- No update to AGENTS.md (no new unique pattern — column-naming convention is a spec concern, not a project-wide rule).

**Downstream consumers**

- Any client that hardcodes header names from the old CSV (column names containing `.`, the single `metricInfos` column, or unprefixed metric column names like `Accuracy.score`) will need to be updated. Clients that read the preview manifest dynamically pick up the new shape transparently.
