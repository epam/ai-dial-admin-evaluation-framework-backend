## Why

CSV import auto-name generation produces names starting from "Row 2" (because `rowNum` tracks the CSV file row number where the header is row 1). Additionally, the unpadded numbering ("Row 2", "Row 10") breaks string-based sorting — "Row 10" sorts before "Row 2".

## What Changes

- Fix row numbering so auto-generated test case names start from "Row 1" (first data row) instead of "Row 2"
- Zero-pad the row number based on `csv.import.max-rows` config (currently 100,000 → 6 digits) so names sort correctly as strings: "Row 000001", "Row 000002", ..., "Row 000010"
- Apply the same fix to both `preview()` and `importCsv()` flows in `CsvImportService`

## Capabilities

### New Capabilities

_None — this is a bug fix within existing CSV import capability._

### Modified Capabilities

- `test-cases`: Auto-name generation during CSV import changes naming pattern from `Row N` (N = CSV row number) to `Row 00000N` (N = 1-based data row index, zero-padded to `digits(maxRows)`)

## Impact

- **Code**: `CsvImportService.parseRow()` — naming logic; `preview()` and `importCsv()` — row counter initialization
- **APIs**: No API contract change (names are opaque strings). Preview response will show new naming pattern.
- **Data/Migration**: No migration needed. Existing test cases keep their names; only new imports affected.
- **Tests**: Functional tests asserting auto-generated names need updating to match new pattern.
