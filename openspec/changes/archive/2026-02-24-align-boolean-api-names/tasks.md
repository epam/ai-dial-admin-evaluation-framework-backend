## 1. Data Layer — Rename filter and sort whitelist keys

- [x] 1.1 In `FilterWhitelists.java`: rename `"isValid"` → `"valid"` and `"isEnabled"` → `"enabled"` in `TEST_CASES`
- [x] 1.2 In `SortWhitelists.java`: rename `"isValid"` → `"valid"` and `"isEnabled"` → `"enabled"` in `TEST_CASES`
- [x] 1.3 In `WhereBuilderTest.java`: update test cases that reference `"isEnabled"` filter field to `"enabled"`

## 2. Service Layer — Rename CSV headers and PATCH body key

- [x] 2.1 In `CsvExportService.java`: rename CSV column header from `"isEnabled"` to `"enabled"`
- [x] 2.2 In `CsvImportService.java`: rename `IS_ENABLED_HEADER` constant from `"isEnabled"` to `"enabled"` and update all references (header matching, column binding, case labels)
- [x] 2.3 In `TestCaseService.java`: rename PATCH body key from `"isEnabled"` to `"enabled"` in merge patch handling

## 3. Web Layer — Rename query parameter

- [x] 3.1 In `TestCaseController.java`: rename `includeIsEnabled` query parameter to `includeEnabled` (both `@RequestParam` name and method parameter)

## 4. OpenAPI Examples

- [x] 4.1 Update `api-v1-test-suites-testSuiteId-test-cases-PATCH-request-full.json`: rename `"isEnabled"` key to `"enabled"`

## 5. Tests — Update references to old key names

- [x] 5.1 Update `TestCaseFunctionalTests.java`: rename `includeIsEnabled` references in CSV export tests and `"isEnabled"` in PATCH body tests
- [x] 5.2 Update `TestCaseBatchPatchFunctionalTests.java`: rename `"isEnabled"` keys in batch patch request bodies to `"enabled"`
- [x] 5.3 Run full build (`./gradlew clean build`) to verify all tests pass and checkstyle is clean
