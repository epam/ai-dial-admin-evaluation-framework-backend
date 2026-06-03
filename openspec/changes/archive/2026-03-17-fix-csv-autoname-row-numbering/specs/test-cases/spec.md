## ADDED Requirements

### Requirement: CSV import auto-name generation uses 1-based padded row index
When a CSV row has a blank or missing `testCaseName` column during import (any mode), the system SHALL auto-generate the name as `"Row " + zeroPaddedIndex` where the index is 1-based (first data row = 1) and zero-padded to `digits(csv.import.max-rows)` width (e.g., 100000 → 6 digits → "Row 000001").

#### Scenario: First data row gets name "Row 000001"
- **WHEN** client imports a CSV with blank `testCaseName` for the first data row and `csv.import.max-rows` is 100000
- **THEN** system SHALL assign the name "Row 000001"

#### Scenario: Auto-generated names sort correctly as strings
- **WHEN** client imports a CSV with 10+ rows with blank `testCaseName`
- **THEN** string-sorting the auto-generated names SHALL produce the same order as numeric sorting (e.g., "Row 000002" < "Row 000010")

#### Scenario: Preview shows padded auto-generated names
- **WHEN** client calls the CSV preview endpoint with blank `testCaseName` values
- **THEN** the preview response SHALL show the same padded naming pattern as the actual import

#### Scenario: Padding width derived from maxRows config
- **WHEN** `csv.import.max-rows` config value has N digits
- **THEN** the zero-padding width SHALL be N (e.g., max-rows=100000 → 6-digit padding)
