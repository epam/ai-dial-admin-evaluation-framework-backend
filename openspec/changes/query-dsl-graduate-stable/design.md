## Context

The Structured Query DSL lives under `com.epam.aidial.evaluation.experimental.query.*`: 73 production
classes across `.model`, `.service`, `.service.dto`, `.service.metricscore`, `.service.repository`,
`.service.translate`, `.service.translate.function`, `.web`, plus a mirrored `~25`-class test tree. It is
governed by a dedicated `LayeredArchitectureTest` boundary:

```
experimentalWeb -> experimentalService -> (service, data)
```

with `experimentalWeb`/`experimentalService` only reachable from `configuration` — i.e. nothing outside
`experimental.query` may depend on it. Because of that one-way wall, three stable-layer interfaces exist
purely to let stable code trigger experimental behavior without a compile-time edge crossing the wall:

- `service.domain.job.MetricScoreComputation` → implemented by
  `experimental.query.service.metricscore.MetricScoreComputationExecutor`, triggered by
  `TestSuiteEvaluationJob`.
- `service.domain.job.RunnableTestCaseSelector` → implemented by
  `experimental.query.service.QueryDslRunnableTestCaseSelector`, used by `TestSuiteRunService` and
  `TestSuiteService`.
- `service.domain.analytics.RunComparisonProvider` → implemented by
  `experimental.query.service.metricscore.RunComparisonService`, used by `RunComparisonController`.

No file outside `experimental.query.*` imports its classes directly today — every existing cross-boundary
call already goes through one of the three interfaces above. That confirms the inversion is complete and
isolated: removing it only requires touching those 4 call sites and deleting the 3 interfaces.

The DSL is now the mechanism behind metric-score computation, run comparison, test-case filtering, and
ad-hoc query execution — it is exercised on every suite run and analytics read, not an experiment. This
design lifts it to first-class status: same code, same behavior, moved out from under `experimental` and
freed of the inversion plumbing that only existed to enforce the old wall.

## Goals / Non-Goals

**Goals:**
- Rename the package tree `experimental.query.*` → `query.*` (production + test), with no change in
  runtime behavior, SQL generated, or REST contracts.
- Remove `MetricScoreComputation`, `RunnableTestCaseSelector`, `RunComparisonProvider` and repoint their
  4 call sites at the concrete DSL classes directly.
- Update `LayeredArchitectureTest` so the DSL is governed by the same `web`/`service` layer rules as any
  other code, instead of a separate mirrored boundary.
- Bring documentation (`AGENTS.md`, `docs/key-packages.md`, pattern docs, relevant `openspec/specs/**`
  implementation-reference passages) in line with the new package path and non-experimental framing.
- Relocate `docs/experimental/{structured-query-model,structured-query-object-model-notes}.md` to
  `docs/query-dsl/`.

**Non-Goals:**
- No behavior, schema, API, or configuration change of any kind.
- No change to the DSL's internal design (translation pipeline, entity resolver SPI, function catalog,
  etc.) — only its package location and the removal of the now-pointless inversion shims.
- No introduction of new capabilities or spec requirement changes (confirmed in the proposal).
- Not reconsidering whether other currently-stable interfaces should be collapsed — scope is limited to
  the 3 shims whose only purpose was the experimental boundary.

## Decisions

### 1. New package root: `com.epam.aidial.evaluation.query`
Chosen over nesting under `.service.query`/`.web.query` because it preserves the existing, already-proven
sub-package shape (`.model`, `.service`, `.service.dto`, `.service.metricscore`, `.service.repository`,
`.service.translate`, `.service.translate.function`, `.web`) with a single mechanical prefix change
(`experimental.query` → `query`) rather than restructuring nesting, minimizing diff noise across ~100
files while still reading as a first-class sibling of `web`/`service`/`data`.

### 2. Remove the interface-inversion shims rather than keep them
The three interfaces (`MetricScoreComputation`, `RunnableTestCaseSelector`, `RunComparisonProvider`)
exist solely to satisfy `LayeredArchitectureTest`'s experimental/stable wall — each has a doc comment
saying exactly that. Once that wall is removed (Decision 3), the interfaces have no remaining purpose:
each has exactly one implementation, isn't used for testability (tests can mock the concrete class), and
keeping them would leave a permanent "why does this exist" question for future readers. Alternative
considered: keep them as seams for future alternate implementations — rejected because there is no
concrete plan for a second implementation, and AGENTS.md's guidance is to avoid speculative abstractions
("Don't design for hypothetical future requirements").

Call sites updated to depend on the concrete class directly:

