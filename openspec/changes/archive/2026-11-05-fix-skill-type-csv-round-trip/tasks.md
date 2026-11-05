## 1. Inference component

- [x] 1.1 Create `src/main/java/com/epam/aidial/evaluation/service/domain/csv/CsvColumnTypeInferrer.java` (`@Component`, `@LogExecution`, `@RequiredArgsConstructor`; collaborators `ObjectMapper`, runner-core `SkillRefResolver`). Move `inferCellType` there verbatim as `public SchemaFieldType inferCellType(String trimmedValue)` and add a `SKILL` branch after NUMBER and before the STRING fallback: `skillRefResolver.validate(value).isEmpty()` → `SKILL` (design D1/D2). Move `widenType` verbatim as `public static SchemaFieldType widen(SchemaFieldType a, SchemaFieldType b)` (D3). Class javadoc states the branch order and the widening table. (done: class compiles; no other behaviour change)
- [x] 1.2 `service/domain/CsvImportService.java`: inject `CsvColumnTypeInferrer`, and have `updateInferredTypes` / `updateInferredTypesForNewFields` delegate to `inferCellType` / `widen`. Delete the private `inferCellType` / `widenType` and the now-unused "Type inference" section. (done: no private inference left in `CsvImportService`; `./gradlew compileJava` green)
- [x] 1.3 Add the new constructor argument to the manual `new CsvImportService(...)` calls in `CsvImportServiceSchemaTest` and `CsvImportServiceSchemaHintsTest`, using a real `new CsvColumnTypeInferrer(objectMapper, new SkillRefResolver(new BindingTypeResolver()))`, not a mock, so inference is exercised. (done: both classes compile and their existing tests pass unchanged)
- [x] 1.4 New `src/test/java/com/epam/aidial/evaluation/service/domain/csv/CsvColumnTypeInferrerTest.java`. Regression cases for OBJECT / ARRAY / invalid-JSON → STRING / BOOLEAN (`TRUE`, not `1`) / INTEGER / NUMBER / STRING. SKILL cases: `skills/b/x`, `skills/b/team/x`, trailing `/` → SKILL. Not SKILL: `skills/b`, `skills/b/`, `Skills/b/x`, `files/b/x`, `/skills/b/x`, `skills/b/../x`, `skills/b//x` → STRING. `widen`: SKILL+SKILL → SKILL, SKILL+STRING → STRING, SKILL+INTEGER → STRING, INTEGER+NUMBER → NUMBER. (done: test passes)
- [x] 1.5 Run `./gradlew :test --tests "com.epam.aidial.evaluation.service.domain.csv.CsvColumnTypeInferrerTest" --tests "com.epam.aidial.evaluation.service.domain.CsvImportServiceSchemaTest" --tests "com.epam.aidial.evaluation.service.domain.CsvImportServiceSchemaHintsTest"`, then `./gradlew spotlessApply checkstyleMain checkstyleTest`. (done: all green — `CsvColumnTypeInferrerTest` 32, `CsvImportServiceSchemaTest` 36, `CsvImportServiceSchemaHintsTest` 20, 0 failures; spotless/checkstyle clean. Note: use `:test` — an unscoped `test` with `--tests` fails on `eval-cli` with "No tests found")

## 2. Import-path unit coverage

- [x] 2.1 `CsvImportServiceSchemaTest`, covering the `test-cases` spec scenarios:
  - OVERRIDE / empty-schema auto-detect of a column holding only well-formed refs plus blanks → `SKILL`, values stored verbatim
  - a skill-ref + `hello` column → `STRING`
  - an all-`skills/b` column → `STRING`
  - MERGE new column of skill refs → `SKILL` in the delta
  - APPEND into a schema declaring the column `STRING` → stays `STRING`
  - OVERRIDE into a schema declaring the column `STRING` → re-derived as `SKILL`
  - blank cells between skill refs do not demote the column from `SKILL`
  - preview `autoDetectedSchema` (and `detectedColumns[].inferredType`) lists `SKILL`

  (done: each scenario has a test whose `@DisplayName` matches the assertion)
- [x] 2.2 `CsvImportServiceSchemaHintsTest`:
  - manifest-less hints: a `fileColumns` column is still `FILE` and a skill-ref column in the same CSV is `SKILL`
  - a manifest declaring the skill-ref column as `STRING` beats the inferred `SKILL` (design D4 precedence)

  (done: tests pass)
