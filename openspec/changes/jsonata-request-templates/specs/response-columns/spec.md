## ADDED Requirements

### Requirement: Request/response frame for response column extraction
When evaluating a response column's JSONata expression, the service SHALL provide a `Frame` binding `$request` (the parsed JSON of the request body actually sent) and `$response` (the parsed JSON of the response body) in addition to the existing evaluation. The root evaluation document remains the raw response body, unchanged — `$request`/`$response` are additive frame variables; no existing response column expression's meaning changes. This applies uniformly to single-turn suites, multi-turn suites (each turn's own request/response), and MCP suites.
Status: **Implemented**

#### Scenario: Existing expression unaffected
- **WHEN** a response column has `expression: "choices[0].message.content"` (no reference to `$request`/`$response`)
- **THEN** extraction behaves exactly as before this change — evaluated against the response body as the root document

#### Scenario: Expression references $response explicitly
- **WHEN** a response column has `expression: "$response.choices[0].message.content"`
- **THEN** extraction SHALL produce the identical result as the equivalent root-document expression `"choices[0].message.content"`, since `$response` is bound to the same parsed response body

#### Scenario: Expression correlates request and response
- **WHEN** a response column has `expression: "$response.result = $request.expected"`
- **THEN** extraction SHALL evaluate the expression with both `$request` and `$response` populated from the turn's actual sent request body and received response body

#### Scenario: Multi-turn extraction uses that turn's own request/response
- **WHEN** a multi-turn case's turn k is extracted
- **THEN** `$request` and `$response` are bound to turn k's own resolved request body and received response body, not an earlier or later turn's

### Requirement: Reserved response column names
A response column's `name` SHALL NOT collide with a JSONata built-in function name (the same registry the query-DSL function catalog enumerates) or with the reserved frame variable names `request` or `response`. Suite create/update SHALL reject a colliding name with HTTP 400. This is independent of, and in addition to, the existing `::`-sequence name restriction and the existing per-suite name-uniqueness check.
Status: **Implemented**

#### Scenario: Response column name collides with a JSONata built-in function name
- **WHEN** client saves a suite with a response column named `"count"` (a JSONata built-in function name)
- **THEN** system SHALL respond with HTTP 400, error code `VALIDATION_ERROR`, identifying the offending column

#### Scenario: Response column name collides with the reserved request/response frame names
- **WHEN** client saves a suite with a response column named `"request"` or `"response"`
- **THEN** system SHALL respond with HTTP 400, error code `VALIDATION_ERROR`, identifying the offending column

#### Scenario: Non-colliding name accepted
- **WHEN** client saves a suite with a response column named `"answer"` (not a JSONata function name, not `request`/`response`)
- **THEN** system SHALL accept and persist the column, unaffected by this requirement

### Requirement: Failed-extraction frame binding uses explicit null, not undefined (F2)
When a response column's extraction genuinely fails (JSONata evaluation error or type-mismatch reconciliation failure) and that column's value is subsequently bound as a request-template frame variable for the next turn (per the `multi-turn-test-case` frame-driven turn-loop requirement), the service SHALL bind the JSONata explicit-null sentinel for that variable — not a plain Java `null` — so that a downstream expression such as `$append($historyColumn, [...])` observes null-append semantics (the prior value is treated as a present-but-null entry) rather than undefined-append semantics (as if the column had never been extracted). Binding a plain Java `null` is observably indistinguishable, at the JSONata level, from leaving the variable unbound entirely — both cause `$append`'s undefined-argument short-circuit — so a plain Java `null` binding MUST NOT be used to represent "this column's value is JSON null."
Status: **Implemented**

#### Scenario: Failed extraction feeds a real null into the next turn's frame
- **WHEN** turn k's extraction of a response column named `answer` fails (evaluation error or type mismatch), and turn k+1's request template references `$answer` inside `$append($answer, [...])`
- **THEN** turn k+1's evaluation observes null-append semantics — the result includes an explicit `null` entry for turn k's contribution, not merely the new array

#### Scenario: Unextracted (never-configured) column is genuinely unbound
- **WHEN** a response column named `context` is not configured for the suite at all
- **THEN** turn 1's reference to `$context` is unbound (undefined), producing undefined-append semantics — distinct from the "configured but failed" case above

## Implementation Notes

- `DashjoinJsonataEvaluationService` gains the 3-arg (`$request`/`$response` frame) evaluate overload; documented as a narrow, explicit exception to (not a repeal of) the "only class that imports `com.dashjoin.jsonata`" invariant if the frame-population/`Jsonata.NULL_VALUE`-binding logic also lives there — see `jsonata-request-templates/design.md` Decision 5/6 and Flag F2.
- `JsonataReservedNames` (new constants class) is the single source for both the built-in-function-name set (reused, not re-derived, from the query-DSL function registry) and the `request`/`response` reserved names; `TestSuiteRequestValidator` consumes it for the new 400 check.
