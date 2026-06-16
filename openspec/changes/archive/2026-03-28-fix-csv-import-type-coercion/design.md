## Context

CSV import uses `CsvCellParser.parseCell()` to convert raw CSV strings into typed Java objects via heuristic pattern matching (integer regex, number regex, boolean literals). The result is serialized to JSONB via Jackson's `ObjectMapper`, which preserves Java types (Long → JSON number, Boolean → JSON boolean, etc.).

The declared `testCaseSchema` field type (e.g., `STRING`) is only consulted for `OBJECT`/`ARRAY` types (to attempt JSON parsing). For all other schema types (`STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`), the heuristic-inferred type is stored as-is, ignoring the schema declaration.

This causes a type mismatch: a column declared as `STRING` with CSV value `1865` gets stored as JSON number `1865` instead of JSON string `"1865"`. Downstream metric evaluation passes these values uncoerced to metric providers, which reject them (e.g., Pydantic `422: Input should be a valid string`).

### Current flow (buggy)

```
CSV cell "1865"
    → CsvCellParser.parseCell() → Integer(1865)    // heuristic: matches integer regex
    → parseRow() stores Integer(1865)               // schema type STRING ignored
    → Jackson serializes → JSON number 1865
    → JSONB stores 1865
    → BindingResolver passes Integer(1865) to metric
    → Metric rejects: expected string
```

### Desired flow (fixed)

```
CSV cell "1865"
    → CsvCellParser.parseCell() → Long(1865)       // heuristic: now uses Long
    → coerceToSchemaType(STRING, Long(1865))        // NEW: coerce to match schema
    → returns String "1865"
    → Jackson serializes → JSON string "1865"
    → JSONB stores "1865"
    → BindingResolver passes String "1865" to metric
    → Metric accepts
```

## Goals / Non-Goals

**Goals:**
- Parsed CSV cell values MUST be coerced to match the declared schema field type before storage
- Type correctness MUST be ensured even when schema is undefined at import start (default use case) — via a post-persist fixup pass
- `CsvCellParser` stops treating `"1"`/`"0"` as boolean — they are parsed as integers, eliminating lossy coercion
- Coercion logic is an injectable `@Component` in `service.domain.csv` for independent testability

**Non-Goals:**
- Retroactively fixing already-stored test case data from previous imports (out of scope — would require a data migration). The fixup pass only applies to the current import operation.
- Adding type coercion at the `BindingResolver` level (the fix belongs at the data ingestion point)
- Changing the CSV preview schema type inference logic (auto-detection remains heuristic-based and is unaffected). Note: preview sample row values will reflect coercion when a schema type is known, because `parseRow()` is shared between preview and import. This is a natural side effect and desirable — preview should accurately reflect what will be stored
- Coercing values during API-based test case create/update (JSON API clients are responsible for sending correct types — but they will now get TYPE warnings)

## Decisions

### Decision 1: Remove `"1"`/`"0"` from `CsvCellParser.isBoolean()` and switch to `Long` for integer parsing

**Choice:** Modify `CsvCellParser.isBoolean()` to only match literal `"true"`/`"false"` (case-insensitive). `"1"` and `"0"` now fall through to the integer pattern. Additionally, switch `CsvCellParser.parseCell()` from `Integer.parseInt()` to `Long.parseLong()` so that all parsed integer values are `Long`.

