## 1. Database & jOOQ

- [x] 1.1 Add Flyway meta migration `src/main/resources/db/migration/meta/POSTGRES/V{next}__AddMultiStepToTestSuites.sql` adding `multi_step` BOOLEAN NOT NULL DEFAULT false and `multistep_input_bindings` JSONB (nullable) to `test_suites` (done: migration applies cleanly on a fresh DB and existing rows default to `multi_step=false`).
- [x] 1.2 Run `./gradlew generateJooq` and commit the regenerated `TestSuites` table sources under `src/main/java-generated/` (done: generated `TestSuites` has `MULTI_STEP` and `MULTISTEP_INPUT_BINDINGS` fields; `JooqSchemaDriftTest` passes).
- [x] 1.3 Update `docs/database-schema.md` with the two new `test_suites` columns (done: table reflects new columns + types).

## 2. Domain model, DTOs, mappers, snapshot

- [x] 2.1 Add `boolean multiStep` and `String multistepInputBindings` (JSON string) to `data.db.model.TestSuite` (done: pure carrier, no logic).
- [x] 2.2 Add a `List<List<InputBindingDto>>` ser/deser pair to `service.domain.mapper.JsonbMapper` (done: round-trips array-of-arrays; null/blank → null).
- [x] 2.3 Add `multiStep` + `multistepInputBindings` (`List<List<InputBindingDto>>`) to `TestSuiteRequestDto` and `TestSuiteResponseDto` with `@Valid`/`@Schema` annotations (done: fields bind and validate nested bindings).
- [x] 2.4 Update `service.domain.mapper.TestSuiteMapper` (toEntity/toDto/update) and `data.db.mapper.TestSuiteRecordMapper` to map the new fields (done: create/update/read preserve both fields).
- [x] 2.5 Add `multiStep` + `multistepInputBindings` to `SuiteSnapshotDto` (additive; `CURRENT_VERSION` stays `"2"`) and populate them in `SuiteSnapshotBuilder.build` for DEPLOYMENT suites (done: new snapshots carry the fields; legacy `"2"` snapshots deserialize with `multiStep=false`).

## 3. Validation

- [x] 3.1 Add `MAX_CONVERSATION_STEPS = 10` to `constants.ValidationConstants` (done: single source of truth).
- [ ] 3.2 Extend `service.domain.SuiteValidationService.validateDeploymentSuite` for `multiStep == true`: require JSON body with top-level `messages` array; require non-empty `multistepInputBindings` with size ≤ cap; validate each step's bindings via the existing `BindingValidator`; ignore single `inputBindings` (done: any violation adds a warning and `isValid=false`).

## 4. Multi-step execution

- [ ] 4.1 Add `snapshotMultiStep` (boolean) and `snapshotMultistepInputBindings` (`List<List<InputBindingDto>>`) fields to `service.domain.job.EvaluationContext`, and populate both in `TestSuiteEvaluationJob.buildContext` from the resolved `SuiteSnapshotDto` (alongside the existing `snapshotRequestTemplate`/`snapshotInputBindings` mappings) (done: `EvaluationWorker` can read the flag and per-step bindings via `context.getSnapshotMultiStep()` / `context.getSnapshotMultistepInputBindings()` without touching `SuiteSnapshotDto` directly).
- [ ] 4.2 Create injectable `service.domain.job.MultiStepConversationExecutor` implementing the turn loop: per step resolve template (`ResolvedRequestService.resolve`), append resolved `messages` to running history, overwrite body `messages` with full history, invoke non-streaming with per-step `invokeWithRetries`, extract `choices[0].message.content` → append assistant message, run `ResponseColumnExtractor.extract` and accumulate per-step array (done: returns one `TestCaseRunResult` with `responseBody`=accumulated messages, `extractedColumns`=per-step array).
- [ ] 4.3 Implement fail-fast in the executor: stop on a step's retry-exhausted failure or unextractable assistant content; persist partial history + partial per-step extractions; set `executionStatus`/`responseStatusCode` from the failing step; `traceId` = last attempted step (done: matches fail-fast scenarios).
- [ ] 4.4 Branch `EvaluationWorker.execute` on `context.getSnapshotMultiStep()`: delegate to `MultiStepConversationExecutor` (passing `context.getSnapshotMultistepInputBindings()`) when true, keep existing single path when false; one semaphore permit per conversation (no change to `InProcessEvaluationExecutor` permit granularity) (done: single-step path unchanged; multi-step delegates).

## 5. Metric normalization

- [ ] 5.0 Create a shared injectable `service.domain.job.ExtractedColumnsNormalizer` `@Component` that applies shape detection to an `extractedColumns` value: JSON array → last element (`array[n-1]`); empty array (`n == 0`) → empty JSON object `{}` (no throw, no array); JSON object → unchanged (done: single source of truth used by both metric paths).
- [ ] 5.1 Normalize the metric-binding path: in `MetricEvaluationWorker.buildRequest`, run the `extractedColumns` value through `ExtractedColumnsNormalizer` before `BindingResolver.resolveBindings` (scoped to `extractedColumns` only; leave the sibling `testCaseData` parse untouched) (done: metric bindings resolve against the last step for multi-step; object unchanged for single-step; testCaseData unaffected).
- [ ] 5.2 Normalize the EvalSummary-copy path: in `InProcessMetricEvaluationExecutor.buildItem` (the private `parseJsonNode(result.getExtractedColumns())` site, which runs for both SUCCESS and propagated non-SUCCESS results), apply `ExtractedColumnsNormalizer` so the last-step object (or `{}` for an empty array) is stored into `EvalSummary.extractedColumns` (done: `EvalSummary` never stores an array; export/filter paths unchanged).

## 6. OpenAPI & docs

- [ ] 6.1 Add `@Schema(example=...)` for `multiStep` and `multistepInputBindings` on the request/response DTOs (done: Swagger shows a minimal multi-step example).

## 7. Tests

- [ ] 7.1 Unit tests for `JsonbMapper` `List<List<InputBindingDto>>` round-trip (done: covers null/empty/multi-step shapes).
- [ ] 7.2 Unit tests for `MultiStepConversationExecutor`: happy-path history accumulation, full-history resend, assistant append, per-step extraction array, fail-fast (mid-conversation HTTP failure and unextractable assistant content) (done: deterministic assertions, mocked invoker).
- [ ] 7.3 Unit tests for the metric shape-detection normalization (`ExtractedColumnsNormalizer`): array→last element; object→unchanged; length-1 array; length-0 (empty) array → empty object `{}` (done: covers length-1 array, length-0 array → `{}`, and object).
- [ ] 7.4 Unit tests for multi-step suite validation (empty bindings, over-cap, non-messages body, bad per-step binding) (done: each produces a warning + `isValid=false`).
- [ ] 7.5 Functional test (`@PostgresFunctionalTests`, boots context) exercising create → run of a multi-step suite against a mocked DIAL Core deployment, asserting `responseBody` = accumulated messages and `extractedColumns` = per-step array via repository (done: end-to-end path green).
- [ ] 7.6 Run `./gradlew spotlessApply checkstyleMain checkstyleTest test` and confirm green (done: build passes including the new tests).

## 8. Spec sync

- [ ] 8.1 Sync delta specs into `openspec/specs/` at archive time (`multi-step-conversation` added; `test-suites` and `eval-execution-engine` updated) and update `openspec/specs/README.md` per the Spec Index Maintenance Policy for the new `multi-step-conversation` folder (done: index lists the new spec, summaries accurate).
