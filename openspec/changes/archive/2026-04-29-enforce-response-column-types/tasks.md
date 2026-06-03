## 1. Domain types and reconciler

- [x] 1.1 Add `service/domain/exception/TypeMismatchException.java` extending `RuntimeException` with constructors `(SchemaFieldType expected, String actualTypeLabel)` and `(SchemaFieldType expected, String actualTypeLabel, Object actualValue, String suffix)`. Message format: `Type mismatch: expected <EXPECTED>, got <ACTUAL>` and `Type mismatch: expected <EXPECTED>, got <ACTUAL> ("<truncated value, ≤80 chars>") — <suffix>`. (done: class compiles, used by reconciler)
- [x] 1.2 Add `service/domain/ResponseColumnTypeReconciler.java` (`@Component`, `@LogExecution`, `@Slf4j`) with method `Object reconcile(Object jsonataResult, SchemaFieldType declaredType)` implementing the coercion table from `design.md` (D2). Operate on raw Java types (`String`, `Number`, `Boolean`, `Map`, `List`, `null`) — no Jackson conversion inside. (done: class compiles, all branches covered by unit tests in 4.1)
- [x] 1.3 Truncate value preview to ≤80 characters using a private helper that calls `String.valueOf(value)` and substrings. (done: covered by INTEGER+non-parseable scenario in 4.1)

## 2. Wire reconciler into extractor

- [x] 2.1 Inject `ResponseColumnTypeReconciler` into `service/domain/ResponseColumnExtractor.java` via the existing `@RequiredArgsConstructor`. (done: field added, Spring context still starts in 5.2)
- [x] 2.2 In `ResponseColumnExtractor.extract()`, call `typeReconciler.reconcile(value, col.getType())` between `jsonataEvaluationService.evaluate(...)` and the `extracted.set(...)` call. The existing `catch (Exception ex)` block already handles `TypeMismatchException` — verify by inspection, no change to the catch. (done: bug-fix scenario passes in 4.2)
- [x] 2.3 Confirm the existing `log.warn("Extraction failed for column '{}': {}", col.getName(), ex.getMessage(), ex)` complies with SLF4J convention (exception as last arg). No change expected. (done: visual review)

## 3. Apply reconciliation contract uniformly

- [x] 3.1 Verify both DEPLOYMENT and MCP_TOOL evaluation paths route through `ResponseColumnExtractor.extract()` (search for `ResponseColumnExtractor` usages). No new wiring expected — flag if a second call site exists. (done: grep shows the extractor is the single chokepoint, or follow-up tasks added)

## 4. Tests

- [x] 4.1 Unit test: `service/domain/ResponseColumnTypeReconcilerTest.java` covering every cell of the coercion table from `design.md` (D2) — at minimum: ARRAY+scalar→singleton, ARRAY+null→null, ARRAY+array→as-is, ARRAY+object→singleton, STRING+number→`String.valueOf`, STRING+array→throw, INTEGER+wholeDouble→Long, INTEGER+fractionalDouble→throw, INTEGER+parseableString→Long, INTEGER+nonParseableString→throw with value preview, NUMBER+parseableString→Double, BOOLEAN+"true"/"false"→Boolean (case-insensitive), OBJECT+scalar→throw, OBJECT+object→as-is, declared-type-null→pass-through. Use plain JUnit 5 (no Spring context). (done: `./gradlew test --tests "*.ResponseColumnTypeReconcilerTest"` green)
- [x] 4.2 Unit test: extend `service/domain/ResponseColumnExtractorTest.java` (or create if absent) with scenarios — (a) ARRAY column + JSONata returning single match → singleton stored, no warning; (b) STRING column + array result → null stored, warning emitted with `expected STRING, got ARRAY`; (c) NUMBER column + parseable string → coerced silently; (d) **catch-block propagation guard**: with a mocked `ResponseColumnTypeReconciler` that throws `TypeMismatchException`, assert the existing `catch (Exception ex)` block in `ResponseColumnExtractor.extract()` swallows it, stores `null` for the cell, and appends an `ExtractionWarningDto` with the exception's message — this guards against future narrowing of the catch (per design D3). Use Mockito for `JsonataEvaluationService` and a real `ResponseColumnTypeReconciler` for (a)-(c); a Mockito mock of the reconciler for (d). (done: `./gradlew test --tests "*.ResponseColumnExtractorTest"` green)
- [x] 4.3 Functional test (regression for issue #883): add a nested test class to `PostgresFunctionalTests` that wires a TestSuite with one ARRAY response column whose JSONata expression returns a single match, runs metric evaluation against a stub metric provider that asserts `input.<param>` is a JSON array (not a scalar), and asserts the test case run result has the singleton list in `extracted_columns` and no extraction warning. (done: `./gradlew test --tests "*PostgresFunctionalTests*<NestedTests>*"` green; Spring context boots)
- [x] 4.4 Scoped CSV-import probe test: in `CsvImportServiceTest` (or the closest existing test class) add a `shouldDocumentArrayCellHandling` test that imports a CSV row with a single value into an ARRAY-typed schema column and asserts the *current* persisted shape (whatever it is). The test serves as a tripwire — if behaviour later changes, it surfaces. Add a comment linking back to this change. (done: test passes against current behaviour and documents the observed shape)

## 5. Verification

- [x] 5.1 Run `./gradlew checkstyleMain checkstyleTest` — must be clean (no FQN usages, no line-length violations in new files). (done: command exits 0)
- [x] 5.2 Run the full test suite `./gradlew test` to confirm no regressions in unrelated suites — particularly `MetricEvaluationWorker`-adjacent tests, `BindingResolver` tests, and any existing `ResponseColumnExtractor` tests. (done: command exits 0)
- [x] 5.3 Run `./gradlew clean build` end-to-end. (done: command exits 0)

## 6. Spec sync and docs

- [x] 6.1 After implementation passes 5.x, run `/opsx:sync` (or `openspec sync`) to fold the delta from `openspec/changes/enforce-response-column-types/specs/response-columns/spec.md` into `openspec/specs/response-columns/spec.md` — switch the new requirements' Status from `Planned` to `Implemented`. (done: main spec contains the new requirements at `Implemented` status)
- [x] 6.2 No `docs/configuration.md` update (no new properties).
- [x] 6.3 No `docs/database-schema.md` update (no migrations).
- [x] 6.4 No `openspec/specs/README.md` update — the `response-columns` spec folder already exists and the one-line summary remains accurate.
- [x] 6.5 No `AGENTS.md` update — this change adds a component within the existing `service.domain.*` pattern; no new architectural layer, qualifier, or convention.
- [x] 6.6 No `openspec/config.yaml` update — change follows existing rules (D1 in `design.md` confirms the litmus test).
