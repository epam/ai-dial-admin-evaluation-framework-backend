## Why

The `test_suite_runs` query entity exposes every plain column of the run table plus snapshot-backed refs, but nothing from `run_config` — an `EXCLUDED_COLUMNS` JSONB payload. Clients comparing runs of the same suite therefore cannot see or filter by **repetitions per test case** (`RunConfigDto.numberOfRuns`) and fall back to `GET /api/v1/test-suite-runs/{id}` per row — the per-item fan-out the entity was introduced to remove.

`RunConfigDto.testRunName` needs no work: it is already the exposed `test_run_name` column. This change covers `numberOfRuns` only.

## What Changes

- Add flat field `number_of_runs` (`integer`, `source: "run_config"`) to the `test_suite_runs` entity, extracted in-database from `run_config ->> 'numberOfRuns'`. Usable in `filter`/`select`/`sort`/`group_by`, present in the empty-`select` projection, published by the schema endpoint.
- `run_config` itself stays excluded (HTTP 400, absent from projection).
- Extend `JsonPathAccessor` SPI with a single-key integer extraction (existing `jsonbAtAsNumeric` is two-level `BigDecimal`).
- No DB schema change, migration, jOOQ regeneration, config property or feature flag. Single PR; rollback = revert.

### Non-Goals

- `run_config.execution.*` / `run_config.retry.*` — tuning knobs, not comparison dimensions.
- `run_config` as an `object`-typed field — reintroduces the heavy-JSONB projection the derived table exists to prevent.

## Capabilities

### Modified Capabilities

- `test-suite-runs-query-entity`: "Entity field set" gains `number_of_runs`; new requirement defines its extraction semantics.

## Impact

Additive API change only (one more field in schema response and row projection). Files and tests: see `tasks.md`. Risks: see `design.md` Decision 4.