**Why (boolean fix):** `CsvCellParser` checking `"1"`.equals(value) || `"0"`.equals(value)` before the integer regex means `parseCell("1")` returns `Boolean(true)`, not an integer. This causes lossy coercion: `String.valueOf(Boolean(true))` = `"true"`, losing the original `"1"`. By parsing `1`/`0` as integers, `String.valueOf(Long(1))` = `"1"` — lossless.

**Why (Long):** The baseline spec's Type System Reference declares `INTEGER → Long`. Using `Integer.parseInt()` silently fails for values exceeding `Integer.MAX_VALUE` (e.g., `3000000000`), causing valid integers to fall through to STRING. `Long.parseLong()` covers the full range of realistic integer values (up to 9.2×10¹⁸). Additionally, Jackson's `ObjectMapper` deserializes JSON integers as either `Integer` or `Long` depending on magnitude — using `Long` throughout the CSV path eliminates this inconsistency. The JSONB round-trip caveat (Jackson reads small JSON integers back as `Integer`) is handled in the validation layer (Decision 7), which accepts both `Integer` and `Long`.

**Impact on schema auto-detection:** `inferTypeName()` also calls `isBoolean()`. Columns with only `1`/`0` values will now be auto-detected as `INTEGER` instead of `BOOLEAN`. This is more correct for typical CSV data. If users need `BOOLEAN`, they can adjust the schema after preview (and in the future, send explicit types with the import request).

**Impact on coercion:** The coercion matrix handles `BOOLEAN ← Long` (`!= 0` → true, `0` → false), so if a column's schema says `BOOLEAN` and the CSV contains `1`/`0`, the coercion produces the correct boolean values.

### Decision 2: Coerce in `CsvImportService.parseRow()`, not in `CsvCellParser`

**Choice:** Add coercion after `csvCellParser.parseCell()` in `parseRow()`, not inside the parser itself.

**Why:** `CsvCellParser` serves dual purposes — cell parsing AND type inference for schema auto-detection. During auto-detection, we want the heuristic type (to correctly infer `INTEGER` for `"1865"`). During import with a known schema, we want coercion. Keeping the parser schema-unaware preserves both use cases.

### Decision 3: Extract a `SchemaTypeCoercer` component

**Choice:** Create a new `SchemaTypeCoercer` component in `service.domain.csv` package.

**Why:** Per project conventions, specialized conversion logic MUST be a top-level injectable `@Component`, not a private method in `CsvImportService`. This also enables isolated unit testing with clear test cases for each type combination.

**Interface:**
```java
@Component
public class SchemaTypeCoercer {
    /**
     * Coerces a parsed cell value to match the declared schema type.
     * Returns the value unchanged if schema type is null (unknown schema).
     *
     * @param value      the parsed cell value (from CsvCellParser or Jackson)
     * @param schemaType the declared schema field type (nullable — null means unknown)
     */
    public Object coerce(Object value, SchemaFieldType schemaType);
}
```

After the `CsvCellParser` fix (Decision 1), `String.valueOf()` is lossless for all parser outputs (`Long`, `Double`, `Boolean(true/false)`, `String`). The `rawValue` parameter is no longer needed — `String.valueOf(value)` always produces the correct string representation. This simplifies the interface and makes `SchemaTypeCoercer` usable for both the inline coercion path (during `parseRow()`) and the post-persist fixup path (re-reading from DB where raw CSV strings are unavailable).

### Decision 4: Coercion rules (complete matrix)

| Schema Type | Parsed Value | Action | Result |
|-------------|-------------|--------|--------|
| `STRING` | Long/Integer/Double/Boolean | `String.valueOf(value)` | String |
| `STRING` | String | no-op | String |
| `INTEGER` | String (numeric) | `Long.parseLong()` | Long |
| `INTEGER` | Double (whole number, e.g. `3.0`) | `longValue()` | Long |
| `INTEGER` | Double (fractional, e.g. `3.14`) | coercion failure — return unchanged | Double (validation catches mismatch) |
| `INTEGER` | Boolean | `true → 1L, false → 0L` | Long |
| `INTEGER` | Integer/Long | no-op (or widen Integer → Long) | Long |
| `NUMBER` | String (numeric) | `Double.parseDouble()` | Double |
| `NUMBER` | Boolean | `true → 1.0, false → 0.0` | Double |
| `NUMBER` | Integer/Long | `doubleValue()` | Double |
| `NUMBER` | Double | no-op | Double |
| `BOOLEAN` | String `"true"`/`"false"` | parse boolean | Boolean |
| `BOOLEAN` | Integer/Long | `!= 0` → true, `0` → false | Boolean |
| `BOOLEAN` | Double | coercion failure — return unchanged | Double (validation catches mismatch) |
| `BOOLEAN` | Boolean | no-op | Boolean |
| `OBJECT`/`ARRAY` | any | existing logic (unchanged) | as-is |
| `FILE` | String | no-op | String |
| `FILE` | Long/Integer/Double/Boolean | `String.valueOf(value)` | String |
| `null` (unknown) | any | no-op | as-is |

**Coercion failures** (e.g., schema says INTEGER but value is `"hello"`): return the value unchanged and let downstream validation catch it. This matches the existing graceful approach — `parseJsonCell` returns null on failure and falls back to the raw value.

**Empty strings:** `CsvCellParser.parseCell()` returns `""` for null/blank cells. Empty strings are not numeric or boolean, so `Long.parseLong("")`, `Double.parseDouble("")`, and boolean parsing all fail — the empty string is returned unchanged as a coercion failure for non-STRING types. Downstream validation catches the type mismatch.

**Integer vs Long handling:** After Decision 1, `CsvCellParser` produces `Long` for all integer values. However, the coercer must also accept `Integer` inputs because Jackson's `ObjectMapper` deserializes small JSON integers (≤ `Integer.MAX_VALUE`) as `Integer` when reading from JSONB. The coercion matrix handles both via `instanceof Number` checks — `Number.longValue()` works for both `Integer` and `Long`. Coercion output for INTEGER schema type is always `Long` for consistency with the baseline spec's Type System Reference (`INTEGER → Long`).

### Decision 5: Inline coercion when schema type is known

Coercion applies inline in `parseRow()` when `fieldTypes.get(fieldName)` returns a non-null `SchemaFieldType`. This means:
- **APPEND/MERGE with existing schema:** `fieldTypes` populated from existing schema → coercion applies inline (the primary fix).
- **OVERRIDE mode / empty schema cases:** `fieldTypes` is empty → no inline coercion → heuristic types stored initially → **post-persist fixup handles these** (Decision 6).

### Decision 6: Post-persist fixup pass for auto-detected/changed schema

**Choice:** After schema is finalized and persisted, run a fixup pass that re-reads all suite test cases in batches, coerces values for columns with newly determined types, re-validates, and batch-updates changed rows.

**Why:** When schema is undefined at import start (default use case), `fieldTypes` is empty and inline coercion cannot fire. Type inference runs incrementally per row, and the final schema types are only known after all rows are processed. Rows already stored during the import loop have heuristic-typed values that may not match the final schema. The fixup pass corrects this.

**Flow:**
```
Existing (inline coercion only):
  for each row:
    ① parseRow(fieldTypes={})        → heuristic types stored
    ② updateInferredTypes()          → build schema incrementally
  ③ persistSchema(inferredTypes)     → save final schema
  → PROBLEM: stored data has wrong types for auto-detected columns

