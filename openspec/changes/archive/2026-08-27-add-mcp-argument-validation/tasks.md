Implemented **TDD-style**: no production line lands before a test that was *watched failing for the
right reason*. Per AGENTS.md, implement at most one task group per iteration.

Shared fixture, hand-written literal (never built by production code):

```json
{"type":"object","properties":{"repoName":{"type":"string"},"branch":{"type":"string"}},
 "required":["repoName"]}
```

## 1. Reproduce GH #69 at the observable boundary

- [x] 1.1 Add a failing test to `src/test/java/com/epam/aidial/evaluation/service/domain/SuiteValidationServiceTest.java`
      — new nested class `McpToolSchemaValidation`, `shouldInvalidateSuite_whenRequiredArgumentIsEmpty()`:
      MCP suite with the fixture `toolRef.inputSchema` and `arguments = {"repoName": ""}`, asserting
      `isValid() == false` plus a `REQUIRED` warning with `fieldName = "repoName"` and
      `path = "$.argumentTemplate.arguments"`. Extend the existing `buildMcpSuite` helper with a
      `toolRef` overload rather than duplicating it. Done when
      `./gradlew test --tests "*SuiteValidationServiceTest"` fails with `expected: false but was: true`
      — *not* an NPE and not a compile error. That message is issue #69 reproduced.
- [x] 1.2 Create `src/main/java/com/epam/aidial/evaluation/service/domain/McpArgumentValidator.java`
      (`@Component`, `@LogExecution`) with the design.md D1 signature, implementing **only** the
      blank-string-constant rule for required properties; inject it into `SuiteValidationService` and
      call it from `validateMcpSuite` inside the existing `argumentTemplate != null` branch. Done when
      1.1 passes.
- [x] 1.3 Run `./gradlew test --tests "*SuiteValidationServiceTest"` in full. Done when the whole class
      is green — in particular the pre-existing `McpBindingValidation` cases, whose fixtures carry no
      `toolRef`, must be untouched (that is design.md D4 under test).

## 2. Remaining unsatisfied shapes (one failing test per rule)

Each of these fails against the 1.2 implementation, so each is a genuine red. New file
`src/test/java/com/epam/aidial/evaluation/service/domain/McpArgumentValidatorTest.java` (plain JUnit 5,
no Spring context).

- [x] 2.1 Failing test `shouldWarn_whenRequiredArgumentAbsent()` — `arguments = {"branch": "main"}`;
      then implement the absent-key rule. Done when the test passes.
- [x] 2.2 Failing test `shouldWarn_whenRequiredArgumentIsNull()` — `{"repoName": null}` (breaks a naive
      `instanceof String` check); then implement. Done when the test passes.
- [x] 2.3 Test `shouldWarn_whenRequiredArgumentIsWhitespaceOnly()` — `{"repoName": "   "}`
      **went green on its first run** — the group-1 implementation already used `isBlank()` rather than
      the strictly-minimal `isEmpty()`, so this is a guard, not a red. Recorded as such rather than
      manufacturing a failure; it is covered by the 5.2 mutation check.
- [x] 2.4 Failing test `shouldWarn_whenRequiredArgumentBoundToBlankConstant()` —
      `{"repoName": "${{repo}}"}` plus an `InputBindingDto` for `repo` with `constantValue = ""`; then
      implement the placeholder → binding resolution from design.md D2. Done when the test passes.
- [x] 2.5 ~~Failing test `shouldWarn_whenArgumentNotDeclaredByTool()` — expecting one `ADDITIONAL`
      warning for an undeclared argument.~~ **Implemented, then reverted after code review.** Any warning
      flips `isValid = false`, which `TestSuiteRunService` turns into a hard 409 on run creation, so a
      stale `toolRef.inputSchema` snapshot could make a working suite un-runnable — a regression outside
      GH #69, which concerns *required* arguments only. Code, test, and spec scenario removed; see
      design.md D3a.

## 3. Must-not-warn guards

These go green immediately against the group-2 implementation. That is expected for non-regression
guards — they earn their place via the mutation check in 5.2, not via a red run.

- [x] 3.1 `shouldNotWarn_whenRequiredArgumentBoundToDataField()` — `{"repoName": "${{repo}}"}` with a
      binding carrying `dataField = "repository_name"`. Done when green.
- [x] 3.2 `shouldNotWarn_whenOptionalArgumentIsEmpty()` — `{"repoName":"dial","branch":""}`. Done when
      green.
- [x] 3.3 `shouldNotWarn_whenRequiredArgumentIsNonStringConstant()` — a required numeric argument with
      value `0` (a falsy-looking but valid constant). Done when green.
