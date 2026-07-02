## MODIFIED Requirements

### Requirement: Binding resolution
The `BindingResolver` SHALL resolve TSMD config and input bindings against test case data and the **raw** `extractedColumns` from a `TestCaseRunResult` (no shape normalization), producing `Map<String, Object>` for config and input. Resolution SHALL fail fast when a binding references a column that does not exist in the data map. A `Response` **or** `TestCase` binding source MAY carry an optional `jsonataExpression`; when it is non-blank, the resolver SHALL evaluate it (via `JsonataEvaluationService`) against the **resolved column value** and use the result — which MAY be `null` when the expression matches nothing. When `jsonataExpression` is absent or blank, the resolver SHALL use the raw column value unchanged (the whole per-column array for a multi-step response column, or an array-valued test-case data column, a scalar otherwise). `Constant` binding sources SHALL NOT carry `jsonataExpression`.
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

#### Scenario: TestCase binding jsonataExpression selects an array element
- **WHEN** a binding has `source: { $type: "TestCase", columnName: "user_turns", jsonataExpression: "$[1]" }` and the test case data contains `{"user_turns": ["hi", "and then?"]}`
- **THEN** the resolver SHALL produce the value `"and then?"` for that binding's property

#### Scenario: TestCase binding without jsonataExpression yields the whole column value
- **WHEN** a binding has `source: { $type: "TestCase", columnName: "user_turns" }` with no `jsonataExpression` and the test case data contains `{"user_turns": ["hi", "and then?"]}`
- **THEN** the resolver SHALL produce the array `["hi", "and then?"]` for that binding's property

#### Scenario: TestCase binding jsonataExpression selects a nested object path
- **WHEN** a binding has `source: { $type: "TestCase", columnName: "meta", jsonataExpression: "labels.topic" }` and the test case data contains `{"meta": {"labels": {"topic": "geography"}}}`
- **THEN** the resolver SHALL produce the value `"geography"` for that binding's property

#### Scenario: jsonataExpression matching nothing resolves to null
- **WHEN** a binding has `source: { $type: "Response", columnName: "answer", jsonataExpression: "$[2]" }` and the extracted columns contain `{"answer": ["Paris","Tokio"]}` (only indices 0 and 1 exist)
- **THEN** the resolver SHALL produce `null` for that binding's property (no exception thrown)

#### Scenario: TestCase jsonataExpression matching nothing resolves to null
- **WHEN** a binding has `source: { $type: "TestCase", columnName: "user_turns", jsonataExpression: "$[5]" }` and the test case data contains `{"user_turns": ["hi", "and then?"]}` (only indices 0 and 1 exist)
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
