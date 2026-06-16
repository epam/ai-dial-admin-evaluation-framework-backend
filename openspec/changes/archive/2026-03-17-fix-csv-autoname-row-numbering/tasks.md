## 1. Core Implementation

- [x] 1.1 In `CsvImportService.parseRow()`, add a `padWidth` parameter and change auto-name from `"Row " + rowNumber` to `String.format("Row %0" + padWidth + "d", dataRowIndex)` (done: naming uses 1-based padded index)
- [x] 1.2 In `CsvImportService.preview()`, compute `padWidth` from `csvImportProperties.getMaxRows()`, add a `dataRowIndex` counter starting at 0 and incremented per data row, pass both to `parseRow()` (done: preview shows "Row 000001" for first data row)
- [x] 1.3 In `CsvImportService.importCsv()`, apply the same `padWidth` and `dataRowIndex` changes as preview (done: import produces "Row 000001" for first data row)

## 2. Tests

- [x] 2.1 Update functional tests in `TestCaseFunctionalTests` and `CsvImportModeFunctionalTests` that assert auto-generated names to expect the new padded format (done: no existing tests assert auto-generated names — all provide explicit testCaseName values)
- [x] 2.2 Run `./gradlew checkstyleMain checkstyleTest` and `./gradlew test` (done: clean build)
