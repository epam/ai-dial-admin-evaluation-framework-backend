## Why

Boolean fields on response DTOs serialize as `valid`, `enabled` (Jackson strips the `is` prefix from primitive `boolean` getters), but filter and sort parameter keys use `isValid`, `isEnabled`. Clients see `"valid": true` in JSON but must send `filter=isValid:EQ:true` — an inconsistency that forces them to "just know" the Java naming convention. The same mismatch appears in CSV export/import column headers and the PATCH body key for `isEnabled`.

## What Changes

- **BREAKING**: Rename filter keys `isValid` → `valid`, `isEnabled` → `enabled` in `FilterWhitelists.TEST_CASES`
- **BREAKING**: Rename sort keys `isValid` → `valid`, `isEnabled` → `enabled` in `SortWhitelists.TEST_CASES`
- **BREAKING**: Rename CSV export/import column header `isEnabled` → `enabled` in `CsvExportService` / `CsvImportService`
- **BREAKING**: Rename PATCH body key `isEnabled` → `enabled` in `TestCaseService` patch handling
- **BREAKING**: Rename CSV export query parameter `includeIsEnabled` → `includeEnabled` in `TestCaseController`
- Update all affected tests (`WhereBuilderTest`, functional tests referencing old key names)
- Update OpenAPI examples that reference old key names

## Capabilities

### New Capabilities

_None_

### Modified Capabilities

- `entity-filtering`: Filter key names for boolean fields change from Java getter convention (`isValid`, `isEnabled`) to JSON property convention (`valid`, `enabled`)
- `sorting`: Sort key names for boolean fields change from Java getter convention to JSON property convention
- `test-cases`: CSV column header and PATCH body key for enabled field change from `isEnabled` to `enabled`; export query param changes from `includeIsEnabled` to `includeEnabled`

## Impact

- **API consumers**: All clients using `isValid`/`isEnabled` in filter, sort, CSV, or PATCH requests must update to `valid`/`enabled`. This is a breaking change.
- **Affected files**: `FilterWhitelists`, `SortWhitelists`, `CsvExportService`, `CsvImportService`, `TestCaseService`, `TestCaseController`, OpenAPI examples, and corresponding tests.
- **No database changes**: Filter/sort keys are API-level names mapped to DB columns — the DB column names (`is_valid`, `is_enabled`) remain unchanged.
