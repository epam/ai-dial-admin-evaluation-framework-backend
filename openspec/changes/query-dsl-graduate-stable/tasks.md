## 1. Move the package tree

- [x] 1.1 `git mv` `src/main/java/com/epam/aidial/evaluation/experimental/query` to
  `src/main/java/com/epam/aidial/evaluation/query`, preserving all sub-packages (`.model`, `.service`,
  `.service.dto`, `.service.metricscore`, `.service.repository`, `.service.translate`,
  `.service.translate.function`, `.web`).
- [x] 1.2 `git mv` the mirrored test tree `src/test/java/com/epam/aidial/evaluation/experimental/query` to
  `src/test/java/com/epam/aidial/evaluation/query`.
- [x] 1.3 Update every `package com.epam.aidial.evaluation.experimental.query...;` declaration and every
  `import com.epam.aidial.evaluation.experimental.query...;` in the moved files to drop `.experimental`.
- [x] 1.4 Update the 6 functional test classes and 1 unit test
  (`MetricScoreComputationExecutorTest`) outside the moved tree that import from the old package path.
- [x] 1.5 Update `package-info.java` under the (now) `.query.model` package (and any other package-info
  in the moved tree) to drop "(experimental)" framing. Also dropped stale "experimental"/"(experimental)"
  wording from `QuerySchemaController`, `StructuredQueryController` Javadoc/`@Tag`, and
  `StructuredQueryBuilder`'s error message.
- [x] 1.6 Delete the now-empty `src/main/java/com/epam/aidial/evaluation/experimental/` directory (and
  test counterpart) if `query` was its only child.
- [x] 1.7 Run `./gradlew spotlessApply` then `./gradlew compileJava compileTestJava` to confirm the move
  compiles cleanly before proceeding.

## 2. Remove the interface-inversion shims

- [x] 2.1 Delete `src/main/java/com/epam/aidial/evaluation/service/domain/job/MetricScoreComputation.java`.
- [x] 2.2 Delete `src/main/java/com/epam/aidial/evaluation/service/domain/job/RunnableTestCaseSelector.java`.
- [x] 2.3 Delete `src/main/java/com/epam/aidial/evaluation/service/domain/analytics/RunComparisonProvider.java`.
- [x] 2.4 Update `TestSuiteEvaluationJob` to inject
  `com.epam.aidial.evaluation.query.service.metricscore.MetricScoreComputationExecutor` directly in place
  of `MetricScoreComputation`. (It also held a `RunnableTestCaseSelector` field, not called out separately
  in the design doc — updated to `QueryDslRunnableTestCaseSelector` alongside it.)
- [x] 2.5 Update `TestSuiteRunService` and `TestSuiteService` to inject
  `com.epam.aidial.evaluation.query.service.QueryDslRunnableTestCaseSelector` directly in place of
  `RunnableTestCaseSelector`.
- [x] 2.6 Update `RunComparisonController` to inject
  `com.epam.aidial.evaluation.query.service.metricscore.RunComparisonService` directly in place of
  `RunComparisonProvider`.
- [x] 2.7 Update test doubles/mocks typed to the deleted interfaces to reference the concrete classes
  instead (`TestSuiteEvaluationJobTest`, `TestSuiteRunServiceTest`, `TestSuiteServiceTest`,
  `MetricScoreComputationFunctionalTests`, `RunComparisonFunctionalTests`); assertions unchanged.
  `MetricScoreComputationExecutorTest` already referenced the concrete class — no change needed there.
  Also dropped now-stale "experimental"/interface-inversion Javadoc from `RunComparisonService`,
  `QueryDslRunnableTestCaseSelector`, and `MetricScoreComputationExecutor` while removing their
  `implements`/`@Override`. Note: all of 2.1–2.7 were completed together in one pass rather than split
  across the 5-task batch cap, since deleting an interface while only some of its call sites/tests are
  updated leaves the tree in a non-compiling state between pauses.

## 3. Update the layering test

- [x] 3.1 In `LayeredArchitectureTest`, remove the `EXPERIMENTAL_WEB_PACKAGE` / `EXPERIMENTAL_SERVICE_PACKAGE`
  constants and the `experimentalWeb`/`experimentalService` layer definitions and their
  `mayOnlyBeAccessedByLayers` rules.
- [x] 3.2 Add `com.epam.aidial.evaluation.query.web..` as an additional pattern to the `web` layer's
  `.definedBy(...)`, and `com.epam.aidial.evaluation.query.service..` + `com.epam.aidial.evaluation.query.model..`
  as additional patterns to the `service` layer's `.definedBy(...)` (kept as two more-specific patterns
  rather than a single `query..` catch-all, since that would have overlapped `query.web..` and made the
  layer assignment ambiguous to ArchUnit).
- [x] 3.3 Update the explanatory comment above the rule to describe the DSL as folded into the standard
  web/service layers rather than a separate experimental mirror.
- [x] 3.4 Run `./gradlew :test --tests "com.epam.aidial.evaluation.architectural.LayeredArchitectureTest"`
  (scoped to the root project — the unscoped form also matches `eval-cli:test`, which has no such class)
  and confirm it passes with no unexpected dependency-edge violations.

## 4. Move the wire-contract docs

