## Why

The `test_suite_runs` query entity exposes every plain column of the run table plus snapshot-backed refs, but nothing from `run_config` — that column is in `EXCLUDED_COLUMNS` as a heavy/opaque JSONB payload. Clients building run listings and comparisons over the unified query API therefore cannot see or filter by **how many repetitions per test case a run used** (`RunConfigDto.numberOfRuns`), which is a primary axis when comparing two runs of the same suite. They must fall back to `GET /api/v1/test-suite-runs/{id}` per row — exactly the per-item fan-out the entity was introduced to remove.

`RunConfigDto.testRunName` needs no work: it is persisted as the first-class column `test_suite_runs.test_run_name` and is **already** an exposed `string` field of the entity. This change therefore covers `numberOfRuns` only, and records the `test_run_name` finding so the gap is not re-filed.

## What Changes

- Add one flat field `number_of_runs` (type `integer`, `source: "run_config"`) to the `test_suite_runs` query entity, extracted in-database from `run_config -> 'numberOfRuns'`.
- The field is a first-class entity field: usable in `filter`, `select`, `sort` and `group_by`, present in the empty-`select` row projection, and published by `GET /api/v1/queries/entities/schema/test_suite_runs`.
- `run_config` itself stays **excluded** — it is still rejected with HTTP 400 as an unknown field, and still absent from the row projection. Only the scalar extraction is exposed.
- The remaining `RunConfigDto` members (`execution`, `retry`) stay unexposed — see Non-Goals.
- Extend the `JsonPathAccessor` SPI with a single-key integer extraction (the existing `jsonbAtAsNumeric` is two-level and yields `BigDecimal`), keeping the `->>`/cast dialect detail inside `data.db.repository.sql.json`.
- No DB schema change, no Flyway migration, no jOOQ regeneration, no new configuration property.

### Non-Goals

- Flattening `run_config.execution.*` / `run_config.retry.*`. They are execution tuning knobs, not comparison dimensions; adding them would push the entity toward a nested shape, which the "flat and simple" contract of this entity rules out.
- Exposing `run_config` as an `object`-typed field. That would reintroduce the heavy-JSONB projection the derived table exists to prevent.
- Any change to `GET /api/v1/test-suite-runs` or to `RunConfigDto` (no new persisted column, no DTO change).
- Backfilling or normalising historical `run_config` payloads.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `test-suite-runs-query-entity`: the "Entity field set" requirement gains `number_of_runs` (`integer`, source `run_config`); a new requirement defines its extraction semantics (value, nullability on a missing key, filter/sort/group-by support) and reaffirms that `run_config` remains a non-exposed column.

## Impact

**Code (main app, `query` slice):**
- `query/service/TestSuiteRunQueryFields.java` — add the field name + `run_config` JSON key constants (single source of truth for resolver and schema provider).
- `query/service/repository/PostgresTestSuiteRunEntityResolver.java` — add the aliased extraction to the `tsr` derived-table projection and an `INTEGER` binding.
- `query/service/TestSuiteRunsSchemaProvider.java` — publish the field with source `run_config`.
- `data/db/repository/sql/json/JsonPathAccessor.java` + `PostgresJsonPathAccessor.java` — new single-key integer accessor.

**Tests:**
- `PostgresTestSuiteRunEntityResolverTest`, `TestSuiteRunsSchemaProviderTest` (the paired anti-drift tests), `QuerySchemaDiscoveryFunctionalTests`, `TestSuiteRunStructuredQueryFunctionalTests` (new filter/sort/group-by coverage, plus a missing-key null case).

**Docs:**
- `docs/patterns/test-suite-runs-query-entity.md` — note that the entity now also reads a scalar out of `run_config` while keeping the column unexposed.
- `openspec/specs/test-suite-runs-query-entity/spec.md` — delta-synced at archive time. `openspec/specs/README.md` summary stays accurate (no update expected).

**API:** additive only. The entity's schema response and the empty-`select` row projection both gain one field; clients that enumerate schema fields see one more row. No field is renamed or removed, so no client breaks.

**Risks:**
- *Projection cost*: extracting from `run_config` makes the derived table read that column for every projected row. `run_config` is a small JSON document (a handful of scalars) so it is stored inline rather than TOASTed in practice; unlike `suite_snapshot` it carries no per-test-case payload. Mitigation: extraction is a plain target-list expression — no join, no subquery — and the column is still never projected whole.
- *Malformed legacy payloads*: a `run_config` whose `numberOfRuns` is non-numeric would fail the cast and abort the whole query rather than yielding null for that row. `numberOfRuns` is `@NotNull @Min(1) Integer` on `RunConfigDto` and every row is written through that DTO, so no such value can exist; a missing key (structurally possible for a hand-edited row) yields null via `->>`, not an error. Design decides whether to harden the cast further.

**Rollout:** single PR, no migration, no feature flag, no config. Nothing to sequence or reverse beyond the PR itself.

**Test plan (summary; detailed in tasks):** unit tests on the two paired `query/service` classes asserting field-set/type agreement including the new field; functional tests asserting the schema response, the row projection, an `eq`/`gt` filter, a sort, an aggregate `group_by number_of_runs`, a null result for a run whose `run_config` lacks the key, and continued HTTP 400 for a direct `run_config` reference.
