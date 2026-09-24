# Query result page extension

`query.service.repository.QueryResultPageExtender` is the post-execution seam for a structured-query
result page: a place to add values that cannot come from the query's own SQL, typically because they
live on another datasource or behind an external service. One method,
`extend(StructuredQuery, QueryResultPage)`; an implementation reads `query` to decide for itself
whether it applies and returns `page` unchanged when it does not — the coordinator holds no
per-entity knowledge of which extenders exist.

## Why this seam exists

The pipeline has exactly one entity hook, `StructuredQueryEntityResolver.rewrite` (see
[Query DSL entity resolution](query-dsl-entity-resolution.md)), and it runs *before* translation —
there was no post-execution hook before this pattern. The motivating case is `test_suite_runs`'
run-level `overall` score: the entity resolves on `metaDsl`, but the value lives in
`metric_score_result` on the **analytics** datasource. A cross-datasource subquery fails at the
database, so the value cannot enter the entity's SQL at all, from either side. Client-side
resolution is no better — there is no batch metric-score read API and the `computation=latest`
sentinel only resolves a single run, so the FE would need one request per row.

*Alternatives rejected.* Widening `StructuredQueryEntityResolver` with a post-execution hook ties
the extension to one entity: that entity could not carry two independent derived values from two
different sources, and a cross-entity extender (e.g. one that stamps a derived key onto several
entities) would have nowhere to live. A direct branch in the executor for this one case would work,
but it puts analytics-specific domain knowledge into the entity-agnostic query engine, and the same
shape was already needed twice — the run-level `overall` score today, run cost (dial-adas) next —
which is why this is a registered list of implementations rather than a one-off `if`.

## The coordinator

`JooqStructuredQueryExecutorExtender` is the `@Primary` bean implementing `StructuredQueryExecutor`.
It wraps the concrete `JooqStructuredQueryExecutor` — not the `StructuredQueryExecutor` interface,
which would inject itself — runs the query as-is, then folds the resulting page through every
registered `QueryResultPageExtender` bean **in bean order**:

```java
QueryResultPage page = executor.execute(query);
for (QueryResultPageExtender extender : extenders) {
    try {
        final QueryResultPage extended = extender.extend(query, page);
        if (extended == null) {
            log.warn("... returned null for entity {}; continuing without its contribution", query.entity());
        } else {
            page = extended;
        }
    } catch (RuntimeException e) {
        log.warn("... {}", extender.getClass().getSimpleName(), query.entity(), e.getMessage(), e);
    }
}
return page;
```

A `null` return is contained identically to a thrown exception — logged and skipped, page unchanged,
remaining extenders still run. It is not a separate code path bolted on as an afterthought: it exists
because "never return null" is exactly as unenforceable as "never throw" (see the contract section
below), so the coordinator defends against both the same way.

Adding a second extender is a new `@Component QueryResultPageExtender` bean and nothing else — no
coordinator edit, same shape as [the `QueryFunction` catalog](query-dsl-function-catalog.md). The
second implementation, `TotalCostTestSuiteRunsPageExtender` (`enrich-test-suite-runs-total-cost`),
attaches the same entity's run-level `total_cost` from dial-adas usage data, conditional on
`query-dsl.extension.test-suite-run.cost.enabled=true`. Unlike the first extender, its source value
comes from a network call it must bound itself: it captures the caller's credential and OpenTelemetry
context, submits the lookup to a dedicated executor, and waits with a timed `Future.get` that is the
authoritative end-to-end deadline. Every expected asynchronous outcome other than an on-time success
— rejection, timeout, interruption, or the lookup task failing — is caught inside the extender itself,
logged once with the exception last, and degrades to the page it received; see the documented
exception on `QueryResultPageExtender`'s javadoc for why this is not the general failure-swallowing
the contract otherwise warns against. See [`test_suite_runs` query entity](test-suite-runs-query-entity.md)
for the `total_cost` key itself.

## The open key set

A row's keys are **the projection's keys plus zero or more extension-derived keys** — never a
closed set equal to the projection or to the entity's published schema, for every entity, whether or
not any extension currently applies to it. This is deliberately stated once, at the mechanism,
rather than per entity or per extender, so a future extender needs no further spec amendment.

