## Context

`CsvImportService` auto-generates test case names when the CSV row has a blank/missing `testCaseName` column. Currently it uses `"Row " + rowNumber` where `rowNumber` is the CSV file row number (header = 1), so the first data row gets "Row 2". The unpadded number also breaks string sorting ("Row 10" < "Row 2").

## Goals / Non-Goals

**Goals:**
- Auto-generated names start from "Row 1" for the first data row
- Zero-pad the row number so names sort correctly as strings
- Derive padding width from `csv.import.max-rows` config (currently 100,000 → 6 digits)

**Non-Goals:**
- Changing existing test case names in the database (no migration)
- Changing the "Row" prefix to something else
- Natural sort in the database layer

## Decisions

### D1: Use a separate 1-based data row index for naming

**Decision**: Introduce a `dataRowIndex` counter starting at 0 and incremented alongside `rowNum`. Use `dataRowIndex` (1-based after increment) for name generation. Keep `rowNum` unchanged for error messages that reference CSV file line numbers.

**Rationale**: `rowNum` serves a dual purpose — error reporting (where CSV line number matters) and naming (where logical row index matters). Separating them avoids breaking error messages. Alternative — subtracting 1 in the naming code — was rejected because it couples naming to the header-offset convention.

### D2: Padding width derived from maxRows config

**Decision**: Compute `padWidth = String.valueOf(maxRows).length()` once at import/preview start. Format names as `String.format("Row %0" + padWidth + "d", dataRowIndex)`.

**Rationale**: Single-pass (no need to count rows first). Deterministic — same padding for all imports regardless of actual row count. `maxRows` is already injected via `CsvImportProperties`.

**Trade-off**: Small imports (5 rows) get "Row 000001" through "Row 000005". Acceptable because names are primarily machine-sorted and the padding is visually regular.

### D3: Inject CsvImportProperties into the naming path

**Decision**: `CsvImportProperties` is already injected into `CsvImportService`. Compute `padWidth` from `csvImportProperties.getMaxRows()` at the top of `preview()` and `importCsv()`, pass it to `parseRow()`.

**Rationale**: No new dependencies needed. The format string is computed once per import, not per row.

## Risks / Trade-offs

- **[Visual noise]** → Acceptable for machine-consumed data; users rarely read auto-generated names directly
- **[Config change affects naming]** → If `maxRows` changes, new imports get different padding width. Old names are unaffected (already stored). This is expected and documented.
