## ADDED Requirements

### Requirement: Results import accepts ISO UTC timestamps
Status: **Implemented**

For `startedAt` and `completedAt`, CSV results import SHALL accept signed epoch-millisecond integers in the signed 64-bit range or ISO 8601 UTC timestamps with a trailing uppercase `Z`, seconds, and an optional fraction of one to three digits. Surrounding whitespace SHALL be ignored. Valid ISO values SHALL be converted exactly to signed 64-bit epoch milliseconds before existing duration calculation, ordering validation, and persistence. Both formats SHALL be usable within one file or row. Required-field validation SHALL remain unchanged.

Invalid dates, leap seconds, missing timezone, non-`Z` offsets, more than three fractional digits, and values outside the epoch range SHALL produce HTTP 400 identifying the row and field through existing aggregated validation; the request SHALL create no run. Other fields and import limits SHALL remain unchanged.

#### Scenario: ISO values persist as epoch milliseconds
- **WHEN** a row has `startedAt=2026-05-25T10:22:12.226Z` and `completedAt=2026-05-25T10:22:13.226Z`
- **THEN** the stored values SHALL be `1779704532226` and `1779704533226`, with duration `1000` milliseconds

#### Scenario: Legacy and mixed formats remain supported
- **WHEN** timestamp cells mix trimmed epoch integers and UTC ISO values with zero, one, two, or three fractional digits, including pre-epoch instants
- **THEN** each SHALL resolve to its exact epoch-millisecond value

#### Scenario: Invalid timestamps fail atomically
- **WHEN** any timestamp cell is invalid, missing, too precise, or out of range
- **THEN** import SHALL return HTTP 400 with row/field details and create no run

#### Scenario: Ordering is validated after conversion
- **WHEN** valid mixed-format timestamps resolve to `completedAt` earlier than `startedAt`
- **THEN** existing ordering validation SHALL reject the import without creating a run

## Implementation notes

- `src/main/java/com/epam/aidial/evaluation/service/domain/analytics/EvalResultsCsvParser.java` delegates timestamp conversion to `service/domain/csv/CsvTimestampCodec.java`; existing `EvalResultsImportService` validation/persistence consumes epoch milliseconds.
