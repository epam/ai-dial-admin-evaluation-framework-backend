## 1. Shared Model Validation

- [ ] 1.1 Add centralized runner-core constants for the two canonical paths and make `DialCoreUrlBuilder` consume them with routing behavior byte-identical (case-insensitive prefix for Responses, exact for Anthropic); `DialCoreUrlBuilderTest` lives in the ROOT module (`src/test/java/com/epam/aidial/evaluation/service/domain/DialCoreUrlBuilderTest.java`), so run it with `./gradlew test --tests "*DialCoreUrlBuilderTest"`, not `:evaluation-runner-core:test`
- [ ] 1.2 Implement the injectable `RequestModelValidator` and `RequestBodyValidationException`, covering exact-path applicability, absent/non-JSON bodies, missing/null/non-string models, embedded `${{...}}` placeholders (`find()` semantics), mismatch, and match cases with `./gradlew :evaluation-runner-core:test --tests "*RequestModelValidatorTest"`
- [ ] 1.3 Add `REQUEST_BODY_VALIDATION_ERROR` to the runner execution and validation-warning code catalogs, and verify runner-core compilation succeeds with `./gradlew :evaluation-runner-core:compileJava`

## 2. Static Suite Validation

- [ ] 2.1 Replace the Anthropic-only best-effort check with shared plain-content validation and the dedicated `REQUEST_BODY_VALIDATION_ERROR` warning for both canonical endpoints, retaining JSONata syntax validation and indexed request-chain paths
- [ ] 2.2 Expand suite validation unit tests for matching, missing, null, non-string, embedded-placeholder, and mismatched models on both endpoints, JSONata deferral, non-target paths, and additional requests; run `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.SuiteValidationServiceTest"`
- [ ] 2.3 Add a focused functional test proving create/update persists an invalid suite warning while valid and JSONata-authored suites retain their intended state, and run its `@PostgresFunctionalTests` suite to verify application-context wiring

## 3. Backend and CLI Runtime Execution

- [ ] 3.1 Integrate resolved-body validation into `TurnLoopExecutor` before invocation, preserve the resolved body, and emit a dedicated ERROR outcome; verify `TurnLoopExecutorTest` covers no-call, error-envelope, and chain-abort behavior
- [ ] 3.2 Add runner tests for both canonical paths, valid and invalid resolved models, JSONata-resolved bodies, and non-target bypass; run `./gradlew :evaluation-runner-core:test --tests "*TurnLoopExecutorTest"`
- [ ] 3.3a Extend `EvaluationContextFactoryTest` to assert the `--deployment-id` override lands in `EvaluationContext.snapshotDeploymentRef` (and that the fetched suite's deployment is used without an override); run `./gradlew :eval-cli:test --tests "*EvaluationContextFactoryTest"`
- [ ] 3.3b Cover the CLI-effective behavior in the shared engine by varying `EvaluationContext.snapshotDeploymentRef` in `TurnLoopExecutorTest` — override mismatch produces `REQUEST_BODY_VALIDATION_ERROR` with no invocation, override match invokes normally; run `./gradlew :evaluation-runner-core:test --tests "*TurnLoopExecutorTest"`
- [ ] 3.4 Add or extend a backend run functional test using a `jsonataContent`-authored (hence statically valid) body that resolves to a mismatched `model`, proving it persists `REQUEST_BODY_VALIDATION_ERROR` without invoking DIAL Core; note plain-`content` mismatches cannot reach a run at all because `createRun` guard #3 rejects `isValid=false` suites. Execute the corresponding `@PostgresFunctionalTests` suite

## 4. Try-It-Out Runtime Validation

- [ ] 4.1 Integrate the shared validator at the Try-It-Out pre-invocation boundary for test-case and variables modes, converting single-invocation failures to the established HTTP 400 response with the dedicated warning; verify focused `TryItOutServiceTest` cases pass
- [ ] 4.2 Catch `RequestBodyValidationException` explicitly in chained Try-It-Out (separate from `validateResolutionResult`), map it to the status-code-zero `REQUEST_BODY_VALIDATION_ERROR` envelope, retain the resolved request/history, and abort later calls; verify chain tests cover the failure position
- [ ] 4.3 Add functional coverage for matching and invalid plain/JSONata-resolved models on both canonical endpoints and assert zero DIAL Core interactions on failure; run the corresponding `TryItOutFunctionalTests` suite

## 5. Contracts, Documentation, and Quality Gates

- [ ] 5.1 After implementation, mark the delta requirements Implemented with code-path notes, sync all five delta specs through `$openspec-sync-specs` (never copy fragments over main specs), inspect `git diff` for a correct merge, and verify `openspec validate validate-request-model-deployment-id --strict` passes
- [ ] 5.2 Update the Anthropic Messages pattern documentation with the unified fixed-path validation and OpenAI Responses behavior, and update the corresponding summaries in `docs/patterns/README.md` and `AGENTS.md`; verify references to the superseded best-effort rule are removed with `rg`
- [ ] 5.3 Run Spotless, runner/root/CLI targeted tests, and checkstyle; verify `./gradlew --no-daemon spotlessApply`, `./gradlew :evaluation-runner-core:test`, `./gradlew test`, `./gradlew :eval-cli:test`, and `./gradlew checkstyleMain checkstyleTest` all pass
- [ ] 5.4 Check Config and OpenSpec spec-index maintenance policies; verify no updates are needed unless implementation introduces broader conventions or changes the indexed summaries
