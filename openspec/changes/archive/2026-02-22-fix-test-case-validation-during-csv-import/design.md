## Context

During CSV import, `CsvImportService` loads the suite's `testCaseSchema` from DB at the start, then validates each row against it via `TestCaseValidationService.validateTestCase()`. The schema is only persisted (replaced/merged) **after** all rows are saved. This means validation runs against a stale schema — the old suite schema or an empty schema — producing false "Unknown data field" warnings and `isValid=false` on every imported test case.

The bug affects:
- **OVERRIDE** (always): CSV columns differ from old schema, or schema was empty
- **MERGE + new columns**: new columns flagged as unknown against old schema
- **APPEND/MERGE + empty schema**: all data fields flagged as unknown against empty schema
- **APPEND + existing schema**: NOT affected (unknown columns discarded at parse time)

The bug exists in both `importCsv()` and `preview()`.

`TestCaseValidationService.validateTestCase()` uses the schema for two checks:
1. **Required field presence** (lines 117-127): iterates `FieldDefinitionDto`, checks `isRequired()` + `getName()`
2. **Unknown field detection** (lines 129-136): checks `schemaFieldNames.contains(key)` — a `Set<String>` of field names

Neither check uses the `type` field from `FieldDefinitionDto`. Types are used separately during CSV cell parsing in `CsvImportService.parseRow()` via the `fieldTypes` map.

## Goals / Non-Goals

**Goals:**
- Fix validation so imported test cases have correct `isValid` and `validationWarnings`
- Fix both `importCsv()` and `preview()` code paths
- Add test coverage for `isValid` state after import
- Preserve streaming/batching architecture (no second pass, no buffering)

**Non-Goals:**
- Changing `TestCaseValidationService` itself
- Adding type-based validation to the import flow
- Changing the schema persistence timing or order of operations
- Modifying the revalidation service or async revalidation

## Decisions

### Decision 1: Build a "validation schema" from CSV headers before processing rows

**Chosen approach — Header-based validation schema:**

Before the row-processing loop, build a `List<FieldDefinitionDto>` representing the target schema state after import, using the known CSV column bindings. Pass this to `validateTestCase()` instead of the stale `testCaseSchema`.

The validation schema is built based on import mode and current schema state:

| Mode | Schema State | Validation Schema |
|------|-------------|-------------------|
| OVERRIDE | any | All data columns from CSV headers (`required=false`, `type=STRING`) |
| MERGE | non-empty | Existing schema + new CSV columns (`required=false`, `type=STRING`) |
| APPEND/MERGE | empty | All data columns from CSV headers (`required=false`, `type=STRING`) |
| APPEND | non-empty | Existing `testCaseSchema` (unchanged — already correct) |

**Why this works:**
- All CSV column names are known from the header row, parsed **before** any data rows
- `TestCaseValidationService` only uses `name` and `required` from the schema — never `type`
- Setting `required=false` is correct: auto-detected columns from CSV should never be required
- Setting `type=STRING` is a placeholder — irrelevant since type is not checked by the validation service

**Pros:**
- Zero extra DB I/O — no revalidation pass needed
- Single mechanism for both `importCsv()` and `preview()`
- Preserves streaming/batching architecture entirely
- Simple implementation: ~15 lines to build the validation schema
- `validCount`/`invalidCount` in import result are correct from the start

**Cons:**
- Validation schema has approximate types (`STRING` for all new fields) — but types are not used by the validator, so this is cosmetic
- If `TestCaseValidationService` is ever extended to use types, the validation schema would need updating — but that would be a new feature, not this bug fix

### Decision 2 (considered, rejected): Revalidation pass after schema persistence

**Alternative — Revalidation pass:**

Skip validation during row processing, persist schema, then read back all test cases from DB, validate against the final schema, and update `isValid`/`validationWarnings`.

**Pros:**
- Validates against ground truth (actual persisted schema with correct types)
- Future-proof: automatically correct if validation logic ever uses types
- Matches existing `RevalidationService` pattern

**Cons:**
- Doubles or triples DB I/O: every row gets INSERT + SELECT + UPDATE instead of just INSERT
- Increases transaction duration and lock hold time for large imports
- Preview has no DB to read back from — requires a separate mechanism (deferred validation of buffered sample rows), creating two different code paths for the same fix
- `validCount`/`invalidCount` requires the extra pass to compute, complicating the result DTO flow
- Significantly more complex implementation for no practical benefit (types are unused in validation)

### Decision 3: Extract validation schema building into a dedicated method

Create a private method `buildValidationSchema(mode, schemaEmpty, bindings, testCaseSchema)` in `CsvImportService` that returns the appropriate `List<FieldDefinitionDto>` based on the mode matrix above. Call it once after `resolveColumnBindings()` and use the result for all subsequent `validateTestCase()` calls. This keeps the logic centralized and testable.

## Risks / Trade-offs

- **[Risk] Type approximation in validation schema** — New fields use `type=STRING` which differs from the auto-detected type. → **Mitigation**: `TestCaseValidationService` does not use types; the persisted schema (via `persistSchema()`) still has correct auto-detected types. If type validation is added later, the validation schema builder must be updated.
- **[Risk] Required flag on existing schema fields** — In MERGE mode, existing fields retain their `required` flag from the current schema. If the CSV data is missing a required existing field, validation will correctly flag it. → **Mitigation**: This is correct behavior — the field IS required and the data IS missing it.
