## MODIFIED Requirements

### Requirement: Binding resolution
The `BindingResolver` SHALL resolve TSMD config and input bindings against test case data and extracted columns from a `TestCaseRunResult`, producing `Map<String, Object>` for config and input. Resolution SHALL fail fast when a binding references a column that does not exist in the data map.

#### Scenario: TestCase binding source
- **WHEN** a binding has `source: { $type: "TestCase", columnName: "expected" }` and the test case data contains `{"expected": "A planet"}`
- **THEN** the resolver SHALL produce `{"expected": "A planet"}` for that binding's property

#### Scenario: Response binding source
- **WHEN** a binding has `source: { $type: "Response", columnName: "model_answer" }` and the extracted columns contain `{"model_answer": "Earth is the third planet"}`
- **THEN** the resolver SHALL produce the value `"Earth is the third planet"` for that binding's property

#### Scenario: Constant binding source
- **WHEN** a binding has `source: { $type: "Constant", value: "gemini-2.5-flash-lite" }`
- **THEN** the resolver SHALL produce the literal value `"gemini-2.5-flash-lite"` for that binding's property

#### Scenario: Missing column in test case data fails fast
- **WHEN** a TestCase binding references a `columnName` that does not exist as a key in the test case data map (i.e., `containsKey` returns false)
- **THEN** the resolver SHALL throw `IllegalArgumentException` with a message identifying the missing column name and the binding source type
- **AND** the per-test-case error handling in `MetricEvaluationWorker` / `InProcessMetricEvaluationExecutor` SHALL catch this exception and record it as an error in the EvalSummary (the run does not crash)

#### Scenario: Missing column in extracted columns fails fast
- **WHEN** a Response binding references a `columnName` that does not exist as a key in the extracted columns map
- **THEN** the resolver SHALL throw `IllegalArgumentException` with a message identifying the missing column name and the binding source type

#### Scenario: Present column with null value resolves to null
- **WHEN** a binding references a column that exists in the data map but has a `null` value (i.e., `containsKey` returns true, `get` returns null)
- **THEN** the resolver SHALL produce `null` for that binding's property (valid — the field was explicitly null)

#### Scenario: Multiple bindings merged into single map
- **WHEN** input bindings contain `[{property: "actual", source: Response/model_answer}, {property: "ground_truth", source: TestCase/expected}]`
- **THEN** the resolver SHALL produce `{"actual": <value>, "ground_truth": <value>}`
