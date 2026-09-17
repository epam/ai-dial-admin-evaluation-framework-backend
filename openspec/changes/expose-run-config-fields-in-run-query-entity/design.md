## Context

See `proposal.md` — Why. Requirements are in `specs/test-suite-runs-query-entity/spec.md`.

Constraints that shape the approach:

- `test_suite_runs` is a **fully derived-table** entity: `PostgresTestSuiteRunEntityResolver.table()` returns `DSL.select(<projection>).from(TEST_SUITE_RUNS).asTable("tsr")`, and row mode with an empty `select` projects `table().fields()`. A field exists for the entity **iff** it is an aliased expression in that projection and has a matching binding. See `docs/patterns/test-suite-runs-query-entity.md`.
- Exclusion of `suite_snapshot`/`run_config`/`error_details` works by simply never putting those columns in the projection list — nothing rejects them explicitly. Deriving a scalar from `run_config` therefore must not put the column itself back into the projection.
- `TestSuiteRunQueryFields` is the single vocabulary both the resolver (`bindings()`) and `TestSuiteRunsSchemaProvider` (`baseSchema()`) build from, with paired unit tests asserting the two agree field-for-field and type-for-type. Any new field must enter through that class or the anti-drift tests will not cover it.
- JSONB path/scalar reads in SQL go through the `JsonPathAccessor` SPI (`data.db.repository.sql.json`), which today offers `jsonbAt`, `jsonbAtAsText` (one key) and `jsonbAtAsNumeric` (two keys, `BigDecimal`). Nothing extracts a one-level integer.
- `run_config` is `JSONB NOT NULL`, always written by serializing a bean-validated `RunConfigDto` whose `numberOfRuns` is `@NotNull @Min(1) Integer`.

## Goals / Non-Goals

**Goals:**

- One aliased scalar expression in the `tsr` projection plus one binding, so `number_of_runs` behaves exactly like any other plain `integer` field — no special-casing in `StructuredQueryBuilder`, `FilterTranslator`, `OrderBy`/`GroupBy` translation, or the schema DTO.
- Keep the SQL dialect detail (JSONB attribute access + the cast) inside `JsonPathAccessor`, not in the `query` slice.
- Add zero table access: the field must be a target-list expression over the run row, not a join or subquery.

