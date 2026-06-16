## Summary

CSV export uses Java `List.toString()` / `Map.toString()` for ARRAY and OBJECT cell values, producing non-JSON output (e.g., `[value1, value2]` instead of `["value1", "value2"]`). When reimported, `parseJsonCell()` fails to parse this format, falling back to storing the value as a plain string. Downstream metric evaluation then sends string-typed values to metric providers that expect arrays, causing 422 errors.

## Goals

- Fix CSV/ZIP export to serialize ARRAY and OBJECT values as valid JSON strings
- Ensure CSV round-trip (export → reimport) preserves types for all schema field types

## Non-goals

- Migrating or fixing existing corrupted data in deployed instances (data fix is out of scope)
- Changing the import path (import-side `parseJsonCell()` already handles valid JSON correctly)

## Proposed Change

### What Changes

- **Fix `cellValue()` in `CsvExportService`** (line 120): For `List` and `Map` values, use `objectMapper.writeValueAsString(value)` instead of `value.toString()`
- **Fix `cellValue()` in `ZipExportService`** (line 215): Same fix — identical method, same bug
- **Add round-trip functional test**: Export test cases with ARRAY/OBJECT columns, reimport, verify type preservation

### Capabilities

#### New Capabilities

_(none)_

#### Modified Capabilities

- `test-cases`: Add explicit requirement that CSV export SHALL serialize ARRAY and OBJECT values as valid JSON strings, ensuring round-trip fidelity

## API Impact

No API contract changes. The export endpoint returns the same CSV structure; only the cell content format for ARRAY/OBJECT values changes from Java `toString()` to JSON.

**Breaking**: Existing consumers that parse Java `toString()` format (e.g., `[value1, value2]`) would need to handle JSON format (`["value1", "value2"]`). This is considered a bug fix, not a breaking change, since the previous format was never intentionally specified and produced data corruption on reimport.

## Data/Migration Impact

None. No schema changes. Existing corrupted data (arrays stored as strings) is not auto-fixed by this change.

## Security/Permissions Impact

None.

## Risks

- **Low**: Consumers that relied on Java `toString()` format will see changed output. Mitigated by the fact that JSON is the universally expected format.

## Rollout

Standard deployment. No feature flags needed.

## Test Plan

- Functional test: Export CSV with ARRAY/OBJECT columns → verify cell content is valid JSON
- Functional test: Round-trip export → reimport for CSV and ZIP with ARRAY/OBJECT data → verify type preservation
- Edge cases: delimiter-containing elements (`["hello, world"]`), empty arrays/objects (`[]`, `{}`), nested structures (`[{"k":[1,2]}]`), mixed-type arrays (`[1,"two",true,null]`), special characters (quotes, newlines, Unicode), already-corrupted string-encoded arrays (no double-encoding), mixed column types in same suite
