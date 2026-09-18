## Context

Requirements: `specs/test-suite-runs-query-entity/spec.md`. Pattern: `docs/patterns/test-suite-runs-query-entity.md`.

- Exclusion of `suite_snapshot`/`run_config`/`error_details` works by omitting them from the `tsr` derived-table projection; nothing rejects them explicitly. A scalar derived from `run_config` must not put the column back into the projection.
- `TestSuiteRunQueryFields` is the single vocabulary for both resolver `bindings()` and `TestSuiteRunsSchemaProvider.baseSchema()`; paired unit tests assert agreement. New fields must enter through it.
- `JsonPathAccessor` offers `jsonbAt`, `jsonbAtAsText` (one key) and `jsonbAtAsNumeric` (two keys, `BigDecimal`). Nothing extracts a one-level integer.
- `run_config` is `JSONB NOT NULL`, always written from a bean-validated `RunConfigDto` (`numberOfRuns` is `@NotNull @Min(1) Integer`).

## Goals / Non-Goals

**Goals:** one aliased expression in the projection + one binding, so `number_of_runs` is an ordinary `integer` field with no special-casing above the resolver; dialect detail stays in `JsonPathAccessor`; no join, subquery or extra table access.

**Non-Goals:** no expression index on `((run_config ->> 'numberOfRuns')::int)` — same cost class as the existing `suite_type` filter, revisit only with a measured slow query. No generalised "expose a JSONB scalar" mechanism; a second such field is the trigger.

## Decisions

### 1. Name `number_of_runs`, not `run_config::numberOfRuns`

`::` means "nested object flattened via `RefDescriptor`" (`deployment_ref::id`). `run_config` is deliberately not addressable; `run_config::*` would invite `run_config::execution::timeout`. `number_of_runs` matches the DTO name and reads as a sibling of `number_of_test_cases`.

### 2. Type `integer`, matching `number_of_test_cases`

`@Min(1) Integer`; same literal shape `{"type":"integer"}` as `number_of_test_cases`. Reusing `jsonbAtAsNumeric` would surface a counting field as `decimal`.

### 3. Projection order: after plain columns, before `suite_type`

Order is observable (empty-`select` projection and schema response, both exact-order asserted). Derived fields stay grouped after the columns; the plain-column loop cannot place it next to `number_of_test_cases` anyway.

### 4. `(run_config ->> 'numberOfRuns')::integer`, fail-fast on non-numeric

Built as `jsonPathAccessor.jsonbAtAsInteger(TEST_SUITE_RUNS.RUN_CONFIG, DSL.val("numberOfRuns"))` aliased `number_of_runs`.

- Missing key → `->>` yields NULL → cast yields NULL. No `CASE`, no guard.
- Non-numeric value (e.g. `"3"` string) → cast raises, whole query fails.

Not guarded on purpose: `run_config` is the run's immutable execution contract, not regenerable data, so the project rule is fail-fast. Such a value means the write path was bypassed; masking it as NULL would hide that while producing wrong comparisons. Bean validation makes it unreachable via the API. `->` jsonb→int cast rejected only for house-style consistency with existing `->>` extractions.

### 5. New SPI method `jsonbAtAsInteger(Field<JSONB>, Field<String>)`

`jsonbGetAttributeAsText(...).cast(SQLDataType.INTEGER)` in `PostgresJsonPathAccessor`, next to `jsonbAtAsNumeric`. Inline cast in the resolver rejected: the JSONB-to-SQL-type decision has one home per dialect. Resolver already injects the accessor; no wiring.

### 6. Constants in `TestSuiteRunQueryFields`

`NUMBER_OF_RUNS_FIELD = "number_of_runs"`, `NUMBER_OF_RUNS_CONFIG_KEY = "numberOfRuns"`; `source` reuses `RUN_CONFIG_COLUMN`, which now is both an `EXCLUDED_COLUMNS` member and a published `source`. Intended; javadoc on both must say so.

### Component interaction

Unchanged. The field enters only at `PostgresTestSuiteRunEntityResolver.table()`/`bindings()`; every layer above sees an ordinary `integer` field. No transaction, error-handling, layering or `service.domain.*` change.

## Migration Plan

None.
