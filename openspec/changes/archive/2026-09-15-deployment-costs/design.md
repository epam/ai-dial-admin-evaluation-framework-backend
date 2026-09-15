## Context

`GET /api/v1/test-suite-runs/{id}/costs` (`TestSuiteRunController#getRunCosts` →
`TestSuiteRunService#getRunCosts`) already answers "what did this run cost" by issuing two dial-adas
`StructuredQuery` aggregate queries against the `dial_usage_log` entity — one per `eval.phase`
(`execution`, `metric-evaluation`) — filtered by `eval.run.id` via a `co` (contains) match against the
`usage_request_baggage.baggage` string field (`eval.run.id` and `eval.phase` are OTel baggage entries, not
first-class ADAS columns; `RunCostQueryBuilder` is the only component that knows this). The query builder
constructs the typed AST directly (package `com.epam.aidial.evaluation.query.model`); `DialAdasClient`
posts it verbatim to dial-adas and deserializes the `count`/`avg_cost` aggregate row.

We need a second way to ask the same cost question — by **deployment id** and an **arbitrary time
range** — since a run-scoped endpoint has no notion of "this month's spend on deployment X". Unlike
`eval.run.id`, dial-adas exposes `deployment` and `request_time` as real top-level columns, so this
query shape differs structurally (direct `eq`/`ge`/`le` comparisons, no baggage `co` wrapping)
even though it reuses the same entity and the same `eval.phase` baggage filter. The select list
diverges from the run query: the deployment-scoped query sums `total_price` (`count()`/`sum(total_price)`,
aliased `total_cost`) instead of averaging it, since callers want total spend over the window, not a
per-call average — the run-scoped query is unchanged and still selects `count()`/`avg(total_price)`
(`avg_cost`).

## Goals / Non-Goals

**Goals:**
- Add `GET /api/v1/costs?deploymentId=&from=&to=` returning `{totalTestCaseCost, totalMetricEvalCost}`
  for a deployment over `[from, to]` (inclusive epoch-millisecond bounds), computed via two dial-adas
  aggregate queries (one per `eval.phase`, `count()`/`sum(total_price)`), mirroring the existing
  run-costs computation shape but summing instead of averaging.
- Support querying *any* time range (this month, previous month, a custom window) by taking `from`/`to`
  as plain caller-supplied epoch-millisecond bounds — no server-side date math, no timezone handling.
- Keep the one dial-adas-query-builder-per-entity convention: extend the existing builder rather than
  creating a second one that duplicates its `dial_usage_log`/`total_price` knowledge.

**Non-Goals:**
- No deployment-existence validation against DIAL Core (a deployment id is treated as an opaque ADAS
  filter value, exactly like `eval.run.id` is today — no matching rows just means `null` totals).
- No server-side time-range presets ("this month", "previous month") — the client computes bounds and
  passes them; this keeps the endpoint's contract simple and avoids introducing timezone-dependent logic.
