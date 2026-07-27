## 1. Migrations and generated sources

- [x] 1.1 Add meta migration `V1.29__AddAdditionalRequestsToTestSuites.sql` adding `additional_requests JSONB` (nullable) and `request_label VARCHAR(255)` (nullable) to `test_suites` (done: migration applies on a clean DB and on a DB at V1.28, no backfill)
- [x] 1.2 Add analytics migration `V1.15__AddRequestColumnsToResultsAndSummaries.sql` adding `request_index INTEGER NOT NULL DEFAULT 0` and `request_label VARCHAR(255)` to both `test_case_run_results` and `test_case_eval_summaries` (done: existing rows backfill to `request_index = 0`)
- [x] 1.3 In the same analytics migration, drop and recreate both unique constraints/indexes to include `request_index` — `(test_suite_run_id, test_case_id, run_index, turn_index, request_index, created_at_ms)` and `(test_suite_run_id, test_case_id, run_index, turn_index, request_index, computation_id, created_at_ms)` — following the `V1.13`/`V1.14` precedent and carrying forward their `CREATE UNIQUE INDEX CONCURRENTLY` note for large deployments (done: constraints match the specs; no `total_requests` column is created)
- [x] 1.4 Run `./gradlew generateJooq` and commit the generated diff under `src/main/java-generated/` (done: `JooqSchemaDriftTest` passes)
- [x] 1.5 Update `docs/database-schema.md` with the new columns and revised unique keys on `test_suites`, `test_case_run_results`, and `test_case_eval_summaries` (done: doc matches the migrations)

## 2. Chain model and normalizer

- [x] 2.1 Add the chain element DTO as a Jackson-discriminated polymorphic type keyed on `type` (`HTTP` | `MCP_TOOL`, absent ⇒ `HTTP`), carrying `label`, `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, following the `RequestBodyDto`/`MetricBindingSourceDto` pattern (done: serializes/deserializes round-trip in a unit test)
- [x] 2.2 Add `responseField` to `InputBindingDto` and update `isValidBinding` so exactly one of `dataField` / `constantValue` / `responseField` is set (done: unit tests cover all both-and-neither combinations)
- [x] 2.3 Add the chain normalizer as an injectable `@Component` in `service.domain` producing a uniform ordered `List<RequestSpec>` with element 0 synthesized from the suite's flat fields, defaulting absent labels to `request-{n}` (done: unit tests cover single-request, multi-request, and label defaulting)
- [x] 2.4 Add a chain-union response-column helper on the normalizer returning all response columns across the chain in chain order (done: unit test asserts order and completeness; helper is the single source consumed by validation, export, and schema discovery)
- [x] 2.5 Add `ValidationConstants` / `application.yml` wiring for `test-suite.multi-request.max-requests` with default `10`, defined in YAML per AGENTS.md (done: property binds and is readable from a properties class)
- [x] 2.6 Run the new unit tests: `./gradlew test --tests "*ChainNormalizer*" --tests "*InputBindingDto*"` (done: all pass)

## 3. Suite persistence and API surface

- [x] 3.1 Add `additionalRequests` and `requestLabel` to `TestSuite` model, `TestSuiteRequestDto`, `TestSuiteResponseDto` with OpenAPI `@Schema` annotations and examples (done: fields appear in Swagger UI with examples)
- [x] 3.2 Wire the new columns through `TestSuiteRecordMapper`, `TestSuiteMapper`, `JsonbMapper`, and `PostgresTestSuiteRepository` insert/update/select column lists (done: create → read → update → read round-trips the chain)
- [x] 3.3 Add `requestIndex` (nullable Integer) to `ValidationWarningDto` with `@JsonInclude(NON_NULL)`, mirroring the existing `turnIndex` field (done: absent for non-chain warnings, populated for chain warnings)
- [x] 3.4 Update the suite OpenAPI examples under `src/main/resources/openapi/examples/` to include a minimal single-request example and a full multi-request chain example (done: examples reflect the new contract)
- [x] 3.5 Add functional tests for suite create/read/update/delete carrying a chain (done: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$TestSuite*"` passes)

## 4. Suite validation

- [x] 4.1 Enforce chain-wide response-column name uniqueness at save, 400 `VALIDATION_ERROR` naming the duplicate (done: rejects cross-request and within-request duplicates)
- [x] 4.2 Enforce resolved-label-set uniqueness at save, 400 on duplicate — catching both duplicate explicit labels and an explicit label colliding with another request's `request-{n}` default (done: both cases rejected)
- [x] 4.3 Enforce `responseField` reference rules at save, 400 for forward, self, and unknown-column references, and for any `responseField` on a single-request suite (done: each case has a test)
- [x] 4.4 Enforce the chain cap at save, 400 naming both chain length and cap; reject `type: MCP_TOOL` chain elements at save with 400 (done: at-cap accepted, over-cap rejected)
- [x] 4.5 Extend `SuiteValidationService` to validate each chain element's `requestTemplate`/`inputBindings` against that element's own `endpointRef`, attributing each warning to its `requestIndex` (done: a body valid for its own endpoint produces no warning; unbound variable warns with the right index)
- [x] 4.6 Run validation tests: `./gradlew test --tests "*SuiteValidation*" --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$TestSuiteValidation*"` (done: all pass)

