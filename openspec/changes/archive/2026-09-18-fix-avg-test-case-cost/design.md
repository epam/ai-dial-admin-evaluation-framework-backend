## Context

`CostService.getRunCosts` reports `avgTestCaseCost`/`avgMetricEvalCost` via
`AdasCostQueryBuilder.buildRunAggregateQuery` + `DialAdasClient.executeAggregate`, which sends a
`StructuredQuery` (`"mode": "aggregate"`) to dial-adas's `/v1/queries/execute` and computes a flat
`avg(total_price)` over every `dial_usage_log` row matching `eval.run.id=<runId>` and
`eval.phase=<phase>`. A test case can issue multiple requests within one phase (multi-request suites,
multi-turn cases, one metric-evaluation request per TSMD), all tagged with the same `testcase.id=<id>`
baggage value. The correct per-test-case average is `avg(sum(total_price) grouped by testcase.id)` — sum
each test case's requests first, then average those sums across test cases — which is a two-level
aggregation `StructuredQuery`'s single `GROUP BY` + aggregate `select` shape cannot express.

dial-adas separately exposes `POST /v1/queries/execute-sql`, accepting `{"sql": "<raw SQL>"}` and
returning the same `{"rows": [...]}` shape as `/v1/queries/execute`. A CTE-based query against this
endpoint has been manually verified to produce the correct two-level result:

```sql
WITH tagged AS (
  SELECT total_price, array_join(split_string("usage_request_baggage.baggage", ',')) AS testcase_tag
  FROM dial_usage_log
  WHERE contains("usage_request_baggage.baggage", 'eval.run.id=<runId>')
    AND contains("usage_request_baggage.baggage", 'eval.phase=<phase>')
),
per_testcase AS (
  SELECT testcase_tag, sum(total_price) AS case_cost
  FROM tagged
  WHERE starts_with(testcase_tag, 'testcase.id=')
  GROUP BY testcase_tag
)
SELECT avg(case_cost) AS avg_cost, count(*) AS count
FROM per_testcase
```

`getDeploymentCosts`/`getTotalRunCosts` only ever `sum`, which is identical whether computed as one flat
sum or a sum-of-per-group-sums — they are out of scope for this design.

## Goals / Non-Goals

**Goals:**
- `avgTestCaseCost`/`avgMetricEvalCost` correctly reflect average cost per test case (per phase), even
  when a test case issues multiple requests within that phase.
- Reuse existing DTOs/error-handling/config wherever the response or transport shape is unchanged.
- Keep the raw-SQL construction free of any real injection surface despite building a literal SQL string.

**Non-Goals:**
- No new public API, DTO, or OpenAPI contract change — `RunCostsResponseDto`'s field names/types/nullability
  are unchanged; only the underlying computation is corrected.
- No change to `getDeploymentCosts`/`getTotalRunCosts` (sum-based, unaffected).
- No general-purpose "run arbitrary SQL" capability — `executeSql` is a narrow client primitive used only
  by this one query builder method, not exposed through our own API.
- No combining `execution` and `metric-evaluation` phase costs into one number — they remain two separate
  fields/queries, matching the existing `test-suite-run-costs` design's non-goal.

## Decisions

1. **New `DialAdasClient.executeSql` method, not a new client class.** dial-adas is one external service;
   `/v1/queries/execute-sql` is a sibling of the existing `/v1/queries/execute` on the same base URL,
   auth, and timeout config (`DialAdasProperties`/`DialAdasClientConfiguration`). Adding a second method to
   the existing client mirrors how the client already has exactly one method per endpoint, and avoids a
   second `@Service`/`@ConfigurationProperties` pair for what is the same downstream system.

2. **Reuse `AdasAggregateResponseDto<T>` for the SQL endpoint's response**, since the manually-verified
   response is `{"rows": [...]}`, structurally identical to the structured-query endpoint's response.
   Alternative considered: a distinct `AdasSqlResponseDto`. Rejected — it would be a duplicate type with
   the same single field, adding a class without adding any distinction.

3. **Reuse `AdasRunAvgCostRowDto` (`count`, `avg_cost`) as the SQL query's row DTO, by aliasing the SQL's
   own `SELECT ... AS ...` output columns to those exact names**, rather than introducing a new row DTO
   with different `@JsonProperty` names (e.g. `avg_cost_per_testcase`/`testcase_count`). The aliases are
   ours to choose — dial-adas doesn't dictate them — so choosing them to match an existing DTO costs
   nothing and avoids a DTO that would otherwise become dead weight after `buildRunAggregateQuery` is
   removed.

4. **Plain Java text-block SQL template, not a SQL builder library (e.g. jOOQ).** Considered building the
   query via jOOQ's rendering-only mode (`DSL.using(SQLDialect.DEFAULT)`, no live connection). Rejected:
   dial-adas's SQL dialect (`array_join`, `split_string`, `starts_with`, `contains`, dotted-literal field
   names) isn't a dialect jOOQ knows, so most of the query would still need jOOQ's generic escape hatches
   (`DSL.function(...)`, `DSL.condition("...{0}...")`) — effectively hand-written SQL text routed through
   jOOQ's API for no real gain. jOOQ's main benefit, safe parameter binding, isn't needed here since the
   only interpolated values are a `UUID` (`.toString()` is inherently hex+hyphen only) and a `phase` value
   restricted to two known constants (decision 5). A literal template also preserves the exact SQL text
   already verified against live dial-adas, rather than risking jOOQ's generic renderer producing subtly
   different (and unverified) syntax.

5. **Guard `phase` against the two known `TracingConstants` values inside `buildAvgCostPerTestCaseSql`**,
   throwing `IllegalArgumentException` otherwise. Defense-in-depth: this is the one method in
   `AdasCostQueryBuilder` that builds a literal SQL string instead of a `StructuredQuery` AST, so it's the
   one place a future caller passing an unvalidated string could introduce SQL injection. The guard makes
   that impossible regardless of future call sites.

6. **Delete `buildRunAggregateQuery`/`selectCountAndAvgCost` rather than keep them alongside the new
   method.** Its only caller (`CostService.fetchAvgCost`) is being replaced in the same change, so keeping
   it would leave dead code (also flagged as unused code per project convention: delete rather than retain
   "just in case").

## Risks / Trade-offs

- **[Risk] dial-adas's `execute-sql` endpoint has different availability/rate-limit/auth characteristics
  than `execute`.** → Mitigation: reuses the same `dialAdasRestClient` bean (same base URL, timeouts, auth
  interceptor), and the same error-mapping (`RestClientResponseException`/`ResourceAccessException` →
  `DialAdasClientException` 502/504), so failure behavior is identical to the existing endpoint from the
  caller's perspective.
- **[Risk] A literal SQL template is more brittle to dial-adas dialect changes than a structured DSL query
  (no AST-level type checking).** → Mitigation: the query is covered by a unit test asserting the exact
  generated SQL string, and the `phase` allowlist guard limits the blast radius of a malformed template to
  a build-time-caught test failure, not a runtime injection risk.
- **[Trade-off] Two dial-adas calls per `getRunCosts` request (one per phase), same as today.** Combining
  both phases into one SQL query (one CTE producing two rows, one per phase) was considered but rejected
  for this change to keep the diff minimal and match the existing two-call shape exactly; revisit only if
  latency becomes a concern (no evidence of that today).