- No change to the existing run-costs endpoint's *response contract* to API clients — `{avgTestCaseCost,
  avgMetricEvalCost}` is unaffected. The one exception is the outbound dial-adas baggage-field fix in
  Decision 1a: the internal query sent *to* dial-adas is corrected, but what the endpoint returns to
  callers does not change.

## Decisions

**1. Direct comparison nodes for `deployment`/`request_time`, unlike `eval.run.id`/`eval.phase`.**
`deployment` and `request_time` are real ADAS columns, so the new filter builds
`ComparisonNode(EQ, [FieldExpr("deployment"), ValueExpr(STRING, deploymentId)])` and two
`ComparisonNode`s (`GE`/`LE`) on `FieldExpr("request_time")` with `ValueExpr(TIMESTAMP,
String.valueOf(epochMillis))` — directly matching the user-supplied DSL example. `eval.phase` keeps
using the existing `co`-over-baggage-field technique unchanged, since that part of the ADAS schema
hasn't changed structurally (only the field name backing it — see Decision 1a). Alternative
considered: wrap `deployment` in the same baggage-`co` machinery for filter-shape consistency —
rejected, since `deployment` is not actually in baggage and forcing it through the baggage field would
be both wrong and slower (real column vs. string-contains scan).

**1a. Baggage is a direct queryable field (`usage_request_baggage.baggage`), not a `json_extract_string`
unwrap of `dial_usage_log_payload.request_tags`.** dial-adas's schema changed after this design was
first written: `eval.run.id`/`eval.phase` baggage matching no longer needs
`json_extract_string(dial_usage_log_payload.request_tags, "baggage")` wrapping — dial-adas now exposes
the same comma-joined baggage string directly as `usage_request_baggage.baggage`, so the shared
`baggageField()` helper (formerly `jsonExtractBaggage()`) in `AdasCostQueryBuilder` returns a plain
`FieldExpr("usage_request_baggage.baggage")`. This applies to both the existing run-costs query and the
new deployment-costs query, since both reuse the same helper. `AdasCostQueryBuilderTest`'s wire-shape
assertions (`serializesToDialAdasWireShape`, `serializesDeploymentQueryToDialAdasWireShape`) assert the
flattened `{"type": "field", "name": "usage_request_baggage.baggage"}` shape with no `fn` wrapper, and
`TestSuiteRunFunctionalTests`'s `shouldGetRunCosts` re-verification still covers the end-to-end path
with the corrected field.

**2. Rename `RunCostQueryBuilder` → `AdasCostQueryBuilder`; add `buildDeploymentAggregateQuery` beside
the renamed `buildRunAggregateQuery`.** The class is small (75 lines) and is already the single owner of
`dial_usage_log`'s shape (entity name, `total_price`/`avg_cost`/`total_cost` aliasing, baggage-extraction
helper).
Once it builds two structurally different filters for the same entity, keeping the name "Run" is
misleading and would encourage a second, duplicate builder down the line. Alternative considered: leave
`RunCostQueryBuilder` untouched and add a new `DeploymentCostQueryBuilder` — rejected, since it would
duplicate the `ENTITY`/`TOTAL_PRICE_FIELD`/`AVG_COST_ALIAS` constants and the `jsonExtractBaggage()`/
`baggageContains()` helpers the phase filter still needs.

**3. New `CostController`/`CostService`/`DeploymentCostsResponseDto`, not folded into
`TestSuiteRunController`/`Service`.** This endpoint is deployment-scoped, not run-scoped — it has no
run entity to look up, no DB existence check, no `TransactionTemplate` involvement. Bolting it onto
`TestSuiteRunController` would mix an ADAS/analytics concern with the run-entity domain purely because
they happen to share a query builder. `CostService` depends on `AdasCostQueryBuilder` and the existing
`DialAdasClient` bean directly — both are already injectable, no new wiring beyond the two new beans
themselves. Alternative considered: extend `DeploymentService` — rejected per exploration, since
`DeploymentService` only wraps `DialCoreClient` for deployment *metadata* and has no ADAS/cost
dependency; adding one would blur its single responsibility.

**Amendment: `getRunCosts` later moved from `TestSuiteRunService` into `CostService`.** After this
design was written, the existing `getRunCosts` (with its cost-fetching logic) was relocated from
`TestSuiteRunService` into `CostService`, so both the run-scoped and deployment-scoped cost lookups now
live behind one service — the natural home for "cost" as a concern, and it removes the duplicated
empty/zero-count-row mapping this design accepted in Decision 5. This does re-introduce exactly the
DB-existence check this decision said `CostService` wouldn't have: `CostService.getRunCosts` calls
`testSuiteRunService.ensureRunExists(runId)` rather than injecting `TestSuiteRunRepository` directly,
which keeps `TestSuiteRunRepository` scoped to its own domain per AGENTS.md's cross-domain rule (`ensureRunExists`
stays `TransactionTemplate`-scoped and package-private inside `TestSuiteRunService`, now also called from
`CostService`). `TestSuiteRunController#getRunCosts` keeps its route
(`GET /api/v1/test-suite-runs/{id}/costs`, run-scoped URL) but now delegates to `CostService` instead of
`TestSuiteRunService`.