New (inline + fixup):
  for each row:
    ① parseRow(fieldTypes)           → inline coercion when types known
    ② updateInferredTypes()          → build schema incrementally
  ③ persistSchema(inferredTypes)     → save final schema
  ④ fixupPass(changedColumns)        → re-read ALL suite test cases in batches,
                                       coerce changed columns with SchemaTypeCoercer,
                                       re-validate, batch UPDATE changed rows
```

**When fixup runs:**

| Mode | Schema at start | Changed columns | Fixup scope |
|------|----------------|-----------------|-------------|
| OVERRIDE | any | ALL (schema rebuilt) | just-imported (existing deleted) |
| APPEND + empty schema | empty | ALL (auto-detected) | ALL suite test cases |
| APPEND + existing schema | populated | NONE | no fixup needed |
| MERGE + existing schema | populated | only new columns from `inferredTypes` | ALL suite test cases |
| MERGE + empty schema | empty | ALL (auto-detected) | ALL suite test cases |

**Schema save timing:** Save schema BEFORE fixup. The fixup needs target types to coerce against, and re-validation should use the new schema. All within the same `@Transactional` — if fixup fails, everything rolls back.

**Implementation:**
1. Compute `changedColumns`: the set of column names whose schema type was newly determined during this import (from `inferredTypes` for auto-detected, or diff between old and new schema for OVERRIDE)
2. If `changedColumns` is empty, skip fixup
3. Read all test cases for the suite in pages (using `testCaseRepository.findByTestSuiteId()` with `PageRequest` for batch reads)
4. For each test case, for each changed column: call `schemaTypeCoercer.coerce(value, schemaType)`
5. If any values changed: re-validate the test case, batch UPDATE `data`, `is_valid`, `validation_warnings` (using existing `testCaseRepository.batchUpdate()` or a new batch-update method if needed)
6. Process in batches to bound memory usage

**Performance:** Extra DB read+write pass, but only when schema was auto-detected or changed. For the common case (first import into empty suite), this is a one-time cost proportional to the number of imported rows. Subsequent imports with a known schema use inline coercion only — no fixup needed.

## Risks / Trade-offs

**[Risk] Coercion failure for invalid data** → Mitigation: Return the original value unchanged and let the existing validation pipeline handle it. No new failure modes introduced.

**[Risk] Performance overhead of inline coercion** → Mitigation: Negligible — one `instanceof` check and at most one `String.valueOf()` per cell. CSV import is already I/O-bound.

**[Risk] Fixup pass performance on large suites** → Mitigation: Runs only when schema changes (not on every import). Processes in batches. For OVERRIDE mode, only just-imported rows need fixup (existing rows are deleted). The cost is proportional to suite size, same order as the import itself.

**[Trade-off] Existing data from previous imports not retroactively fixed** → Accepted. The fixup pass only covers the current import operation (all suite test cases at that point in time). Historical data from before this code change is not fixed. Users can re-import or use re-validation to surface issues.

**[Trade-off] No coercion at BindingResolver level** → Accepted. Fixing at ingestion is the right layer — it prevents the wrong type from ever being stored, rather than papering over it at read time.

**[Future enhancement]** Client sends explicit column types with the import request — the user calls `/preview`, reviews/adjusts the auto-detected types, and the adjusted types are passed to the actual import call so coercion can apply from row 1 without needing a fixup pass.

### Decision 7: Add type mismatch validation warnings in `TestCaseValidationService`

**Choice:** Add type-checking logic to `TestCaseValidationService.validateTestCase()` that emits `ValidationWarningCode.TYPE` warnings when a data field's value type doesn't match the schema's declared type. No coercion — soft validation only.

**Why:** The CSV coercion fix only covers CSV import. API clients (POST/PUT/PATCH) can still send data with wrong JSON types (e.g., `{"answer": 1865}` when schema says STRING). Since all write paths go through `TestCaseValidationService`, adding type checking here creates a safety net for all ingestion paths.

**Behavior:**
- CSV import: SchemaTypeCoercer runs first → types match → no TYPE warning
- API create/update/patch: no coercion → mismatch detected → TYPE warning emitted
- Test case is still saved (`isValid=false`, warning attached) — not rejected
- Re-validation of existing test cases will now surface TYPE warnings for historical data

**Type compatibility matrix:**

| Schema Type | Compatible JSON Types |
|-------------|----------------------|
| `STRING` | String |
| `INTEGER` | Integer, Long |
| `NUMBER` | Integer, Long, Double |
| `BOOLEAN` | Boolean |
| `OBJECT` | Map |
| `ARRAY` | List |
| `FILE` | String |

Note: `NUMBER` accepts integers (an integer is a valid number). `null` values are skipped (handled by existing REQUIRED check). Both `Integer` and `Long` are accepted because: (1) `CsvCellParser` now produces `Long`, (2) Jackson deserializes small JSON integers as `Integer` and large ones as `Long` on JSONB round-trip. The check uses `value instanceof Number && !(value instanceof Double)` for INTEGER, and `value instanceof Number` for NUMBER, to handle both uniformly. Jackson's default `ObjectMapper` deserializes JSON floating-point to `Double` (not `Float`), so `Float` is omitted.

**Warning format:** `ValidationWarningCode.TYPE`, path `$.data.<fieldName>`, message: `"Field '<fieldName>' has schema type <schemaType> but value is <actualType>"`.

**Why not coerce at service level:** API clients explicitly chose the JSON types they sent. Coercing silently would mask client bugs. Warnings inform without mutating — the client can then fix their payload.

**[Risk] Existing test cases may become invalid on re-validation** → Accepted. This is desirable — it surfaces previously hidden type mismatches. The test case data is not changed, only the validation state.
