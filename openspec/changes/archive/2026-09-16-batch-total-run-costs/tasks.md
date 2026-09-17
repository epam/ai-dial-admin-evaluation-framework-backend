## 1. Add the `case` expression kind to the query model

- [x] 1.1 Add `src/main/java/com/epam/aidial/evaluation/query/model/WhenClause.java`:
  `record WhenClause(@JsonDeserialize(using = FilterNodeDeserializer.class) FilterNode when, Expr then)`
  (done: compiles; `when` deserializes via the same `FilterNodeDeserializer` used by
  `StructuredQuery.filter`/`.having`).
- [x] 1.2 Add `src/main/java/com/epam/aidial/evaluation/query/model/CaseExpr.java`:
  `record CaseExpr(List<WhenClause> when, @JsonProperty("else") Expr elseExpr) implements Expr`
  (done: compiles; `else` maps to the `elseExpr` component name via `@JsonProperty`).
- [x] 1.3 Register `CaseExpr` in `src/main/java/com/epam/aidial/evaluation/query/model/Expr.java`: add
  `CaseExpr` to the sealed interface's `permits` clause and a `@JsonSubTypes.Type(value = CaseExpr.class,
  name = "case")` entry (done: `Expr` has 7 permitted kinds; deserializing `{"type": "case", ...}` binds
  to `CaseExpr`).
- [x] 1.4 Update `ExprTranslator.toField`
  (`src/main/java/com/epam/aidial/evaluation/query/service/translate/ExprTranslator.java`) with a new
  `case CaseExpr _ -> throw new ValidationException(...)` branch mirroring the existing `ArrayExpr`
  rejection (done: the exhaustive switch compiles; a `case` expression submitted against any internal
  entity via `POST /api/v1/queries/execute` returns HTTP 400 — verified by task 2.2's test).
- [x] 1.5 Update `QueryParameterResolver.resolveExpr`
  (`src/main/java/com/epam/aidial/evaluation/query/service/translate/QueryParameterResolver.java`) with a
  recursive `CaseExpr` branch that rewrites each `WhenClause.when()` via the existing `resolveFilter`
  helper and each `WhenClause.then()`/`elseExpr` via `resolveExpr` (done: the exhaustive switch compiles; a
  `param` inside a `case` expression's `when`/`then`/`else` is substituted the same as anywhere else —
  verified by task 2.1's test).

## 2. Query-model tests and spec sync

- [x] 2.1 Add/extend `QueryParameterResolverTest` with a case substituting a `param` inside a `CaseExpr`'s
  `then` and `else`, and inside a `WhenClause.when()` filter (done: test passes, asserts the rewritten
  `CaseExpr` structure).
- [x] 2.2 Add a test asserting `ExprTranslator.toField` rejects `CaseExpr` with `ValidationException` for
  an internal entity (new test method in the existing `ExprTranslator` test class, or via
  `StructuredQueryController` functional coverage if no unit test class exists yet) (done: test passes;
  added new `ExprTranslatorTest` unit test class, since none existed).
- [x] 2.3 Sync `openspec/specs/structured-query-model/spec.md`'s "Expression grammar" requirement with the
  MODIFIED content already drafted in
  `openspec/changes/batch-total-run-costs/specs/structured-query-model/spec.md` (done: the live spec
  documents all 7 `Expr` kinds; this sync happens for real at `/opsx:archive` via `openspec update`, so
  this task tracks reviewing the delta content only, not hand-editing the live spec early — reviewed, the
  delta covers all 7 kinds, the `case` grammar, the internal-entity rejection scenario, and the
  param-substitution-inside-`case` scenario).
- [x] 2.4 Run `./gradlew test --tests "com.epam.aidial.evaluation.query.service.translate.*"` and confirm
  it passes (done: `:test --tests "com.epam.aidial.evaluation.query.service.translate.*"` passes, covering
  both `QueryParameterResolverTest` and the new `ExprTranslatorTest`).

## 3. `AdasCostQueryBuilder.buildPageTotalCostQuery`

- [x] 3.1 Add `run_id`/`"other"` constants and
  `buildPageTotalCostQuery(Collection<UUID> runIds)` to
  `src/main/java/com/epam/aidial/evaluation/service/domain/AdasCostQueryBuilder.java`: OR filter across
  per-run `baggageContains(baggageField(), "eval.run.id=" + id)` predicates (single predicate unwrapped
  when `runIds.size() == 1`, no `eval.phase` predicate), a `run_id`-aliased `CaseExpr` select column (one
  `WhenClause` per run id, `else` = the `"other"` sentinel), plus `count()` and `sum(total_price)` aliased
  `total_cost`, `group_by: ["run_id"]` (done: method compiles and returns a `StructuredQuery` matching the
  design's Decision 2 shape).
- [x] 3.2 Add `run_id` (`@JsonProperty("run_id") private String runId;`) to
  `src/main/java/com/epam/aidial/evaluation/client/dialadas/dto/AdasAggregateRowDto.java` (done: `runId`
  deserializes from a dial-adas response row alongside the existing `totalCost`). **Superseded later**:
  `AdasAggregateRowDto` was subsequently deleted and split into three per-query-shape row DTOs
  (`AdasRunAvgCostRowDto`, `AdasDeploymentCostRowDto`, `AdasBatchRunCostRowDto`) per explicit user
  feedback that the single shared DTO was growing unwieldy — `run_id` now lives only on
  `AdasBatchRunCostRowDto`, the row type this task's `buildPageTotalCostQuery` actually pairs with.
  `AdasAggregateResponseDto` became generic and `DialAdasClient.executeAggregate` gained a `Class<T>
  rowType` parameter to support this; `DialAdasClientTest`, `CostServiceTest`, and
  `TestSuiteRunFunctionalTests` updated accordingly (all pass). See design.md Decision 3's third revision
  note.
- [x] 3.3 Add `AdasCostQueryBuilderTest` coverage for `buildPageTotalCostQuery`: a structural assertion
  (OR filter for N>1 runs, unwrapped single predicate for N=1, no phase filter, `CaseExpr` select column,
  `group_by`) and a full JSON wire-shape assertion matching the user-verified query, mirroring the existing
  `serializesToDialAdasWireShape` pattern (done: test passes; added
  `buildsPageTotalCostQueryForMultipleRuns`, `buildsPageTotalCostQueryForSingleRun`, and
  `serializesPageTotalCostQueryToDialAdasWireShape`).
- [x] 3.4 Run
  `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.AdasCostQueryBuilderTest"` and confirm
  it passes (done: passes clean, including a re-run after the final `spotlessApply` pass).

