## ADDED Requirements

### Requirement: MCP argument resolution converts placeholders bound to FILE columns without a type hint

In addition to the `|file` type-hint behaviour of `McpRequestResolver`, a full-value placeholder with **no** type hint whose binding `dataField` names a schema field of type `FILE` SHALL be resolved via `DialFileRefResolver.resolveToDialRef()` when the resolved value is a `String`, using the shared `FilePlaceholderDetector` rule (see `dial-file-ref` spec). The resolver's `resolve` method SHALL receive the test case schema (`List<FieldDefinitionDto>`, nullable) as an additional parameter; `null`/empty schema means only explicit hints convert. `resolveWithVariables` (an alias of `resolve`) SHALL be removed; variables-mode try-out SHALL call `resolve` with the dataset schema, and a supplied variable that the suite binds via `dataField` to a `FILE` column SHALL be passed as a `dataField` binding with the value in the data map, so it converts like in a run. Embedded (mixed-text) placeholders SHALL remain string-concatenated without conversion.

Status: Implemented

#### Scenario: Hintless placeholder bound to a FILE column resolves to DIAL ref
- **WHEN** the argument template contains `{"document": "${{contract}}"}`
- **AND** a binding maps `contract` to `dataField: "contract_file"` and the schema declares `contract_file` as `FILE`
- **AND** test case data contains `{"contract_file": "@ef/datasets/abc/contract.pdf"}`
- **THEN** the resolver SHALL return `{"document": "files/real-bucket/datasets/abc/contract.pdf"}`

#### Scenario: Hintless placeholder bound to a non-FILE column is unchanged
- **WHEN** the argument template contains `{"query": "${{q}}"}`
- **AND** `q` is bound to a `STRING` schema field whose value is `@ef/datasets/abc/contract.pdf`
- **THEN** the resolver SHALL return `{"query": "@ef/datasets/abc/contract.pdf"}`

#### Scenario: Hintless placeholder with no binding and a matching field name is unchanged
- **WHEN** the argument template contains `{"document": "${{contract}}"}`
- **AND** bindings is `null` or empty, and test case data contains `{"contract": "@ef/datasets/abc/contract.pdf"}`
- **THEN** the resolver SHALL return the value unchanged (the direct-name lookup fallback carries no column type)

#### Scenario: Embedded placeholder bound to a FILE column is not converted
- **WHEN** the argument template contains `{"path": "prefix/${{doc}}/suffix"}`
- **AND** `doc` is bound to a `FILE` schema field with value `@ef/datasets/abc/data.csv`
- **THEN** the resolver SHALL return `{"path": "prefix/@ef/datasets/abc/data.csv/suffix"}`

#### Scenario: Null schema keeps explicit-hint behaviour
- **WHEN** `resolve` is called with a `null` schema
- **AND** the argument template contains `{"document": "${{contract|file}}"}`
- **THEN** the resolver SHALL still convert the value via `DialFileRefResolver.resolveToDialRef()`

#### Implementation notes
- `McpRequestResolver.resolveFileRef` delegates to `FilePlaceholderDetector`; callers: `EvaluationWorker` (schema from `EvaluationContext.snapshotTestCaseSchema`) and `TryItOutService` (dataset schema).
