## Why

GitHub issue #218: a test suite whose request template binds a placeholder to a FILE-typed schema column **without** the `|file` type hint (e.g. `"url": "${{file}}"`) passes validation and shows as valid, but the run fails with HTTP 400. The runner converts the stored `@ef/...` reference to a DIAL ref (`files/<bucket>/...`) only when the *template text* carries `|file`; it never consults the bound column's declared type, so the raw `@ef/...` string reaches DIAL Core. The type-inference rules already treat such a variable as `effectiveType: FILE` (request-template spec, "Type inference priority"), so runtime and validation disagree with the system's own type model.

## What Changes

- **Runtime**: a full-value placeholder whose variable is bound via `dataField` to a schema field of type `FILE` is resolved to a DIAL ref exactly as if it carried `|file`, even when the hint is absent. Applies to DEPLOYMENT request templates (JSON / URL-encoded bodies, nested at any depth), MCP_TOOL argument templates, and JSONata-mode templates.
- **One shared decision point**: the "is this placeholder a FILE reference?" decision (explicit `|file` hint **or** bound-column type `FILE`) moves into a single injectable helper in `evaluation-runner-core`, replacing the three independent hint checks in `TemplateContentResolver`, `McpRequestResolver` and `JsonataSourcePreprocessor`. The resolvers receive the test-case schema (field name → type) alongside the bindings so the helper can look the bound column up.
- **Validation (inverse warning)**: `BindingValidator` emits a `TYPE` validation warning when a placeholder carries an explicit `|file` hint but is bound via `dataField` to a schema field whose type is not `FILE` (the existing spec says the declared type "wins", which silently sends a non-reference value through the file resolver).
- **Unchanged scope**: only full-value placeholders (the entire string is one placeholder) are converted; embedded/interpolated placeholders keep today's stringify behaviour. Explicit `|file` keeps working exactly as before (including defaults and `constantValue` validation). Multipart `FILE` form parts are reviewed for the same gap; any fix is limited to what the design confirms.
- Tests (unit per resolver, `BindingValidator`, one functional test) and docs/pattern updates.
- No **BREAKING** API/DTO changes. Behavioural notes: suites that previously 400'd at run time now succeed; suites with `|file` on a non-FILE column now carry a `TYPE` validation warning, and because suite validity is `warnings.isEmpty()` they become **invalid** (which blocks run creation) until the template is fixed — see design Decision 3 / Open Question 1.
- Multipart `FILE` form parts keep today's behaviour (the serializer resolves the raw `@ef/...` itself), so they are excluded from auto-resolution.

## Capabilities

### New Capabilities
<!-- None: the change tightens existing behaviour. -->

### Modified Capabilities
- `dial-file-ref`: FILE-typed value resolution to DIAL ref format is triggered by the bound schema column type as well as the `|file` hint (JSON and URL-encoded bodies, any nesting depth).
- `request-template`: runtime/preview resolution must agree with the existing `effectiveType: FILE` inference (new requirement; the existing "declared type overrides binding inference" scenario is unchanged).
- `mcp-tool-invocation`: MCP argument-template resolution converts hintless placeholders bound to FILE columns to DIAL refs (new requirement alongside the `|file` hint behaviour).
- `file-ref-validation`: new requirement for the `|file`-hint-on-non-FILE-column `TYPE` warning in the shared `BindingValidator` (DEPLOYMENT and MCP_TOOL); multipart FILE-part validation unchanged.

## Impact

- **Code (`evaluation-runner-core`)**: `TemplateContentResolver`, `McpRequestResolver`, `JsonataSourcePreprocessor`, `RequestResolver` (and callers that build the resolve context) — schema threaded through; new shared helper class (new top-level injectable `@Component`, `@LogExecution`). No new packages.
- **Code (root app)**: `BindingValidator` (new `TYPE` warning), plus whatever passes `testCaseSchema` into the runner on the run path (`eval-cli` consumes the same runner code and must keep compiling).
- **API/DB/config**: no endpoint, DTO, Flyway or configuration changes → `docs/database-schema.md` and `docs/configuration.md` unaffected. OpenAPI examples unaffected (no contract change); validation-warning wording only.
- **Security**: no new file access paths; resolution still goes through `DialFileRefResolver`, so existing prefix/ownership rules (`@ef/suites/...`, `@ef/datasets/...`, `public/...`) are unchanged. No new credential handling.
- **Risks**: (1) a non-FILE-looking string in a FILE column — mitigated because only `String` values are converted, as today; (2) `endpointRef` schema vs column type conflicts — design must state the precedence (follow the existing effective-type order); (3) the schema must be available on every resolve path (run, try-it-out, eval-cli) — design to enumerate call sites.
- **Rollout**: single release, no flag; explicit `|file` suites unaffected.
- **Test plan**: `RequestResolverTest`, `TemplateContentResolverTest`, `McpRequestResolverTest`, JSONata preprocessor tests, `BindingValidator` tests (mismatch warning, MCP + DEPLOYMENT), and one `@PostgresFunctionalTests` case running a suite with a hintless FILE-bound placeholder end to end. Docs: `docs/patterns/jsonata-evaluation-seam.md`, `docs/patterns/dial-file-storage.md`, and the affected specs.
