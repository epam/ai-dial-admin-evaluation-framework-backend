## MODIFIED Requirements

### Requirement: McpRequestResolver resolves argument templates

The system SHALL provide a `McpRequestResolver` component in `service.domain` that resolves an MCP argument template by substituting `${{variable}}` placeholders with values from input bindings and test case data. The resolver SHALL produce a `Map<String, Object>` of resolved tool arguments. The resolver is a stateless transformer — it receives the argument template, input bindings, and test case data as method parameters and does NOT perform any DB I/O. The caller (worker or try-it-out service) is responsible for loading data before calling the resolver.

Method signatures:
- `resolve(ArgumentTemplateDto argumentTemplate, List<InputBindingDto> bindings, Map<String, Object> testCaseData)` — used by test-case try-it-out and evaluation worker
- `resolveWithVariables(ArgumentTemplateDto argumentTemplate, List<InputBindingDto> bindings, Map<String, Object> variables)` — used by variables try-it-out (delegates to `resolve`)

Resolution priority per variable:
1. Binding `constantValue` (if a binding exists for the variable and has `constantValue`)
2. Binding `dataField` lookup (if a binding exists and has `dataField`, look up `data[dataField]`)
3. Direct variable name lookup in data (fallback when no binding matches the variable)
4. Template default value (from `${{var:default}}` syntax)
5. `null` + `REQUIRED` validation warning (when no value can be resolved and no default exists)

When `bindings` is `null` or empty, the resolver falls back directly to step 3 (direct variable name lookup). Duplicate `templateVariable` entries in bindings are deduplicated first-wins.

**File reference resolution:** When a full-value placeholder carries a `|file` type hint (e.g., `${{document|file}}`) and the resolved value is a `String`, the resolver SHALL transform the value via `DialFileRefResolver.resolveToDialRef()` — converting short-format references (e.g., `@ef/suites/abc/data.csv`) to DIAL API paths (e.g., `files/real-bucket/suites/abc/data.csv`). This enables DIAL-aware MCP tools to receive properly resolved file references. File resolution applies only to full-value placeholders (entire argument value is one `${{var|file}}`); embedded placeholders in mixed text are string-concatenated without file resolution.

Status: **Implemented**

#### Scenario: Resolve arguments with binding dataField
- **WHEN** the argument template contains `{"query": "${{search_query}}", "limit": "${{max_results:10}}"}`
- **AND** input bindings map `search_query` to data field `question`
- **AND** test case data contains `{"question": "What is MCP?"}`
- **THEN** the resolver SHALL return `{"query": "What is MCP?", "limit": 10}` (binding dataField used for lookup, default value used for unbound variable)

#### Scenario: Resolve arguments with binding constantValue
- **WHEN** the argument template contains `{"query": "${{search_query}}"}`
- **AND** input bindings map `search_query` with `constantValue: "fixed-value"`
- **AND** test case data contains `{"search_query": "from-data"}`
- **THEN** the resolver SHALL return `{"query": "fixed-value"}` (constantValue overrides data lookup)

#### Scenario: Resolve arguments with no bindings (direct name lookup)
- **WHEN** the argument template contains `{"query": "${{searchQuery}}"}`
- **AND** bindings is `null` or empty
- **AND** test case data contains `{"searchQuery": "hello world"}`
- **THEN** the resolver SHALL return `{"query": "hello world"}` (direct variable name lookup in data)

#### Scenario: Binding dataField missing from data falls back to default
- **WHEN** the argument template contains `{"query": "${{searchQuery:fallback}}"}`
- **AND** a binding maps `searchQuery` to `dataField: "missing_key"`
- **AND** test case data does not contain `missing_key`
- **THEN** the resolver SHALL return `{"query": "fallback"}` (template default used as fallback)

#### Scenario: Constant values in argument template
- **WHEN** the argument template contains `{"format": "json", "query": "${{search_query}}"}`
- **THEN** `format` SHALL be included as-is (constant value), while `search_query` SHALL be resolved from bindings

#### Scenario: Type coercion from test case data
- **WHEN** a test case field value is a number (e.g., `10`) and the argument template placeholder expects it
- **THEN** the resolver SHALL preserve the original JSON type (number, boolean, object, array) — not convert to string

#### Scenario: Binding constantValue preserves non-string types
- **WHEN** a binding has `constantValue: 42` (integer)
- **AND** the argument template has `{"count": "${{num}}"}`
- **THEN** the resolver SHALL return `{"count": 42}` (type preserved via full-value resolution)

#### Scenario: Missing required variable
- **WHEN** a `${{variable}}` placeholder has no default value, no binding provides a value, and the variable name is not in test case data
- **THEN** the resolver SHALL produce a `REQUIRED` validation warning with `fieldName` = variable name, `path` = `$.argumentTemplate.arguments`, and set the resolved value to `null` (full-value mode) or empty string (embedded mode)

