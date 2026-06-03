# Response Columns — MCP Extension (Delta)

## ADDED Requirements

### Requirement: MCP response extraction paths

Response column JSONata expressions SHALL work against the serialized MCP response envelope structure. The system SHALL NOT impose any format restrictions on JSONata expressions — users may target any path in the serialized response JSON.

#### Scenario: Extract isError flag
- **WHEN** a response column has `expression: "isError"` and the MCP response has `isError = true`
- **THEN** `extracted_columns["error_flag"]` SHALL be `true`

#### Scenario: Extract first text content block
- **WHEN** a response column has `expression: "content[0].text"` and the MCP response has `content: [{"type": "text", "text": "Hello"}]`
- **THEN** `extracted_columns["response_text"]` SHALL be `"Hello"`

#### Scenario: Extract from structuredContent
- **WHEN** a response column has `expression: "structuredContent.score"` and the MCP response has `structuredContent: {"score": 0.95}`
- **THEN** `extracted_columns["score"]` SHALL be `0.95`

#### Scenario: Count content blocks
- **WHEN** a response column has `expression: "$count(content)"` and the MCP response has 3 content blocks
- **THEN** `extracted_columns["block_count"]` SHALL be `3`

#### Scenario: Extract from specific content type
- **WHEN** a response column has `expression: "content[type='text'][0].text"`
- **THEN** the extraction SHALL return the text of the first text-type content block (skipping image/audio blocks)

#### Scenario: structuredContent absent
- **WHEN** a response column targets `structuredContent.field` but the MCP response has no `structuredContent`
- **THEN** `extracted_columns["field"]` SHALL be `null` and `extraction_warnings` SHALL contain a path-not-found warning

### Requirement: MCP response columns work with existing validation

The existing JSONata expression validation (syntax check on suite save) SHALL work for MCP-targeting expressions — JSONata syntax is format-agnostic.

#### Scenario: Valid MCP-targeting expression accepted
- **WHEN** suite is saved with `expression: "content[0].text"`
- **THEN** system SHALL accept and persist the column (JSONata syntax is valid regardless of expected response shape)

#### Scenario: Complex MCP expression accepted
- **WHEN** suite is saved with `expression: "$count(content[type='text'])"` or `expression: "structuredContent.results[score > 0.8]"`
- **THEN** system SHALL accept (valid JSONata syntax)

## Implementation Notes
- No code changes to `ResponseColumnExtractor` or `JsonataEvaluationService` — they already work on arbitrary JSON strings
- The MCP envelope structure (`content`, `structuredContent`, `isError`) is preserved by `McpResponseSerializer`
- FE/UI can suggest MCP-specific extraction paths based on `toolRef.outputSchema` — this is a client-side concern
