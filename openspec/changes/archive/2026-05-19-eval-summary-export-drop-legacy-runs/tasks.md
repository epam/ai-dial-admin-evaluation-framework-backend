## 1. Service simplification

- [x] 1.1 In `EvalSummaryExportService` (`src/main/java/com/epam/aidial/evaluation/service/domain/analytics/EvalSummaryExportService.java`), remove the `TestSuiteRepository testSuiteRepository` and `SuiteSnapshotBuilder suiteSnapshotBuilder` fields, the matching constructor parameters, and the `TestSuite` / `TestSuiteRepository` / `SuiteSnapshotBuilder` imports.
- [x] 1.2 Rewrite `resolveSnapshot(TestSuiteRun run)` to throw `SnapshotSuiteMissingException` immediately when `run.getSuiteSnapshot()` is `null` or blank, with a message that names the offending `run.getId()` (no live-suite lookup, no `SuiteSnapshotBuilder.build`). Keep the existing version-check + `UnsupportedSnapshotVersionException` branch and the `JsonProcessingException` → `IllegalStateException` branch unchanged.
- [x] 1.3 Run Checkstyle: `./gradlew checkstyleMain` and confirm `EvalSummaryExportService` is clean (no unused-import warnings).

## 2. Tests

- [x] 2.0 In `MetaTestDataHelper`, update `createTestSuiteRun(UUID suiteId)` to snapshot the live suite's `testCaseSchema`/`responseColumns` into `suite_snapshot` (mirroring production's snapshot phase), and add `createLegacyTestSuiteRun(UUID suiteId)` for the one rejection test that needs a null snapshot. This keeps the existing 16-site `updateSuiteSchema(...)` → `createTestSuiteRun(...)` test pattern working without churning all call sites.
- [x] 2.1 + 2.2 Consolidate the two old legacy-run tests in `EvalSummaryExportFunctionalTests` into a single `exportLegacyRunReturns422` (uses `createLegacyTestSuiteRun`, drops the `forceRunTestSuiteIdBypassingFk` setup — live-suite state no longer matters).
- [x] 2.3 Added `previewLegacyRunReturns422` mirroring the export rejection on `GET /export/preview`.
- [x] 2.4 Verified `src/test/java/com/epam/aidial/evaluation/service/domain/analytics/` contains no mocks of `TestSuiteRepository` or `SuiteSnapshotBuilder` — nothing to drop.
- [x] 2.5 `./gradlew test --tests 'com.epam.aidial.evaluation.functional.PostgresFunctionalTests$EvalSummaryExportTests' --tests 'com.epam.aidial.evaluation.functional.PostgresFunctionalTests$EvalSummaryExportPageSizeTests'` → BUILD SUCCESSFUL, 22 tests, 0 failures.

## 3. OpenAPI surface

- [x] 3.1 In `EvalSummaryController` (`src/main/java/com/epam/aidial/evaluation/web/controller/EvalSummaryController.java`), updated both `@ApiResponse(responseCode = "422", …)` descriptions (one shared `replace_all` edit) to: "Run has no suite_snapshot (legacy runs are not exportable) or the snapshot version is not understood by the service".

## 4. Spec sync

- [x] 4.1 Synced delta into `openspec/specs/eval-summary-export/spec.md`: rewrote `Legacy run snapshot handling` requirement (2 scenarios) and removed the two legacy-related scenarios under `Preview endpoint`, replacing them with one `Preview rejects legacy runs` scenario. `Column set is derived from run snapshot` was intentionally not modified in the delta and stayed as-is in the main spec.
- [x] 4.2 Confirmed `openspec/specs/README.md:105-106` one-liner is unaffected — it never mentioned legacy-run handling.

## 5. Verification

- [x] 5.1 Constructor parameter list is fully vertical, one param per line, max line length 102 chars (well under the 180-char Checkstyle limit). Conforms to AGENTS.md formatting rule.
- [x] 5.2 `grep -n "TestSuiteRepository\|SuiteSnapshotBuilder" src/main/java/com/epam/aidial/evaluation/service/domain/analytics/EvalSummaryExportService.java` → empty (exit 1).
- [x] 5.3 `./gradlew build` BUILD SUCCESSFUL — 1578 tests, 0 failures. One pre-existing test in `PostgresTestSuiteRunRepositoryFunctionalTests` that explicitly verified null-snapshot retrieval was migrated to the new `createLegacyTestSuiteRun` helper.
