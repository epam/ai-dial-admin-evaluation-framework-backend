# Typed `OverallScoreDefinition` for suite `overallScore`

A suite's run-level `overall` metric-score definition (`TestSuiteRequestDto`/`TestSuiteResponseDto`/`SuiteSnapshotDto`.`overallScore`) is a sealed, JSON-discriminated model in `com.epam.aidial.evaluation.runner.dto` (`evaluation-runner-core` module, shared with `eval-cli`), not a raw `Map<String, Object>`:

- `Mean` — no params
- `WeightedMean` — a `List<WeightedMetric>` of `{metricName, outputField, weight}`
- `CustomFunction` — the prior free-form raw `StructuredQuery` expression Map, unchanged escape hatch

`query.service.metricscore.OverallScoreDefinitionResolver` (a plain same-package collaborator of `MetricScoreComputationExecutor`) turns the typed definition into a `StructuredQuery` at Phase-3 computation time:

- `Mean` resolves against the run's **currently discovered** numeric metric fields (not anything persisted on the definition).
- `WeightedMean` composes directly from its stored list (not cross-validated against the suite's configured TSMDs at write time — permissive; a missing metric's `avg` resolves to SQL `NULL` but is coalesced to `0` for that term via the `coalesce` DSL function, so it does not null the whole `overall` result).
- `CustomFunction` converts its Map via `objectMapper.convertValue(..., StructuredQuery.class)` (catch `JacksonException`, not `IllegalArgumentException`, on malformed input — log + skip).

`MetricScoreComputationContext.overallScoreDefinition` carries the typed value directly (no JSON-string round trip between the suite snapshot and Phase 3).

See also: [Query DSL function catalog](query-dsl-function-catalog.md).
