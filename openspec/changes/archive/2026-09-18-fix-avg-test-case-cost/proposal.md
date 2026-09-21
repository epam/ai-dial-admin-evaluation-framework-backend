## Why

`GET /api/v1/costs/test-suite-run/{id}` (`CostService.getRunCosts`) reports `avgTestCaseCost` and
`avgMetricEvalCost`, both currently computed as a flat `avg(total_price)` over every `dial_usage_log` row
tagged with the run's `eval.run.id` and a given `eval.phase` (`AdasCostQueryBuilder.buildRunAggregateQuery`).
This is average cost **per LLM call**, not average cost **per test case**: a test case can issue multiple
requests (multi-request suites, multi-turn cases) and a metric evaluation issues one request per TSMD, all
sharing the same `testcase.id` baggage tag. Averaging over individual call rows silently dilutes the
result by per-test-case call-count variance instead of first summing cost per test case and then averaging
across test cases — the field name promises a per-test-case average the current query doesn't deliver.

The correct computation, `avg(sum(total_price) grouped by testcase.id)`, is a two-level aggregation.
dial-adas's structured JSON query DSL (`StructuredQuery`, `"mode": "aggregate"`) expresses only one level
of `GROUP BY` + aggregate `select`, so it cannot do "sum per group, then avg across groups" in one call.
dial-adas exposes a second endpoint, `POST /v1/queries/execute-sql`, that accepts a raw SQL string and can
express the needed CTE. This has been manually verified against dial-adas.

## What Changes

- Add `DialAdasClient.executeSql(String sql, Class<T> rowType)`, POSTing to the new
  `/v1/queries/execute-sql` endpoint with a new `AdasExecuteSqlRequestDto` (`{ "sql": "..." }`) body.
  Reuses the existing `AdasAggregateResponseDto<T>` wrapper (`{ rows: [...] }`) for the response, since its
  shape is identical to the structured-query endpoint's. Reuses the existing `RestClientResponseException`
  / `ResourceAccessException` error mapping (`DialAdasClientException`, 502/504) via a shared private
  helper instead of duplicating it.
- Add `AdasCostQueryBuilder.buildAvgCostPerTestCaseSql(UUID runId, String phase)`, building a parameterized
  SQL string: a CTE that tags each usage-log row with its `testcase.id=` baggage value, sums
  `total_price` per test case, then averages those per-test-case sums. Output columns are aliased
  `avg_cost` / `count` to match the existing `AdasRunAvgCostRowDto` exactly, so no new response row DTO is
  needed. `runId` (a `UUID`) and `phase` (restricted to the two known `TracingConstants` phase values,
  guarded against any other input) are the only interpolated values, so the raw-SQL construction carries
  no injection surface.
- Remove `AdasCostQueryBuilder.buildRunAggregateQuery` and its now-unused `selectCountAndAvgCost` helper —
  its only caller is being replaced below.
- Update `CostService.fetchAvgCost` to call `buildAvgCostPerTestCaseSql` + `executeSql` instead of
  `buildRunAggregateQuery` + `executeAggregate`. The method's null-handling (`null` when there are zero
  matching test cases) is unchanged.
- No changes to `getDeploymentCosts` or `getTotalRunCosts`: both use `sum`, which is mathematically
  identical whether computed as one flat sum or as a sum of per-group sums, so they are unaffected by the
  multi-request-per-test-case issue this change fixes.

## Capabilities

### New Capabilities
(none — the new dial-adas SQL-execution call is an internal implementation detail behind an existing
public field, not a new externally observable capability)

### Modified Capabilities
- `test-suite-run-costs`: `avgTestCaseCost` and `avgMetricEvalCost` change from "average cost per
  matching usage-log row" to "average cost per test case" (each phase's requests summed by `testcase.id`,
  then averaged across test cases that had at least one matching row in that phase). The response field
  names, types, and null-on-zero-matches behavior are unchanged.

## Impact

- `src/main/java/com/epam/aidial/evaluation/client/dialadas/DialAdasClient.java` — new `executeSql`
  method, new endpoint path constant, shared error-mapping helper.
- `src/main/java/com/epam/aidial/evaluation/client/dialadas/dto/AdasExecuteSqlRequestDto.java` — new
  request DTO.
- `src/main/java/com/epam/aidial/evaluation/service/domain/AdasCostQueryBuilder.java` —
  `buildRunAggregateQuery` removed, `buildAvgCostPerTestCaseSql` added.
- `src/main/java/com/epam/aidial/evaluation/service/domain/CostService.java` — `fetchAvgCost` rewired.
- No DB schema, no new `@ConfigurationProperties` (the new endpoint reuses the existing
  `dialAdasRestClient` bean / `DialAdasProperties`), no OpenAPI/DTO contract change on our own API.
- Test updates: `AdasCostQueryBuilderTest`, `DialAdasClientTest`, `CostServiceTest` (see design/tasks).
- `openspec/specs/test-suite-run-costs/spec.md` needs its `avgTestCaseCost`/`avgMetricEvalCost`
  description corrected as part of this change.