## 4. Batch total-cost fetching logic

> **Revised twice after initial implementation, both per explicit user feedback**: (1) this group
> originally also added `RunCostStatus` (`enum { AVAILABLE, NO_DATA, UNAVAILABLE }`) and `RunCost(Double
> value, RunCostStatus status)`, with a `RunCostFetcher` component returning `Map<UUID, RunCost>` — the
> per-item status was dropped, so fetching returns a plain `Map<UUID, Double>` (a requested id absent from
> the map means "no data"), and `DialAdasClientException` is no longer caught — it propagates, failing the
> whole request with 502/504 instead of resolving every id to `UNAVAILABLE`. `RunCost.java` and
> `RunCostStatus.java` were deleted. (2) the `RunCostFetcher` `@Component` itself was then removed —
> folded directly into `CostService` as a private `fetchTotalCosts` method, since a dedicated class with a
> single caller and no remaining status/record types to justify was pure indirection. `RunCostFetcher.java`
> and `RunCostFetcherTest.java` were deleted; the fetcher's test coverage moved into
> `CostServiceTest`'s `GetRunCostsBatch` nested class, mocking `AdasCostQueryBuilder`/`DialAdasClient`
> directly instead of a `RunCostFetcher` mock. See design.md Decision 3's revision notes.

- [x] 4.1 ~~Add `RunCostStatus.java`~~ — removed; see revision note above.
- [x] 4.2 ~~Add `RunCost.java`~~ — removed; see revision note above.
- [x] 4.3 ~~Add `RunCostFetcher.java` as a separate `@Component`~~ — folded into `CostService` as a
  private `fetchTotalCosts(Collection<UUID> runIds)` method: calls `DialAdasClient.executeAggregate` via
  `AdasCostQueryBuilder.buildPageTotalCostQuery`; groups returned rows by parsed `run_id` (skipping the
  `"other"` bucket); a requested id with no matching row is simply absent from the returned map; a
  `DialAdasClientException` from the underlying call is not caught, so it propagates to the caller (done:
  compiles; matches design.md Decision 3's revised text; unparseable/`"other"` run ids are skipped via a
  `UUID.fromString` parse failure rather than a hardcoded string match).
