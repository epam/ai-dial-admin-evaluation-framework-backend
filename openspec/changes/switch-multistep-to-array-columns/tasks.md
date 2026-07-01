## 1. Drop the column (schema + jOOQ)

- [x] 1.1 Add Flyway migration `src/main/resources/db/migration/meta/POSTGRES/V1.24__DropMultistepInputBindingsFromTestSuites.sql` dropping `test_suites.multistep_input_bindings` (done: file present, SQL drops the column)
- [x] 1.2 Run `./gradlew generateJooq` and commit the regenerated `src/main/java-generated/.../jooq/meta/tables/TestSuites.java` + `TestSuitesRecord.java` (done: `MULTISTEP_INPUT_BINDINGS` no longer present in generated sources)
- [x] 1.3 Update `docs/database-schema.md`: remove the `multistep_input_bindings` column row; add the `V1.24` migration-history row; **and fix the two now-stale descriptions** — the `input_bindings` row (drop "Ignored when `multi_step = true`") and the `multi_step` row (replace "drives a scripted conversation via `multistep_input_bindings` and ignores `input_bindings`" with: uses the single `input_bindings`; turns derived per test case from array-valued bound columns) (done: no `multistep_input_bindings` reference and both descriptions reflect the new model)

## 2. Remove the field from model / data access / transport

- [x] 2.1 Remove `multistepInputBindings` from `data/db/model/TestSuite.java` and `data/db/mapper/TestSuiteRecordMapper.java` (done: field and mapping gone)
- [x] 2.2 Remove the two `.set(...)` writes for the column at all 3 write sites in `data/db/repository/PostgresTestSuiteRepository.java` (done: no reference to the column)
- [x] 2.3 Remove the `multistepInputBindings` field (and its `@Schema`/`@Size`/`@Valid`) from `service/domain/dto/TestSuiteRequestDto.java` and `TestSuiteResponseDto.java`; **and rewrite the retained `multiStep` field's `@Schema` description on `TestSuiteRequestDto`** (currently "drives a scripted conversation via multistepInputBindings and ignores inputBindings") to reflect the new model: multi-step uses the single `inputBindings`; per-turn variation comes from array-valued test-case columns (done: DTOs no longer expose `multistepInputBindings` and the `multiStep` description is accurate)
- [x] 2.4 Remove the field mapping from `service/domain/mapper/TestSuiteMapper.java` (toDto/toEntity/update/toRequestDto/toCloneEntity incl. clone file-ref rewrite) (done: mapper compiles without the field)
- [x] 2.5 Remove `mapMultistepInputBindings(...)` overloads and `MULTISTEP_BINDING_LIST_TYPE` from `service/domain/mapper/JsonbMapper.java` (done: no multistep methods remain)

## 3. Remove from snapshot / execution context + reduce validation

- [x] 3.1 Remove `multistepInputBindings` from `service/domain/dto/SuiteSnapshotDto.java` and stop populating it in `service/domain/SuiteSnapshotBuilder.java` (keep `multiStep`; snapshot version stays `"2"`) (done: snapshot has only `multiStep`)
- [x] 3.2 Remove `snapshotMultistepInputBindings` from `service/domain/job/EvaluationContext.java` and its population in `service/domain/job/TestSuiteEvaluationJob.buildContext` (done: context field gone)
- [x] 3.3 Reduce `service/domain/SuiteValidationService.validateMultiStep`: for `multiStep == true` run the normal `bindingValidator.validate(variables, inputBindings, testCaseSchema, suiteId)` plus the messages-array body check; delete the non-empty/step-cap/per-step-loop logic and the `multistepInputBindings` references in the model-based `validateSuite(TestSuite,...)` overload (done: validation uses single bindings + messages check only; unused `ValidationConstants` import removed)

## 4. Rewrite executor turn derivation

