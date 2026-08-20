## REMOVED Requirements

### Requirement: McpRequestResolver resolves argument templates
**Reason**: The dedicated MCP resolver was a second implementation of the placeholder-resolution engine already used for HTTP request templates, with three silent behavioural divergences (implicit binding by variable name, type-coerced template defaults, and different warning paths). Its observable behaviour is absorbed by the shared request-body evaluation seam, which additionally supplies frame bindings that an MCP argument template previously could not reach. Replaced by "Argument template resolution and binding precedence", "File reference resolution in argument templates", and "JSONata argument templates receive frame bindings".
**Migration**: No client action required and no change to how an `arguments`-authored template resolves: binding precedence, template defaults, type preservation for whole-value placeholders, embedded-placeholder stringification, and `|file` handling are all preserved. Authors gain the optional `jsonataArguments` form for arguments that must reference values returned by an earlier turn or request.

## ADDED Requirements

### Requirement: Argument template resolution and binding precedence
An MCP argument template SHALL be resolved to a `Map<String, Object>` of tool arguments by substituting `${{variable}}` and `${{variable|type:default}}` placeholders with values drawn from the request's effective input bindings and the turn's effective test-case data. Resolution SHALL use the same placeholder engine, binding-priority chain, and warning contract as an HTTP request template's structural body. Resolution SHALL be a pure transformation over its inputs and SHALL perform no database access — the caller supplies the template, bindings, and data.

Resolution priority per variable SHALL be:
1. Binding `constantValue`, when a binding exists for the variable and carries one
2. Binding `dataField` lookup in the turn's effective data
3. Direct lookup of the variable's own name in the turn's effective data, when no binding matches the variable
4. Template default value from `${{var:default}}` syntax
5. `null` together with a `REQUIRED` validation warning, when no value can be resolved and no default exists

Step 3 — implicit binding by variable name — SHALL be retained for argument templates. It has no counterpart in HTTP request-template resolution, but existing MCP suites depend on it (an argument template referencing `${{query}}` against a dataset field named `query`, with no `inputBindings` declared, is the shape the documented MCP example uses), so unifying the resolution path SHALL NOT remove it. When `bindings` is null or empty, resolution SHALL fall through directly to step 3.

Duplicate `templateVariable` entries in bindings SHALL be deduplicated first-wins. A whole-value placeholder — one whose placeholder is the entire argument value — SHALL preserve the resolved value's JSON type; a placeholder embedded in surrounding text SHALL be stringified into the enclosing string. Nested objects and arrays within `arguments` SHALL be resolved recursively.

Status: **Planned**

#### Scenario: Resolve arguments with binding dataField
- **WHEN** the argument template contains `{"query": "${{search_query}}", "limit": "${{max_results:10}}"}`
- **AND** input bindings map `search_query` to data field `question`
- **AND** the effective data contains `{"question": "What is MCP?"}`
- **THEN** the resolved arguments SHALL be `{"query": "What is MCP?", "limit": 10}`

#### Scenario: Binding constantValue overrides data lookup
- **WHEN** a binding maps `search_query` with `constantValue: "fixed-value"` and the effective data contains `{"search_query": "from-data"}`
- **THEN** the resolved arguments SHALL use `"fixed-value"`

#### Scenario: Binding constantValue preserves non-string types
- **WHEN** a binding has `constantValue: 42` and the argument template has `{"count": "${{num}}"}`
- **THEN** the resolved arguments SHALL be `{"count": 42}` with the integer type preserved

#### Scenario: Resolve arguments with no bindings via direct name lookup
- **WHEN** the argument template contains `{"query": "${{searchQuery}}"}`
- **AND** the suite declares no input bindings
- **AND** the effective data contains `{"searchQuery": "hello world"}`
- **THEN** the resolved arguments SHALL be `{"query": "hello world"}`, preserving the pre-existing implicit-binding-by-name behaviour