- [x] 4.4 ~~Add `RunCostFetcherTest`~~ — folded into `CostServiceTest`'s `GetRunCostsBatch` nested class:
  a matched row's `total_cost` returned, an unmatched id (and the `"other"` bucket) omitted from the
  result, `DialAdasClientException` propagating from `getRunCosts`, and empty `runIds` input rejected
  (done: test passes).
- [x] 4.5 Run `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.CostServiceTest"` and
  confirm it passes (done: all cases pass, including the batch-related ones and a re-run after the final
  `spotlessApply` pass).

## 5. `CostService` batch method and `TotalRunCostResponseDto`

> **Renamed after initial implementation, per explicit user feedback**: `RunCostResponseDto` →
> `TotalRunCostResponseDto`, `RunCostsBatchRequestDto` → `TotalRunCostRequestDto`,
> `CostService.getRunCosts(List<UUID>)` → `CostService.getTotalRunCosts(List<UUID>)`,
> `CostController`'s matching batch method likewise → `getTotalRunCosts`. The original names were too easy
> to confuse with the pre-existing single-run `RunCostsResponseDto`/`CostService.getRunCosts(UUID)`
> (average cost, not total cost) — `totalRunCost` in the name resolves exactly that ambiguity. See
> design.md Decision 4's rename note.

- [x] 5.1 Add `ValidationConstants.MAX_BATCH_RUN_IDS = 1000` (matching `pagination.max-size`) to the
  project's `ValidationConstants` class (done: constant exists, no duplicate definition elsewhere).
