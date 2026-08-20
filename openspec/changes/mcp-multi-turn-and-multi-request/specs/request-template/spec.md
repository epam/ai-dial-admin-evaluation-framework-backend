## MODIFIED Requirements

### Requirement: Unconditional JSONata evaluation of JSON request bodies
Every `application/json` request body — `content`-authored (Map) or `jsonataContent`-authored (String), single-turn or multi-turn — SHALL be evaluated as a JSONata expression before being sent. The same rule SHALL apply to an MCP argument template: `arguments`-authored (Map) or `jsonataArguments`-authored (String), it SHALL be evaluated as a JSONata expression before the tool call is issued. There SHALL be no content-inspection or mode flag that decides whether to evaluate; the field a template is authored in selects only how the body or argument text is produced (structural resolution + serialization vs. textual placeholder preprocessing), never whether evaluation happens. A plain JSON literal is valid JSONata source that evaluates to itself (JSON is a syntactic subset of JSONata), so this SHALL NOT change the resolved body of any pre-existing Map-authored template, nor the resolved arguments of any pre-existing `arguments`-authored MCP template, that does not itself use JSONata-specific syntax (functions, `$` variables, operators).

Status: **Planned**

#### Scenario: Legacy Map body with no JSONata syntax is unaffected
- **WHEN** a suite's `requestTemplate.body.content` is a plain Map with only literal values and `${{variable}}` placeholders (no JSONata functions or `$` references)
- **THEN** the resolved request body sent to the deployment is identical to the body that would have been produced by pre-JSONata structural resolution alone

#### Scenario: Legacy MCP arguments map with no JSONata syntax is unaffected
- **WHEN** an `MCP_TOOL` suite's `argumentTemplate.arguments` is a plain Map with only literal values and `${{variable}}` placeholders
- **THEN** the resolved tool arguments are identical to those produced before argument templates were routed through JSONata evaluation

#### Scenario: Field choice does not gate evaluation
- **WHEN** a suite's body is authored in `jsonataContent` and another suite's equivalent body is authored in `content`
- **THEN** both SHALL be JSONata-evaluated before being sent, and both SHALL be subject to the same runtime object contract

#### Scenario: Argument authoring field does not gate evaluation
- **WHEN** one MCP suite's arguments are authored in `jsonataArguments` and another's equivalent arguments in `arguments`
- **THEN** both SHALL be JSONata-evaluated before the tool call, and both SHALL be subject to the same runtime object contract

### Requirement: Template variables for MCP suites

The `TemplateVariableService` SHALL support MCP_TOOL suites via `GET /api/v1/test-suites/{id}/template-variables` and `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables`. When the suite type is `MCP_TOOL`, the service SHALL extract variables from the argument template (not `requestTemplate`) and resolve them with input bindings support. Extraction SHALL cover both argument authoring forms: the structural `arguments` map and the `jsonataArguments` source text. For a suite carrying a request chain, extraction SHALL cover request #0's argument template and every `additionalRequests[i].argumentTemplate`, in chain order, so a chained MCP suite reports the variables of its whole chain.

MCP suites support the same `inputBindings` mechanism as HTTP suites, and the resolution priority for MCP template variables SHALL be the same chain used for HTTP template variables: binding `constantValue` > binding `dataField` lookup > template default > `null`.

Status: **Planned**

#### Scenario: MCP suite-level template variables extracted from argument template
- **WHEN** a suite with `suiteType = MCP_TOOL` has `argumentTemplate.arguments = {"query": "${{userQuery}}", "limit": "${{maxResults:10}}"}`
- **THEN** `GET /api/v1/test-suites/{id}/template-variables` SHALL return variables `userQuery` and `maxResults` with `sources = [ARGUMENT]`
- **AND** `resolvedValue` SHALL be `null` for `userQuery` (no default, no data at suite level) and `"10"` for `maxResults` (has default)

#### Scenario: Variables inside a JSONata argument source are extracted
- **WHEN** an `MCP_TOOL` suite's `argumentTemplate.jsonataArguments` source contains `${{userQuery}}` spliced into an expression
- **THEN** the template-variables endpoint SHALL report `userQuery`, exactly as it does for a `jsonataContent` request body

#### Scenario: Variables of every chain position are reported
- **WHEN** an `MCP_TOOL` suite's request #0 references `${{a}}` and its additional request references `${{b}}`
- **THEN** the template-variables endpoint SHALL report both `a` and `b`

#### Scenario: MCP suite-level template variables with constant-value binding
- **WHEN** a suite with `suiteType = MCP_TOOL` has a binding with `templateVariable: "userQuery"` and `constantValue: "fixed query"`
- **THEN** `GET /api/v1/test-suites/{id}/template-variables` SHALL return `userQuery` with `resolvedValue = "fixed query"` and `binding` populated

#### Scenario: MCP test-case-level template variables resolved from bindings and data
- **WHEN** a test case in the dataset of an MCP_TOOL suite has a binding mapping `userQuery` to `dataField: "question"`
- **AND** the test case has `data = {"question": "What is AI?"}`
- **THEN** `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables` SHALL return `userQuery` with `resolvedValue = "What is AI?"` (resolved via binding dataField lookup)

