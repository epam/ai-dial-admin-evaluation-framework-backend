## Why

Three issues discovered during E2E runs with metrics cause incorrect request resolution, corrupted metric inputs, and silent data loss:
(1) placeholder type hints (`${{var|file}}`) prevent binding lookup — the `|type` suffix is captured as part of the variable name, so the binding is never found and the property is absent from the resolved request;
(2) CSV import stores ARRAY/OBJECT cell values as plain strings when no schema exists at import time, which causes the metric evaluation job to send strings instead of arrays to the metric provider;
(3) metric binding resolution silently returns `null` when a binding references a column that doesn't exist in test case data or extracted columns — if the run started, validation warnings are assumed resolved, so missing columns indicate a data integrity issue that should fail fast.

## What Changes

- Fix `ResolvedRequestService.PLACEHOLDER_PATTERN` and `McpRequestResolver.PLACEHOLDER_PATTERN` to strip the `|type` type hint from the captured variable name (group 1), matching how `TemplateVariableExtractor` already handles it.
- Fix `CsvImportService.parseRow` to auto-detect and parse JSON array/object cell values when the field's schema type is unknown (`type == null`), using the same `[`/`{` heuristic already used by `inferCellType`.
- Add fail-fast behavior in `BindingResolver.resolveSource` — throw when a TEST_CASE or RESPONSE binding references a column that does not exist in the data map (distinguish missing key from present-but-null value via `containsKey`).

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `request-template`: Type hints (`|file`, `|string`, etc.) in placeholder syntax must not interfere with variable-name-to-binding lookup during request resolution.
- `test-cases`: CSV import must store ARRAY/OBJECT cell values as structured JSON (not strings) even when no existing schema is present at import time.
- `metric-evaluation`: Metric binding resolution must fail fast when a binding references a column that does not exist in the test case data or extracted columns map.

## Impact

- **`ResolvedRequestService`** — `PLACEHOLDER_PATTERN` regex updated; no API changes.
- **`McpRequestResolver`** — `PLACEHOLDER_PATTERN` regex updated; no API changes.
- **`CsvImportService.parseRow`** — extended to attempt JSON parse for `[`/`{`-prefixed cells when field type is not in schema; no API changes.
- **`BindingResolver.resolveSource`** — throws `IllegalArgumentException` when a referenced column is missing from the data map; no API changes (exception is caught by `MetricEvaluationWorker` error handling).
- No DB migrations, no config changes, no new packages.
- Existing test cases stored with string arrays are unaffected at the code level (data must be re-imported to fix stored strings); fixing the import prevents future occurrences.