**4. Validation: inline `from > to` check in `CostService`; `@NotBlank` on `deploymentId`.** Matches the
project's convention of explicit inline checks (throwing the existing `ValidationException`, mapped to
`400 VALIDATION_ERROR`) for simple rules not backed by a `@Valid @RequestBody` DTO. `from`/`to` are
required `Long` `@RequestParam`s — Spring already 400s when a required simple param is missing, so no
extra code is needed for "absent" beyond declaring them non-optional.

**Amendment: route restructured to `GET /api/v1/costs/deployment/**` post-archive.** After this change
was archived (pre-merge), the flat `GET /api/v1/costs?deploymentId=&from=&to=` shape was replaced with
`CostController` mapped at the resource root `/api/v1/costs`, with the deployment lookup nested under
`/deployment/**` — anticipating future cost sub-resources under `/api/v1/costs`, which the single
flat-query-param endpoint would not have scaled to cleanly. Since a deployment id may itself contain
slashes (as already true for `deploymentId` here — see the existing run/deployment-id test fixtures),
the id can no longer be a `@RequestParam` bound with `@NotBlank`; it's resolved from the trailing
wildcard via the shared `web.path.WildcardPathResolver` (the same mechanism `DeploymentController` uses
for its own by-ID lookups — see [Slash-containing path values](../../../../docs/patterns/slash-path-values.md)),
with `CostController` performing the blank-tail check itself (`ValidationException`, mirroring
`DeploymentController.validateId`) rather than via Bean Validation. `from`/`to` remain ordinary
`@RequestParam Long` query parameters, unaffected by the path restructuring.

**5. Sequential dial-adas calls, one per phase — same as `TestSuiteRunService.getRunCosts` today.** No
parallelization; consistent with the existing endpoint's decision and avoids introducing new
concurrency/executor concerns for a feature that's explicitly not performance-sensitive (an on-demand
cost lookup, not a hot path). The "empty/zero-count rows → null average/total" mapping is duplicated (not
extracted into a shared helper) between `TestSuiteRunService` and `CostService`: it's ~5 lines, and the
two services have no other reason to share a base class or utility.

## Risks / Trade-offs

- **[Risk] No deployment-existence check** → a typo'd `deploymentId` silently returns `{null, null}`
  instead of a `404`, which could look like "no usage" rather than "wrong id". **Mitigation**: this
  mirrors the existing run-costs endpoint's behavior for a run-id filter that matches zero rows (it
  doesn't 404 either — 404 there comes from the *run entity* lookup, not the ADAS query), so the new
  endpoint's behavior is consistent with the established pattern; deployment ids aren't validated
  because — unlike runs — there's no owned entity to check against without an extra DIAL Core round trip
  on every cost lookup.
- **[Risk] Renaming `RunCostQueryBuilder` touches a file with existing production usage and tests, and
  bundles in the baggage-field fix (Decision 1a)** → a rename done carelessly could silently break
  `TestSuiteRunService`'s existing call site, and the field fix changes the *outbound* query dial-adas
  actually receives for the already-shipped run-costs endpoint.
  **Mitigation**: the rename is mechanical (class name, one method name); `AdasCostQueryBuilderTest`
  retains and adapts every existing assertion (structural AST equality + exact wire-shape JSON) updated to
  the corrected field name, so a broken call site or an unintended shape change fails a test immediately,
  and the run-costs functional test (`shouldGetRunCosts`) re-verifies the end-to-end path still returns
  the same response contract with the corrected field wired through.
- **[Risk] Caller passes an unbounded or very wide time range** → a single dial-adas aggregate query
  still returns one row (`count`/`sum`), so this is not a pagination/memory concern; the only cost is
  dial-adas's own query latency over more matching rows, which is an existing, accepted characteristic
  of aggregate queries against this entity (no mitigation needed beyond what dial-adas itself provides).
