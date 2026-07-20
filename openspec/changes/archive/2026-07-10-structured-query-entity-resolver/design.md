## Context

The Structured Query DSL's execution pipeline (`com.epam.aidial.evaluation.experimental.query.*`)
currently has four `StructuredQueryRepository` implementations (`PostgresTestSuiteQueryRepository`,
`PostgresEvalSummaryQueryRepository`, `PostgresMetricScoreResultQueryRepository`,
`PostgresTestCaseQueryRepository`), each hardcoding a `@Qualifier`-injected `DSLContext` and a static
jOOQ `Table<?>` constant, dispatched by `StructuredQueryService` via a `Map<String, StructuredQueryRepository>`
built at startup. `StructuredQueryExecutor` has two overloads: a 4-arg one that resolves bindings from a
`ConcurrentHashMap<Table<?>, Map<String,QueryFieldBinding>>` cache (used by three of the four entities,
whose field sets are fixed per generated table), and a 5-arg one taking caller-supplied bindings, used
only by `test_cases` (whose flattened `data::<field>` JSONB paths depend on the request's own
`dataset_id` — genuinely request-scoped, since the `data` column's shape is user-defined per dataset,
not fixed by any migration). `StructuredQueryBuilder.build(DSLContext, Table<?>, Map<String,QueryFieldBinding>, StructuredQuery)` takes all three of those as caller-supplied parameters even though they are always
fully determined by `query.entity()`.

Subquery support (`in`'s right operand may be a nested `StructuredQuery`) was added on top of this
shape, and needed increasingly indirect plumbing to let a nested query build itself without creating a
`StructuredQueryBuilder ↔ FilterTranslator` bean cycle: first a lazy `ObjectProvider<StructuredQueryBuilder>`
plus a `ThreadLocal<TranslationContext>`, then a `Map<SubqueryExpr, Select<?>>` pre-pass built by the
builder, then a `SubqueryContext(dsl, entity, compiler)` record threaded through translation. Each step
worked, but each also encoded assumptions (the enclosing `dsl`/`table`/`entity`) that become unnecessary
once `build` resolves everything itself from `query.entity()`.

Also relevant: `eval_summaries` (`test_case_eval_summaries`) has the *same* structural shape as
`test_cases` — five JSONB columns (`test_case_data`, `extracted_columns`, `metric_values`,
`metric_infos`, `extraction_warnings`) whose flattened field typing is resolved dynamically by
`EvalSummariesSchemaProvider` (keyed by `test_suite_run_id`/`test_suite_id`, via the run's
`SuiteSnapshotDto` + `RunMetricSnapshot`) for *schema discovery*, but its execution path
(`PostgresEvalSummaryQueryRepository`) deliberately hasn't wired that flattening into the execute path
yet (a known, separate gap called out in its own Javadoc). This confirms the new SPI must support
per-request dynamic binding resolution as a normal capability any entity may need — not a `test_cases`-only
special case — even though only `test_cases` uses it today.

## Goals / Non-Goals

**Goals:**
- Replace the four `StructuredQueryRepository` implementations with a single SPI
  (`StructuredQueryEntityResolver`) plus a registry, so entity → `(dsl, table, bindings, rewrite)`
  resolution is uniform and `test_cases`'s per-request binding logic is that entity's normal
  implementation of the SPI, not a second bypass-the-cache code path.
- Change `StructuredQueryBuilder.build`/`countRows` to accept only a `StructuredQuery`, resolving
  everything else internally via the registry.
- Eliminate `SubqueryContext` and `QueryCompiler` entirely; subquery compilation becomes a single
  `StructuredQueryBuilder.compileSubqueryMembership(SubqueryExpr)` method, reached from `ExprTranslator`
  via one lazy `ObjectProvider<StructuredQueryBuilder>` — the only lazy-bean reference in the pipeline.
- Lift the restriction that `subquery` is valid only as `in`'s right operand — support it as a general
  scalar expression (any comparison operand, select projection, function argument).
- Drop the same-entity restriction on `in`-subqueries; rely on the database's own SQL error (mapped to
  400 by existing error handling) to reject a cross-datasource subquery, rather than validating it
  structurally up front.

**Non-Goals:**
- Wiring `eval_summaries`' JSONB flattening into its execution path — that remains its own,
  already-documented follow-up, unaffected by this change beyond confirming the new SPI would support it.
- Any change to the public wire contract of `POST /api/v1/queries/execute` — request/response DTO shapes
  are unchanged; only where a `subquery` expression is legal to place, and which entity combinations a
  nested subquery may target, expand.
- Any change to `QueryEntityRegistry`/`QueryableEntitySchemaProvider` (the separate schema-discovery
  registry) — it is unrelated to this execution-side registry and is not touched.
- Any change to `QueryDslRunnableTestCaseSelector` (suite `testCaseFilter` evaluation at run time) — it
  doesn't go through `StructuredQueryBuilder`/the repository layer and is out of scope.

## Decisions