#### Scenario: Binding dataField missing from data falls back to the template default
- **WHEN** the argument template contains `{"query": "${{searchQuery:fallback}}"}` and a binding maps `searchQuery` to a `dataField` absent from the data
- **THEN** the resolved arguments SHALL be `{"query": "fallback"}`

#### Scenario: Constant values in the argument template pass through
- **WHEN** the argument template contains `{"format": "json", "query": "${{search_query}}"}`
- **THEN** `format` SHALL be included as-is and `search_query` SHALL be resolved

#### Scenario: Type is preserved from the effective data
- **WHEN** a data field value is a number, boolean, object, or array and a whole-value placeholder references it
- **THEN** the resolved argument SHALL preserve that JSON type rather than stringifying it

#### Scenario: Missing required variable produces a warning
- **WHEN** a `${{variable}}` placeholder has no binding value, no data value, and no default
- **THEN** resolution SHALL produce a `REQUIRED` validation warning naming the variable, and set the value to `null` for a whole-value placeholder or the empty string for an embedded one

#### Scenario: Nested arguments are resolved recursively
- **WHEN** the argument template contains `{"filter": {"terms": ["${{a}}", "${{b}}"]}}`
- **THEN** both placeholders SHALL be resolved in place, preserving the nested object and array structure

### Requirement: File reference resolution in argument templates
A whole-value placeholder carrying a `|file` type hint whose resolved value is a `String` SHALL be transformed from the short DIAL reference format to a DIAL API path before being sent to the tool, so DIAL-aware MCP tools receive a resolvable reference. The type hint SHALL be matched case-insensitively. File resolution SHALL apply only to whole-value placeholders; a `|file` placeholder embedded in surrounding text SHALL be string-concatenated with no file resolution. A non-`String` resolved value SHALL be passed through untransformed. A malformed reference SHALL fail fast, propagating to the caller's existing error handling — for a run this yields an `ERROR` row, for try-it-out an error response.

Status: **Planned**

#### Scenario: Whole-value file placeholder resolves to a DIAL ref
- **WHEN** the argument template contains `{"document": "${{contract|file}}"}` and `contract` resolves to `"@ef/suites/abc/contract.pdf"`
- **THEN** the resolved arguments SHALL carry the DIAL API path form of that reference

#### Scenario: Uppercase type hint is matched case-insensitively
- **WHEN** the argument template contains `{"document": "${{contract|FILE}}"}`
- **THEN** `FILE` SHALL be treated identically to `file`

#### Scenario: Template default is itself resolved as a file ref
- **WHEN** the argument template contains `{"document": "${{contract|file:@ef/suites/abc/default.pdf}}"}` and nothing else provides a value
- **THEN** the default SHALL become the resolved value and SHALL be transformed to its DIAL API path form

#### Scenario: Embedded file placeholder is not resolved
- **WHEN** the argument template contains `{"path": "prefix/${{doc|file}}/suffix"}` and `doc` resolves to `"@ef/suites/abc/data.csv"`
- **THEN** the resolved value SHALL be the plain concatenation, with no file-reference transformation

#### Scenario: Null resolved value is not transformed
- **WHEN** a `|file` placeholder resolves to no value
- **THEN** resolution SHALL produce a `REQUIRED` warning and leave the value `null`, applying no file resolution

#### Scenario: Non-string resolved value is not transformed
- **WHEN** a `|file` placeholder resolves to a number or object
- **THEN** the value SHALL be passed through unchanged

#### Scenario: Malformed file reference fails fast
- **WHEN** a `|file` placeholder resolves to a value that is not a valid file reference
- **THEN** resolution SHALL propagate the failure rather than substituting a fallback

