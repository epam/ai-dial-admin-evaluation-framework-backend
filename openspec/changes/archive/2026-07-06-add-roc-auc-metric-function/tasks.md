## 1. Database: stored function

- [x] 1.1 Add Flyway migration `src/main/resources/db/migration/analytics/POSTGRES/V1.11__CreateRocAucScoreFunction.sql` defining `roc_auc_score(y double precision[], p double precision[]) RETURNS double precision` (`LANGUAGE sql`), porting the rank-sum/Mann–Whitney logic: pair `y[i]`/`p[i]` via `unnest(...) WITH ORDINALITY` joined on ordinal position, rank by `p` with average-rank tie handling, sum ranks of the positive class, and return `(rank_sum_pos - n_pos*(n_pos+1)/2.0) / NULLIF(n_pos*n_neg, 0)` (yielding `NULL` when either class is absent).
- [x] 1.2 Update `docs/database-schema.md` to document the new stored function (signature, purpose, migration version).

## 2. Query DSL function catalog

- [x] 2.1 Add `RocAucFunction`, a standalone `@Component QueryFunction` class in `experimental.query.service.translate.function`, registering name `"roc_auc"`, validating exactly 2 arguments via `ctx.args(fn)` (throw `ValidationException` otherwise, matching the `width_bucket`/`percentile_cont` arity-check pattern), casting both resolved argument fields to `Double`, and building `DSL.function("roc_auc_score", Double.class, DSL.arrayAgg(label), DSL.arrayAgg(probability))`.
- [x] 2.2 Run `./gradlew generateJooq` after the migration is in place (if the codegen pipeline needs to see the new function) and confirm no drift is introduced (`JooqSchemaDriftTest`).

## 3. Tests

- [x] 3.1 Add a SQL-level test for `roc_auc_score` (e.g. a `@PostgresFunctionalTests` case invoking the function directly via jOOQ or a raw query) covering: perfect classifier → `1.0`, coin-toss/no-separation labels → `~0.5`, tied `p` values → correct average-rank handling, single-class input (`n_pos = 0` or `n_neg = 0`) → `NULL`.
- [x] 3.2 Add a unit test for `RocAucFunction.translate(...)` verifying it builds the expected `roc_auc_score(array_agg(...), array_agg(...))` SQL shape, following the existing `BuiltInQueryFunctions` function test patterns (e.g. asserting on rendered SQL or via `StructuredQueryBuilderTest`-style query construction).
- [x] 3.3 Add a unit test asserting `roc_auc` called with an arity other than 2 throws `ValidationException` with HTTP 400 mapping (per `Query validation and allowlist` requirement).
- [x] 3.4 Add/extend a functional test (e.g. `EvalSummaryStructuredQueryFunctionalTests` or the metric-score-statistics functional suite) that executes an aggregate-mode `StructuredQuery` calling `roc_auc("data:<labelField>", "metric:<metricName>:<outputField>")` against seeded `eval_summaries` rows with known labels/probabilities, asserting the returned `value` matches a hand-computed / reference ROC AUC value.
- [x] 3.5 Add a functional test setting a test suite's custom `overallScore` expression to a `roc_auc(...)` call and running the Phase-3 metric score computation end-to-end, asserting the persisted `metric_score_result` row (`metricScoreName = metricName = "overall"`) has the expected AUC value.

## 4. Docs and spec sync

- [x] 4.1 Update `AGENTS.md`'s Query DSL inline conventions to mention `roc_auc` as the first stored-function-backed catalog entry (alongside the existing registry-driven catalog description), noting the `array_agg` + custom Postgres function pattern for future similar additions.
- [x] 4.2 Verify `openspec/specs/structured-query-model/spec.md` delta syncs cleanly on archive (no manual sync needed beforehand — confirm during `/opsx:archive`). `openspec validate "add-roc-auc-metric-function" --strict` passes.
