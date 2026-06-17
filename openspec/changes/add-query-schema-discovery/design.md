## Context

The structured query DSL is body-delivered: a client posts an envelope naming an `entity` and flat
field names in its `filter`/`select`/`sort`/`group_by` sections. For that to be usable, the client
needs a machine-readable catalog of (a) which entities are queryable, (b) the flat field names and
types each entity exposes, and (c) for entities backed by JSONB whose shape varies per row, the
flattened field families for a concrete instance.

The implementation already exists on `feat/query-dsl` under
`com.epam.aidial.evaluation.experimental.query.*`. This design documents it as-built. Two entities
are wired today: `test_suites` (simple — a flat metadata table) and `eval_summaries` (complex — the
analytics `test_case_eval_summaries` table, whose `test_case_data` / `extracted_columns` /
`metric_values` / `metric_infos` JSONB columns flatten into per-run field families).

## Goals / Non-Goals

**Goals:**
- Specify the read-only discovery surface under `/api/v1/queries`: entity catalog, base schema,
  detailed schema.
- Specify the provider SPI + registry that makes adding a queryable entity a matter of registering a
  bean, not editing the web layer.
- Specify how the base schema is derived from the generated jOOQ table (so it follows migrations) and
  how the detailed schema is derived from a test suite **run snapshot** for the one complex entity.

**Non-Goals:**
- Query execution (validation, SQL translation, response envelope) — owned by the separate
  `add-structured-query-execution` change against the `structured-query-model` spec.
- The structured query request object model — already specified in `structured-query-model`.
- Any DB schema, migration, or configuration change.
- The throwaway demo HTML pages — explicitly out of scope and not part of the contract.

## Decisions

### Provider SPI + registry
`QueryableEntitySchemaProvider` exposes three operations: `descriptor()` (a `QueryEntityDto` of wire
`name`, `complex` flag, and `schemaIdField`), `baseSchema()`, and a default `detailedSchema(params)`
that throws `UnsupportedOperationException` unless overridden. Providers are Spring `@Component`s;
`QueryEntityRegistry` collects them into a `TreeMap` keyed by name (stable alphabetical ordering) and
rejects duplicate registrations at startup with `IllegalStateException`. The registry is the single
lookup root for the controller.

### Base schema derived from jOOQ, not hand-rolled
`JooqTableSchemaResolver.resolve(Table)` walks the generated table's fields in DDL order and maps each
column to a `QuerySchemaFieldDto(name, type, source)`. Type inference: `VARCHAR(36)` → `uuid` (project
UUID convention), integer/long/decimal/boolean by Java type, and `JSONB` → `array` when the column's
DDL default is a JSON array (`'[...'`) else `object`. Unmapped Java types fail fast with
`IllegalStateException` directing the author to add a type override. Per-column `nameOverrides` /
`typeOverrides` cover exceptions. A sibling `bindings(Table)` returns the reverse map
(field name → `QueryFieldBinding` of jOOQ `Field` + type) consumed by the execution/translation layer
(out of scope here) — kept in the same resolver so discovery and translation share one naming/typing
convention.

### Base vs detailed schema
Base schema is instance-independent and lists JSONB-backed fields as-is (`object`/`array`). For a
complex entity the detailed schema removes the flattenable JSONB fields and substitutes per-instance
field families. `TestSuitesSchemaProvider` is simple (`complex=false`, no `schemaIdField`) and only
supplies a base schema. `EvalSummariesSchemaProvider` is complex; its detailed schema is derived from
a test suite **run snapshot** so the advertised fields match what that run actually produced, even
after the suite, dataset, or metric definitions later change.

### Detailed-schema instance resolution (eval_summaries)
Keyed by run. `test_suite_run_id` (the advertised `schemaIdField`, preferred) resolves the run
directly via `TestSuiteRunService.getRun`; failing that, `test_suite_id` resolves the suite's latest
run via `TestSuiteRunService.getLatestRun`; supplying neither is a validation error. The run's
`SuiteSnapshotDto` yields `data:<field>` (from `testCaseSchema`) and `response:<column>` (from
`responseColumns`); the run's latest-computation `run_metric_snapshots` yield `metric:<name>:<field>`
(always numeric) and `metricInfo:<name>` (an opaque object per metric). These families mirror the CSV
export column planner (the same snapshot-sourced manifest). A run with a null snapshot (legacy,
pre-snapshot model) is rejected with a `ValidationException` — there is no live-state fallback.

### API contract & error mapping
Three `GET` endpoints on `QuerySchemaController` under `/api/v1/queries`:
`/entities`, `/entities/schema/{name}`, `/entities/schema/{name}/detailed` (instance-selecting query
params bound as `Map<String,String>`). Error mapping via the existing handler: unknown entity →
`EntityNotFoundException` → 404; detailed request on a simple entity, missing/malformed instance id, or
a run with no snapshot → `ValidationException` → 400.

### Transaction boundaries
Discovery is read-only. The detailed schema reads through `TestSuiteRunService` (meta datasource,
`readOnly` transaction) and `RunMetricSnapshotRepository` (analytics datasource). The provider holds
no transaction itself; it composes those reads.

## Component interaction flow

```
GET /api/v1/queries/entities/schema/eval_summaries/detailed?test_suite_run_id=<uuid>
  QuerySchemaController
    → QueryEntityRegistry.getDetailedSchema(name, params)
        → provider = lookup(name)                 // 404 if absent
        → require provider.descriptor().complex() // 400 if simple
        → EvalSummariesSchemaProvider.detailedSchema(params)
            → TestSuiteRunService.getRun / getLatestRun   // 404 if run absent
            → requireSnapshot(run)                         // 400 if null snapshot
            → RunMetricSnapshotRepository (latest computation)
            → base schema (minus JSONB families) + data:/response:/metric:/metricInfo: families
```

## Risks / Trade-offs

- **Experimental surface.** Endpoints live under `experimental.*` / `/api/v1/queries` and may change;
  acceptable because no stable client depends on them yet.
- **Detailed schema rejects legacy runs.** Runs created before the snapshot model have no
  `suite_snapshot` and return 400 rather than a best-effort live-state schema. Chosen for correctness
  (a live schema could misrepresent what an old run produced); the alternative (live fallback) was
  explicitly rejected.
- **jOOQ-derived base schema couples discovery to generated sources.** A migration that is not
  followed by `./gradlew generateJooq` would leave the published schema stale; this is the same drift
  risk the repo already guards with `JooqSchemaDriftTest`.
- **Type-override escape hatch.** Columns whose Java type the resolver cannot map fail fast at startup
  rather than silently defaulting — preferred so a new column type is a deliberate decision.