**1. New SPI `StructuredQueryEntityResolver`, one implementation per entity, replacing
`StructuredQueryRepository`.**
```java
public interface StructuredQueryEntityResolver {
    String entity();
    DSLContext dsl();
    Table<?> table();
    Map<String, QueryFieldBinding> bindings(StructuredQuery query);
    default StructuredQuery rewrite(StructuredQuery query) { return query; }
}
```
`bindings(query)` takes the query so an instance-aware entity (`test_cases`) can derive request-scoped
bindings; `test_suites`/`eval_summaries`/`metric_score_results` ignore the parameter and return a map
computed once at construction. `rewrite(query)` is a pre-translation hook (default identity), used only
by `metric_score_results` to resolve the `computation_id eq "latest"` sentinel — same rewrite, same
timing as today's `MetricScoreLatestComputationDefaulter` call in `PostgresMetricScoreResultQueryRepository`,
just relocated. Alternative considered: keep bindings resolution as a static `Map` field with a separate
"is this entity instance-aware" flag — rejected because it reintroduces a two-shaped contract (exactly
the cached/precomputed duality being removed) instead of one method every entity implements identically.

**2. `StructuredQueryEntityRegistry` replaces `StructuredQueryService`'s own entity→repository map.**
Collects `List<StructuredQueryEntityResolver>` beans at startup (same `@ConditionalOnProperty`-gated
presence as today's repositories — an entity's resolver only exists as a bean if its datasource vendor
is Postgres). `require(entity)` throws the same `ValidationException` (400, listing supported entities)
that `StructuredQueryService` throws today; this becomes the *single* unknown-entity check, replacing
both that check and `StructuredQueryExecutor`'s now-redundant `entity.equals(query.entity())` guard
(redundant because there is no longer a per-entity repository that could be called with a mismatched
`entity` — the registry resolves strictly from `query.entity()` itself).

**3. `StructuredQueryBuilder.build`/`countRows` take only `StructuredQuery`.**
```java
public SelectQuery<Record> build(StructuredQuery query) {
    final StructuredQueryEntityResolver resolver = entityRegistry.require(query.entity());
    final DSLContext dsl = resolver.dsl();
    final Table<?> table = resolver.table();
    final Map<String, QueryFieldBinding> bindings = resolver.bindings(query);
    ...
}
```
`buildRow`/`buildAggregate`/`resolveGroupKey`/`sortFields` are unchanged apart from receiving these
locally-resolved values instead of caller-supplied parameters.

**4. Subquery compilation is one method on `StructuredQueryBuilder`, reached lazily from
`ExprTranslator` — no `SubqueryContext`, no `QueryCompiler`.**
```java
// StructuredQueryBuilder (package-private — only ExprTranslator calls it)
Select<? extends Record1<?>> compileSubqueryMembership(SubqueryExpr subquery) {
    final StructuredQuery inner = subquery.query();
    if (inner == null) throw new ValidationException("'subquery' requires a 'query'");
    final SelectQuery<Record> subselect = build(inner); // plain self-call
    final Table<?> derived = subselect.asTable(DSL.name("in_subquery"));
    final Field<?> key = derived.field(0);
    if (key == null) throw new ValidationException("'subquery' must select at least one column");
    return subselect.configuration().dsl().select(key).from(derived); // dsl read off the built select
}
```
```java
// ExprTranslator — the ONLY class with an ObjectProvider<StructuredQueryBuilder>
private final ObjectProvider<StructuredQueryBuilder> queryBuilderProvider;

Select<? extends Record1<?>> compileSubqueryMembership(SubqueryExpr subquery) {
    return queryBuilderProvider.getObject().compileSubqueryMembership(subquery);
}
```
`FilterTranslator`'s `in` handling becomes `left.in(exprTranslator.compileSubqueryMembership(subquery))`
— **no new dependency, no signature change** to `FilterTranslator` at all; it already depends only on
`ExprTranslator`. `ExprTranslator.toField` gains one new switch case:
`case SubqueryExpr subquery -> DSL.field(compileSubqueryMembership(subquery));` — this is what makes a
subquery usable as a general scalar expression, since `toField` is the single exhaustive `Expr → Field`
dispatcher already used by comparison operands, select projections (`StructuredQueryBuilder.buildRow`/
`buildAggregate`), and function arguments (`FunctionContext.toField` → every `QueryFunction`).

*Why `ObjectProvider` in `ExprTranslator`, not a `QueryCompiler` function value threaded as a parameter
(an intermediate design considered and rejected):* threading a compiler function through
`toCondition`/`toLogical`/`translateAll`/`toComparison`/`toField`/`FunctionContext` touches every
recursive translation method across three classes. Placing one lazy bean reference in the single class
already responsible for all `Expr → Field` dispatch is a smaller, more localized change, and it means
`FilterTranslator` needs no changes at all beyond one new call.

