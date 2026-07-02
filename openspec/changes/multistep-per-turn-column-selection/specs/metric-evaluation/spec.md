## MODIFIED Requirements

### Requirement: Binding resolution
The `BindingResolver` SHALL resolve TSMD config and input bindings against test case data and the **raw** `extractedColumns` from a `TestCaseRunResult` (no shape normalization), producing `Map<String, Object>` for config and input. Resolution SHALL fail fast when a binding references a column that does not exist in the data map. A `Response` binding source MAY carry an optional `jsonataExpression`; when it is non-blank, the resolver SHALL evaluate it (via `JsonataEvaluationService`) against the **resolved column value** and use the result — which MAY be `null` when the expression matches nothing. When `jsonataExpression` is absent or blank, the resolver SHALL use the raw column value unchanged (the whole per-column array for a multi-step result, a scalar for a single-step result).
Status: **Implemented**

#### Scenario: TestCase binding source
- **WHEN** a binding has `source: { $type: "TestCase", columnName: "expected" }` and the test case data contains `{"expected": "A planet"}`
- **THEN** the resolver SHALL produce `{"expected": "A planet"}` for that binding's property

#### Scenario: Response binding source
- **WHEN** a binding has `source: { $type: "Response", columnName: "model_answer" }` and the extracted columns contain `{"model_answer": "Earth is the third planet"}`
- **THEN** the resolver SHALL produce the value `"Earth is the third planet"` for that binding's property

#### Scenario: Constant binding source
- **WHEN** a binding has `source: { $type: "Constant", value: "gemini-2.5-flash-lite" }`
- **THEN** the resolver SHALL produce the literal value `"gemini-2.5-flash-lite"` for that binding's property

#### Scenario: Response binding without jsonataExpression yields the whole column value
- **WHEN** a binding has `source: { $type: "Response", columnName: "answer" }` with no `jsonataExpression` and the multi-step extracted columns contain `{"answer": ["Paris","Tokio"]}`
- **THEN** the resolver SHALL produce the array `["Paris","Tokio"]` for that binding's property

#### Scenario: Response binding jsonataExpression selects an array element
- **WHEN** a binding has `source: { $type: "Response", columnName: "answer", jsonataExpression: "$[0]" }` and the extracted columns contain `{"answer": ["Paris","Tokio"]}`
- **THEN** the resolver SHALL produce the value `"Paris"` for that binding's property

#### Scenario: Response binding jsonataExpression selects a nested object path
- **WHEN** a binding has `source: { $type: "Response", columnName: "meta", jsonataExpression: "usage.total" }` and the extracted columns contain `{"meta": {"usage": {"total": 42}}}`
- **THEN** the resolver SHALL produce the value `42` for that binding's property

#### Scenario: jsonataExpression matching nothing resolves to null
- **WHEN** a binding has `source: { $type: "Response", columnName: "answer", jsonataExpression: "$[2]" }` and the extracted columns contain `{"answer": ["Paris","Tokio"]}` (only indices 0 and 1 exist)
- **THEN** the resolver SHALL produce `null` for that binding's property (no exception thrown)

#### Scenario: Missing column in test case data fails fast
- **WHEN** a binding has `source: { $type: "TestCase", columnName: "score" }` and the test case `data` map does NOT contain the key `"score"` (i.e. `data.containsKey("score")` is false)
- **THEN** the resolver SHALL throw `IllegalArgumentException` with a message identifying the missing column and source type

#### Scenario: Missing column in extracted columns fails fast
- **WHEN** a binding has `source: { $type: "Response", columnName: "model_answer" }` and the extracted columns map does NOT contain the key `"model_answer"`
- **THEN** the resolver SHALL throw `IllegalArgumentException` with a message identifying the missing column and source type

#### Scenario: Present column with null value resolves to null
- **WHEN** a binding references a column that IS present in the data map (i.e. `data.containsKey(columnName)` is true) but its value is `null`
- **THEN** the resolver SHALL produce `null` for that binding's property (no exception thrown)

#### Scenario: Multiple bindings merged into single map
- **WHEN** input bindings contain `[{property: "actual", source: Response/model_answer}, {property: "ground_truth", source: TestCase/expected}]`
- **THEN** the resolver SHALL produce `{"actual": <value>, "ground_truth": <value>}`

### Requirement: EvalSummary assembly from TestCaseRunResult
The system SHALL build one EvalSummary per TestCaseRunResult, copying context fields from the result and adding computed metric values. The `extractedColumns` value SHALL be copied from the result in its stored shape without normalization (a column-major object of per-column arrays for a multi-step result; an object of scalars for a single-step result).
Status: **Implemented**

#### Scenario: Field mapping from result to summary
- **WHEN** an EvalSummary is built for a TestCaseRunResult
- **THEN** the batch write envelope SHALL carry `testSuiteId`, `testSuiteRunId`, `computationId`, and `computedAtMs` from the MetricEvaluationContext. Each item SHALL carry: `testCaseRunResultId` = result.id, `testCaseId`, `testCaseName`, `runIndex`, `testCaseData`, `extractedColumns`, `execDurationMs`, `responseStatusCode` from result. The `createdAtMs` is derived by the service from the run's creation timestamp (not set per-item).

#### Scenario: Multi-step extractedColumns stored verbatim
- **WHEN** an EvalSummary is built for a multi-step result whose `extractedColumns` is `{"answer": ["Paris","Tokio"]}`
- **THEN** `EvalSummary.extractedColumns` SHALL store `{"answer": ["Paris","Tokio"]}` unchanged (no collapse to a single step)

#### Scenario: Non-SUCCESS result propagation
- **WHEN** a TestCaseRunResult has `executionStatus != SUCCESS`
- **THEN** the EvalSummary SHALL have `executionStatus` propagated from the result, `metricValues = {}`, `metricInfos = null` — no metric evaluation SHALL be attempted

#### Scenario: Metric error determines executionStatus
- **WHEN** all metrics evaluate successfully (no `type: "error"` outputs)
- **THEN** the EvalSummary SHALL have `executionStatus = SUCCESS`

#### Scenario: Any metric error or transport failure fails the summary
- **WHEN** at least one metric output field has `type: "error"` OR at least one TSMD evaluation fails with a transport error (worker exception)
- **THEN** the EvalSummary SHALL have `executionStatus = FAILED`
