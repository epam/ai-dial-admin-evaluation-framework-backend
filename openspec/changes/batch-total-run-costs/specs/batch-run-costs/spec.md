## ADDED Requirements

### Requirement: Batch total-cost lookup for multiple test suite runs
The system SHALL expose `POST /api/v1/costs/test-suite-runs`, accepting a JSON request body with a
`runIds` array of run UUIDs (required, at least 1 and at most `ValidationConstants.MAX_BATCH_RUN_IDS`
entries), and returning, for each requested run id, its total cost (both `execution` and
`metric-evaluation` phases combined), null when dial-adas has no matching usage data for that run id. The
response SHALL preserve the order of the requested `runIds`. POST (with `runIds` in the body) rather than
GET (with `runIds` as a query parameter) — a page's worth of run ids would otherwise make for an unwieldy
query string. There is no per-run status field: a null `totalCost` alone communicates "no usage data for
this run," and a failure of the single underlying dial-adas call fails the whole request (see the
dial-adas-failure scenario below) rather than being represented as a per-run value. Status: Planned.

#### Scenario: Runs with usage data return their total cost
- **WHEN** a client requests `POST /api/v1/costs/test-suite-runs` with body `{"runIds": ["R1", "R2"]}`
  where dial-adas has usage rows for both `R1` and `R2`
- **THEN** the response contains one entry per run id, each with the summed `total_price` across both
  `eval.phase` values as its total cost

#### Scenario: A run with no matching usage data returns a null total cost
- **WHEN** a requested run id has no dial-adas usage rows at all (including an unknown or typo'd run id)
- **THEN** that run's entry has a null total cost, while other requested run ids in the same request are
  unaffected

#### Scenario: dial-adas failure fails the whole request
- **WHEN** the single underlying dial-adas call fails (timeout, connection error, or non-2xx response)
- **THEN** the request itself fails with 502 (unreachable/error response) or 504 (timeout) — there is no
  per-run partial result and no 200 response, since the batch is computed by exactly one dial-adas call

#### Scenario: Empty runIds is rejected
- **WHEN** a client requests `POST /api/v1/costs/test-suite-runs` with body `{"runIds": []}` or an absent
  `runIds` field
- **THEN** the system returns 400 with `ErrorCode.VALIDATION_ERROR` and does not query dial-adas

#### Scenario: runIds exceeding the batch limit is rejected
- **WHEN** a client supplies more than `ValidationConstants.MAX_BATCH_RUN_IDS` run ids in the request
  body's `runIds` array
- **THEN** the system returns 400 with `ErrorCode.VALIDATION_ERROR` and does not query dial-adas

### Requirement: One dial-adas call computes total cost for many runs
The system SHALL compute the total cost for all requested run ids with exactly one dial-adas aggregate
query against `dial_usage_log`, filtered by an `or` of per-run baggage-contains predicates on
`eval.run.id` (no `eval.phase` predicate, since the total spans both phases), grouped by a `run_id`
value derived per row via a `case`/`when`/`then`/`else` select expression that maps each row back to the
run id whose baggage predicate matched it. Status: Planned.

#### Scenario: Single dial-adas query regardless of run count
- **WHEN** the system computes total costs for N requested run ids
- **THEN** it issues exactly one `"mode": "aggregate"` dial-adas query, selecting a `case`-derived
  `run_id` alias, `count()`, and `sum(total_price)` aliased `total_cost`, grouped by `run_id`

#### Scenario: Total spans both execution phases
- **WHEN** the system computes a requested run's total cost
- **THEN** the underlying query applies no `eval.phase` filter, so the total includes usage rows from
  both the `execution` and `metric-evaluation` phases

#### Scenario: A single requested run id skips the OR wrapper
- **WHEN** exactly one run id is requested
- **THEN** the underlying query's filter is the single run's baggage-contains predicate, not an `or` node
  wrapping one child
