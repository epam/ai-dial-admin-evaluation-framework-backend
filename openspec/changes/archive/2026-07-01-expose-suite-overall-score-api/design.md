# Design: Expose `overallScore` on the Test Suite REST API

## Context

The per-suite `overall` metric-score definition is already plumbed through every layer except the
REST surface:

- **DB:** `test_suites.overall_score JSONB` exists (`V1.23__AddOverallScoreToTestSuites.sql`).
- **Model:** `TestSuite.overallScore` (String); `TestSuiteMapper.toCloneEntity` already copies it.
- **Snapshot:** `SuiteSnapshotBuilder` captures it verbatim into `SuiteSnapshotDto.overallScore`.
- **Computation:** `MetricScoreComputationExecutor.computeOverall(...)` runs a non-null custom
  expression for **any** metric count (only the *default* overall is single-metric-gated).
- **Query DSL:** `ExprTranslator.resolveFieldOrNull` → `JsonbFieldResolver.resolve` maps a
  `metric::<metricName>::<outputField>` name to `metric_values -> '<metric>' ->> '<field>'::numeric`.
  There is no separate field whitelist, so an authored expression resolves identically to the
  built-in per-metric AVG that a functional test already exercises.

The only missing link is DTO exposure + mapper wiring, plus the `Map → String` write direction in
`JsonbMapper`. This is a web/service-layer change with no schema, config, or architecture impact.

## Goals / Non-Goals

**Goals**
- Accept `overallScore` on `POST`/`PUT /api/v1/test-suites` and return it on read.
- Persist it verbatim to `test_suites.overall_score`; existing snapshot/computation paths pick it up.
- Prove end-to-end that a custom overall referencing one metric of many computes for that metric.

**Non-Goals**
- No richer per-suite "score config" (modes, weights, metric selection compiled into a query) — the
  API surfaces the raw `StructuredQuery` expression only, as reserved in `metric-score-statistics`.
- No change to suite validity semantics (`overall_score` is not a validity component).
- No server-side validation that the expression is a runnable query (see decision below).
- No new migration or jOOQ regeneration.

## Decisions

**1. Represent `overallScore` as `Map<String, Object>`, not `String`.**
Matches the AGENTS.md convention for JSONB-backed JSON fields (e.g. `config_schema`): the DB model
keeps `String`, the DTO exposes a JSON object, and `JsonbMapper` converts. Mirrors the existing
`SuiteSnapshotDto.overallScore` (already `Map<String, Object>`) so the shapes line up across the
request → entity → snapshot chain.
_Alternative (raw `String`):_ rejected — would return escaped JSON strings to clients and break the
established JSONB-as-object contract.

**2. Store the expression opaquely; do not validate it at write time.**
`JsonbMapper.mapOverallScore(Map)` just serializes (serialization of a `Map` cannot fail
meaningfully). This matches `mapJsonSchema(Map)` and the current read-side contract, and keeps the
"fail-fast for data integrity, graceful degradation for regenerable data" split: a malformed or
non-runnable expression is caught at Phase-3 (`MetricScoreComputationExecutor` logs and skips the
`overall` row) rather than blocking suite persistence.
_Alternative (validate/translate the query at write time):_ rejected for now — it would couple the
stable `service`-layer suite write path to the experimental query translator (a new bytecode edge the
`LayeredArchitectureTest` forbids without inversion), for marginal benefit. Deferred; noted as an open
question.

**3. Field references use the double-colon separator: `metric::<metricName>::<outputField>`.**
This is the canonical flattened column form (`EvalSummaryExportColumnConstants.METRIC_COLUMN_PREFIX`
= `"metric::"`, `COLUMN_SEPARATOR` = `"::"`) that `JsonbFieldResolver` resolves. The single-colon
token in the mock-backed `MetricScoreComputationExecutorTest` is never actually translated and must
not be copied into API examples or functional tests.

**4. Wire in `TestSuiteMapper` (`toEntity`, `update`, `toDto`), not a new component.**
The mapper already owns every other JSONB field round-trip; adding one more line per method keeps the
single conversion site. `toRequestDto` also gets it for validation round-trip symmetry.

## Risks / Trade-offs

- **[Client authors an invalid/mistyped expression]** → Phase-3 catches the `ValidationException`,
  logs it (exception as last SLF4J arg), and simply omits the `overall` result; per-metric statistics
  are unaffected. No write-time rejection, so a bad expression is discovered only at run time.
- **[Wrong separator (`metric:...:...`)]** → resolves to an unknown field → `overall` skipped at run
  time. Mitigated by a correct `@Schema` example and the double-colon convention documented here.
- **[Snapshot vs live divergence]** → intentional: a run computes `overall` from the snapshot taken at
  run start, so editing `overall_score` after a run does not retroactively change that run. This is
  the existing snapshot contract, not a regression.

## Migration Plan

None. The column and all downstream wiring already exist; this change only removes an API-layer gap.
Rollback is a pure revert of the DTO/mapper edits — persisted `overall_score` values remain valid and
continue to be honored by computation regardless of whether the API exposes them.

## Open Questions

- Should the API validate that `overallScore` is a translatable `StructuredQuery` at write time
  (returning HTTP 400) rather than silently skipping at run time? Deferred — would require inverting
  the `service → experimental.query` dependency via a `service`-layer interface to preserve layering.
