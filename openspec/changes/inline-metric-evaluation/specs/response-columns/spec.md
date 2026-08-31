## MODIFIED Requirements

### Requirement: Reserved response column names

A response column's `name` SHALL NOT collide with a JSONata built-in function name (a hand-maintained `JsonataReservedNames` constants list, distinct from the query-DSL function registry used by the query DSL subsystem) or with the reserved frame variable names `_request`, `_response`, or `_metrics`. Suite create/update SHALL reject a colliding name with HTTP 400. This is independent of, and in addition to, the existing `::`-sequence name restriction and the existing per-suite name-uniqueness check.

The plain names `request`, `response`, and `metrics` are **not** reserved and SHALL be accepted as ordinary response column names.

The reservation exists for authoring clarity, not to prevent a runtime collision: the response-column extraction frame binds only `_request`/`_response`, and the request-template frame binds the previous turn's extracted column names plus (for an inline run) `_metrics` — these are disjoint from a response column's own `name`, so a column name can never actually shadow a frame variable during evaluation. What the reservation prevents is a `$name` token whose meaning would differ between authoring surfaces. Reserving `_metrics` is a latent tightening for any pre-existing suite that happens to already use that exact name — the same class of change as the original `_request`/`_response` reservation.

Status: **Planned**

#### Scenario: Response column name collides with a JSONata built-in function name
- **WHEN** client saves a suite with a response column named `"count"` (a JSONata built-in function name)
- **THEN** system SHALL respond with HTTP 400, error code `VALIDATION_ERROR`, identifying the offending column

#### Scenario: Response column name collides with the reserved frame variable names
- **WHEN** client saves a suite with a response column named `"_request"`, `"_response"`, or `"_metrics"`
- **THEN** system SHALL respond with HTTP 400, error code `VALIDATION_ERROR`, identifying the offending column

#### Scenario: Response column named request or response accepted
- **WHEN** client saves a suite with a response column named `"request"`, `"response"`, or `"metrics"`
- **THEN** system SHALL accept and persist the column — these names are not reserved, since the frame variables are `_request`/`_response`/`_metrics`

#### Scenario: Non-colliding name accepted
- **WHEN** client saves a suite with a response column named `"answer"` (not a JSONata function name, not `_request`/`_response`/`_metrics`)
- **THEN** system SHALL accept and persist the column, unaffected by this requirement

## ADDED Requirements

### Requirement: `$_metrics` is not bound in response-column expressions

`ResponseColumnExtractor` SHALL NOT bind `$_metrics` in the frame a response-column expression evaluates against. Response columns are extracted from a request's raw response body before any TSMD has run against that row — `ResponseColumnExtractor`'s frame binds only `$_request`, `$_response`, and prior response columns (see the `request-template`/`jsonata-evaluation-seam` pattern), never `$_metrics`, regardless of whether the run is inline. A response-column expression referencing `$_metrics` SHALL therefore resolve that reference to JSONata `undefined` silently — no error is raised, and this holds identically for inline and non-inline runs.

Status: **Planned**

#### Scenario: A response-column expression referencing $_metrics resolves undefined
- **WHEN** a response column's expression references `$_metrics.judge.score.value`
- **THEN** that reference SHALL resolve to JSONata `undefined`, silently, because `ResponseColumnExtractor`'s frame never binds `$_metrics`

#### Scenario: The behavior is the same for inline and non-inline runs
- **WHEN** a response-column expression references `$_metrics` on an inline run versus a non-inline run
- **THEN** both SHALL resolve the reference to `undefined` identically — `$_metrics` availability is scoped to request-body evaluation and `Expression` metric bindings, never to response-column extraction
