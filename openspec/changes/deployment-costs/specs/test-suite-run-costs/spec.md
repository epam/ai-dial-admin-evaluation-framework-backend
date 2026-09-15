## MODIFIED Requirements

### Requirement: dial-adas usage-log correlation by run id and phase
The system SHALL query dial-adas's `dial_usage_log` entity via its query DSL (`POST {dial-adas-base-url}/v1/queries/execute`), filtering rows whose `dial_usage_log_payload.request_tags.baggage` contains the run's own id as `eval.run.id=<runId>` and the relevant execution phase as `eval.phase=execution` or `eval.phase=metric-evaluation`, using the same OTel baggage phase values already emitted by the evaluation engine (`TracingConstants.PHASE_EXECUTION` / `PHASE_METRIC_EVALUATION`). Status: Implemented.

#### Scenario: Query is scoped to a single run and phase
- **WHEN** the system computes the execution-phase average for run `R`
- **THEN** the dial-adas query filter requires both a match on `eval.run.id=R` and a match on `eval.phase=execution` within `dial_usage_log_payload.request_tags.baggage`, so usage-log rows from other runs or from the metric-evaluation phase of the same run are excluded

#### Scenario: Aggregate computed server-side
- **WHEN** the system requests an average for a run and phase
- **THEN** it issues a single `"mode": "aggregate"` query with an `avg(total_price)` selection (aliased so the response can be read directly) rather than fetching individual usage-log rows and averaging them in application code
