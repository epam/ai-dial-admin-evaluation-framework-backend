## ADDED Requirements

### Requirement: Frozen cross-datasource tables are excluded from the non-canonical generator
When a table's canonical generated jOOQ binding must live under one datasource's `jooq.<datasource>.Tables` even though the table's row data physically still exists in the *other* datasource's live schema (a frozen, unread copy kept for rollback safety — see `metrics-storage`), the codegen generator for that other, non-canonical datasource SHALL exclude the table by name via `.withExcludes(...)`, so the table's name resolves to exactly one generated `Tables` constant across the whole codebase.

Status: **Implemented**

#### Scenario: Analytics generator excludes the frozen run_metric_snapshots table
- **WHEN** `./gradlew generateJooq` runs the analytics generator against the live analytics schema, which still physically contains the frozen, unread `run_metric_snapshots` table
- **THEN** the analytics generator's excludes pattern SHALL include `run_metric_snapshots` (alongside the existing `flyway_schema_history` exclusion), so no `RunMetricSnapshots` class is generated under `jooq.analytics.tables`

#### Scenario: The table's canonical binding is unique
- **WHEN** the generated sources are inspected after regeneration
- **THEN** `RUN_METRIC_SNAPSHOTS` SHALL resolve to a generated table constant only under `jooq.meta.Tables`, never under `jooq.analytics.Tables` — eliminating the possibility of a wrong static import that compiles cleanly but queries the frozen analytics copy instead of the live meta table

#### Scenario: Schema drift guard treats the exclusion as expected, not drift
- **WHEN** `JooqSchemaDriftTest` compares the live analytics schema against the generated analytics DSL metadata
- **THEN** it SHALL NOT report `run_metric_snapshots` as missing or drifted on the analytics side — the table is deliberately absent from analytics codegen, and the test asserts its generated binding instead against the meta side's live schema and metadata

## Implementation notes

- `build.gradle`'s analytics jOOQ generator: `.withExcludes("flyway_schema_history|run_metric_snapshots")`.
- `JooqSchemaDriftTest` lists `run_metric_snapshots` under its meta comparison (as an FQN, matching how that test already writes meta tables) and omits it from the analytics comparison entirely.
- This is a permanent convention, not specific to this one table: any future table that must move its canonical binding to the other datasource while a frozen copy remains physically present elsewhere follows the same exclude-by-name pattern.
