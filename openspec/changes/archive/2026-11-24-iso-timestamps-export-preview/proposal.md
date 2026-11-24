## Why

Testing after #322 found that evaluation-summary export preview still displays epoch milliseconds while the downloaded CSV displays readable ISO UTC timestamps. Preview should match the export's timestamp representation.

## What Changes

- **BREAKING**: `GET /api/v1/analytics/eval-summaries/export/preview` returns non-null `createdAt` and `computedAt` cells as ISO UTC strings with exactly three fractional digits and a trailing `Z`, instead of JSON numbers.
- Preserve JSON null timestamps, all other typed cells, existing headers, and the ten-row limit.
- Update OpenAPI descriptions/examples and replace the regression expectation that preview retains epochs.
- Non-goals: changing CSV/ZIP test-case upload preview, ordinary REST timestamp representations, stored models, CSV import, or arbitrary numeric data fields.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `eval-summary-export`: align export-preview timestamp formatting with CSV while preserving JSON nulls and non-timestamp types.

## Impact

- Planned: narrowly scoped changes to `EvalSummaryExportService`, `EvalSummaryController`, OpenAPI examples, unit and functional tests; reuse `CsvTimestampCodec` without new packages or beans.
- Frontend consumers of preview must accept timestamp strings rather than numbers. Roll out backend and any dependent frontend adjustments together; no compatibility flag is proposed.
- No database migrations, configuration changes, dependencies, security changes, or new endpoints.
- Tests cover ISO milliseconds, zero/negative/null values, unchanged duration/data/response/metric cells, and the real preview endpoint. Existing CSV formatting remains unchanged.
