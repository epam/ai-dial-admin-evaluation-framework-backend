## Why

Imports legitimately store optional FILE values as empty strings, and validation treats blank FILE values as absent. Placeholder resolution nevertheless converts those blanks before JSONata filtering, causing `REQUEST_RESOLUTION_ERROR` despite an explicit file default.

## What Changes

- Planned: select an explicit default for blank data-derived placeholders wherever existing full-value FILE conversion applies, before converting the selected value.
- Use existing FILE classification: explicit `|file` or a hintless FILE schema binding. Include structural JSON, JSONata source, MCP arguments, and applicable multipart values.
- Preserve constants, non-FILE values, interpolation, default parsing, reference validation, and raw multipart FILE handling. Blank values without defaults retain existing failures.
- Non-goals: import/data cleanup, generic empty-string fallback, new syntax/configuration, or repairing multipart serializer incompatibility with explicit DIAL-formatted refs.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `dial-file-ref`: explicit-default fallback for blank data-derived FILE placeholders across existing conversion callers.

## Impact

- Existing runner components: `ResourceRefPlaceholderResolver`, `TemplateContentResolver`, `JsonataSourcePreprocessor`, and `McpRequestResolver`; no new packages/classes required.
- Backend/CLI runs and data-backed previews inherit the correction. Existing rows and historical results are unchanged; no re-import is needed for future runs.
- No API/DTO, schema, transaction, dependency, configuration, or security-policy changes; normal deployment/rollback.
- Risk: blank FILE data with a default deliberately selects a new value; explicit constants and ordinary STRING blanks remain unchanged. Defaults still undergo existing validation and transport behavior.
- Tests: contract matrices, JSONata sentinel filtering, multipart boundaries, MCP parity, and one context-booting backend regression.
