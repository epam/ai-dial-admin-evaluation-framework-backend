## 1. Fix export services

- [x] 1.1 Fix `CsvExportService.cellValue()` — change from `static` to instance method; for `List`/`Map` values use `objectMapper.writeValueAsString(value)`, let `JsonProcessingException` propagate as `IllegalStateException` (fail-fast per D3) (done: ARRAY/OBJECT values serialize as valid JSON in CSV export)
- [x] 1.2 Fix `ZipExportService.cellValue()` — same fix as 1.1 (done: ARRAY/OBJECT values serialize as valid JSON in ZIP export)

## 2. Tests

- [x] 2.1 Add functional test for CSV export with ARRAY/OBJECT columns — create test cases with ARRAY and OBJECT data via API, export CSV, verify exported cell content is valid JSON (done: export format verified for List, Map, primitives, and null)
- [x] 2.2 Add functional round-trip test — create test cases with ARRAY/OBJECT columns via API, export CSV, reimport into a new suite, verify data types preserved; include both CSV and ZIP export paths (done: round-trip test passes for ARRAY and OBJECT fields in both CSV and ZIP)

## 3. Verification

- [x] 3.1 Run `./gradlew checkstyleMain checkstyleTest` — verify no style violations (done: clean checkstyle)
- [x] 3.2 Run `./gradlew test` — verify all existing + new tests pass (done: full green test suite)
- [x] 3.3 Sync delta spec to `openspec/specs/test-cases/spec.md` — add the new scenarios (Export ARRAY values as JSON, Export OBJECT values as JSON, Export null ARRAY/OBJECT values, Export primitive values unchanged) and the ARRAY/OBJECT serialization paragraph to the requirement preamble (done: main spec updated)