A derived key is merged after SQL and after paging, from a value that may not even come from a
relational store. It therefore:

- is **not queryable** — referencing it in `filter`, `sort`, `group_by` or `select` still returns
  HTTP 400 as an unknown field, exactly like any other unrecognized name;
- is **not published** at `GET /api/v1/queries/entities/schema/{name}`, which continues to advertise
  only the entity's queryable field set.

Publishing it in schema discovery would make the endpoint self-inconsistent — advertising a field
that `/queries/execute` itself refuses to accept back. Tests that assert row shape against this
entity assert containment of the queryable fields, never exact key equality, precisely because the
key set is open by design (see `TestSuiteRunStructuredQueryFunctionalTests` and
`QuerySchemaDiscoveryFunctionalTests`).

## The contract

`QueryResultPageExtender`'s javadoc is the source of truth; summarized:

- **Additive** — only adds keys to a row; never removes one.
- **Non-overwriting** — never overwrites a key a row already carries (a client-supplied `select`
  alias of the same name wins).
- **Order-preserving** — leaves existing key order, row order, and `totalCount()` unchanged.
- **Omit, don't null** — when a value is unavailable for a row, that row carries no key for it,
  never a key with a `null` value.
- **Never returns `null`** — the method itself must always return a non-null page; the input `page`,
  unchanged, is the correct "nothing to add" result.

An implementation must also never mutate jOOQ's row maps in place — `intoMaps()` hands back mutable
`LinkedHashMap`s it still owns — so extending a row means copying it, not writing into it (see the
test fixtures in `JooqStructuredQueryExecutorExtenderTest`, which build a new `LinkedHashMap` per
row).

**Failure isolation is the coordinator's job, not the SPI's.** An implementation is free to throw, or
— despite the fifth contract clause above — to return `null`; `JooqStructuredQueryExecutorExtender`
is the one that catches or detects either, logs (the exception case at warn with the exception as the
last SLF4J argument), and continues with the unextended page and the remaining extenders. This is
deliberately a coordinator guarantee rather than an obligation on implementations — "implementations
must never throw" is unenforceable, and one badly-behaved extender would otherwise turn a working
query into a 500. "Implementations must never return null" is unenforceable for exactly the same
reason — a contract clause is not a compiler check — so the coordinator defends against both failure
modes the same way rather than trusting the SPI to honor the fifth clause it cannot be made to honor.
A reader who saw only the throw-handling could reasonably assume a null return was someone else's
problem; it is the coordinator's, same as a thrown exception. A query that executed successfully must
never fail because a derived value could not be attached to it; the response degrades (missing key),
it does not fail. One failing (or null-returning) extender also must not suppress a later one — each
gets its own try/catch, not one around the loop.

## The `@LogExecution` exception

`JooqStructuredQueryExecutorExtender` deliberately does **not** carry `@LogExecution`, even though
AGENTS.md states every Spring component must. It delegates to `JooqStructuredQueryExecutor`, which
already carries `@LogExecution`; annotating both would log every query twice — once for the
coordinator's pass-through call, once for the actual execution it wraps. This is an explicit,
reasoned exception for a `@Primary` delegating wrapper around an already-annotated component, not an
oversight: `LoggingConventionTest` only checks the exception-as-last-SLF4J-argument convention, so
nothing enforces `@LogExecution` presence and this deviation would otherwise look like a bug to the
next reader.

## See also

- [Query DSL entity resolution](query-dsl-entity-resolution.md) — the pre-execution hook this seam
  complements; together they are the pipeline's only two extension points.
- [Query DSL function catalog](query-dsl-function-catalog.md) — the closest analogue: a registered
  list of SPI implementations folded in by a coordinator, no branch to edit when adding one.
- [`test_suite_runs` query entity](test-suite-runs-query-entity.md) and
  [Computation Versioning](computation-versioning.md) — the run-level `overall_score_value` derived
  key this seam was built for (`enrich-test-suite-runs-overall-score`), and the latest-computation
  resolution it depends on.
