# `test_cases` query entity + suite `testCaseFilter`

`test_cases` is an **instance-aware** DSL entity keyed by `dataset_id`: `PostgresTestCaseEntityResolver.bindings(query)` requires a top-level `dataset_id` equality filter (missing/non-UUID → 400) and builds dataset-specific `data::<field>` typing via `TestCaseFieldBindingsBuilder` (`co`/`nc` on an `ARRAY` field → JSONB containment).

A suite's optional `testCaseFilter` (JSONB) is validated at write time (`TestSuiteService` → 400 on unknown field / unbound suite) and applied at run time as `is_valid AND NOT excluded AND filter` via the inverted `service.domain.job.RunnableTestCaseSelector` interface (impl in `experimental.query.service`, same inversion as `MetricScoreComputation` — **keeps the `service → experimental` edge from existing**).

Multi-turn filtering is **scope-aware ALL-turns-match** (`buildScoped` binds per-turn fields to the turn element, shared fields to the outer row; wrapped as `NOT EXISTS(… IS NOT TRUE)` so any false-or-unknown turn fails); `FilterTranslator` is unchanged.

See [suite-test-case-filter spec](../../openspec/specs/suite-test-case-filter/spec.md).
