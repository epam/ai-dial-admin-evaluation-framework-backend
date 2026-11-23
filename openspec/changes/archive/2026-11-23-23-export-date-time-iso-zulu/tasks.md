## 1. CSV timestamp conversion

- [x] 1.1 Add `service.domain.csv.CsvTimestampCodec` with strict UTC/millisecond parsing, legacy integer support, and fixed three-digit formatting; verify parameterized unit tests for valid, null, zero, negative, extreme, malformed, precision, timezone, and range cases pass.
- [x] 1.2 Wire CSV-only formatting in `EvalSummaryExportService` and timestamp parsing in `EvalResultsCsvParser`; verify targeted unit tests for both columns, selection, unchanged preview/non-date values, mixed inputs, required fields, aggregated errors, and ordering pass.

## 2. Integration and documentation

- [x] 2.1 Add exactly two happy-path functional test methods to existing export/import suites; run both nested suites and verify ISO export plus epoch persistence/duration on ISO import, adapting existing assertions only where required.
- [x] 2.2 Update relevant OpenAPI descriptions/examples and AGENTS.md timestamp convention; verify contracts match the deltas and review config/spec-index maintenance policies (update only if triggered).
- [x] 2.3 Run Spotless, Checkstyle, and required archive build; finalize statuses/implementation notes and sync `eval-summary-export`/`eval-results-import` deltas during archive. Done: checks pass, main specs match landed behavior, and change is archived.
