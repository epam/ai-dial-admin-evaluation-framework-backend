## Why

The Structured Query DSL was introduced under `com.epam.aidial.evaluation.experimental.query.*` as an
experimental subsystem, with a dedicated `LayeredArchitectureTest` boundary and three interface-inversion
shims (`MetricScoreComputation`, `RunnableTestCaseSelector`, `RunComparisonProvider`) whose sole purpose
was letting stable-layer callers trigger DSL-backed behavior without a compile-time edge into
experimental code. The DSL now backs metric-score computation, run comparison, test-case filtering, and
ad-hoc query execution — it is core, load-bearing functionality, not an experiment. Its package name and
the inversion plumbing built around "experimental" status no longer reflect reality and add indirection
with no remaining purpose.

## What Changes

- Move all 73 production classes from `com.epam.aidial.evaluation.experimental.query.*` to
  `com.epam.aidial.evaluation.query.*` (same sub-package layout: `.model`, `.service`, `.service.dto`,
  `.service.metricscore`, `.service.repository`, `.service.translate`, `.service.translate.function`,
  `.web`), and the mirrored `~25`-class test tree the same way.
- **BREAKING (internal only, no external API change)**: remove the three interface-inversion shims —
  `MetricScoreComputation`, `RunnableTestCaseSelector`, `RunComparisonProvider` — since the
  experimental/stable boundary that motivated them no longer exists. `TestSuiteEvaluationJob`,
  `TestSuiteRunService`, `TestSuiteService`, and `RunComparisonController` depend on the concrete DSL
  classes (`MetricScoreComputationExecutor`, `QueryDslRunnableTestCaseSelector`, `RunComparisonService`)
  directly.
- Update `LayeredArchitectureTest`: remove the `experimentalWeb`/`experimentalService` layers; fold
  `query.web` into the `web` layer definition and `query.*` (service/model) into the `service` layer
  definition, so the DSL is governed by the same rules as any other web/service code.
- Update all documentation and specs that reference the old package path or frame the DSL as
  experimental: `AGENTS.md`, `docs/key-packages.md`, `docs/patterns/README.md` and six pattern docs
  (`query-dsl-parameters.md`, `query-dsl-function-catalog.md`, `overall-score-definition.md`,
  `query-dsl-entity-resolution.md`, `test-cases-query-entity.md`, plus any `package-info.java`), and the
  relevant `openspec/specs/**/*.md` implementation-reference sections.
- Move the wire-contract docs `docs/experimental/structured-query-model.md` and
  `docs/experimental/structured-query-object-model-notes.md` to `docs/query-dsl/`.

No REST API, request/response contract, database schema, or runtime behavior changes — this is a
package/naming/internal-wiring refactor. All DSL behavior (schema discovery, query execution, metric
score computation, run comparison, test-case filtering) is unchanged.

## Capabilities

### New Capabilities
(none — no new externally observable capability)

### Modified Capabilities
(none — no spec-level requirement changes; existing specs' implementation-reference notes that cite the
old `experimental.query` package path will be updated as part of `tasks.md`, but no `Requirement:` or
`Scenario:` text changes)

## Impact

- **Code**: 73 main classes + ~25 test classes relocated (package rename only, no logic changes); 3
  interface files deleted; 4 call sites (`TestSuiteEvaluationJob`, `TestSuiteRunService`,
  `TestSuiteService`, `RunComparisonController`) repointed to concrete classes;
  `LayeredArchitectureTest` layer definitions updated; `MetricScoreComputationExecutorTest` and any other
  test doubles referencing the deleted interfaces updated to reference concrete classes.
- **Docs**: `AGENTS.md`, `docs/key-packages.md`, `docs/patterns/README.md`, 6 pattern docs, and
  implementation-reference passages in 5 `openspec/specs/**/spec.md` files plus
  `openspec/specs/README.md`; `docs/experimental/` directory relocated to `docs/query-dsl/`.
- **No** database, API, or configuration changes.
- **Risk**: purely mechanical but wide-reaching (package move touches ~100 files); the main correctness
  risk is `LayeredArchitectureTest` regressions or missed import/reference updates — mitigated by running
  the architecture test, the moved unit tests, and the DSL-related functional test suites after the move.
