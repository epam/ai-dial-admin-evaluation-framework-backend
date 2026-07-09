## MODIFIED Requirements

### Requirement: Binding resolution
The `BindingResolver` SHALL resolve TSMD config and input bindings against test case data and the `extractedColumns` from a `TestCaseRunResult`, producing `Map<String, Object>` for config and input. Because each result row is now a single turn carrying scalar `test_case_data` and scalar `extracted_columns`, resolution SHALL use the column value **directly** — there is no turn/element selection. A `Response` or `TestCase` binding source carries only a `columnName`; a `Constant` binding source carries a literal `value`. Binding sources SHALL NOT carry a `jsonataExpression`. Resolution SHALL fail fast when a `Response`/`TestCase` binding references a column that does not exist in the corresponding map; a column present with a `null` value SHALL resolve to `null`.
Status: **Planned**

#### Scenario: TestCase binding source
- **WHEN** a binding has `source: { $type: "TestCase", columnName: "expected" }` and the (per-turn) test case data contains `{"expected": "A planet"}`
- **THEN** the resolver SHALL produce the value `"A planet"` for that binding's property

#### Scenario: Response binding source
- **WHEN** a binding has `source: { $type: "Response", columnName: "model_answer" }` and the (per-turn) extracted columns contain `{"model_answer": "Earth is the third planet"}`
- **THEN** the resolver SHALL produce the value `"Earth is the third planet"` for that binding's property

#### Scenario: Constant binding source
- **WHEN** a binding has `source: { $type: "Constant", value: "gemini-2.5-flash-lite" }`
- **THEN** the resolver SHALL produce the literal value `"gemini-2.5-flash-lite"` for that binding's property

#### Scenario: No jsonataExpression on any binding source
- **WHEN** a binding source is deserialized
- **THEN** `Response`, `TestCase`, and `Constant` sources SHALL have no `jsonataExpression` field, and the resolver SHALL contain no JSONata-selection step and no array/scalar mismatch guard

#### Scenario: Missing column in test case data fails fast
- **WHEN** a `TestCase` binding references a column key absent from the per-turn data map
- **THEN** the resolver SHALL throw `IllegalArgumentException` identifying the missing column and source type

#### Scenario: Missing column in extracted columns fails fast
- **WHEN** a `Response` binding references a column key absent from the per-turn extracted columns map
- **THEN** the resolver SHALL throw `IllegalArgumentException` identifying the missing column and source type

#### Scenario: Present column with null value resolves to null
- **WHEN** a binding references a column present in its map but whose value is `null`
- **THEN** the resolver SHALL produce `null` for that binding's property (no exception)

#### Scenario: Multiple bindings merged into single map
- **WHEN** input bindings contain `[{property: "actual", source: Response/model_answer}, {property: "ground_truth", source: TestCase/expected}]`
- **THEN** the resolver SHALL produce `{"actual": <value>, "ground_truth": <value>}`

### Requirement: EvalSummary assembly from TestCaseRunResult
The system SHALL build one EvalSummary per `TestCaseRunResult`, copying context fields from the result and adding computed metric values. Because a multi-turn conversation now produces one result row per turn, it likewise produces one EvalSummary per turn. The `extractedColumns` value SHALL be copied from the result **verbatim** — a scalar object for every result (single-turn and per-turn alike; there is no longer a column-major array shape). Each summary SHALL carry `turnIndex` and `totalTurns` copied from the source result.
Status: **Planned**

#### Scenario: Field mapping from result to summary
- **WHEN** an EvalSummary is built for a `TestCaseRunResult`
- **THEN** the batch-write envelope SHALL carry `testSuiteId`, `testSuiteRunId`, `computationId`, and `computedAtMs` from the `MetricEvaluationContext`, and each item SHALL carry `testCaseRunResultId` = result.id, `testCaseId`, `testCaseName`, `runIndex`, `turnIndex`, `totalTurns`, `testCaseData`, `extractedColumns`, `execDurationMs`, and `responseStatusCode` from the result

#### Scenario: extractedColumns stored verbatim as a scalar object
- **WHEN** an EvalSummary is built for any result whose `extractedColumns` is `{"answer": "Paris"}`
- **THEN** `EvalSummary.extractedColumns` SHALL store `{"answer": "Paris"}` unchanged, with no normalization step and no array handling

#### Scenario: One summary per turn
- **WHEN** a 3-turn conversation is evaluated
- **THEN** three EvalSummary rows SHALL be produced, with `turnIndex` `0`,`1`,`2` and `totalTurns` `3`

#### Scenario: Non-SUCCESS result propagation
- **WHEN** a `TestCaseRunResult` has `executionStatus != SUCCESS` (including a failing turn row or a `0/0` data-error row)
- **THEN** the EvalSummary SHALL propagate that status, set `metricValues = {}` and `metricInfos = null`, and SHALL NOT attempt metric or condition evaluation
