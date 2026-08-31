## ADDED Requirements

### Requirement: The `$_metrics` frame accumulates along the request chain
Alongside the existing response-column frame, the JSONata frame used to resolve a request's template SHALL also carry a second accumulator, `$_metrics`, holding the output of every TSMD evaluated inline so far in the same test-case execution (see `metric-evaluation` for when a run is inline and `request-template` for the frame shape). `RequestExecutionResult` SHALL carry this accumulator as a fourth component, and `RequestChainExecutor` SHALL thread it from one request to the next exactly as it threads the response-column accumulator today. Within a single row, sibling TSMDs (multiple TSMDs both evaluated on the same row) are **not** ordered relative to each other — they are dispatched without an `ORDER BY` — so a TSMD's `Expression` binding can only reliably read a TSMD output produced on an **earlier request or turn**, never a same-row sibling. Accumulation is monotonic and last-writer-wins per `(tsmdName, outputField)`: a TSMD without a `condition` runs on every row and therefore overwrites its own prior entry on every turn; an author wanting a single, stable producer pins it with a `condition` (e.g. `request.index = 0`). This accumulator is populated only for inline runs; a non-inline run's frame carries no `$_metrics` key at all.

Status: **Planned**

#### Scenario: Second request reads the first request's metric output
- **WHEN** request #0 has an inline-evaluated TSMD named `judge` and request #1's body expression references `` $_metrics.`judge`.score.value ``
- **THEN** request #1's body SHALL resolve with that value bound, exactly as `$configId` resolves for an accumulated response column

#### Scenario: Same-row sibling TSMDs are unordered
- **WHEN** two TSMDs, `judge` and `scorer`, are both evaluated inline on request #0's same row, and `scorer`'s `Expression` binding references `` $_metrics.`judge`.score.value ``
- **THEN** whether `judge`'s output is visible to `scorer`'s evaluation on that same row is unspecified — an author needing this ordering MUST split `judge` and `scorer` across different requests or turns

#### Scenario: Last-writer-wins across turns of a multi-turn request
- **WHEN** an inline-evaluated TSMD without a `condition` runs on every turn of a 3-turn multi-turn request, producing a different score each turn
- **THEN** the next request's `$_metrics` reference to that TSMD's output SHALL see turn 2's (the last turn's) value

#### Scenario: Non-inline run carries no $_metrics accumulator
- **WHEN** a run is non-inline
- **THEN** `RequestExecutionResult`'s `accumulatedMetrics` component SHALL remain empty throughout the chain, and no request's frame SHALL carry a `$_metrics` binding