- [x] 4.1 In `service/domain/job/MultiStepConversationExecutor.java`, source the effective single bindings (`input.getInputBindingsOverride()` else `context.getSnapshotInputBindings()`) instead of `snapshotMultistepInputBindings`; also refresh the class Javadoc (and any `execute(...)` param comments) to the data-driven per-test-case model and drop the stale "author-scripted"/"design D1–D5" references (done: no multistep-bindings reference; Javadoc reflects array-driven turns)
- [x] 4.2 Add a private `resolveTurnCount(bindings, data)` helper: `N` = common length of array-valued `dataField` columns; return an error signal for no-array, length-mismatch, or `N > MAX_CONVERSATION_STEPS` (done: helper covers all three cases, plus empty-array `N==0`)
- [x] 4.3 Add a private `projectTurnData(data, iteratingFields, i)` helper returning a per-turn data map (array fields → element `i`; scalars unchanged) and drive the loop `0..N-1` calling the existing `resolvedRequestService.resolve(template, bindings, perTurnData)` (done: loop uses projected data)
- [x] 4.4 On a turn-count error, persist a single `ERROR` `TestCaseRunResult` with a descriptive message and return without throwing (other cases proceed) (done: fail-fast returns ERROR result); keep last-turn `responseBody`, per-step `extractedColumns` array, retries, and history-resend unchanged; **retain the existing runtime "resolved body is not JSON with a top-level messages array → ERROR" guard branch**

## 5. Tests

- [x] 5.1 Rewrite `MultiStepConversationExecutorTest`: single-binding + array-column fixtures; cover happy path, scalar/constant broadcast, mismatched lengths → ERROR, no-array → ERROR, `N > cap` → ERROR, mid-conversation HTTP fail-fast (done: `./gradlew test --tests "...MultiStepConversationExecutorTest"` green)
- [x] 5.2 Update `SuiteValidationServiceMultiStepTest`: valid with single bindings + messages body; invalid on missing messages array and on bad bindings (done: test green)
- [x] 5.3 Rewrite the functional `MultiStepConversationRunFunctionalTests` (nested `MultiStepConversationRunTests`): dataset ARRAY column, single `inputBindings`, assert per-step extraction array + last-turn `responseBody`; add a case where two test cases (array lengths 2 and 3) run 2 and 3 turns (done: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$MultiStepConversationRunTests"` green)
- [x] 5.4 Remove or repurpose `JsonbMapperMultistepTest` (mapper method deleted) and fix any other tests referencing the removed field/constructor args (done: `JsonbMapperMultistepTest` deleted; grep confirms no other test referenced the removed symbols; full `./gradlew compileTestJava` clean)

## 6. Docs & spec sync

- [x] 6.1 Update the multi-step inline convention in `AGENTS.md` per AGENTS.md Maintenance guidelines: bindings come from the single `inputBindings`; per-turn variation from array-valued columns; turn count = per-test-case common array length; failure isolates the test case; **also correct the trailing clause that names `multistepInputBindings` as a `SuiteSnapshotDto` field → only `multiStep` is the additive snapshot field (version stays `"2"`)** (done: convention rewritten to the array-driven model, per-test-case turn count, fail-isolated-test-case, config-time validation scope; trailing clause now names only `multiStep`)
- [ ] 6.2 Sync delta specs into `openspec/specs/multi-step-conversation/spec.md` and `openspec/specs/test-suites/spec.md`, and update the `multi-step-conversation` summary in `openspec/specs/README.md` per the Spec Index Maintenance Policy — new summary wording: drop `multistepInputBindings`; suite uses the single `inputBindings`; turn count derived **per test case** from array-valued bound columns (scalars/constants broadcast); keep the result-shape/normalizer/fail-fast phrasing (done: main specs + index reflect the change) — performed at archive time via openspec-sync-specs
- [x] 6.3 Run `./gradlew spotlessApply` and a full `./gradlew build` to confirm compile, Checkstyle, and all tests pass end-to-end (done: `spotlessApply` clean; full `./gradlew build` BUILD SUCCESSFUL — compileJava, checkstyleMain/checkstyleTest, spotlessCheck, and the full `test` task all pass)
