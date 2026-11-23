## Context

See proposal.md for motivation. `EvalSummaryExportColumnPlanner` supplies raw values to both CSV and JSON preview; only `createdAt`/`computedAt` are timestamp columns today. `EvalResultsCsvParser` separately imports `startedAt`/`completedAt`. These are different CSV contracts.

## Goals / Non-Goals

**Goals:** exact millisecond conversion at the CSV boundary; reuse one small codec.
**Non-Goals:** generic date detection, new columns, export/import round-trip redesign, JSON/DB changes, CLI output changes.

## Decisions

- Add an injectable `CsvTimestampCodec` in existing `service.domain.csv`, using JDK time parsing/formatting. Fixed three-digit UTC export preserves milliseconds, including zero and pre-epoch values. Strict ISO input grammar permits zero to three fractional digits and uppercase `Z`; reject leap seconds and excess precision instead of normalizing/truncating them. Numeric input remains signed 64-bit milliseconds.
- Apply formatting only when writing CSV timestamp descriptors in `EvalSummaryExportService`; leave planner extractors unchanged so JSON preview stays numeric. A small explicit column set is sufficient for two known columns; schema inference or a new descriptor strategy would add unnecessary machinery.
- `EvalResultsCsvParser` calls the codec for its two timestamp cells and aggregates specific parse/range failures with row/field context. Blank required fields follow existing checks. Converted `Long` values enter unchanged persistence, duration, and ordering paths.
- Existing streaming, transaction boundaries, batch limits, and fail-fast validation remain. No migrations, properties, packages, or dependencies. Update OpenAPI descriptions/examples and AGENTS.md's timestamp convention to document the CSV-only exception.
- Prefer parameterized unit coverage: exact output, null/zero/negative/extreme epochs, whitespace, legacy/mixed input, fractional precision, invalid dates/leap seconds/zones/range, parser aggregation/required fields/ordering, and preview isolation. Add exactly two happy-path functional methods in existing export/import suites; assert CSV ISO output and imported epoch persistence/duration respectively.

## Risks / Trade-offs

- CSV consumers expecting integers break → document the change; retain legacy numeric import.
- Shared planner changes could alter JSON → keep conversion in CSV writing and unit-test preview.
- Strict UTC/millisecond input excludes offsets and sub-millisecond values → explicit errors, no lossy conversion.

## Migration Plan

No data migration. Deploy normally; rollback restores numeric CSV output while already imported data remains epoch milliseconds.
