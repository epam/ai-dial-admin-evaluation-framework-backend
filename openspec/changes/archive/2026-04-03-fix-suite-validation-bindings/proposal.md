## Why

`SuiteValidationService` has two validation bugs that produce incorrect results at suite save time:

1. **FILE form part placeholder false positive**: When a multipart FILE part value is a template variable placeholder (e.g., `${{contract_file}}`), the validator passes it directly to `FileRefValidator.validate()`, which rejects the `${{...}}` string as a disallowed prefix. This marks valid suites as `valid: false` with a spurious warning like `"FILE form part 'file_attachment': File reference uses disallowed prefix '${{contract_file}}'"`. The FILE reference will be resolved at runtime via the binding chain — the placeholder itself is not a file reference.

2. **Missing MCP binding cross-validation**: `validateMcpSuite()` only checks if `argumentTemplate` is null. It performs zero cross-validation of template variables against `inputBindings` — no checks for required variables without bindings, no checks for bindings referencing unknown schema fields, no orphan binding detection. All of this validation exists in `validateDeploymentSuite()` but is completely absent for MCP suites. Errors only surface at runtime in `McpRequestResolver.resolve()`.

Additionally, MCP argument templates currently have no support for the `|file` type hint that DEPLOYMENT suites use for file reference handling:

3. **No `|file` support in MCP suites**: MCP argument templates can contain `${{document|file}}` placeholders, but `McpRequestResolver` ignores the `|file` type hint — it discards it during regex parsing and returns the raw file reference string (e.g., `@ef/suites/abc/data.csv`) without resolving it to a DIAL API path. DIAL-aware MCP tools expect the resolved format (`files/bucket/path`). The validation layer also doesn't validate `|file` constant bindings for MCP variables.

## What Changes

- **Skip FILE form part validation for template variable placeholders**: In `SuiteValidationService.validateDeploymentSuite()`, the multipart FILE part validation section must detect `${{...}}` placeholders and skip `FileRefValidator.validate()` for them. Only literal (non-placeholder) FILE part values should be validated as file references.
- **Add MCP binding cross-validation**: Port the template-variable-to-binding and binding-to-template cross-validation logic from `validateDeploymentSuite()` into `validateMcpSuite()`, using `TemplateVariableExtractor.extractFromArgumentTemplate()` for variable extraction. This includes:
  - Required variable without binding warning
  - Binding maps to unknown schema field warning
  - Orphan binding warning (binding for variable not in template)
  - `|file` constant-value validation via `FileRefValidator` (same as deployment suites)
- **Extract shared binding validation**: Extract the binding cross-validation logic into a reusable `BindingValidator` `@Component` to avoid duplication between deployment and MCP validation paths.
- **MCP `|file` runtime resolution**: In `McpRequestResolver`, capture the `|file` type hint from placeholders and resolve file reference values via `DialFileRefResolver.resolveToDialRef()` — converting `@ef/path` to `files/bucket/path` for DIAL-aware MCP tools.
- **Expose type hint warnings for MCP**: Add `extractFromArgumentTemplateWithWarnings()` to `TemplateVariableExtractor` so `validateMcpSuite()` can surface unrecognized type hint warnings (same as deployment path).

## Capabilities

### New Capabilities
_(none)_

### Modified Capabilities
- `file-ref-validation`: FILE form part validation must skip template variable placeholders — only validate literal file reference values. MCP `|file` constant bindings must be validated via `FileRefValidator`.
- `request-template`: The template-to-binding cross-validation (required variable, unknown field, orphan binding, `|file` constant validation) must also apply to MCP suites via `argumentTemplate` variables, not just deployment suites via `requestTemplate` variables.
- `mcp-tool-invocation`: `McpRequestResolver` must capture the `|file` type hint and resolve file references via `DialFileRefResolver.resolveToDialRef()` at runtime.

## Impact

- **Code**: `SuiteValidationService` — main changes in `validateDeploymentSuite()` (FILE part section) and `validateMcpSuite()` (new binding validation + `|file` support). New `BindingValidator` `@Component` extracted for shared binding cross-validation. `McpRequestResolver` — inject `DialFileRefResolver`, update regex to capture type hint, add `|file` resolution. `TemplateVariableExtractor` — new `isPlaceholder()` and `extractFromArgumentTemplateWithWarnings()` methods.
- **APIs**: No API contract changes. Validation warnings may change for existing suites (fewer false positives for FILE parts, new warnings for misconfigured MCP bindings). **Breaking behavioral change:** MCP suites with null `argumentTemplate` will change from `valid: true` to `valid: false` because `validateMcpSuite()` currently hardcodes `.valid(true)` even when warnings exist — this is corrected to `.valid(warnings.isEmpty())`. Affected MCP suites will be unable to start evaluation runs until updated. This is correct behavior (aligns with the deployment path) and surfaces real configuration problems that would fail at runtime anyway. MCP suites with null `argumentTemplate` are expected to be uncommon (no useful tool invocation without an argument template).
- **Tests**: Unit tests for `McpRequestResolver` file resolution. Functional tests for validation fixes. Existing test suites with FILE-typed bindings that were incorrectly marked `valid: false` will become `valid: true` after re-validation.
- **No DB/migration changes**. No config changes.
