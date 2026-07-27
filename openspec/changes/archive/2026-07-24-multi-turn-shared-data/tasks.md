## 1. Schema field + migration

- [x] 1.1 Add `perTurn` (nullable `Boolean`, absent ⇒ shared) to `FieldDefinitionDto` with `@Schema` doc; serializes into/out of `test_case_schema` JSONB via Jackson. (Nullable `Boolean`, not primitive, so pre-existing schemas omitting the field deserialize cleanly.)
- [x] 1.2 Dataset schema validation accepts `perTurn` as a boolean via Jackson/bean validation (non-boolean → 400); no extra constraint needed.
- [x] 1.3 Edited `V1.27__AddMultiTurnDataToTestCases.sql` to keep the `multi_turn_data JSONB` column and remove the `chk_test_cases_multi_turn_exclusive` CHECK.
- [x] 1.4 Added `TestCaseFieldScopeResolver` (`@Component` in `service.domain`): shared/per-turn field-name sets, sub-schema splits, and flat-map partition — single source used by validation, executor scope, and CSV. Unit-tested.

## 2. Validation (placement 400 + per-scope content warnings)

- [x] 2.1 `MultiTurnFieldsValidator`: dropped mutual-exclusivity; kept empty-array 400; added scope-placement 400s (per-turn field in `data`, shared field in a turn map). Unit tests: coexist-OK, misplaced-per-turn-400, misplaced-shared-400, empty-array-400.
- [x] 2.2 `TestCaseValidationService.validateMultiTurn(sharedData, turns, schema, …)`: validates shared against `data` (shared sub-schema) and per-turn against each turn (per-turn sub-schema); per-turn warnings carry turnIndex; all-empty turns valid without required per-turn field. Unit tests added.
- [x] 2.3 `TestCaseService.runValidation` passes shared `data` + turns into the scope-aware path; placement validated against the dataset schema.

## 3. PATCH independence

- [x] 3.1 `TestCaseService.applyMergePatch`: removed opposite-field clearing — `data` and `multiTurnData` patch independently; `multiTurnData: null` reverts to single-turn; validation runs after merge.

## 4. Execution merged view

- [x] 4.1 `MultiTurnExecutor`: each turn resolves the template against the merged effective view `merge(shared data, turn[i])` (per-turn wins), shared sourced from the snapshot `testCaseData`. Functional test asserts a shared field appears in every turn's request body.
- [x] 4.2 Achieved without touching `ConditionExpressionEvaluator`: the merged view is persisted as each turn row's `testCaseData`, which is exactly the source of both the condition JSONata `data` namespace and the metric input — so shared fields are visible to conditions/metrics with no evaluator change.

## 5. Scope-aware filtering

- [x] 5.1 `TestCaseFieldBindingsBuilder.buildScoped(datasetId, elem)`: per-turn fields bind to `elem`, shared fields to `TEST_CASES.DATA` (consulting `perTurn`).
- [x] 5.2 `QueryDslRunnableTestCaseSelector.compile()` uses `buildScoped`; functional test with a shared-field filter selects at case level (excludes the non-matching case), confirming correlation inside the `NOT EXISTS` lateral.
- [~] 5.3 DEFERRED — annotating `TestCasesSchemaProvider.detailedSchema` with scope requires adding a component to the `QuerySchemaFieldDto` record, which breaks ~68 construction sites + OpenAPI. It is discovery-only metadata (not needed for correct filtering, which 5.1/5.2 deliver). Skipped intentionally.

## 6. CSV shared columns

- [x] 6.1 `CsvExportService`: multi-turn export merges shared `data` into every turn row (shared columns repeat; per-turn vary; single-turn unchanged).
- [x] 6.2 `CsvImportService`: partitions each row by dataset-schema scope (re-applying persisted `perTurn` onto the OVERRIDE-rebuilt validation schema via `applyScopeFromDataset`), requires a case's shared columns identical across rows (mismatch → conflict warning + invalidate), assembles per-turn columns into `multiTurnData` and shared into `data`. Round-trip + assemble functional tests pass.

## 7. Docs, OpenAPI, spec sync

- [x] 7.1 Updated multi-turn OpenAPI examples (POST request/response) to show shared `data` + per-turn `multiTurnData` coexisting; added `perTurn` to the dataset schema examples; `@Schema` on `FieldDefinitionDto.perTurn`.
- [x] 7.2 `docs/database-schema.md`: removed the `chk_test_cases_multi_turn_exclusive` CHECK from the `test_cases` row and the V1.27 migration note; documented coexistence + scope.
- [x] 7.3 `AGENTS.md`: rewrote the multi-turn inline convention (shared vs per-turn scope, coexistence, merged view, scope-aware bindings, placement 400) and the filter bullet (`buildScoped`).
- [x] 7.4 `openspec/specs/README.md`: refreshed the multi-turn-conversation summary (coexistence/scope/merged view; added `datasets` relation).

## 8. Verification

- [x] 8.1 New unit tests green: `TestCaseFieldScopeResolverTest`, `TestCaseValidationServiceMultiTurnTest`, `MultiTurnFieldsValidatorTest`, `TestCaseValidationServiceTypeTest`, `CsvImportServiceSchemaTest`.
- [x] 8.2 Multi-turn functional suites green: `MultiTurnRunTests`, `MultiTurnFilterTests`, `MultiTurnCsvTests`, and the new `MultiTurnSharedDataTests` (shared-visible-every-turn, shared-field filter, placement 400).
- [x] 8.3 `./gradlew build` SUCCESSFUL — 2090 tests, Spotless, Checkstyle, ArchUnit/LayeredArchitecture, JooqSchemaDrift, LoggingConvention all pass.