## 5. Snapshot

- [x] 5.1 Add `additionalRequests` and `requestLabel` to `SuiteSnapshotDto`, keeping `CURRENT_VERSION = "2"` and the flat fields as request 0's representation (done: a snapshot without `additionalRequests` deserializes as a single-request chain with no version branch)
- [x] 5.2 Populate both fields in `SuiteSnapshotBuilder` for DEPLOYMENT suites; leave them null for MCP_TOOL suites (done: snapshot JSON matches the spec scenarios)
- [x] 5.3 Apply the chain normalizer when reading a snapshot so snapshot and live suite normalize identically (done: unit test asserts identical chains for a suite and its snapshot)
- [x] 5.4 Add a functional test asserting a frozen chain survives subsequent live-suite edits (done: `MultiRequestExecutionFunctionalTests.frozenChainSurvivesSuiteEdit` — the first run's 3 rows and its snapshot's 2 `additionalRequests` are unchanged after the suite grows to 4, and the new run executes 4)

## 6. Chain execution

- [x] 6.1 Add the `ChainStepExecutor` SPI plus a registry keyed on the element `type`, following the `RequestBodySerializerRegistry` pattern (done: registry resolves `HTTP`; duplicate/unknown type handling covered by unit tests)
- [x] 6.2 Implement `HttpChainStepExecutor` — resolve one chain request's template and bindings, issue the call using that element's `endpointRef` method and resolved URL against the suite-level `deploymentRef`, extract that request's own response columns (done: unit test covers a single step end to end with a stubbed invoker)
- [x] 6.3 Implement `McpChainStepExecutor` as a stub throwing `UnsupportedOperationException` (done: registry returns it for `MCP_TOOL`; save-time rejection makes it unreachable)
- [x] 6.4 Implement the chain executor — sequential loop over the normalized chain under one permit, accumulating extracted response columns, resolving `responseField` from that map, persisting one row per request with `request_index`/`request_label` and `turn_index=0`/`total_turns=1` (done: a 3-request chain writes 3 rows and a later request consumes an earlier request's value)
- [x] 6.5 Implement fail-fast plus the unresolvable-dependency rule — placeholder default when declared, otherwise ERROR row and abort (done: failure at request k yields k SUCCESS rows + 1 ERROR row and no later rows)
- [x] 6.6 Dispatch to the chain executor from `EvaluationWorker.execute` when the normalized chain size exceeds one, leaving the MCP and multi-turn branches untouched (done: single-request and multi-turn paths byte-identical in behavior)
- [x] 6.7 Run execution tests: `./gradlew test --tests "*ChainExecutor*" --tests "*ChainStepExecutor*"` and one functional run test that boots the context (done: all pass)

## 7. Rate limiter conformance fix

- [x] 7.1 Add a `RunRateLimiter` gate component wrapping the Bucket4j bucket, a no-op when `rateLimitRps` is null, propagating `InterruptedException` so cancellation is not delayed (done: unit test asserts no-op and throttling behavior)
- [x] 7.2 Carry the gate on `EvaluationContext` in place of the raw `rateLimitRps` value used to build the bucket locally (done: context exposes the gate; no behavior depends on the raw double at the call sites)
- [x] 7.3 Acquire a token inside `EvaluationWorker.invokeSingle`, `EvaluationWorker.invokeMcpSingle`, and `DeploymentTurnInvoker.invokeSingle`, and remove the per-dispatch `consume(1)` from `InProcessEvaluationExecutor` (done: one token per HTTP attempt including retries; N-turn and N-request cases consume N tokens)
- [x] 7.4 Add a test asserting a multi-turn case of N turns consumes N tokens and a chain of N requests consumes N tokens (done: closes the pre-existing gap against the already-specified per-HTTP-call requirement)
- [x] 7.5 Add a test asserting a cancelled run interrupts a pending token wait without issuing the call (done: worker terminates promptly)

## 8. Conditional metric execution

- [x] 8.1 Add `requestIndex` and `requestLabel` to `ConditionContext` (done: additive builder fields, no signature churn at call sites)
- [x] 8.2 Add the `request` namespace (`index`, `label`) to `ConditionExpressionEvaluator.buildDictionaryJson` alongside `turn` (done: unit tests cover `request.label = "..."`, `request.index = n`, and combination with `response`/`data`)
- [x] 8.3 Populate the new context fields from the result row in `InProcessMetricEvaluationExecutor` (done: conditions gate correctly per chain request in a functional run)
- [x] 8.4 Run: `./gradlew test --tests "*ConditionExpressionEvaluator*"` (done: all pass, including single-request suites seeing `request.index = 0`)

## 9. TSMD validation

- [x] 9.1 Source the response-column name set in `MetricDefinitionValidationService`'s `UNRESOLVED_REFERENCE` check from the chain-union helper instead of the suite's flat `responseColumns` (done: `TestSuiteMetricDefinitionFunctionalTests.shouldMarkValid_whenResponseBindingNamesLaterChainRequestColumn` — valid with no warnings; the sibling test still reports UNRESOLVED_REFERENCE for a name absent from the whole chain)
- [x] 9.2 Ensure TSMD auto-revalidation on suite update uses the same chain-union set, so editing a chain element's `responseColumns` revalidates dependent TSMDs (done: `shouldRevalidateTsmds_whenChainElementResponseColumnsEdited` — renaming a chain element's column, with the flat `responseColumns` untouched, flips the dependent TSMD to invalid)
- [x] 9.3 Confirm an unconditioned TSMD on a multi-request suite validates cleanly with no condition-related warning (done: `shouldMarkValid_whenUnconditionedTsmdOnMultiRequestSuite` asserts `condition == null`, empty warnings, `isValid = true`)
- [x] 9.4 Run: `./gradlew test --tests "*MetricDefinitionValidation*"` (done: all pass)