### Requirement: JSONata argument templates receive frame bindings
An argument template MAY be authored as a JSONata source (`jsonataArguments`) instead of a structural map (`arguments`). Both forms SHALL converge on one evaluation path and SHALL be evaluated with a frame carrying, bound by name, every response column extracted so far within the test-case repetition — this request's earlier turns plus every earlier request in the chain. The evaluated result SHALL be a JSON object; a non-object result or an evaluation failure SHALL NOT issue the tool call and SHALL persist the row as `ERROR`. A `${{}}` placeholder inside a JSONata argument source SHALL be resolved and spliced into the source text before evaluation, under the same three substitution modes that apply to a JSONata request body. The structural `arguments` form SHALL NOT be able to reference frame variables, because a JSON map cannot express an unquoted variable reference; authors needing frame access SHALL use `jsonataArguments`.

Status: **Planned**

#### Scenario: Later turn reads an earlier turn's extracted column
- **WHEN** an `MCP_TOOL` suite extracts a response column `nextCursor` and its `jsonataArguments` source references `$nextCursor`
- **THEN** turn 1's arguments SHALL carry the value extracted from turn 0's response

#### Scenario: Later request reads an earlier request's extracted column
- **WHEN** request #0 of an MCP chain extracts `ticketId` and request #1's `jsonataArguments` source references `$ticketId`
- **THEN** request #1's arguments SHALL carry the value extracted by request #0

#### Scenario: Turn 0 of request #0 evaluates with an empty frame
- **WHEN** the first turn of the first request in a chain is resolved
- **THEN** the frame SHALL be empty and a source referencing an unset column SHALL evaluate that reference as undefined rather than failing

#### Scenario: Structural arguments cannot reach the frame
- **WHEN** an `arguments`-authored template contains the string value `"$history"`
- **THEN** the resolved argument SHALL be the literal string `"$history"`, not the frame value

#### Scenario: Non-object evaluation result is an ERROR row
- **WHEN** a `jsonataArguments` source evaluates to an array, a scalar, or throws at runtime
- **THEN** the tool call SHALL NOT be issued and the row SHALL be persisted as `ERROR`

#### Scenario: Failed extraction binds an explicit null
- **WHEN** a response column defined by the suite fails to extract for a turn
- **THEN** the next turn's frame SHALL bind that column's name to an explicit JSON null rather than leaving it unbound

### Requirement: Per-request tool reference within a chain
A tool call's target SHALL be resolved from the suite-level `mcpDeploymentRef` combined with the executing request's own `toolRef`. Request #0's `toolRef` is the suite-level field; requests 1..N-1 take theirs from their `additionalRequests` entry. The transport SHALL be taken from the suite-level `mcpDeploymentRef` and SHALL therefore be uniform across the chain. Each request SHALL resolve its own `argumentTemplate`, `inputBindings`, and `responseColumns`.

Status: **Planned**

#### Scenario: Each chain position calls its own tool
- **WHEN** an `MCP_TOOL` suite's request #0 targets tool `search` and its additional request targets tool `fetch`
- **THEN** the run SHALL invoke `search` then `fetch`, both against the suite's single `mcpDeploymentRef`

#### Scenario: Transport is uniform across the chain
- **WHEN** the suite's `mcpDeploymentRef.transport` is `SSE`
- **THEN** every request in the chain SHALL use `SSE`, and no chain entry SHALL be able to override it

#### Scenario: Each request extracts its own response columns
- **WHEN** request #0 defines column `a` and request #1 defines column `b`
- **THEN** request #0's row SHALL carry `a`, and request #1's row SHALL carry the accumulated union of `a` and `b`

## Implementation Notes

- Argument resolution and JSONata evaluation reuse `runner.service.RequestBodyEvaluator`, `TemplateContentResolver`, `TemplateVariableResolver`, and `JsonataSourcePreprocessor`; `runner.service.McpRequestResolver` is deleted.
- The per-turn invoke step is selected once per chain by suite type; the MCP implementation wraps `McpToolInvoker` and `McpResponseSerializer` and returns the same per-turn outcome shape as the HTTP implementation, so retry/backoff, oversize handling, status mapping, and row building stay shared.
- Response-column extraction over the MCP envelope (`{content, structuredContent, isError}`) is unchanged, including the `$_request`/`$_response` frame bindings.
