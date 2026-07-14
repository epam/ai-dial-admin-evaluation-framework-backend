## Why

The admin UI needs to let a user define a suite's `overall` score three ways: **mean of all metric
output**, **weighted mean of specific metric output** (`Σ(wᵢ×mᵢ)/Σwᵢ`), or an arbitrary **specific
function**. The third option already worked as a free-form Structured Query DSL expression before this
change, and this change's first phase added the arithmetic (`add`/`subtract`/`multiply`/`divide`) and
`mean` functions the DSL's function catalog was missing (its own spec had already flagged this as "the
planned extension") — letting a client express both remaining options by composing nested function
calls over per-metric aggregates (`avg(metric::A::f)`, …).

That first phase works end-to-end, but a raw DSL composition has two costs: the **frontend** must
hand-build a 3-level nested `divide(add(multiply(...)), add(...))` JSON tree from its metric/weight UI
table, and the **backend** has no structural understanding that "this suite's overall score is a
weighted mean" — Phase 3 just sees an arbitrary expression. This second phase of the same change
replaces the opaque `overallScore` `Map<String, Object>` with a small **typed, sealed
`OverallScoreDefinition`** model (`Mean` / `WeightedMean` / `CustomFunction`):
- The frontend sends a tiny structured payload for the "mean" and "weighted mean" radios — a `type`
  discriminator plus, for `WeightedMean`, a flat `{metricName, outputField, weight}` list mirroring the
  UI table 1:1 — and never builds DSL JSON for either. Only the "specific function" radio still sends a
  raw DSL expression, via `CustomFunction` — exactly today's free-form escape hatch, just wrapped in the
  new discriminator.
- The backend resolves the typed definition into a `StructuredQuery` server-side, still using the
  already-shipped `add`/`multiply`/`divide`/`mean` primitives internally — no new DSL function is needed
  for this; the composition becomes purely a resolver implementation detail.
- `Mean` carries zero parameters and always resolves against whatever metrics the run actually has (the
  same metric-field list Phase 3 already discovers for its per-metric AVG/P10/P90/MIN/MAX statistics) —
  not a list the frontend passed.

## What Changes

- **(Shipped)** Added `add`/`subtract`/`multiply`/`divide`/`mean` to the registry-driven Query DSL
  function catalog (`BuiltInQueryFunctions`, `MeanFunction`) — unaffected by this phase, remain the
  internal building blocks the new resolver composes with.
- Introduce a typed, sealed `OverallScoreDefinition` model (`Mean`, `WeightedMean`, `CustomFunction`)
  replacing the untyped `Map<String, Object>` `overallScore` field on `TestSuiteRequestDto`/
  `TestSuiteResponseDto`/`SuiteSnapshotDto` — **BREAKING** wire-format change for that field (existing
  raw-`StructuredQuery`-shaped payloads must be re-expressed as `{"type":"custom_function","expression":
  {...}}`).
- Add an `OverallScoreDefinitionResolver` that turns the typed definition into a `StructuredQuery` at
  Phase-3 computation time, replacing today's JSON-string `parseExpression` step for the custom branch.
- `Mean` resolution uses the run's currently-discovered metric fields (not anything persisted on the
  suite or passed by the frontend); `WeightedMean` resolution composes `divide(add(multiply(w, avg(...)),
  ...), add(w, ...))` directly from the stored weight list, with no write-time cross-check against the
  suite's actually-configured metrics (stays permissive, matching today's unvalidated philosophy) and no
  change to the existing null-`overallScore` single-metric-only default behavior.
- No DB schema change — `TestSuite.overallScore` stays a `String`/JSONB column; only the JSON shape
  stored inside it changes.

## Capabilities

### New Capabilities
(none — this extends existing capabilities' contracts, not a new feature surface)

### Modified Capabilities
- `structured-query-model`: **(shipped)** the "Supported function catalog" requirement gains five
  entries (`add`, `subtract`, `multiply`, `divide`, `mean`).
- `metric-score-statistics`: the "Overall score" requirement is rewritten — `overall_score` is now a
  typed `OverallScoreDefinition` (`Mean`/`WeightedMean`/`CustomFunction`) instead of an opaque
  `Map<String, Object>`; `Mean` resolves against the run's current metric fields; `WeightedMean` composes
  a weighted average from an explicit metric/weight list; `CustomFunction` preserves the prior free-form
  escape hatch.

## Impact

- **Code**: `service.domain.dto.overallscore` (new package: `OverallScoreDefinition`, `Mean`,
  `WeightedMetric`, `WeightedMean`, `CustomFunction`); `TestSuiteRequestDto`/`TestSuiteResponseDto`/
  `SuiteSnapshotDto`/`JsonbMapper`/`MetricScoreComputationContext`/`TestSuiteEvaluationJob` (field-type
  changes); `experimental.query.service.metricscore.OverallScoreDefinitionResolver` (new);
  `MetricScoreComputationExecutor.computeOverall` (rewritten custom branch).
- **API**: **BREAKING** — `overallScore`'s wire shape changes from an opaque `StructuredQuery` object to
  a discriminated `{"type": "mean" | "weighted_mean" | "custom_function", ...}` object. OpenAPI examples
  on `TestSuiteRequestDto`/`TestSuiteResponseDto` updated accordingly.
- **DB / migrations**: none — `overall_score` column type/name unchanged, only its JSON contents' shape.
- **Config**: none.
- **Docs**: `openspec/specs/metric-score-statistics/spec.md` ("Overall score" requirement, rewritten);
  `openspec/specs/structured-query-model/spec.md` (unchanged from the shipped phase);
  `docs/database-schema.md` (no change — explicitly confirmed, not just skipped).
- **Tests**: JSON round-trip tests for `OverallScoreDefinition`'s three variants; unit tests for
  `OverallScoreDefinitionResolver`; the existing `TestSuiteRunFunctionalTests` overall-score tests
  rewritten to build the typed model instead of hand-written DSL JSON strings (same expected numeric
  results: ROC AUC 0.75, weighted mean 0.68, mean 0.5, unnormalized-weights 1/3).