- [x] 5.2 Add `src/main/java/com/epam/aidial/evaluation/service/domain/dto/TotalRunCostResponseDto.java`
  (originally `RunCostResponseDto.java`; see rename note above): `record TotalRunCostResponseDto(UUID
  runId, Double totalCost)` with `@Schema` documentation (done: compiles; implemented as a
  `@Data`/`@Builder`/`@NoArgsConstructor`/`@AllArgsConstructor` class with the same two fields —
  originally three, including `RunCostStatus status`, before that field was dropped per user feedback
  (see Group 4's revision note) — matching the sibling `RunCostsResponseDto`/`DeploymentCostsResponseDto`
  convention in the same package rather than a bare Java `record` — no existing response DTO in
  `service.domain.dto` uses `record`, and this keeps `@Schema` placement consistent; behavior/shape is
  identical to the design's spec).
- [x] 5.3 Add `CostService.getTotalRunCosts(List<UUID> runIds)` (originally
  `getRunCosts(List<UUID>)`; see rename note above) to
  `src/main/java/com/epam/aidial/evaluation/service/domain/CostService.java`: inline `ValidationException`
  checks for empty and over-`MAX_BATCH_RUN_IDS` input (same convention as the existing `getDeploymentCosts`
  `from > to` check); delegates to the private `fetchTotalCosts` helper (see Group 4's revision note);
  maps the result to `List<TotalRunCostResponseDto>` preserving the caller's requested `runIds` order
  (done: compiles; no existence check against `TestSuiteRunRepository`, per design.md non-goals; placed
  directly beside the existing `getRunCosts(UUID)` overload per `OverloadMethodsDeclarationOrder`
  checkstyle rule when it was still an overload — no longer applicable now that the batch method has its
  own distinct name, but the placement is still logical so it was left in place).
- [x] 5.4 Extend `CostServiceTest` with cases for `getTotalRunCosts`: multi-run success (ordering
  preserved), empty-input rejection, over-limit rejection (done: test passes; added `GetTotalRunCosts`
  nested test class (renamed from `GetRunCostsBatch`), mocking `AdasCostQueryBuilder`/`DialAdasClient`
  directly since the fetching logic now lives in `CostService` itself rather than a separately-mocked
  `RunCostFetcher`).
- [x] 5.5 Run `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.CostServiceTest"` and
  confirm it passes (done: passes clean, including a re-run after the final `spotlessApply` pass).

## 6. `CostController` endpoint

- [x] 6.1 Add `POST /api/v1/costs/test-suite-runs` to
  `src/main/java/com/epam/aidial/evaluation/web/controller/CostController.java`, accepting a JSON body
  (`TotalRunCostRequestDto { List<UUID> runIds }`, originally `RunCostsBatchRequestDto`) via `@Valid
  @RequestBody`, delegating to `CostService.getTotalRunCosts` (originally `getRunCosts`, an overload),
  returning `List<TotalRunCostResponseDto>` (originally `RunCostResponseDto`) (done: compiles; route
  reachable; controller method itself renamed `getRunCosts` → `getTotalRunCosts` alongside the DTOs, per
  the same rename note as Group 5). **Revised after initial implementation**: originally built as `GET
  /api/v1/costs/test-suite-runs?runIds=...` with `@RequestParam @Size(min = 1, max =
  ValidationConstants.MAX_BATCH_RUN_IDS) List<UUID> runIds` (comma-separated, following
  `RunComparisonController#compare`'s precedent) — changed to POST-with-body per explicit user feedback
  that a GET with a page's worth of ids makes for an unwieldy query string. `CostService.getTotalRunCosts(List<UUID>)`
  treats a null `runIds` (an absent field in the body) the same as an empty list. See design.md
  Decision 4's revision notes.
- [x] 6.2 Add OpenAPI annotations to the new endpoint: `@Operation` (summary/description covering
  ordering, null-`totalCost`-means-no-data semantics, why POST, and that a dial-adas failure fails the
  whole request), Swagger `@RequestBody` with inline minimal/full `@ExampleObject`s (matching
  `TestCaseBulkDeleteController`'s inline-example precedent for a single-field body, rather than separate
  request example files), `@ApiResponse` for 200/400/502/504 (done: annotations present, matches project
  convention; description text updated when the per-item `status` field was dropped).
- [x] 6.3 Add minimal + full OpenAPI example JSON files under `src/main/resources/openapi/examples/` named
  `api-v1-costs-test-suite-runs-POST-response-200-{minimal,full}.json` per the openapi-examples spec (done:
  files exist and match the registered path key; renamed from the original `-GET-` filenames when the
  endpoint changed to POST; `status` field removed from both examples' entries when it was dropped). Not
  affected by the class/method rename — these are wire-shape JSON, not Java identifiers.
- [x] 6.4 Extend `CostControllerTest` with request-binding cases: 400 on missing body, 400 on empty
  `runIds`, 400 on over-limit `runIds`, delegation to `CostService.getTotalRunCosts` (done: test passes —
  all four cases now genuinely unit-testable, since standard `@Valid @RequestBody` validation (and
  `ValidationException` → 400 via `DefaultExceptionHandler`) works in a standalone MockMvc setup without
  the AOP proxy that method-level `@Size` on a `@RequestParam` required; this is strictly better coverage
  than the GET version had, which needed the empty/over-limit cases deferred to a functional test).
  Also added a 502 case (`CostService` throwing `DialAdasClientException` → `DefaultExceptionHandler` maps
  it to 502) once the per-item `UNAVAILABLE` status was replaced by whole-request failure.
- [x] 6.5 Run `./gradlew test --tests "com.epam.aidial.evaluation.web.controller.CostControllerTest"` and
  confirm it passes (done: passes clean, including a re-run after the final `spotlessApply` pass).

## 7. Config property

> **Reversed after initial implementation, per explicit user feedback** ("not sure we need it now"): all
> three tasks below were completed as originally written, then undone in full — `EnrichedList`/`Cost`
> removed from `TestSuiteRunProperties`, the `enriched-list` block removed from `application.yml`, and
> §6.14 (plus its TOC entry) removed from `docs/configuration.md`. Compiled clean and re-verified via
> `PostgresFunctionalTests$NoSecurityStartupTests` after removal. Rationale: with no enriched-list
> consumer built or concretely planned, the property was settled shape with nothing to validate it
> against; if that consumer is eventually built, a config property can be added then, informed by what it
> actually needs. See design.md Decision 5's revision note.

- [x] 7.1 ~~Add nested `EnrichedList.Cost.enabled` (`Boolean`, `@NotNull`) to
  `TestSuiteRunProperties.java`~~ — added, then removed; see revision note above.
- [x] 7.2 ~~Add `test-suite-run.enriched-list.cost.enabled: ${TEST_SUITE_RUN_ENRICHED_COST_ENABLED:true}`
  to `application.yml`~~ — added, then removed; see revision note above.
- [x] 7.3 ~~Add a row to `docs/configuration.md`~~ — added (as §6.14 "Test Suite Run — Enriched List
  Cost"), then removed along with its TOC entry; see revision note above.

## 8. Functional test coverage

- [x] 8.1 Add a functional test under `@PostgresFunctionalTests` for `POST
  /api/v1/costs/test-suite-runs`: multi-run case (one matched, one unmatched run id → the unmatched one's
  `totalCost` is null), and a 502 case mocking `DialAdasClient` to throw, using the already-`@Autowired`
  `DialAdasClient` mock bean and `TestRestTemplate` (done: test passes, boots the full Spring context).
  Also cover empty `runIds` and over-`MAX_BATCH_RUN_IDS` `runIds` 400s here (done: added 5 tests to
  `TestSuiteRunFunctionalTests` — the existing home for `CostController`'s other functional coverage —
  rather than a new file, since none existed and this mirrors the existing
  `shouldGetDeploymentCosts`/`shouldReturn400WhenDeploymentCostsFromAfterTo` pattern in the same class.
  **Revised twice after initial implementation**: (1) GET→POST — originally written against `GET
  ...?runIds=...`, with the empty/over-limit 400s deferred here specifically because standalone
  `CostControllerTest` couldn't trigger `HandlerMethodValidationException` without a real
  `ApplicationContext` AOP proxy; converted to `restTemplate.exchange`/`postForEntity` with a
  `RunCostsBatchRequestDto` body once the endpoint became POST — the empty/over-limit 400s are now also
  covered directly in `CostControllerTest` (task 6.4), so this functional test's copies are
  redundant-but-harmless end-to-end confirmation, not the only coverage; (2) status removal — the
  `AVAILABLE`/`NO_DATA` assertions became plain `totalCost` assertions, and the `UNAVAILABLE` case became
  a 502 status-code assertion, per design.md Decision 3's revision note).
- [x] 8.2 Run the new functional test class/nested group and confirm it passes (done: all 5 new tests pass
  under `PostgresFunctionalTests$TestSuiteRunTests`, both in the full suite run and a targeted re-run after
  the final `spotlessApply` pass).

## 9. Docs and spec index

- [x] 9.1 Update `docs/key-packages.md` to list `TotalRunCostResponseDto`, `CaseExpr`, `WhenClause` (done:
  entries added under their respective packages — `.client.dialadas`'s row extended with `CostService`'s
  batch fetching (`Map<UUID, Double>`, a requested id absent means no data, a dial-adas failure propagates
  as 502/504) and `TotalRunCostResponseDto`/`TotalRunCostRequestDto` (originally `RunCostResponseDto`/
  `RunCostsBatchRequestDto`), revised three times: first from `RunCostFetcher`/`RunCost`/
  `RunCostStatus`/`RunCostResponseDto` once `RunCost`/`RunCostStatus` were deleted, then again once
  `RunCostFetcher` itself was folded into `CostService`, then again for the `RunCostResponseDto` →
  `TotalRunCostResponseDto` / `getRunCosts` → `getTotalRunCosts` rename (design.md Decision 3/4's revision
  notes) — matching how that row already named `AdasCostQueryBuilder`/`CostService` rather than the
  generic `.service.domain` row; `.query.model`'s row extended with `CaseExpr`/`WhenClause` alongside the
  sealed `Expr` kinds it already enumerates).
- [x] 9.2 Update `openspec/specs/README.md` per the Spec Index Maintenance Policy to list the new
  `batch-run-costs` spec folder once it exists under `openspec/specs/` (done: this happens for real at
  `/opsx:archive` when `openspec update` materializes `specs/batch-run-costs/spec.md` — tracked here so the
  index update isn't forgotten in the same PR as the archive; reviewed the live `README.md` and confirmed
  no `batch-run-costs` entry exists yet, as expected pre-archive).
- [x] 9.3 Run `./gradlew spotlessApply` then `./gradlew checkstyleMain checkstyleTest` and confirm both are
  clean (done: both clean).