**Non-Goals (design-level, beyond the proposal's scope boundary):**

- No expression index on `((run_config ->> 'numberOfRuns')::int)`. Filtering on the field costs a per-candidate-row expression evaluation, the same cost class as the existing `suite_type` filter; there is no evidence of a workload that needs it, and an unused index is pure write cost. Revisit only with a measured slow query.
- No change to `StructuredQueryBuilder`'s projection logic, and no generalised "expose a JSONB scalar" mechanism (no `RefDescriptor`-style descriptor for `run_config`). One field does not justify a framework; a second such field is the trigger to generalise.
- No hardening of the entity against structurally invalid `run_config` payloads beyond what `->>` gives for free (see Decision 4).

## Decisions

### 1. Flat snake_case name `number_of_runs`, not `run_config::numberOfRuns`

The `::` convention in this entity means "one nested object flattened into a fixed set of sub-fields", driven by a `RefDescriptor` (`deployment_ref::id`, `mcp_deployment_ref::transport`). `numberOfRuns` is a single scalar, and `run_config` is deliberately **not** an addressable thing — publishing `run_config::*` would imply the column is navigable and invite requests for `run_config::execution::timeout`.

*Alternatives considered:*
- `run_config::numberOfRuns` — rejected above; also mixes camelCase into an otherwise all-snake_case field vocabulary.
- `number_of_repetitions` / `repetitions` — more descriptive of the semantics, but `number_of_runs` is the name the API already uses (`RunConfigDto.numberOfRuns`) and it reads as a sibling of the existing `number_of_test_cases`. Renaming the concept only at the query layer would be gratuitous drift.

### 2. Type `integer`, matching `number_of_test_cases`

`numberOfRuns` is an `Integer` with `@Min(1)`; a run repetition count has no use for `long` or `decimal` range. `QueryFieldType.INTEGER` also gives the field the same literal-type expectations (`{"type":"integer"}`) as `number_of_test_cases`, so a client filtering on either uses one shape.

*Alternative:* `decimal`, which is what a naive reuse of the existing `jsonbAtAsNumeric` (`BigDecimal`/`NUMERIC`) would have produced. Rejected: it would surface a counting field as a decimal and force decimal literals in filters.

### 3. Projection placement: end of the plain-column block, start of the derived block

Order is `<plain columns>`, `number_of_runs`, `suite_type`, `deployment_ref::*`, `mcp_deployment_ref::*`, `metric_names` — matching the spec's field table. Field order is observable (empty-`select` projection order and the schema-discovery response order, both asserted with exact-order assertions), so it is a decision, not an accident: derived fields stay grouped after the columns they are not, rather than being interleaved next to `number_of_test_cases` where the loop over `TEST_SUITE_RUNS.fields()` cannot place them anyway.

### 4. Text extraction + cast, fail-fast on a non-numeric value

Expression: `(run_config ->> 'numberOfRuns')::integer`, built as `jsonPathAccessor.jsonbAtAsInteger(TEST_SUITE_RUNS.RUN_CONFIG, DSL.val("numberOfRuns"))` and aliased `number_of_runs`.

- A **missing key** → `->>` yields SQL NULL → cast of NULL is NULL. The "missing configuration key yields null" scenario is satisfied with no `CASE` and no guard.
- A **non-numeric value** (e.g. `"3"` as a JSON string, or `null`) → the cast raises, failing the query.

Deliberately not guarded (no regex pre-check, no `CASE WHEN ... ~ '^[0-9]+$'`). Rationale: this is a data-integrity condition, and the project rule is fail-fast for data integrity, graceful degradation only for regenerable data — `run_config` is the run's immutable execution contract, not regenerable. A value that is not an integer means the write path was bypassed, and masking it as NULL would hide that indefinitely while producing silently wrong comparisons. Bean validation on `RunConfigDto` makes the condition unreachable through the API.

*Alternatives considered:*
- `(run_config -> 'numberOfRuns')::integer` (jsonb→int cast, PG 11+). Equivalent behaviour, but every other extraction in this entity and in `jsonbAtAsNumeric` goes through `->>`; matching the house style keeps one mental model of how scalars leave JSONB here.
- `jsonb_path_query_first(..., 'lax $.numberOfRuns')` — lax mode swallows type errors, i.e. the guard rejected above, plus a function call per row.

### 5. New SPI method `jsonbAtAsInteger(Field<JSONB>, Field<String>)`

`JsonPathAccessor` + `PostgresJsonPathAccessor` gain a one-key integer accessor (`jsonbGetAttributeAsText(...).cast(SQLDataType.INTEGER)`), alongside the existing `jsonbAtAsNumeric`. The resolver already injects `JsonPathAccessor`, so this adds no wiring.

*Alternative:* call `jsonbAtAsText(...).cast(SQLDataType.INTEGER)` inline in the resolver. Rejected: it puts the JSONB-scalar-to-SQL-type decision in the `query` slice, where the next such field would copy it; the SPI exists precisely so that decision has one home per dialect. (`jsonbAtAsNumeric` sets the precedent that the cast belongs behind the interface.)

### 6. Constants: two new entries in `TestSuiteRunQueryFields`

`NUMBER_OF_RUNS_FIELD = "number_of_runs"` and `NUMBER_OF_RUNS_CONFIG_KEY = "numberOfRuns"`; the published `source` reuses the existing `RUN_CONFIG_COLUMN`. This is what puts the field under the existing paired anti-drift tests (resolver binding keys ≡ schema field names, types agree) with no new test mechanism.

Note `RUN_CONFIG_COLUMN` now plays two roles — a member of `EXCLUDED_COLUMNS` and the `source` label of a published field. That is the intended relationship (the column is unexposed; a scalar read from it is exposed), and the javadoc on both constants should say so, since the pairing looks contradictory at a glance.

### Component interaction (unchanged shape)

`POST /api/v1/queries/execute` → `StructuredQueryEntityResolver` registry → `PostgresTestSuiteRunEntityResolver.table()`/`bindings()` → `StructuredQueryBuilder` translation → `metaDsl` execution. The new field enters only at `table()`/`bindings()`; every layer above sees an ordinary `integer` field. `GET /api/v1/queries/entities/schema/test_suite_runs` reads `TestSuiteRunsSchemaProvider.baseSchema()`.

No transaction-boundary change (structured query execution is a single read statement), no new error handling path (unknown-field 400 and cast failure are both existing behaviours of existing components), no layering change, and no new package or service-layer component — this change adds no parsing, validation or conversion logic, so it introduces no `service.domain.*` class.

## Risks / Trade-offs

- **`run_config` is now read for every projected row** → It is a small JSON document (`numberOfRuns`, an optional run name, and two optional settings objects of scalars), so it is stored inline rather than TOASTed in practice, unlike `suite_snapshot`. The column is still never projected whole. If a future `RunConfigDto` grows a large member, the extraction — not the exclusion — becomes the thing to re-measure.
- **Filtering/sorting on `number_of_runs` is not index-served** → Per-candidate-row expression evaluation, identical in cost class to the existing `suite_type` filter, and cheap (one JSONB attribute fetch + cast). Accepted; expression index deferred to a measured need (Non-Goals).
- **A malformed `run_config` fails the whole query rather than one row** → Unreachable via the API (bean validation), and a deliberate fail-fast choice (Decision 4). Mitigation if it ever fires: the failure names the cast, so the offending rows are findable with a one-off query; the fix is the data, not the entity.
- **`number_of_runs` reads as a sibling of `number_of_test_cases` but has different semantics** (repetitions per case vs. number of cases) → Mitigated by the schema response carrying `source: "run_config"` for one and `source: "number_of_test_cases"` for the other, plus the spec's field-table note. Worth a sentence in the pattern doc.

## Migration Plan

None. No DDL, no Flyway migration, no jOOQ regeneration (the change touches no table definition), no configuration property, no feature flag. Additive schema field; rollback is reverting the PR.