#### Scenario: |file type hint resolves to DIAL ref
- **WHEN** the argument template contains `{"document": "${{contract|file}}"}`
- **AND** the resolved value for `contract` is `"@ef/suites/abc/contract.pdf"` (from binding or data)
- **THEN** the resolver SHALL return `{"document": "files/real-bucket/suites/abc/contract.pdf"}` (resolved via `DialFileRefResolver.resolveToDialRef()`)

#### Scenario: |file type hint with constantValue binding
- **WHEN** the argument template contains `{"attachment": "${{doc|file}}"}`
- **AND** a binding for `doc` has `constantValue: "@ef/suites/abc/report.pdf"`
- **THEN** the resolver SHALL return `{"attachment": "files/real-bucket/suites/abc/report.pdf"}`

#### Scenario: |file type hint with null resolved value
- **WHEN** the argument template contains `{"document": "${{contract|file}}"}`
- **AND** no binding, data, or default provides a value for `contract`
- **THEN** the resolver SHALL produce a `REQUIRED` warning and set the value to `null` (no file resolution applied to null)

#### Scenario: |file in embedded placeholder (mixed text) not resolved
- **WHEN** the argument template contains `{"path": "prefix/${{doc|file}}/suffix"}`
- **AND** the resolved value for `doc` is `"@ef/suites/abc/data.csv"`
- **THEN** the resolver SHALL return `{"path": "prefix/@ef/suites/abc/data.csv/suffix"}` (string concatenation, NO file ref resolution — embedded mode)

#### Scenario: Non-string resolved value with |file hint not transformed
- **WHEN** the argument template contains `{"doc": "${{doc|file}}"}`
- **AND** the resolved value for `doc` is an integer or object (not a String)
- **THEN** the resolver SHALL return the resolved value as-is without file ref transformation

#### Scenario: |file resolution error propagates
- **WHEN** the argument template contains `{"document": "${{contract|file}}"}`
- **AND** the resolved value is a malformed file reference (e.g., `"invalid-no-prefix"`)
- **THEN** the resolver SHALL propagate the exception from `DialFileRefResolver.resolveToDialRef()` (fail-fast — the caller `EvaluationWorker`/`TryItOutService` handles errors at the job/request level via existing exception handling)

#### Scenario: |file with default value resolves the default as a file ref
- **WHEN** the argument template contains `{"document": "${{contract|file:@ef/suites/abc/default.pdf}}"}`
- **AND** no binding, data, or explicit value provides a value for `contract`
- **THEN** the resolver SHALL use the default value `"@ef/suites/abc/default.pdf"` as the resolved value
- **AND** SHALL transform it via `DialFileRefResolver.resolveToDialRef()` (the default string IS the resolved value — file resolution applies to it)

#### Scenario: |FILE uppercase type hint resolves as file ref (case-insensitive)
- **WHEN** the argument template contains `{"document": "${{contract|FILE}}"}`
- **AND** the resolved value for `contract` is `"@ef/suites/abc/contract.pdf"`
- **THEN** the resolver SHALL treat `FILE` the same as `file` (case-insensitive match via `SchemaFieldType.FILE.name().equalsIgnoreCase()`)
- **AND** return the value resolved via `DialFileRefResolver.resolveToDialRef()`

#### Scenario: Multiple |file variables in same argument template resolved independently
- **WHEN** the argument template contains `{"doc1": "${{a|file}}", "doc2": "${{b|file}}"}`
- **AND** the resolved value for `a` is `"@ef/suites/abc/first.pdf"` and for `b` is `"@ef/suites/abc/second.pdf"`
- **THEN** the resolver SHALL resolve each independently via `DialFileRefResolver.resolveToDialRef()`
- **AND** return `{"doc1": "files/real-bucket/suites/abc/first.pdf", "doc2": "files/real-bucket/suites/abc/second.pdf"}`

## Implementation Notes

- `McpRequestResolver.PLACEHOLDER_PATTERN` must be updated to capture the type hint: change `(?:\|[^:}]+)?` (non-capturing) to `(?:\|([^:}]+))?` (capturing group 2). This shifts the default value from group 2 to group 3 — update all `matcher.group()` references accordingly.
- Inject `DialFileRefResolver` into `McpRequestResolver` via constructor injection. Both are in `service.domain` — no layering issue.
- File resolution check: `if (SchemaFieldType.FILE.name().equalsIgnoreCase(typeHint) && resolved instanceof String resolvedRef)` — same pattern as `ResolvedRequestService` line 262.
- Only full-value placeholders trigger file resolution. Embedded placeholders use string concatenation and skip file ref transformation.
