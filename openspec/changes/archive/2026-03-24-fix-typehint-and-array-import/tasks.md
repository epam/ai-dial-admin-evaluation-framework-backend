## 1. Fix type-hint stripping in HTTP request resolver

- [x] 1.1 Update `ResolvedRequestService.PLACEHOLDER_PATTERN` from `([^:}]+)` to `([^:|}]+)(?:\\|[^:}]+)?` so `|type` is consumed but not captured as part of the variable name
- [x] 1.2 Update `ResolvedRequestService.FULL_VALUE_PATTERN` check: verify `FULL_VALUE_PATTERN` still matches type-hinted placeholders correctly (no change needed — `[^}]+` already covers `|`); add a comment confirming this
- [x] 1.3 Add unit tests in `ResolvedRequestServiceTest` (or equivalent) covering: `${{var|file}}` resolves to bound value; `${{var|file:default}}` falls back to default; `${{var}}` unchanged; `${{var:default}}` unchanged

## 2. Fix type-hint stripping in MCP request resolver

- [x] 2.1 Update `McpRequestResolver.PLACEHOLDER_PATTERN` identically to 1.1 — change `([^:}]+)` to `([^:|}]+)(?:\\|[^:}]+)?`
- [x] 2.2 Add unit tests in `McpRequestResolverTest` covering: `${{param|string}}` full-value path resolves to data map value; `${{param|array}}` embedded path resolves correctly; existing no-type-hint cases remain unchanged

## 3. Fix CSV import storing ARRAY/OBJECT cells as strings

- [x] 3.1 In `CsvImportService.parseRow`, add an `else if (type == null)` branch: when the raw cell starts with `[` or `{`, attempt `objectMapper.readValue(raw.trim(), Object.class)`; if successful and result is `List` or `Map`, store the parsed value; otherwise fall through to `csvCellParser.parseCell` result (no `hasJsonParseErrors` flag)
- [x] 3.2 Add unit/functional tests covering: OVERRIDE import with empty schema stores `["a","b","c"]` cell as a JSON array (not string); APPEND+empty-schema import stores array correctly; a cell starting with `[` that is not valid JSON is stored as a string without error; existing STRING/INTEGER/BOOLEAN cell behavior is unchanged

## 4. Add fail-fast for missing columns in metric binding resolution

- [x] 4.1 In `BindingResolver.resolveSource`, for `TestCaseBindingSourceDto`: use `testCaseData.containsKey(columnName)` — if false, throw `IllegalArgumentException` with message identifying the missing column; if true, return `testCaseData.get(columnName)` (may be null)
- [x] 4.2 Same for `ResponseBindingSourceDto`: use `extractedColumns.containsKey(columnName)` — if false, throw; if true, return value
- [x] 4.3 Add unit tests for `BindingResolver` covering: column exists with value → returns value; column exists with null → returns null; column missing → throws `IllegalArgumentException`; constant binding → unchanged behavior

## 5. Verify end-to-end correctness

- [x] 5.1 Add or extend a functional test that: creates a suite with ARRAY-typed field and OBJECT-typed field, imports test cases via CSV (or direct creation), configures metric TSMDs with bindings to those columns (both constant and testcase-column bindings), runs evaluation + metric evaluation, and asserts: (a) the metric provider receives arrays/objects (not strings) for bound fields, (b) constant bindings resolve correctly, (c) test case column bindings resolve correctly for complex types
- [x] 5.2 Run `./gradlew checkstyleMain checkstyleTest` and confirm no new Checkstyle violations