#### Scenario: MCP test-case-level template variables with no bindings (direct name lookup)
- **WHEN** a test case in the dataset of an MCP_TOOL suite with no input bindings
- **AND** the test case has `data = {"userQuery": "What is AI?"}`
- **THEN** the variable `userQuery` SHALL resolve via direct variable name lookup in data, returning `resolvedValue = "What is AI?"`

#### Scenario: MCP variable type inference
- **WHEN** an MCP template variable has no declared type hint
- **THEN** `effectiveType` SHALL be inferred from the dataset's test-case schema by matching the variable name to a schema field name
- **AND** if no match is found, `effectiveType` SHALL default to `STRING`

#### Scenario: MCP variable with declared type hint takes priority
- **WHEN** an MCP template variable has a declared type hint (e.g., `${{count|integer}}`)
- **THEN** `declaredType` SHALL take priority over the `testCaseSchema` type

#### Scenario: MCP suite with null argument template
- **WHEN** an MCP_TOOL suite has `argumentTemplate` as null and no chain
- **THEN** the template variables endpoint SHALL return an empty list

#### Scenario: MCP variable extraction uses TemplateVariableExtractor
- **WHEN** extracting variables from an MCP argument template
- **THEN** the system SHALL use `TemplateVariableExtractor`, which recursively scans `argumentTemplate.arguments` for `${{variable}}` placeholders using the same extraction logic as HTTP templates
- **AND** SHALL additionally scan `argumentTemplate.jsonataArguments` as raw source text, using the same explicit-scan path already applied to `jsonataContent`

## ADDED Requirements

### Requirement: Mutual exclusivity of `arguments` and `jsonataArguments`
An `ArgumentTemplateDto` SHALL carry at most one of `arguments` (`Map<String, Object>`) and `jsonataArguments` (`String`). Populating both SHALL be rejected at write time with HTTP 400 (`VALIDATION_ERROR`), on create, update, and clone, and for request #0 as well as every `additionalRequests[i].argumentTemplate` — the error message SHALL identify which chain position is at fault. Populating neither SHALL be permitted and SHALL mean "no arguments", consistent with an absent argument template today. The rule mirrors the existing `content` / `jsonataContent` exclusivity on a JSON request body.

Status: **Planned**

#### Scenario: Both argument fields set is rejected
- **WHEN** a suite is created with an `argumentTemplate` carrying both `arguments` and `jsonataArguments`
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) and SHALL NOT persist the suite

#### Scenario: Both argument fields set on a chain entry is rejected
- **WHEN** an `additionalRequests[1].argumentTemplate` carries both fields
- **THEN** the system SHALL respond HTTP 400 with a message naming that chain position

#### Scenario: Neither argument field set is accepted
- **WHEN** an `argumentTemplate` carries neither field
- **THEN** the suite SHALL be accepted and the tool SHALL be called with no arguments

#### Scenario: Exclusivity is enforced on clone
- **WHEN** a clone's effective post-override suite would carry both fields on any argument template
- **THEN** the clone SHALL be rejected with HTTP 400

### Requirement: JSONata syntax validation for `jsonataArguments`
A `jsonataArguments` source SHALL be parsed at write time with its `${{}}` placeholders neutralized, and a source that fails to parse SHALL be rejected with HTTP 400 (`VALIDATION_ERROR`) identifying the chain position and carrying the parser's message. Validation SHALL be syntax-only — a source that parses but cannot evaluate against a given test case remains a run-time concern, surfacing as an `ERROR` row rather than a write-time rejection. This mirrors the existing write-time syntax validation of `jsonataContent` request bodies.

Status: **Planned**

#### Scenario: Malformed JSONata argument source is rejected at write time
- **WHEN** a suite is created with a `jsonataArguments` source that is not parseable JSONata
- **THEN** the system SHALL respond HTTP 400 (`VALIDATION_ERROR`) with the parser message, and SHALL NOT persist the suite

#### Scenario: Placeholders do not break parsing
- **WHEN** a `jsonataArguments` source embeds `${{}}` placeholders in quoted, embedded, and bare positions
- **THEN** validation SHALL neutralize them before parsing, so a source that is valid once substituted is accepted

#### Scenario: Parseable source referencing an unavailable column is accepted
- **WHEN** a `jsonataArguments` source references a frame variable that no response column defines
- **THEN** the write SHALL be accepted, and the outcome SHALL be decided at run time by the runtime object contract

#### Scenario: Syntax validation applies to every chain position
- **WHEN** `additionalRequests[2].argumentTemplate.jsonataArguments` is unparseable
- **THEN** the write SHALL be rejected with a message naming that chain position

## Implementation Notes

- `ArgumentTemplateDto` gains `jsonataArguments`; both forms route through `runner.service.RequestBodyEvaluator`, so placeholder substitution modes, frame binding, the object-result contract, reserved frame-binding names, and the `JsonataProperties` evaluation bounds are shared with request bodies by construction.
- Write-time checks mirror the existing `content`/`jsonataContent` pair in `TestSuiteRequestValidator`, including the `JsonataSourcePreprocessor.neutralize` step before parsing.
- `TemplateVariableExtractor` must scan `jsonataArguments` explicitly — as it already does for `jsonataContent` — or `${{}}` variables in JSONata argument sources go unreported.
