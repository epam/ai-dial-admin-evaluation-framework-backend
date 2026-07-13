## Why

The Structured Query DSL's execution pipeline currently duplicates entity-specific wiring across four
near-identical `StructuredQueryRepository` implementations, each hardcoding a `DSLContext` qualifier and
jOOQ `Table`, and — for `test_cases` — building field bindings externally and routing through a second,
cache-bypassing `StructuredQueryExecutor` overload just to support that one entity's dataset-scoped JSONB
typing. `StructuredQueryBuilder.build(dsl, table, bindings, query)` also takes three parameters that are
always fully determined by `query.entity()`, which is exactly why subquery support (`in (subquery)`)
needed increasingly awkward plumbing (a builder callback, then a map pre-pass, then a context record) to
let a nested query resolve and build itself. Collapsing entity resolution into a single SPI removes that
duplication, and once `build` self-resolves everything from `query.entity()`, subquery compilation no
longer needs any of that plumbing — it also stops being structurally limited to `in`'s right operand,
since the same compiled result can serve as a scalar `Field` anywhere an expression is valid.

## What Changes

- Replace the four `StructuredQueryRepository` implementations (`PostgresTestSuiteQueryRepository`,
  `PostgresEvalSummaryQueryRepository`, `PostgresMetricScoreResultQueryRepository`,
  `PostgresTestCaseQueryRepository`) and the `StructuredQueryRepository` interface + its four marker
  sub-interfaces with a new `StructuredQueryEntityResolver` SPI (`entity()`, `dsl()`, `table()`,
  `bindings(query)`, `rewrite(query)`) and a `StructuredQueryEntityRegistry` that dispatches by
  `query.entity()`. `test_cases`'s dataset-scoped binding logic and `metric_score_results`'s
  `computation_id eq "latest"` rewrite move into their respective resolvers unchanged in behavior.
- `StructuredQueryBuilder.build`/`countRows` change from `(DSLContext, Table<?>, Map<String,QueryFieldBinding>, StructuredQuery)` to a single `(StructuredQuery)` parameter, resolving everything internally via the
  registry. **BREAKING** (internal API only — no public HTTP contract change): any future in-process
  caller of these methods must update its call sites.
- `StructuredQueryExecutor` collapses to one `execute(StructuredQuery)` method; its per-`Table` bindings
  cache is removed (replaced by each resolver computing its static bindings once at construction).
- Delete `SubqueryContext` and `QueryCompiler`. Subquery compilation moves to a single
  `StructuredQueryBuilder.compileSubqueryMembership(SubqueryExpr)` method, reached lazily from
  `ExprTranslator` via a new `ObjectProvider<StructuredQueryBuilder>` dependency (the only lazy-bean
  reference in the pipeline, breaking the `StructuredQueryBuilder → FilterTranslator/ExprTranslator`
  constructor cycle). `FilterTranslator` gets no new dependency or signature change.
- **Lift the restriction that a `subquery` expression is valid only as `in`'s right operand.** A
  `SubqueryExpr` now compiles to a scalar `Field` (via `ExprTranslator.toField`) anywhere any other
  expression is valid: either operand of any comparison, a `select` projection, or a function argument.
  Positions already structurally closed to non-literals (`co`/`nc`'s scalar right operand, `in`'s array
  path) remain closed.
- **Drop the same-entity restriction on `in`-subqueries.** A subquery may now target a different entity
  than its enclosing query. A cross-*datasource* subquery (e.g. a meta-DB entity nested inside an
  analytics-DB query) is not validated up front — it fails naturally at the database with a normal SQL
  error, already mapped to HTTP 400 by existing error handling, consistent with how other DB-level
  type-mismatches are already surfaced.

## Capabilities

### New Capabilities
(none — this extends the existing `structured-query-model` capability)

### Modified Capabilities
- `structured-query-model`:
  - "Expression grammar" requirement: `subquery` kind's description no longer restricts it to `in`'s
    right operand.
  - "`in` predicate with array or subquery operand" requirement: drop the "valid only as the right
    operand of `in`" and "same entity" constraints; add a requirement/scenario for a subquery used as a
    general scalar expression (comparison operand, select projection, function argument); note
    cross-datasource subqueries are rejected by the database at execution, not by structural validation.
  - "Implementation notes" section: update class names (`StructuredQueryRepository` →
    `StructuredQueryEntityResolver`/`StructuredQueryEntityRegistry`; `PostgresTestSuiteQueryRepository`
    → `PostgresTestSuiteEntityResolver`, etc.).

## Impact

- **Code**: `experimental.query.service.repository` (delete `StructuredQueryRepository` + 4 marker
  interfaces + 4 Postgres impls; add `StructuredQueryEntityResolver`, `StructuredQueryEntityRegistry`,
  4 new resolver impls; rewrite `StructuredQueryExecutor`), `experimental.query.service.translate`
  (rewrite `StructuredQueryBuilder`, `ExprTranslator`; delete `SubqueryContext`, `QueryCompiler`; no
  change to `FilterTranslator`'s dependencies), `experimental.query.service.StructuredQueryService`
  (delegates to the new registry instead of holding its own entity map).
- **API**: No change to the public wire contract or `POST /api/v1/queries/execute` request/response
  shape — `SubqueryExpr`'s JSON shape (`{"type":"subquery","query":{...}}`) is unchanged; only where it's
  legal to place one expands.
- **Tests**: `StructuredQueryBuilderTest`, `EvalSummaryQueryRenderTest`, `StructuredQueryServiceTest`
  need their hand-built fixtures updated for the new constructors/signatures (no production behavior
  change for existing cases). New unit + functional test cases cover subquery-outside-`in` (scalar
  comparison, select projection) with real assertions on returned values, not just absence of errors.
- **Docs**: `openspec/specs/structured-query-model/spec.md` (see Modified Capabilities), `AGENTS.md`'s
  "Query DSL subquery-valued `in`" inline convention bullet.
- **No DB schema or config changes.**
