## Context

CSV export of test case data uses `value.toString()` for all cell values, including `List` and `Map` objects. Java's `ArrayList.toString()` produces `[element1, element2]` (no quotes around string elements), which is not valid JSON. When such a CSV is reimported, `CsvImportService.parseJsonCell()` fails to parse this format and falls back to storing it as a plain string. This causes downstream metric evaluation to send string-typed values where arrays are expected.

Both `CsvExportService.cellValue()` (line 120) and `ZipExportService.cellValue()` (line 215) have the identical bug.

## Goals / Non-Goals

**Goals:**
- Fix both export services to produce valid JSON for ARRAY and OBJECT cell values
- Ensure CSV round-trip (export → reimport) preserves types for all schema field types

**Non-Goals:**
- Migrating existing corrupted data (string-encoded arrays) in deployed instances
- Changing the import path — it already handles valid JSON correctly
- Extracting `cellValue` into a shared component (both services are self-contained; the fix is a 3-line change in each)

## Decisions

### D1: Use `ObjectMapper.writeValueAsString()` for structured types

**Decision:** In `cellValue()`, check if the value is a `List` or `Map` and use `objectMapper.writeValueAsString(value)` instead of `value.toString()`.

**Alternatives considered:**
- *Always use `objectMapper.writeValueAsString()` for all values*: Over-encodes primitives — a string `hello` would become `"hello"` (with JSON quotes), breaking existing CSV format for simple types.
- *Extract a shared `CsvCellSerializer` component*: Over-engineered for a 3-line fix in two places. Can be considered later if more serialization logic is needed.

**Rationale:** Targeted fix — only changes behavior for `List`/`Map` types where `toString()` is incorrect. Primitives (`String`, `Integer`, `Double`, `Boolean`) continue using `toString()` which produces correct CSV values.

### D2: Inject `ObjectMapper` into both export services

**Decision:** Both `CsvExportService` and `ZipExportService` need `ObjectMapper` to serialize structured values. `CsvExportService` already has `ObjectMapper` injected (used by `parseJsonToMap`). `ZipExportService` also already has `ObjectMapper` injected. The `cellValue()` method changes from `static` to instance method to access the injected `ObjectMapper`.

### D3: Fail-fast on serialization errors

**Decision:** If `objectMapper.writeValueAsString()` throws `JsonProcessingException`, let the exception propagate (wrapped in `IllegalStateException`). This follows the project's "fail-fast for serialization/writes" principle. In practice, `writeValueAsString()` on a `List`/`Map` deserialized from Jackson should never throw — these are standard Java collections with primitive/string values. A `JsonProcessingException` here would indicate a truly unexpected state that should not be silently swallowed, especially since falling back to `toString()` would reproduce the exact bug being fixed.

## Risks / Trade-offs

**[Risk] Consumers that relied on Java `toString()` format** → This is considered a bug fix. The previous format was undocumented and produced data corruption on reimport. JSON format is the universally expected serialization for structured values.

**[Trade-off] No backward-compatible import-side parsing** → We are not adding a parser for Java `toString()` format on the import side. CSVs exported by the old code will still reimport with arrays as strings. This is acceptable because: (a) the old format was lossy — Java `toString()` doesn't include type information needed for reliable reconstruction, and (b) the fix prevents new exports from producing the broken format.
