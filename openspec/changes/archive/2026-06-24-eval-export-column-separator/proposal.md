## Why

The EvalSummary CSV export composes hierarchical column headers as `<family>:<name>` using the single colon as the family-separator (e.g. `metric:Accuracy:score`). To keep those headers unambiguous, the colon is reserved and forbidden inside response-column names and metric-definition names. The single colon is an awkward character to reserve: it commonly appears in human-meaningful names. Switching the export separator to the double colon `::` frees the single colon for use in those names while reserving a sequence that is vanishingly rare in practice. This narrows the validation surface to exactly what each constraint actually requires.

## What Changes

- The EvalSummary CSV export column-name family-separator changes from `:` to `::`. Composed headers become `data::<field>`, `response::<column>`, `metric::<metric>::<field>`, `metricInfo::<metric>::<field>`, `metricError::<metric>`. **BREAKING** for the export column-name contract: clients that request a column subset (`POST .../export.csv` body `columns`) or parse preview/export headers must use the `::` form. The selector matches requested columns by exact string, so old `:`-form requests will be rejected as unknown columns.
- Response-column name validation and metric-definition (TSMD) name validation relax from "must not contain `:`" to "must not contain the `::` sequence". A single `:` becomes allowed in those names.
- Test-case schema **field** name validation is unchanged: it keeps the strict single-`:` ban, because `:` is the filter operator separator (`testCaseData.field:op:value`) — a constraint independent of CSV export. Its rationale is re-documented to reflect the filter reason rather than the export reason.
- The shared validation constant `ValidationConstants.IDENTIFIER_NAME_NO_COLON_PATTERN` is split into two: the existing no-colon pattern (kept for field names, filter reason) and a new no-double-colon pattern (for response-column and metric names, export reason).
- OpenAPI examples, schema annotations, and spec text are updated to the `::` form.

No database schema, migration, jOOQ, or configuration changes. The separator is computed at export time and never persisted; export is write-only (no round-trip import parsing depends on it).

## Capabilities

### New Capabilities
- (none)

### Modified Capabilities
- `eval-summary-export`: the "Column header family-separator convention" requirement and all column-naming requirements/scenarios change the family-separator from `:` to `::`.
- `response-columns`: the "Response column name MUST NOT contain `:`" requirement relaxes to forbid only the `::` sequence (export-separator reason).
- `test-suite-metric-definitions`: the "TSMD name MUST NOT contain `:`" requirement relaxes to forbid only the `::` sequence (export-separator reason).

## Impact

- Code (main):
  - `constants/EvalSummaryExportColumnConstants.java` — `COLUMN_SEPARATOR` `:` → `::`; class Javadoc.
  - `constants/ValidationConstants.java` — split the identifier pattern/message into no-colon (filter) and no-double-colon (export) constants.
  - `service/domain/dto/ResponseColumnDefinitionDto.java`, `service/domain/dto/TestSuiteMetricDefinitionRequestDto.java` — switch `@Pattern` to the new no-double-colon constant.
  - `service/domain/dto/FieldDefinitionDto.java` — keep no-colon constant (re-pointed to the filter-reason constant name).
  - `service/domain/dto/analytics/EvalSummaryExportRequestDto.java` — `@Schema` example.
  - No change to `EvalSummaryExportColumnPlanner` / `EvalSummaryExportColumnSelector` logic (they derive from the constant / match by exact string).
- API: export/preview column headers and the export `columns` request contract (BREAKING, as above). Validation responses for response-column / metric names now accept single `:`.
- OpenAPI examples: `openapi/examples/api-v1-analytics-eval-summaries-export.csv-POST-request-subset.json`, `openapi/examples/api-v1-analytics-eval-summaries-export-preview-GET-response-200-minimal.json`.
- Tests: planner/selector unit tests, the three DTO validation tests, and EvalSummaryExport functional tests.
- Docs/specs: `openspec/specs/README.md` inline manifest description; the three modified specs above; any `docs/` reference to the `metric:`/`data:` header form.