- [x] 3.4 `shouldNotWarn_whenToolSchemaAbsentOrWithoutProperties()` — null `inputSchema`, and an
      `inputSchema` with no `properties` key. Done when green (design.md D4). **Corrected after code
      review:** the no-properties case originally passed empty arguments, so no branch could warn and
      the test could not fail; it now passes `{"repoName": ""}`. Re-verified by mutation — removing the
      degradation gate now fails both degradation tests, not just the absent-schema one.
- [x] 3.5 `shouldNotDoubleWarn_whenPlaceholderHasNoBinding()` — `{"repoName": "${{repo}}"}` with empty
      `inputBindings`, asserted at the `SuiteValidationServiceTest` level: exactly **one** `REQUIRED`
      warning (the existing unbound-variable one from `BindingValidator`), not two. Done when green —
      this is the design.md D2 last-row rule.

## 4. End-to-end through HTTP

- [x] 4.1 Add a failing functional test to
      `src/test/java/com/epam/aidial/evaluation/functional/tests/McpTestSuiteFunctionalTests.java`:
      create a valid MCP suite, then PUT it with `argumentTemplate.arguments.repoName = ""`; assert the
      response body has `isValid = false` and a `validationWarnings` entry with `code = REQUIRED`,
      `fieldName = "repoName"`, `path = "$.argumentTemplate.arguments"`, and assert the persisted row
      through `TestSuiteRepository` (per AGENTS.md — no raw SELECT, no `JdbcTemplate` in the test).
      Done when it fails before 1.2 is on the branch and passes with it.
- [x] 4.2 Run
      `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$McpTestSuiteTests"`. Done when green — this boots
      the application context and is the wiring proof for the new constructor-injected bean.

## 6. Code-review follow-ups

- [x] 6.1 Drop the unknown-argument `ADDITIONAL` check (code, test, spec scenario, proposal/design
      text). Done when `McpArgumentValidatorTest` and `SuiteValidationServiceTest` are green with no
      `ADDITIONAL` assertion left.
- [x] 6.2 Failing test `shouldWarn_whenRequiredArgumentPlaceholderHasBlankDefault()` — an unbound
      `${{repo:}}` left a required argument empty with no warning from either validator
      (`BindingValidator` stays silent on any default). Done when the test failed with
      `Expected size: 1 but was: 0`, then passed once `isSatisfied` judged the default.
- [x] 6.3 Fix the vacuous no-properties degradation test (see 3.4) and re-run the gate mutation.
- [x] 6.4 Reword the spec's effective-value bullet: the code never treats an inline default as
      overriding a binding's blank constant, and only judges the default when there is no binding.
- [x] 6.5a Remove the now-redundant `declaredProperties.isEmpty()` early return: with 6.1 done it is
      dead code, which the mutation check surfaced (removing it changed no test outcome). The
      per-required-name `contains` guard carries the degradation; mutating *that* is caught.
- [x] 6.5 Stop the functional test creating a second, discarded dataset. Done when
      `PostgresFunctionalTests$McpTestSuiteTests` is green (12 tests).

## 5. Refactor, verification, close-out

- [x] 5.1 Extract `src/main/java/com/epam/aidial/evaluation/service/domain/JsonSchemaPropertyExtractor.java`
      (`@Component`, `Map` and `String` overloads per design.md D3); have both `McpArgumentValidator`
      and `MetricDefinitionValidationService` delegate to it, deleting the latter's private
      `extractSchemaPropertyNames` / `extractRequiredPropertyNames`. Done when
      `./gradlew test --tests "*MetricDefinitionValidationServiceTest" --tests "*McpArgumentValidatorTest"`
      is green — a pure move, no behavior change.
- [x] 5.2 Run the mutation check: each mutation must break at least one test — drop the blank check
      (→ 2.3), treat any non-null value as satisfied (→ 1.1, 2.2), treat every placeholder as
      unsatisfied (→ 3.1), ignore `required` (→ 3.2), return warnings when `inputSchema` is null
      (→ 3.4 and the existing `McpBindingValidation` cases), skip the `SuiteValidationService` wiring
      (→ 1.1, 4.1). Done when every mutation is caught; a mutation nothing catches means a missing test,
      not a passing check.
- [x] 5.3 Run `./gradlew spotlessApply` then `./gradlew check`. Done when Spotless, Checkstyle,
      `LayeredArchitectureTest`, and `LoggingConventionTest` are all green.
- [x] 5.4 Sync the delta into `openspec/specs/test-suites/spec.md` via `/opsx:sync`, flipping the new
      requirement's status to **Implemented**. Done when `openspec validate add-mcp-argument-validation`
      passes and the main spec carries the requirement.
