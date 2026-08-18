# `test_cases` query entity + suite `testCaseFilter`

`test_cases` is an **instance-aware** DSL entity keyed by `dataset_id`: `PostgresTestCaseEntityResolver.bindings(query)` requires a top-level `dataset_id` equality filter (missing/non-UUID → 400) and builds dataset-specific `data::<field>` typing via `TestCaseFieldBindingsBuilder` (`co`/`nc` on an `ARRAY` field → JSONB containment, never LIKE).

`co`/`nc` accept two operand shapes over an `ARRAY` field, both matching **whole elements** (`"tee"` never matches the element `"tee-shirt"`):

| Left operand | Emitted predicate | Case |
|---|---|---|
| bare field (`data::tags`) | `col ? 'tee'` (string) / `col @> to_jsonb(?)` (other literal) | sensitive |
| `lower(data::tags)` / `upper(data::tags)` | `exists (select 1 from jsonb_array_elements_text(case when jsonb_typeof(col) = 'array' then col else '[]'::jsonb end) as e(v) where lower(e.v) = lower(?))` | **in**sensitive |

The wrapper is a *hint*, not SQL: `lower(jsonb)` does not exist in Postgres, so `FilterTranslator` discards it and folds case instead of emitting it. Translating it literally yields a statement that fails at execution with SQLSTATE 42883 — 400 through `/queries/execute` (`StructuredQueryExecutor` maps it), an unhandled 500 at run creation (GH #142, reported as a 403 because the `/error` dispatch is not in `publicPathPatterns()` and `anyRequest().denyAll()` catches it).

**Two divergences between the shapes**, both deliberate and pinned by tests:
- the wrapped form expands elements as text, so a string operand also matches an equally-rendered non-string element (`"1"` matches `[1, 2]`); the bare `?` form inspects string elements only;
- for a row whose `ARRAY`-declared field holds a **non-array** value (a coerced import can produce one), the wrapped form yields `'[]'` and does not match, while `?` matches a string value equal to the operand and an object with it as a key — and `@>` matches a scalar equal to the operand.

The type guard must stay **inside** the `jsonb_array_elements_text` argument, since the planner may reorder a sibling `AND` conjunct. That guard, not the `is not false` wrapper, is what makes the wrapped `nc` total over null/absent/off-type values (`EXISTS` is never UNKNOWN). For a non-string literal the wrapper is dropped and the case-sensitive `@>` form is used. Neither shape is index-served here: there is no GIN index on `test_cases.data`, and the left side is always the extracted expression `data -> '<field>'`, which `jsonb_ops` cannot satisfy even where one exists.

A suite's optional `testCaseFilter` (JSONB) is validated at write time (`TestSuiteService` → 400 on unknown field / unbound suite) and applied at run time as `is_valid AND filter` (there is no per-suite exclude list) via the inverted `service.domain.job.RunnableTestCaseSelector` interface (impl in `experimental.query.service`, same inversion as `MetricScoreComputation` — **keeps the `service → experimental` edge from existing**).

Multi-turn filtering is **scope-aware ALL-turns-match** (`buildScoped` binds per-turn fields to the turn element, shared fields to the outer row; wrapped as `NOT EXISTS(… IS NOT TRUE)` so any false-or-unknown turn fails). A *negated* leaf never reaches that quantifier as unknown — see [Query DSL null polarity](query-dsl-null-polarity.md) for why, and for why `FilterTranslator` owns that decision rather than the selector.

See [suite-test-case-filter spec](../../openspec/specs/suite-test-case-filter/spec.md).
