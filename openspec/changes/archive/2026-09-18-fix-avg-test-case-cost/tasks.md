## 1. dial-adas client: `execute-sql` capability

- [x] 1.1 Add `AdasExecuteSqlRequestDto` (`client/dialadas/dto/AdasExecuteSqlRequestDto.java`) — single
  `String sql` field, `@JsonProperty("sql")`, mirroring the existing row/response DTO conventions
  (Lombok `@Data`/`@Builder`/`@NoArgsConstructor`/`@AllArgsConstructor`).
- [x] 1.2 In `DialAdasClient`, extract the existing `executeAggregate` try/catch
  (`RestClientResponseException` → `DialAdasClientException`; `ResourceAccessException` →
  `mapResourceAccessException`) into a small private helper reused by both methods.
- [x] 1.3 Add `EXECUTE_SQL_PATH = "/v1/queries/execute-sql"` constant and
  `public <T> AdasAggregateResponseDto<T> executeSql(String sql, Class<T> rowType)` to `DialAdasClient`,
  POSTing an `AdasExecuteSqlRequestDto`, reusing the `TypeUtils.parameterize`/`ParameterizedTypeReference`
  pattern from `executeAggregate` and the shared error-mapping helper from 1.2.

## 2. Cost query builder: per-test-case SQL

- [x] 2.1 Add `AdasCostQueryBuilder.buildAvgCostPerTestCaseSql(UUID runId, String phase)`, building the
  CTE SQL from `design.md` (tag rows by `testcase.id=` baggage value, sum `total_price` per test case,
  average those sums), interpolating only `runId.toString()` and `phase`, aliasing output columns
  `avg_cost`/`count` to match `AdasRunAvgCostRowDto`.
- [x] 2.2 Guard `phase` in `buildAvgCostPerTestCaseSql` to only accept `TracingConstants.PHASE_EXECUTION`
  or `TracingConstants.PHASE_METRIC_EVALUATION`, throwing `IllegalArgumentException` otherwise.
- [x] 2.3 Remove `AdasCostQueryBuilder.buildRunAggregateQuery` and its now-unused `selectCountAndAvgCost`
  helper.

## 3. `CostService` rewiring

- [x] 3.1 Update `CostService.fetchAvgCost(UUID runId, String phase)` to call
  `adasCostQueryBuilder.buildAvgCostPerTestCaseSql(runId, phase)` + `dialAdasClient.executeSql(sql,
  AdasRunAvgCostRowDto.class)` in place of `buildRunAggregateQuery` + `executeAggregate`; keep the
  existing null-on-empty/zero-count handling unchanged.

## 4. Tests

- [x] 4.1 In `AdasCostQueryBuilderTest`, replace the `buildRunAggregateQuery` test cases with cases
  asserting the exact SQL string produced by `buildAvgCostPerTestCaseSql` for a given `runId` and each of
  the two phases (string equality), plus a case asserting `IllegalArgumentException` for an unrecognized
  phase value. Run: `./gradlew test --tests
  "com.epam.aidial.evaluation.service.domain.AdasCostQueryBuilderTest"`.
- [x] 4.2 In `DialAdasClientTest`, add `MockRestServiceServer`-based tests for `executeSql` mirroring the
  existing `executeAggregate` tests: success (POST path `/v1/queries/execute-sql`, request body
  `{"sql": "..."}`, response row parsing via `AdasRunAvgCostRowDto`), 5xx → `DialAdasClientException`, and
  a simulated `SocketTimeoutException` → 504. Run: `./gradlew test --tests
  "com.epam.aidial.evaluation.client.dialadas.DialAdasClientTest"`.
- [x] 4.3 In `CostServiceTest`'s `GetRunCosts` nested class, replace the
  `buildRunAggregateQuery`/`executeAggregate` stubs with `buildAvgCostPerTestCaseSql`/`executeSql` stubs
  (still returning `AdasRunAvgCostRowDto` rows); keep the existing assertions (`avgTestCaseCost`/
  `avgMetricEvalCost` populated, `null` on zero `count`, `EntityNotFoundException` propagation without
  calling dial-adas). Run: `./gradlew test --tests
  "com.epam.aidial.evaluation.service.domain.CostServiceTest"`.
- [x] 4.4 Run `CostControllerTest` as a regression check (`./gradlew test --tests
  "com.epam.aidial.evaluation.web.controller.CostControllerTest"`) — the controller/DTO contract is
  unchanged, so this should pass without modification.

## 5. Docs / spec maintenance

- [x] 5.1 Sync this change's `specs/test-suite-run-costs/spec.md` delta into
  `openspec/specs/test-suite-run-costs/spec.md` (via `opsx:sync` or `openspec sync-specs`), and correct
  that spec's `## Purpose` line, which currently describes "an on-demand average of per-call prices," to
  describe an on-demand average cost per test case instead.
- [x] 5.2 Update the `test-suite-run-costs` entry in `openspec/specs/README.md` — its summary currently
  says "average test-case execution cost and average metric-evaluation cost for a run, computed from
  dial-adas usage-log aggregate queries correlated by the run's OTel baggage (`eval.run.id`,
  `eval.phase`)," which is materially inaccurate after this change (per-test-case average, correlated
  also by `testcase.id`, via the `execute-sql` endpoint rather than the `StructuredQuery` aggregate
  endpoint for this one query).
