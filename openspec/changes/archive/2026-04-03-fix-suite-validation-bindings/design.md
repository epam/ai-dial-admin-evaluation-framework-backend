## Context

`SuiteValidationService` performs soft validation when saving a TestSuite, producing warnings that determine the suite's `isValid` flag. Two bugs exist, plus a missing feature:

1. **FILE form part placeholder false positive** (DEPLOYMENT suites): The multipart FILE part validation section passes raw `${{variable}}` placeholders to `FileRefValidator.validate()`, which rejects them as disallowed prefixes. Template variables are not file references — they resolve to file references at runtime via the binding chain.

2. **Missing MCP binding cross-validation** (MCP_TOOL suites): `validateMcpSuite()` only warns if `argumentTemplate` is null. The full binding cross-validation (required variables, unknown fields, orphan bindings) exists only in `validateDeploymentSuite()`.

3. **No `|file` support in MCP path**: MCP argument templates can contain `${{doc|file}}` placeholders, but `McpRequestResolver` ignores the type hint (non-capturing regex group) and returns the raw `@ef/...` reference. DIAL-aware MCP tools expect resolved `files/bucket/path` format. The validation layer also doesn't validate `|file` constant bindings for MCP variables.

**Current state:**
- `validateDeploymentSuite()` has ~140 lines of validation logic including template-to-binding cross-checks and FILE part validation.
- `validateMcpSuite()` has ~10 lines — only a null check on `argumentTemplate`.
- `TemplateVariableExtractor` already has `extractFromArgumentTemplate()` for MCP variable extraction (preserves `declaredType` from `|file` hints).
- `McpRequestResolver.PLACEHOLDER_PATTERN` has a non-capturing group for type hint: `(?:\|[^:}]+)?` — captures varName (group 1) and default (group 2), discards type.
- `ResolvedRequestService` (deployment path) already implements `|file` resolution at line 262 via `DialFileRefResolver.resolveToDialRef()`.

## Goals / Non-Goals

**Goals:**
- Fix FILE form part validation to skip template variable placeholders, only validating literal file reference values.
- Add binding cross-validation to MCP suites: required variable without binding, binding to unknown schema field, orphan bindings, `|file` constant-value validation.
- Extract shared binding validation logic to avoid duplication between deployment and MCP paths.
- Add `|file` runtime resolution to `McpRequestResolver` for DIAL-aware MCP tools (v1b).
- Expose type hint warnings for MCP argument templates via `extractFromArgumentTemplateWithWarnings()`.

**Non-Goals:**
- Changing the validation warning format or codes (existing codes `REQUIRED`, `UNKNOWN`, `ADDITIONAL` are reused).
- Base64 file content encoding for generic (non-DIAL) MCP tools — deferred to v2. Would require `DialFileClient` byte download, size limits, and encoding convention design.
- Changing the hard validation in `TestSuiteRequestValidator` (it already validates MCP binding limits and duplicates).
- Re-validating existing suites (users can trigger re-validation by updating a suite).

## Decisions

### Decision 1: Detect placeholders using `TemplateVariableExtractor.PLACEHOLDER_PATTERN`

The existing `PLACEHOLDER_PATTERN` regex (`\$\{\{([^:|}]+)(?:\|([^:}]+))?(?::([^}]*))?\\}\\}`) is already compiled and tested. Rather than creating a new regex pattern, we add an `isPlaceholder(String)` method that checks if the FILE part value is a **full-value placeholder** (entire string matches the pattern via `Matcher.matches()`). If the value is a placeholder, skip `FileRefValidator.validate()`.

**Why not a simple `startsWith("${{")`?** The regex handles edge cases (e.g., `${{var:@ef/default.pdf}}` where the default IS a file ref) and is consistent with how other parts of the codebase detect placeholders.

**Alternative considered:** Resolve the placeholder via the binding chain and validate the resolved value. Rejected — this duplicates the runtime resolution logic and the binding's `constantValue` for `|file` typed variables is already validated separately.

### Decision 2: Extract shared binding validation into `BindingValidator` component

The binding validation logic follows the same pattern for both suite types:
1. Build `bindingByVar` lookup map
2. Build `schemaFieldNames` set
3. For each template variable: check binding existence (REQUIRED), check dataField validity (UNKNOWN), validate `|file` constantValue via `FileRefValidator`
4. For each binding: check template variable existence (orphan → ADDITIONAL)

