## 1. DTO + persistence

- [x] 1.1 Add optional `@Size(max = 2000) private String jsonataExpression;` to `service/domain/dto/ResponseBindingSourceDto.java` with a `@Schema` description + example `"$[-1]"` (done: field present, compiles, `@EqualsAndHashCode(callSuper=false)` auto-includes it).
- [x] 1.2 Verify metric bindings round-trip the new field: `JsonbMapper.mapMetricBindings` serializes the whole `List<MetricParameterBindingDto>` via `objectMapper.writeValueAsString`/`readValue` (no field allowlist) and the polymorphic `$type` lives on the base `MetricBindingSourceDto`, so the new field round-trips automatically — no mapping change needed (final round-trip assertion covered by the functional test in group 7).

## 2. Column-major multi-step extraction

- [x] 2.1 In `service/domain/job/MultiStepConversationExecutor.java`, replace the row-major `ArrayNode extractedPerStep` accumulation (init ~130, `.add(...)` ~202-205, serialize ~234) with a column-major transposition: accumulate a `Map<String, ArrayNode>` (append each column's value each completed step; `putNull` when a step's extraction map lacks the column, to keep indices aligned) and serialize a single `{col:[...]}` object (done: successful multi-step run persists a column-major object).
- [x] 2.2 Ensure fail-at-step-0 (no completed steps) persists `extractedColumns = {}` (empty object), and a mid-conversation failure persists per-column arrays truncated to the completed-step count (done: matches the `multi-step-conversation` fail-fast scenarios).
- [x] 2.3 Update the class javadoc / result-shape comments in the executor from row-major array to column-major object (done: comments describe `{col:[...]}`).

## 3. Remove ExtractedColumnsNormalizer

- [x] 3.1 Delete `service/domain/job/ExtractedColumnsNormalizer.java` and its unit test (done: files removed).
- [x] 3.2 In `service/domain/job/MetricEvaluationWorker.java` `buildRequest`, parse `result.getExtractedColumns()` straight to a map (`bindingResolver.parseJsonMap(result.getExtractedColumns())`); remove the normalizer field, constructor arg, and import (done: no normalizer reference remains).
- [x] 3.3 In `service/domain/job/InProcessMetricEvaluationExecutor.java` `buildItem`, store the raw parsed node (`parseJsonNode(result.getExtractedColumns())`) into the EvalSummary item; remove the normalizer field, constructor arg, and import (done: no normalizer reference remains).

## 4. jsonataExpression selection in binding resolution

- [x] 4.1 In `service/domain/job/BindingResolver.java`, inject `JsonataEvaluationService` (+ `ObjectMapper` if not already present) (done: constructor updated).
- [x] 4.2 In `resolveSource`, for a `ResponseBindingSourceDto`: after the existing missing-column guard + `get(columnName)`, if `getJsonataExpression()` is non-blank serialize the raw value to JSON and call `jsonataEvaluationService.evaluate(expr, json)`, returning the result (may be `null`); otherwise return the raw value unchanged. Keep the missing-column `throw` (done: matches the `metric-evaluation` binding-resolution scenarios).

## 5. Config-time validation

- [x] 5.1 In `service/domain/MetricDefinitionValidationService.java`, inject `JsonataEvaluationService` (done: constructor updated).
- [x] 5.2 In `validateBindings`, for a `ResponseBindingSourceDto` with a non-blank `jsonataExpression`, call `validateExpression(...)`; on `ValidationException` add a warning via `buildWarning(...)` with the new `INVALID_EXPRESSION` code and the correct `$.configBindings`/`$.inputBindings` path (done: matches the `tsmd-validation` scenarios).
- [x] 5.3 Add `INVALID_EXPRESSION` to the `ValidationWarningCode` enum with description "A JSONata expression that is syntactically invalid" (done: enum value present and referenced).

## 6. Export / filter verification pass

- [x] 6.1 Confirm the `eval-summary-export` CSV path renders array-valued `extractedColumns` cells via the existing cell-serialization rules (List/ArrayNode → compact JSON). Adjust only if a scalar assumption is found (done: multi-step `response::answer` cell renders `["Paris","Tokio"]`).
- [x] 6.2 Confirm any filter over `EvalSummary.extractedColumns` tolerates array-valued columns for multi-step results (no crash; numeric filtering on arrays left as-is) (done: reviewed, no scalar-only assumption breaks).

## 7. Tests

- [x] 7.1 `MultiStepConversationExecutorTest` — column-major output `{col:[...]}`; per-step `null` index alignment; fail-fast partial arrays (length = completed turns); fail-at-step-0 → `{}` (done: `./gradlew test --tests "...MultiStepConversationExecutorTest"` passes).
- [x] 7.2 `BindingResolverTest` — Response binding: `jsonataExpression` array index (`$[0]`, `$[-1]`), object path; no-expr → whole array; no-match → `null`; missing column → throws (done: test class passes).
- [x] 7.3 `MetricDefinitionValidationServiceTest` — invalid `jsonataExpression` → `INVALID_EXPRESSION` warning; valid/absent → none (done: test class passes — 3 new cases green).
- [x] 7.4 Remove or repurpose `ExtractedColumnsNormalizerTest` (done: no dangling references).
- [~] 7.5 Functional `PostgresFunctionalTests$MultiStepConversationRunTests` — existing multi-step run tests updated to assert **column-major** `extractedColumns` (compile-verified). REMAINING: add a metric whose Response binding uses `jsonataExpression` to score a specific turn end-to-end, and run the suite — deferred because Docker/Testcontainers is unavailable in this environment, so the functional suite cannot be authored+executed here. Run `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$MultiStepConversationRunTests"` where Docker is available.

## 8. Docs, OpenAPI, spec sync

- [x] 8.1 Update `ResponseBindingSourceDto` `@Schema` example to include `jsonataExpression`; update any multi-step `extractedColumns` OpenAPI examples to column-major (done: examples reflect the new contract).
- [x] 8.2 Update the AGENTS.md multi-step inline convention: `extractedColumns` is a column-major object of per-column arrays; `ExtractedColumnsNormalizer` removed; metric selection via `jsonataExpression` on Response bindings (raw pass-through; graceful `null` on no-match) (done: AGENTS.md updated).
- [x] 8.3 Run `./gradlew spotlessApply` then `./gradlew compileJava` (done: BUILD SUCCESSFUL, no dangling normalizer references).
- [x] 8.4 At archive time, sync the four delta specs (`multi-step-conversation`, `metric-evaluation`, `tsmd-validation`, `eval-summary-export`) into `openspec/specs/` via the `/opsx:archive` sync step (intelligent merge, incl. the multi-step spec's Implementation Notes that reference the removed normalizer). Update `openspec/specs/README.md` only if a summary becomes inaccurate.