## 10. Analytics models, repositories, and DTOs

- [x] 10.1 Add `requestIndex` (default 0) and `requestLabel` to `TestCaseRunResult` and `EvalSummary` models and their record mappers (done: round-trips through both tables)
- [x] 10.2 Update `PostgresTestCaseRunResultRepository` insert column list **and** the `ON CONFLICT` target to include `request_index`, matching the widened unique index (done: two chain requests of one case both persist; a duplicate write is idempotent)
- [x] 10.3 Update the eval-summary repository write path for the widened natural key including `request_index` (done: two chain requests' summaries both persist per computation)
- [x] 10.4 Add `requestIndex`/`requestLabel` to `TestCaseRunResultResponseDto`, `EvalSummaryResponseDto`, `EvalSummaryDetailResponseDto` and their mappers, with OpenAPI `@Schema` annotations (done: fields present in listing and detail responses)
- [x] 10.5 Add optional `requestIndex` (`@Min(0)`, default 0) and client-supplied `requestLabel` (`@Size(max = 255)`) to `TestCaseRunResultItemDto` and the batch-write path, with **no** snapshot cross-validation or index bound check (done: external-run import with arbitrary labels succeeds; negative index rejected with 400)
- [x] 10.6 Document on the result and eval-summary listing endpoints that intra-run row order is arbitrary and clients MUST sort by `(runIndex, requestIndex, turnIndex)` (done: OpenAPI descriptions state the contract)
- [x] 10.7 Run: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$Analytics*"` (done: all pass)

## 11. Export and query schema discovery

- [x] 11.1 Add `requestIndex`, `requestLabel`, `turnIndex`, `totalTurns` identity descriptors to `EvalSummaryExportColumnPlanner`, positioned after `runIndex` within the identity block (done: header order matches the spec; turn columns close the pre-existing multi-turn export gap)
- [x] 11.2 Source the `response::` family in the planner from the chain-union helper, preserving chain order (done: chain columns all present; rows sparse per owning request)
- [x] 11.3 Source the `response:` family in `EvalSummariesSchemaProvider` from the same chain-union helper (done: `EvalSummariesSchemaProviderTest.shouldAdvertiseChainUnionResponseFields` + `chainResponseFieldsKeepChainOrder` — all three requests' columns present, in chain order)
- [x] 11.4 Verify `request_index`/`request_label` appear in the `eval_summaries` base schema automatically from generated table metadata, and add a query-DSL test filtering on `request_label` (done: `EvalSummaryStructuredQueryFunctionalTests.filtersByRequestLabel` returns only the `invoke` row; `groupsByRequestIdentity` groups a run's chain rows by `request_label`)
- [x] 11.5 Run: `./gradlew test --tests "*EvalSummaryExport*" --tests "*EvalSummariesSchemaProvider*"` (done: all pass. The `MAX_EXPORT_COLUMNS` cap is pre-existing and untouched by this change; no over-wide-chain fixture was added)

## 12. Run creation guards, try-it-out, and clone

- [x] 12.1 Insert guard 3b in `TestSuiteRunService.createRun` re-checking chain length against the current cap, 409 `INVALID_OPERATION` naming length and cap (done: `TestSuiteRunServiceTest.ChainCapGuard` — a 3-request suite under a cap of 2 gets 409 before the runnable-count query; the same chain at a cap of 3 passes the guard)
- [x] 12.2 Insert guard 3c rejecting a multi-request suite bound to a dataset containing any multi-turn case, 409 `INVALID_OPERATION`, reusing `existsMultiTurnByDatasetId` (done: both guards precede the runnable-count query)
- [x] 12.3 Add optional `requestIndex` to both try-it-out endpoints selecting which chain element to instantiate, defaulting to 0, 400 when out of range (done: `TryItOutServiceTest.ChainRequestSelection` — index 1 resolves element 1's own template and issues its GET exactly once; out-of-range/negative/beyond-single-request all rejected)
- [x] 12.4 In test-case mode, surface an unresolvable `responseField` as a `ValidationWarningDto` in `resolvedRequest.warnings` and still send the request, applying the placeholder default when declared (done: `unresolvableResponseFieldWarnsAndStillSends` returns 200 with the `sid` warning; `unresolvedDataFieldStillBlocks` shows a genuinely missing data field still blocks)
- [x] 12.5 Copy `additional_requests` and `request_label` verbatim in the clone service without adding them to the overridable set, and validate a `responseColumns` override against the chain union (done: `TestSuiteCloneServiceTest` (j)/(k) — colliding `responseColumns` override rejected before suite validation with files cleaned up; `TestSuiteMapperCloneTest` covers verbatim `additionalRequests`/`requestLabel` copy, file-ref rewriting inside the chain, and a null chain for single-request sources)
- [x] 12.6 Run: `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$TestSuiteRun*" --tests "*TryOut*" --tests "*Clone*"` (done: all pass)

## 13. Documentation and spec sync

- [x] 13.1 Add the `test-suite.multi-request.max-requests` row to `docs/configuration.md` with all six columns (`Property | Environment Variable | Default | Required | Applied when | Description`) (done: row matches the configuration-docs spec)
- [x] 13.2 Add `docs/patterns/request-chain.md` covering the chain-wide response-column namespace, accumulating-map resolution order, fail-fast, condition-based metric targeting including the unconditioned-metric N× cost and FAILED-row signal, and the normalizer as the single definition of the chain; link it from `docs/patterns/README.md` (done: pattern is discoverable without reading six classes)
- [x] 13.3 Update AGENTS.md per AGENTS.md Maintenance guidelines — add the new pattern to the Unique Patterns table and note the multi-request/multi-turn exclusion (done: relevant sections reflect the change)
- [ ] 13.4 Update `openspec/specs/README.md` per Spec Index Maintenance Policy for the new `multi-request-test-suite` spec folder — **deferred to archive time.** The policy requires the index to match the `specs/` directory with no phantoms, and `openspec/specs/multi-request-test-suite/` does not exist until `/opsx:sync` creates it during `/opsx:archive`. An entry added now is a broken link, so the summary text below is staged here and MUST be added to the index in the same step that creates the folder.

  > - **[multi-request-test-suite](multi-request-test-suite/spec.md)** — Implemented
  >   Multi-request test suites as an ordered chain of independent HTTP requests per test case, declared by a non-empty `additionalRequests` array (absent/empty ⇒ single-request, unchanged). Persisted asymmetrically (request 0 in the flat suite fields, `1..N-1` in the array) and normalized once into a uniform request list that every consumer works against. Response column names are unique chain-wide, making the suite's effective set the chain union; a new `responseField` binding source lets a later request consume any earlier request's extracted column from an accumulating map. Sequential fail-fast execution emitting one result row per request (`request_index` in the natural key, `request_label` as payload, no `total_requests`); metric targeting reuses the existing per-metric `condition` via a `request:{index,label}` namespace, with an unconditioned metric running on every row. Configurable chain cap (`test-suite.multi-request.max-requests`, default 10) enforced at save (400) and run creation (409); MCP chaining has a registry seam but is rejected at save; multi-request × multi-turn rejected at run creation (409). Also brings rate-limiter accounting into conformance (one token per HTTP call, retries included) and adds `requestIndex`/`requestLabel`/`turnIndex`/`totalTurns` identity columns to the eval-summary CSV export. Related: test-suites, request-template, response-columns, eval-execution-engine, test-suite-runs, conditional-metric-execution, tsmd-validation, analytics-eval-results, metrics-storage, eval-summary-export, eval-results-import, suite-run-snapshot, try-it-out, multi-turn-test-case, test-suite-clone, query-schema-discovery.
- [x] 13.5 Run `./gradlew spotlessApply` then `./gradlew clean build` (done: Spotless, Checkstyle, `LoggingConventionTest`, `LayeredArchitectureTest`, `JdbcTemplateFenceTest`, `JooqSchemaDriftTest`, and the full test suite all pass)