Extract a `BindingValidator` `@Component` in `service.domain` with `@RequiredArgsConstructor`, `@Slf4j`, `@LogExecution`. Inject `FileRefValidator`. Method: `validate(List<ExtractedVariable> variables, List<InputBindingDto> bindings, List<FieldDefinitionDto> schema, UUID suiteId)` returning `List<ValidationWarningDto>`.

Both `validateDeploymentSuite()` and `validateMcpSuite()` in `SuiteValidationService` inject `BindingValidator` and call `bindingValidator.validate(...)` with their respective extracted variables. The deployment path additionally runs FILE form part validation and content-type / header checks. The `|file` constant-value validation is in the shared component — applies to both suite types.

**Why a separate `@Component`?** The binding cross-validation logic is a specialized validation concern shared between two callers (`validateDeploymentSuite()` and `validateMcpSuite()`). Per project conventions, specialized validation logic MUST be a top-level injectable class (not a private/inner method) to facilitate reuse and independent testing.

### Decision 3: MCP binding validation uses same warning codes and paths

MCP binding warnings use the same codes (`REQUIRED`, `UNKNOWN`, `ADDITIONAL`) and path (`$.inputBindings`) as deployment binding warnings. This ensures frontend can handle MCP and deployment warnings uniformly.

### Decision 4: Capture type hint in `McpRequestResolver.PLACEHOLDER_PATTERN`

Change the non-capturing group `(?:\|[^:}]+)?` to a capturing group `(?:\|([^:}]+))?` so the type hint is available during resolution. This shifts the default value from group 2 to group 3.

After resolving a full-value placeholder, check if the type hint is `"file"` and the resolved value is a `String`. If yes, call `dialFileRefResolver.resolveToDialRef(resolvedRef)` — same pattern as `ResolvedRequestService` line 262.

Inject `DialFileRefResolver` into `McpRequestResolver` — both are in `service.domain`, no layering issue.

**Scope:** Only full-value placeholders (entire string is `${{var|file}}`) are resolved. Embedded placeholders (e.g., `"prefix_${{var|file}}_suffix"`) are string-concatenated and NOT resolved as file refs — same semantics as the deployment path.

**Error handling:** If `dialFileRefResolver.resolveToDialRef()` throws (e.g., malformed reference, bucket not yet discovered), `McpRequestResolver` SHALL let the exception propagate — consistent with the fail-fast pattern for data integrity issues. The caller (`EvaluationWorker` or `TryItOutService`) handles errors at the job/request level.

### Decision 5: Add `extractFromArgumentTemplateWithWarnings()` to `TemplateVariableExtractor`

The existing `extractFromArgumentTemplate()` creates a `typeHintWarnings` list but discards it. Add a new method `extractFromArgumentTemplateWithWarnings()` that returns `ExtractionResult` (same type as `extractWithWarnings()` for deployment templates). This lets `validateMcpSuite()` surface unrecognized type hint warnings (e.g., `${{var|unknown_type}}`).

## Risks / Trade-offs

- **[Risk] Existing suites with FILE placeholder warnings won't auto-heal** → Users must update (even a no-op PUT) to trigger re-validation. This is consistent with how all other validation fixes work. No migration needed.
- **[Risk] MCP suites that were previously `valid: true` (no warnings) may become `valid: false`, blocking evaluation runs** → This is correct behavior — previously undetected configuration issues are now surfaced at save time. The suites were broken at runtime anyway. Additionally, the current `validateMcpSuite()` hardcodes `.valid(true)` even when the null `argumentTemplate` warning is present; this will be fixed to `.valid(warnings.isEmpty())`, meaning MCP suites with null `argumentTemplate` will change from `valid: true` to `valid: false`. **Operational impact:** `TestSuiteRunService.createRun()` rejects runs when `isValid == false`, so affected suites will be unable to start evaluation runs until updated. This is the correct behavior — it aligns with how the deployment path works. Mitigation: MCP suites with null `argumentTemplate` are expected to be uncommon (no useful tool invocation without an argument template), and the fix surfaces real configuration problems that would fail at runtime anyway.
- **[Risk] `|file` resolution changes MCP tool input for existing suites** → Suites that already use `${{var|file}}` in argument templates will now get resolved DIAL refs instead of raw `@ef/...` strings. If the MCP tool was already working with raw refs, this could break it. Mitigation: this is the correct behavior for DIAL-aware tools; tools that don't understand DIAL refs shouldn't have `|file` hints in their templates.
- **[Trade-off] Base64 file content (v2) deferred** → Generic MCP tools that need actual file bytes can't be served yet. This is acceptable for v1 — the `|file` hint covers DIAL-aware tools, and v2 can introduce a new hint like `|file-content` later.
