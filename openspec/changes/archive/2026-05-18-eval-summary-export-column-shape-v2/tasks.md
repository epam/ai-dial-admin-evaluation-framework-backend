## 1. Constants and row-side routing

- [x] 1.1 Create `com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants` (final class, private constructor) exposing `COLUMN_SEPARATOR = ":"`, `DATA_COLUMN_PREFIX`, `RESPONSE_COLUMN_PREFIX`, `METRIC_COLUMN_PREFIX`, `METRIC_INFO_COLUMN_PREFIX`, `METRIC_ERROR_COLUMN_PREFIX` (done: class compiles, used by the planner in task 2.x).
- [x] 1.2 Add `JsonNode metricInfo(String metricName, String fieldName, Set<String> schemaFieldKeys)` to `EvalSummaryExportRow` implementing the per-field routing rule from `design.md` §Decisions / 8 (done: unit-tested via task 4.1).
- [x] 1.3 Add `JsonNode metricWholesaleError(String metricName, Set<String> schemaFieldKeys)` to `EvalSummaryExportRow` implementing the wholesale-error routing rule (done: unit-tested via task 4.1).
- [x] 1.4 Inspect `EvalSummaryExportRow` callers — confirm no production code depends on the existing `metricInfos()` accessor being wired to a CSV descriptor; keep the accessor as an internal helper used by the two new methods (done: grep `metricInfos\\(\\)` shows callers limited to row internals or removed).

## 2. Column planner

- [x] 2.1 Update `EvalSummaryExportColumnPlanner` to use `EvalSummaryExportColumnConstants` for every `data:`, `response:`, `metric:`, `metricInfo:`, `metricError:` prefix; remove the three `"."` literals (done: no `"."` literals remain in the file; checkstyle passes).
- [x] 2.2 Replace the single `metricInfos` plain descriptor with: (a) per-field `metricInfo:<m>:<f>` descriptors driven by `OutputSchemaFieldExtractor`, capturing the per-metric field-key `Set<String>` once and closing it into each descriptor; (b) one `metricError:<m>` descriptor per snapshot closing the same field-key set (done: planner emits the manifest order specified in `design.md` §Decisions / 6).
- [x] 2.3 Verify the planner-output cap (`MAX_EXPORT_COLUMNS`) still fires correctly for the new wider manifest — run the existing planner-cap fixture in `EvalSummaryExportColumnPlannerTest` and confirm it asserts on a count consistent with `2K + 1` (done: existing fixture updated to reflect the new column-count math; failure path still returns the offending count vs. cap).

## 3. Selector, DTO, and OpenAPI surface

- [x] 3.1 Confirm `EvalSummaryExportColumnSelector` requires no logic change — name-based exact lookup flows through; only update any fixture column-name strings used in its unit tests (done: tests pass with new names).
- [x] 3.2 Refresh `EvalSummaryExportRequestDto` `@Schema(example=…)` snippets that mention `data.foo` / `Accuracy.score` / `metricInfos` to the new `data:foo` / `metric:Accuracy:score` / `metricInfo:Accuracy:score` / `metricError:Accuracy` shape (done: openspec examples spec scenario for the request DTO compiles; `./gradlew checkstyleMain` passes).
- [x] 3.3 Refresh `@ExampleObject` JSON on `EvalSummaryController.exportCsv` and `EvalSummaryController.previewExport` to reflect the new header line and the new preview headers manifest (done: Swagger UI shows the new examples; openapi-examples spec untouched structurally).
- [x] 3.4 Refresh any preview/export response JSON files under `src/main/resources/openapi/examples/` that hardcode old column names (done: grep for `data\\.|response\\.|"metricInfos"` returns no matches under that directory).

## 4. Tests

- [x] 4.1 Add unit tests on `EvalSummaryExportRow` covering: per-field success with details, per-field error envelope, partial per-field map, wholesale-error envelope (object with no schema-key overlap), non-object metricInfos entry (string/array/null), empty `metricInfos`. Use `Clock.fixed` for any timestamp-touching fixture if needed (done: `./gradlew test --tests "*.EvalSummaryExportRowTest"` passes).
- [x] 4.2 Update `EvalSummaryExportColumnPlannerTest` fixtures and assertions: header now uses `:` separator; metric value columns are `metric:<m>:<f>`; manifest contains `metricInfo:<m>:<f>` × N and `metricError:<m>` × 1 per metric; no `metricInfos` plain column; identity columns and `extractionWarnings` keep camelCase (done: `./gradlew test --tests "*.EvalSummaryExportColumnPlannerTest"` passes).
- [x] 4.3 Update `EvalSummaryExportServiceTest` (CSV cell + preview cell rendering) for new column names; add cases asserting that per-field error envelopes and wholesale errors route to the correct columns (done: `./gradlew test --tests "*.EvalSummaryExportServiceTest"` passes).
- [x] 4.4 Update `PostgresFunctionalTests$EvalSummaryExportFunctionalTests`: seed three eval-summary rows (per-field success with details, per-field error, wholesale error); round-trip `POST /export.csv` and `GET /export/preview`; assert exact CSV header line, sample cells per row class, and preview headers/typed cells (done: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\\$EvalSummaryExportFunctionalTests"` passes).

## 5. Spec sync and verification

- [x] 5.1 Run `./gradlew checkstyleMain checkstyleTest` and resolve any 180-char or FQN violations introduced by the new column-name strings (done: both tasks succeed).
- [x] 5.2 Run `./gradlew test` once end-to-end to surface any cross-cutting fixture that still asserts the old shape (done: full suite green).
- [x] 5.3 At archive time, delta-sync the change's `specs/eval-summary-export/spec.md` into `openspec/specs/eval-summary-export/spec.md` per the project archive checklist (`MODIFIED` requirements overwrite their counterparts; `ADDED` requirements appended in the canonical ordering) (done: post-archive `openspec/specs/eval-summary-export/spec.md` carries the new column-shape requirements and no stale dot-separator language).