*Why no same-entity check:* the only reason `subquerySelect` (the pre-refactor method) validated
`ctx.entity().equals(inner.entity())` was to pre-empt a cross-datasource subquery (nesting a meta-DB
table inside an analytics-DB statement, which is not physically possible in one SQL string) with a
clearer error than the database would give. But a cross-datasource subquery still fails — just at
`StructuredQueryExecutor`'s existing `BadSqlGrammarException`/`DataIntegrityViolationException` → 400
mapping, the same path every other DB-level type/grammar mismatch already goes through. Removing the
check removes validation code without removing safety.

**5. `StructuredQueryExecutor` collapses to one `execute(StructuredQuery)` method; the per-`Table`
bindings cache is deleted.**
```java
public QueryResultPage execute(StructuredQuery query) {
    final StructuredQueryEntityResolver resolver = entityRegistry.require(query.entity());
    final StructuredQuery rewritten = resolver.rewrite(query);
    final SelectQuery<Record> select = queryBuilder.build(rewritten);
    // fetch/count/error-mapping unchanged; no dsl/table params needed —
    // select is already attached to the resolved dsl's Configuration via dsl.selectQuery()
}
```
The `ConcurrentHashMap<Table<?>, Map<String,QueryFieldBinding>>` cache is replaced by each
non-instance-aware resolver computing `schemaResolver.bindings(TABLE)` once in its own constructor —
simpler than a runtime `computeIfAbsent` map, since each resolver is already scoped to exactly one table.
`resolver.rewrite(query)` runs once, here, before `build` — not recursively for nested subqueries (see
Risks).

**6. `StructuredQueryService` delegates entirely; no longer holds its own entity map.**
Depends on `StructuredQueryEntityRegistry` + `StructuredQueryExecutor` + `QueryParameterResolver`.
`execute(query, params)`: `entityRegistry.require(query.entity())` (fail fast, same message as today),
then `executor.execute(parameterResolver.resolve(query, params))`. `supportedEntities()` delegates to
the registry.

## Risks / Trade-offs

- **Rewrite-hook scope** → `resolver.rewrite(query)` runs once, at the top-level `execute`, not
  recursively for nested subqueries. This exactly preserves today's behavior (the "latest" rewrite
  already only ever applied to the outer query). A future change wanting the rewrite to also apply
  recursively inside subqueries would need to move the call into `StructuredQueryBuilder.build` itself
  — a deliberate, separate decision, not a side effect of this refactor.
- **Dropping the same-entity check changes error timing, not safety** → a cross-datasource subquery now
  surfaces as a database grammar/type error (still mapped to 400) instead of a purpose-written message.
  Acceptable since the failure mode (400, request rejected) is unchanged; only the error message
  wording differs.
- **Lifting `in`-only restricts less than it might first appear** → several positions remain
  structurally closed regardless (the `co`/`nc` scalar right operand and the `in`-array path both
  reject non-literal operands before ever reaching `toField`), so "usable anywhere" means anywhere the
  grammar already allows a general `Expr`, not literally every syntactic position.
- **Test fixture churn** → `StructuredQueryBuilderTest`/`EvalSummaryQueryRenderTest` need a hand-built
  `StructuredQueryEntityResolver` + `StructuredQueryEntityRegistry`, and `ExprTranslator`'s new
  `ObjectProvider<StructuredQueryBuilder>` constructor argument needs a lambda closing over the test's
  own `builder` field (`() -> builder` — legal since `ObjectProvider` is effectively a single-method
  interface, and Java permits a lambda to reference an instance field declared later in the same class).
  `StructuredQueryServiceTest` needs a larger rewrite since it currently mocks `StructuredQueryRepository`
  per entity; the new `StructuredQueryService` mocks `StructuredQueryExecutor` instead.

## Migration Plan

1. Add `StructuredQueryEntityResolver` + `StructuredQueryEntityRegistry`; add the four resolver
   implementations (ported from the existing repositories, `test_cases`'s `requireDatasetId(...)` logic
   moved verbatim); delete `StructuredQueryRepository` + its four marker interfaces + the four
   `PostgresXxxQueryRepository` classes.
2. Rewrite `StructuredQueryExecutor` to the single `execute(StructuredQuery)` method; delete the
   bindings cache.
3. Rewrite `StructuredQueryBuilder.build`/`countRows` to resolve via the registry; add
   `compileSubqueryMembership`.
4. Add `ObjectProvider<StructuredQueryBuilder>` + `compileSubqueryMembership` + the new `SubqueryExpr`
   case to `ExprTranslator`; update `FilterTranslator`'s `in` handling to delegate to it; delete
   `SubqueryContext` and `QueryCompiler`.
5. Rewrite `StructuredQueryService` to delegate to the registry + executor.
6. Update the three affected unit tests and add new subquery-outside-`in` unit + functional cases.
7. Sync `openspec/specs/structured-query-model/spec.md` and `AGENTS.md` per the proposal.
8. **Rollback**: purely a refactor of in-process wiring with no schema/config/wire changes — reverting
   the commit(s) is sufficient; no data migration or external state to unwind.

## Open Questions

None.