| Interface (deleted) | Concrete class (now injected directly) | Caller(s) |
|---|---|---|
| `MetricScoreComputation` | `query.service.metricscore.MetricScoreComputationExecutor` | `TestSuiteEvaluationJob` |
| `RunnableTestCaseSelector` | `query.service.QueryDslRunnableTestCaseSelector` | `TestSuiteRunService`, `TestSuiteService` |
| `RunComparisonProvider` | `query.service.metricscore.RunComparisonService` | `RunComparisonController` |

`MetricScoreComputationExecutorTest` and any other test doubles typed to the deleted interfaces are
updated to reference the concrete classes; test behavior/assertions are unchanged.

### 3. Fold `query.web`/`query.*` into the existing `web`/`service` ArchUnit layers
Rather than keep a parallel `experimentalWeb`/`experimentalService` pair of layers (now just renamed),
`LayeredArchitectureTest`'s `.layer("web").definedBy(...)` and `.layer("service").definedBy(...)` each
take an additional package pattern:

```java
private static final String QUERY_WEB_PACKAGE = "com.epam.aidial.evaluation.query.web..";
private static final String QUERY_PACKAGE = "com.epam.aidial.evaluation.query..";
```

- `web` layer `definedBy(WEB_PACKAGE, QUERY_WEB_PACKAGE)`
- `service` layer `definedBy(SERVICE_PACKAGE, QUERY_PACKAGE)` — this also covers `query.model` and
  `query.service.*`, so no separate model layer is needed (it isn't gated today either).

This is chosen over inventing a distinct third layer for `query` because, post-inversion-removal, `query`
classes are called from `web` (controllers use `query.service.*` directly, e.g. `RunComparisonController`)
and from `service` (e.g. `TestSuiteEvaluationJob`), and `query` classes themselves call into `service`
and `data` — exactly the access pattern `web`/`service` already have with each other. Modeling `query` as
literally part of those two layers means the existing rules (`web mayOnlyBeAccessedByLayers
configuration`, `service mayOnlyBeAccessedByLayers web, configuration`, `data mayOnlyBeAccessedByLayers
service, configuration`) apply unchanged — no new `mayOnlyBeAccessedByLayers` clauses required, and the
"nothing outside may depend on experimental" restriction disappears because that restriction no longer
applies to first-class code.

### 4. Treat this as a pure mechanical move, verified by the existing test suite
No new tests are introduced. The moved unit/functional tests (listed in the proposal's Impact section)
and `LayeredArchitectureTest` are the verification surface — a green run of all three after the move is
the acceptance bar, per AGENTS.md's Test Execution Discipline (tests must actually be run, not just
written/moved).

## Risks / Trade-offs

- **[Risk] A missed import or stray `experimental.query` reference in a non-obvious file (e.g. a
  `package-info.java`, a Javadoc `{@link}`, or a doc/spec cross-reference) compiles fine but leaves stale
  documentation.** → Mitigation: after the move, grep the full repo (`src/`, `docs/`, `openspec/`) for the
  literal string `experimental.query` / `experimental/query` and confirm zero hits outside intentionally
  preserved historical references (e.g. archived changes), per the proposal's verification step.
- **[Risk] Widening the `web`/`service` layer package patterns could silently permit an unintended
  dependency edge that the old, narrower `experimentalWeb`/`experimentalService` layers would have
  caught.** → Mitigation: run `LayeredArchitectureTest` after the change; because `query.*` classes today
  only call into `service`/`data` (confirmed in exploration) and are only called from `web`/`service`
  (via the 4 call sites), the widened definitions describe the actual dependency graph already in place —
  no behavior change, just a relaxed *label*.
- **[Risk] Large mechanical diff (~100 files) increases the chance of an incomplete IDE-driven rename
  (e.g. a string literal package reference in a test, or a Spring `@ComponentScan`/`AutoConfiguration`
  reference) being missed.** → Mitigation: grep for both the old package literal and `@ComponentScan`
  usages; the module has no existing explicit component-scan override for this package (confirmed no
  `@ComponentScan(basePackages = ...)` targeting `experimental.query` in exploration), so a plain
  class-path rename plus package-declaration/import updates is sufficient.
- **Trade-off**: removing the interfaces is a small internal "breaking" change (nothing external) that
  slightly increases coupling between e.g. `TestSuiteEvaluationJob` and `MetricScoreComputationExecutor`.
  Accepted because the alternative — keeping unmotivated interfaces — has an ongoing readability cost
  with no offsetting benefit once the boundary they served is gone.
