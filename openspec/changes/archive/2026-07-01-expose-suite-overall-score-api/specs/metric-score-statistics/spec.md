## MODIFIED Requirements

### Requirement: Overall score (default; per-suite definition reserved)
The `overall` metric score is a **per-test-suite** definition. The test suite SHALL carry a nullable `overall_score` column (a structured-query `StructuredQuery` expression, JSONB), captured **verbatim** into the suite snapshot at run start, so each run computes `overall` per the suite's configuration at run time. The column defaults to NULL, meaning "use the system default".

The system SHALL expose `overall_score` for reading and writing through the suite API (`overallScore` on suite create/update/get — see `test-suites`). A non-null value is a self-contained expression over the configured metric columns (`metric::<metricName>::<outputField>`), run with only the run-scoping params (`:runId`, `:computationId`); it is stored opaquely and not validated as a runnable query at write time. When the column is NULL, `overall` is computed from the built-in **default** (the single metric's `avg(:metricField)`).

At computation time (Phase 3) the system SHALL resolve the run's `overall` expression from the snapshot and compute it **through the structured-query DSL** (not hardcoded), persisting a single result with `metric_score_name` and `metric_name` both equal to `overall`. A non-null custom expression SHALL be computed **regardless of metric count**. The default (null column) SHALL be computed **only when the run resolves to exactly one numeric metric field** — `overall` is then that metric's average (the system binds `:metricField` to the single field); with more than one metric field the default produces **no** `overall` result.
Status: **Implemented**

#### Scenario: Default overall for a single-metric run
- **WHEN** a run with exactly one numeric metric field completes (suite has no custom `overall_score`)
- **THEN** an `overall` result is produced equal to that metric's average, computed by executing the default `avg(:metricField)` query bound to that field

#### Scenario: Default overall skipped for a multi-metric run
- **WHEN** a run with more than one numeric metric field completes (suite has no custom `overall_score`)
- **THEN** no `overall` result is produced (only the per-metric statistics)

#### Scenario: Custom overall targets one metric of many
- **WHEN** a suite with two numeric metric fields has a custom `overall_score` whose expression averages exactly one of them (e.g. `avg(field metric::<metricA>::score)`), and a run completes
- **THEN** a single `overall` result is produced equal to that one metric's average — not the mean across both metrics — computed through the DSL for any metric count

#### Scenario: Overall configuration is pinned in the suite snapshot
- **WHEN** a run's suite snapshot is taken
- **THEN** the snapshot carries the suite's `overall_score` verbatim, and Phase 3 computes `overall` from the snapshotted value, not the live suite (later edits to the suite's `overall_score` do not change past runs)

### Requirement: Statistics are code-defined (no management API)
The predefined per-metric statistics (`AVG`/`P10`/`P90`/`MIN`/`MAX`) SHALL be defined in code as typed structured-query objects (`BuiltInMetricStatistics`), and SHALL NOT be created, edited, or deleted through any HTTP endpoint; the system exposes no `metric-score-definitions` API. The Phase-3 computation reads these built-in statistics directly; only the computed results are exposed (via the `metric_score_results` Query DSL entity). The `overall` definition is a per-suite property (`test_suites.overall_score`) set through the suite create/update API (`overallScore` — see `test-suites`), not through a dedicated metric-score-definitions endpoint; when unset it stays null and `overall` uses the built-in default.
Status: **Implemented**

#### Scenario: No definition management endpoints exist
- **WHEN** a client looks for endpoints to create, update, or delete metric-score definitions
- **THEN** none exist; the per-metric statistics originate only from code, and the per-suite `overall` definition is set via the suite API, not a definitions endpoint