- [x] 2.3 `CsvSchemaFieldBuilderTest`: re-title `columnIsNeverTypedSkillWithoutManifest` so it describes what it asserts (the builder passes the supplied inferred type through; no `fileColumns`/manifest override). Add a case where `types` maps the column to `SKILL` → emitted `SKILL`. Review the `CsvSchemaFieldBuilder` javadoc for any "never SKILL" or FILE-only-precedence wording and align it with D4 (javadoc only, no code change). (done: test passes; javadoc accurate)
- [x] 2.4 Run `./gradlew :test --tests "com.epam.aidial.evaluation.service.domain.CsvImportServiceSchema*" --tests "com.epam.aidial.evaluation.service.domain.csv.*"`. (done: all green — 298 tests, 0 failures; `CsvImportServiceSchemaTest` 44 (+8), `CsvImportServiceSchemaHintsTest` 22 (+2), `CsvSchemaFieldBuilderTest$BuildFromBindingsWithHints` 9 (+1); checkstyle clean)

## 3. Functional tests, spec finalisation, verification

- [x] 3.1 `functional/tests/SkillFieldFunctionalTests.java`:
  - invert `csvSchemaDetectionDoesNotInferSkillType` into `csvSchemaDetectionInfersSkillType` (OVERRIDE into an empty dataset → `SKILL`, value verbatim, test case `valid` with no warnings)
  - add a mixed-column → `STRING` test
  - add a plain-CSV round trip: a dataset with `SKILL` + `INTEGER` columns, export `/datasets/{id}/test-cases/export.csv` (assert `text/csv`), import the body into a new dataset in OVERRIDE mode, assert both types and the verbatim `SKILL` values
  - add a preview test asserting `autoDetectedSchema` reports `SKILL`

  Reuse the existing `newDataset` / `importCsv` / `typeOf` helpers. (done: tests written — inverted test renamed `csvSchemaDetectionInfersSkillType`; added `csvSchemaDetectionWidensMixedSkillColumnToString`, `plainCsvRoundTripRestoresSkillAndIntegerTypes`, `importPreviewReportsInferredSkill`)
- [x] 3.2 Run the functional suites: `./gradlew :test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$SkillFieldTests" --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$CsvImportModeTests" --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$CsvImportPlainGoldenTests" --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$ZipTestCaseImportTests" --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$ZipRoundTripTests"`. These boot the context with the new bean. (done: all green — SkillFieldTests 16, CsvImportModeTests 36, CsvImportPlainGoldenTests 36, ZipTestCaseImportTests 20, ZipRoundTripTests 8; 0 failures)
- [x] 3.3 Finalise the delta specs in `openspec/changes/fix-skill-type-csv-round-trip/specs/`: set "SKILL columns in CSV import and export" (`test-cases`) and the `dial-skill-ref` requirement to `Status: **Implemented**`. Leave "CSV bulk upload with schema detection" at its baseline `Planned` status; this change only edits its "Auto-detect type inference" scenario, and the `Planned` reflects unrelated pending work. Make the implementation notes and test names match the landed code (drop "Planned:" wording). Then hand-edit the baseline `openspec/specs/test-cases/spec.md` "Type System Reference" table, which delta sync cannot reach: `SKILL` → `Yes (every non-blank cell a well-formed skill reference)`, and correct the stale `OBJECT` / `ARRAY` rows to `Yes (valid JSON object/array)` to match `CsvColumnTypeInferrer`. (done: `openspec validate fix-skill-type-csv-round-trip --strict` passes; table matches `CsvColumnTypeInferrer`)
- [x] 3.4 Full checks: `./gradlew spotlessApply checkstyleMain checkstyleTest` and `./gradlew :test --tests "com.epam.aidial.evaluation.architectural.LayeredArchitectureTest" --tests "com.epam.aidial.evaluation.architectural.LoggingConventionTest"`. No AGENTS.md, `openspec/config.yaml` or `openspec/specs/README.md` update is needed: this follows existing patterns and no spec index entry changes. Re-confirm that and note it here. (done: all green — spotless/checkstyle clean, LayeredArchitectureTest 1, LoggingConventionTest 2, 0 failures. Confirmed: no AGENTS.md / config.yaml change (new component follows the existing injectable-converter pattern); specs/README.md `dial-skill-ref` and `test-cases` summaries remain accurate)
