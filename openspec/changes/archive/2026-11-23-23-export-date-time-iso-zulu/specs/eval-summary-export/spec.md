## MODIFIED Requirements

### Requirement: CSV cell serialization rules
These rules apply to the **CSV export endpoint only**; the preview endpoint preserves typed JSON values per the Preview endpoint requirement above. The service SHALL serialize CSV cells as follows: `null` → empty string; an **empty** `Map`, `List`, or empty Jackson container (`ObjectNode`/`ArrayNode` with no entries) → empty string; `String`, `Number`, `Boolean` → `toString()`, except `createdAt` and `computedAt`, which SHALL follow "CSV timestamps use ISO UTC"; a non-empty `Map`, `List`, or non-empty Jackson `JsonNode` → compact JSON string via the shared `ObjectMapper`. DIAL file references SHALL be emitted as their raw string form (no resolution or download in V1).
Status: **Implemented**

#### Scenario: List value serialization
- **WHEN** a cell value is the Java `List.of("a", "b")`
- **THEN** the rendered cell SHALL be `["a","b"]`

#### Scenario: Null vs empty string
- **WHEN** a cell value is `null`
- **THEN** the rendered cell SHALL be the empty string (CSV-quoted as needed)

## ADDED Requirements

### Requirement: CSV timestamps use ISO UTC
Status: **Implemented**

The CSV export SHALL render non-null `createdAt` and `computedAt` epoch-millisecond values as ISO 8601 UTC timestamps with exactly three fractional digits and a trailing `Z`. Null timestamps SHALL remain empty cells. Conversion SHALL apply only to these timestamp columns, regardless of default or explicit column selection; durations and arbitrary data/response/metric values SHALL remain unchanged. JSON responses, including export preview, and stored models SHALL retain epoch milliseconds. No columns SHALL be added or renamed.

#### Scenario: Timestamp formatting preserves milliseconds
- **WHEN** a selected timestamp column has value `1779704532226`
- **THEN** its CSV cell SHALL be `2026-05-25T10:22:12.226Z`

#### Scenario: Zero, negative, and null timestamps
- **WHEN** timestamp values are `0`, `-1`, and null
- **THEN** their CSV cells SHALL be `1970-01-01T00:00:00.000Z`, `1969-12-31T23:59:59.999Z`, and empty respectively

#### Scenario: Conversion is confined to CSV timestamp columns
- **WHEN** the same row is exported and previewed, with numeric duration and arbitrary data values
- **THEN** preview timestamps SHALL remain JSON numbers in epoch milliseconds and the other values SHALL retain their existing representations

## Implementation notes

- `src/main/java/com/epam/aidial/evaluation/service/domain/analytics/EvalSummaryExportService.java` applies CSV-only formatting through `service/domain/csv/CsvTimestampCodec.java`; planner and JSON preview retain raw values.