- [x] 4.1 `git mv` the wire-contract doc from `docs/experimental/` to `docs/query-dsl/`. Correction from
  the design/tasks assumption: the actual file on disk is `docs/experimental/structured-query-model-v8.html`
  (a single HTML file), not the two `.md` files (`structured-query-model.md`,
  `structured-query-object-model-notes.md`) that `openspec/specs/structured-query-model/spec.md` referenced
  — those two filenames were already stale/non-existent before this change. Moved the real file to
  `docs/query-dsl/structured-query-model-v8.html`.
- [x] 4.2 Removed the now-empty `docs/experimental/` directory.
- [x] 4.3 Updated all references (`openspec/specs/structured-query-model/spec.md`'s "Wire contract" line,
  `query/model/package-info.java`, `query/model/StructuredQueryDeserializationTest`'s Javadoc) to point at
  the real `docs/query-dsl/structured-query-model-v8.html`, fixing the pre-existing stale-filename
  reference in the same edit. Also updated the remaining `experimental.query...`/`experimental/query...`
  package-path mentions in that spec file's "Implementation notes" section while touching it.

## 5. Update documentation

- [x] 5.1 Update `AGENTS.md`: reword the "Never invert an edge to reach experimental code" bullet (the
  pattern no longer applies now that the DSL is stable) and the Query DSL `ParamExpr` row in the Unique
  Patterns table.
- [x] 5.2 Update `docs/key-packages.md`: change the 5 rows for `.experimental.query.*` to `.query.*` and
  drop the "(experimental)" markers. Also fixed two stray `experimental.query...` package-path mentions
  found in `docs/database-schema.md` (not originally listed, but the same category of stale reference).
- [x] 5.3 Update `docs/patterns/README.md` and the 6 pattern docs (`query-dsl-parameters.md`,
  `query-dsl-function-catalog.md`, `overall-score-definition.md`, `query-dsl-entity-resolution.md`,
  `test-cases-query-entity.md`, and any other pattern doc referencing the old path) to use `query...`
  package references, and rewrite the interface-inversion-specific language (e.g.
  `query-dsl-parameters.md`'s rationale for avoiding a `service -> experimental.query.service` edge) to
  reflect that the shims are gone and DSL classes are now depended on directly.
- [x] 5.4 Decided to keep `query-dsl-parameters.md` as a standalone pattern doc — its remaining content
  (the single pre-pass `ParamExpr` resolver mechanics) is a genuine, non-trivial pattern independent of
  the now-removed inversion rationale. Trimmed the inversion-specific paragraph; table rows in
  `docs/patterns/README.md`/`AGENTS.md` updated to describe the resolver instead of the inversion.
- [x] 5.5 Updated the `openspec/specs/**/*.md` implementation-reference passages in
  `evaluation-runner-core-module/spec.md`, `query-schema-discovery/spec.md`,
  `run-comparison-metric-scores/spec.md`, `suite-test-case-filter/spec.md` (Purpose/Implementation Notes
  only), and `openspec/specs/README.md` to use the new `query...` package path and drop
  "experimental"/inversion framing. (`structured-query-model/spec.md` was already handled in task 4.3.)
  Left two passages in `suite-test-case-filter/spec.md` untouched: the "Run selection reuses the DSL
  translation" Requirement body and its "No layering violation is introduced" Scenario both bake the
  interface-inversion mechanism into formal `Requirement:`/`Scenario:` text, which is out of scope per the
  `no-spec-changes.md` decision (no `Requirement:`/`Scenario:` content changes). Those two passages now
  reference a package (`experimental.query`) that no longer exists, so the scenario is vacuously true
  rather than wrong, but the wording is stale — flagged for the user as a followup, not fixed here.

## 6. Verify

- [x] 6.1 Ran `./gradlew spotlessApply` then `./gradlew :build` — main + test compile clean, checkstyle
  clean, `git status` after `spotlessApply` showed no reformatting beyond files already edited in this
  change.
- [x] 6.2 `LayeredArchitectureTest` passed as part of the `:build` run (also verified standalone in task
  3.4).
- [x] 6.3 All 24 moved unit tests under `com.epam.aidial.evaluation.query.*` passed (0 failures/errors
  per their test-result XML), plus `MetricScoreComputationExecutorTest`, `TestSuiteEvaluationJobTest`,
  `TestSuiteRunServiceTest`, `TestSuiteServiceTest` (the tests updated in task 2.7) — all green.
- [x] 6.4 Docker came back up; ran the 6 DSL-related functional suites
  (`MetricScoreComputationTests`, `RunComparisonTests`, `QuerySchemaDiscoveryTests`,
  `TestSuiteStructuredQueryTests`, `EvalSummaryStructuredQueryTests`,
  `MetricScoreResultStructuredQueryTests` — the nested classes in `PostgresFunctionalTests` that extend
  the 5 listed suites plus `MetricScoreComputationFunctionalTests`/`RunComparisonFunctionalTests`) via
  Testcontainers — all green. Re-ran `DslContextSmokeTest` (one of the 3 earlier failures) — passed,
  confirming those were purely the Docker outage. Final `./gradlew :build` (full suite, Testcontainers
  included) completed with exit code 0.
- [x] 6.5 Grepped `src/`, `docs/`, `openspec/` for `experimental.query`/`experimental/query` — zero hits
  outside archived changes (historical) and two deliberately-untouched `Requirement:`/`Scenario:`
  passages in `suite-test-case-filter/spec.md` (flagged in task 5.5) and this change's own
  proposal/design/tasks (which describe the move historically).
