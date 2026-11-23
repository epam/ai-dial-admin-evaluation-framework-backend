## MODIFIED Requirements

### Requirement: Synthetic ERROR result for worker exception
When a test case worker throws an unexpected exception (an exception that escapes the worker's own internal handling and reaches the executor's per-task `catch (Exception e)`), the executor SHALL emit a synthetic `TestCaseRunResult` with `executionStatus = ERROR` to the result buffer in best-effort fashion. This makes per-case worker bugs visible in the analytics surface — without it, a buggy worker silently drops cases. The synthetic row SHALL carry every value the result store requires — in particular the run's suite id, empty extraction values, and the run's creation timestamp (as its `createdAtMs`), the same suite id and timestamp every other result row of the run carries — so that it can be persisted like any other result row and every run-scoped read (which selects a run's rows by that timestamp) returns it. The synthesis is best-effort: if appending the synthetic row to the buffer itself fails, the executor SHALL log the secondary failure and continue without further fallback. The executor SHALL NOT chain additional retry / fallback layers beyond the single best-effort attempt — the JVM may be in any state, and the next safe action is to keep going so other cases can still finish.
Status: **Planned**

#### Scenario: Worker throws unexpected exception
- **WHEN** the runnable inside `TestCaseRunner` catches an exception escaping `evaluationWorker.execute(...)`
- **THEN** the executor SHALL build a synthetic `TestCaseRunResult` via `TestCaseRunResultFactory.errorResult(...)` with `executionStatus = ERROR`, `responseBody` set to a JSON error envelope `{"error":{"type":"<exception class name>","message":"<exception message>","origin":"executor"}}`, `responseStatusCode = null`, `execStartedAtMs = execCompletedAtMs = clock.millis()`, `execDurationMs = 0`, `retryCount = 0`, `logDetails = null`, empty extraction values (`extractedColumns = "{}"`, `extractionWarnings = "[]"`), `testSuiteId` equal to the run's suite id, and `createdAtMs` equal to the run's creation timestamp
- **AND** the executor SHALL pass that result to `resultsWriter.addResults(List.of(synthetic))` so it is persisted alongside other results

#### Scenario: Synthetic row is persisted without losing other rows
- **WHEN** a synthetic `ERROR` row is written in the same batch as result rows of other test cases
- **THEN** the synthetic row and the other rows SHALL all be persisted

#### Scenario: Synthetic row is returned by run-scoped reads
- **WHEN** a run has produced a synthetic `ERROR` row and its results are read scoped to that run
- **THEN** the synthetic row SHALL be returned together with the run's other result rows

#### Scenario: Synthetic row reaches metric evaluation
- **WHEN** a run that produced a synthetic `ERROR` row for a test case completes
- **THEN** that test case SHALL have an eval summary row for the run with a non-SUCCESS `execution_status`

#### Scenario: Per-case error does not fail the run
- **WHEN** one or more synthetic `ERROR` rows are produced for a run because workers threw unexpected exceptions
- **THEN** the run SHALL still reach `COMPLETED` status (per-case errors do NOT mark the whole run FAILED)

#### Scenario: TestCaseRunResultFactory must not throw
- **WHEN** the executor invokes `TestCaseRunResultFactory.errorResult(...)`
- **THEN** the factory SHALL build the synthetic row from the input, the run index, the caught exception, the run's suite id and creation timestamp, and the current clock millis via fixed-shape construction — no template resolution, no JSON parsing of test case data, no DB access — guaranteeing it cannot throw and double-drop the case

#### Scenario: Buffer append failure is logged, not retried
- **WHEN** the inner `resultsWriter.addResults(List.of(synthetic))` call itself throws (e.g., the buffer's downstream batch flush triggered by reaching `result-batch-size` threshold fails)
- **THEN** the executor SHALL log the secondary failure at `ERROR` level (with the exception as last SLF4J argument) and continue. It SHALL NOT raise, SHALL NOT retry, and SHALL NOT make a second synthesis attempt.

#### Scenario: Broad catch is intentional
- **WHEN** static analysis or code review questions the broad `catch (Exception e)` in the worker runnable
- **THEN** the catch IS intentional and documented as the deliberate exception to AGENTS.md's "catch specific exceptions" rule. The contract of this catch is "any unexpected runtime failure escaping the worker" — narrowing it would re-introduce the silent-drop bug that this requirement exists to prevent. The catch SHALL remain `catch (Exception e)`.

**Implementation notes:** `TestCaseRunner.runWorker` and `TestCaseRunResultFactory.errorResult` (`evaluation-runner-core`, `com.epam.aidial.evaluation.runner.job`); the run's suite id and creation timestamp are `EvaluationContext.getSuiteId()` / `getCreatedAtMs()`. Results are written by `PostgresTestCaseRunResultRepository.saveAll` (requires a suite id) and run-scoped reads filter on `created_at_ms = <run created_at_ms>` in the same repository.
