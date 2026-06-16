## Why

During CSV import, test cases are validated against the **stale/empty suite schema** loaded at the start of the import, rather than the schema that will be in effect after import completes. This causes imported test cases to be marked `isValid=false` with spurious "Unknown data field" warnings — the schema is only persisted after all rows are already saved with incorrect validation state.

## What Changes

- Build a "validation schema" from CSV column bindings **before** processing rows, so validation runs against the correct target field set instead of the stale suite schema
- Fix applies to both `importCsv()` (actual import) and `preview()` (dry-run) in `CsvImportService`
- Add functional test coverage asserting `isValid=true` and empty `validationWarnings` after import

## Capabilities

### New Capabilities

_None_ — this is a bug fix within existing CSV import capability.

### Modified Capabilities

- `test-cases`: Fix the "Validate TestCases against schema, template, and bindings" requirement during CSV import — validation must use the **target** schema (derived from CSV headers and import mode) rather than the pre-import suite schema

## Impact

- **Affected code**: `CsvImportService.java` (both `importCsv` and `preview` methods), possibly a small helper to build the validation schema from column bindings
- **Affected APIs**: `POST .../test-cases/import` and `POST .../test-cases/import/preview` — no contract change, but imported test cases will now have correct `isValid`/`validationWarnings` state
- **Affected modes**: OVERRIDE (always), MERGE with new columns, APPEND/MERGE with empty schema. APPEND with existing schema is unaffected (unknown columns already discarded at parse time)
- **No schema changes, no migration needed**
