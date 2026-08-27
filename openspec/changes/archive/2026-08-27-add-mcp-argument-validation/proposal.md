## Why

GH #69: an `MCP_TOOL` test suite whose required tool argument is saved empty (e.g. `repoName` cleared
in the Method tab) is persisted with `isValid = true` and no validation warning. The suite then fails
only at run time, inside the tool, with an empty argument — the exact class of misconfiguration
suite-level soft validation exists to surface before a run is created.

The root cause is a gap, not a defect: `SuiteValidationService.validateMcpSuite` never reads
`toolRef.inputSchema`. MCP suite validation today covers only *template variables ↔ `inputBindings` ↔
the dataset's `testCaseSchema`* (via `BindingValidator`). An argument holding a constant empty string —
or missing from `argumentTemplate.arguments` altogether — extracts no template variable, so it produces
no warning at all. `toolRef.inputSchema` is persisted (JSONB `tool_ref`) but never validated against.
Nothing downstream compensates: `McpToolInvoker` installs a no-op schema validator, and
`McpRequestResolver` substitutes `""` for an unresolved placeholder.

## What Changes

- Add a schema-driven **argument coverage check** for `MCP_TOOL` suites: every property listed in
  `toolRef.inputSchema.required` must be satisfied by `argumentTemplate.arguments`.
- An argument is **unsatisfied** when it is absent, `null`, a blank/whitespace-only constant, or a
  `${{var}}` placeholder whose `inputBindings` entry supplies a null/blank `constantValue`, or an
  unbound `${{var:}}` placeholder whose inline default is blank. Each
  unsatisfied required argument yields a `REQUIRED` validation warning at
  `$.argumentTemplate.arguments`, flipping the suite to `isValid = false`.
- An argument name absent from `toolRef.inputSchema.properties` is **not** flagged: JSON Schema allows
  additional properties by default and `toolRef.inputSchema` is a client-supplied snapshot, so a stale
  snapshot must never make a working suite invalid (and, via the run guard, un-runnable).
- Introduce `service.domain.McpArgumentValidator` (`@Component`), injected into
  `SuiteValidationService` and invoked from `validateMcpSuite` only.
- Extract the JSON-Schema `properties` / `required` reading currently private to
  `MetricDefinitionValidationService` into a shared `service.domain.JsonSchemaPropertyExtractor`
  component, consumed by both validators.
- No **BREAKING** API change: existing MCP suites already carrying an empty required argument will
  simply report `isValid = false` on their next read-through-write (suite PUT, clone, or dataset-rooted
  revalidation). Existing HTTP status codes and payload shapes are unchanged — this is soft validation,
  never a 400.

Status: all of the above is **Planned** in this change; the surrounding MCP suite validation it plugs
into is **Implemented**.

## Capabilities

### New Capabilities

None. The behavior belongs to the existing suite-validation capability.

### Modified Capabilities

- `test-suites`: adds a requirement that MCP suite soft validation checks `argumentTemplate.arguments`
  against `toolRef.inputSchema` (required-argument coverage plus unknown-argument detection), alongside
  the existing `argumentTemplate: null` warning. The current spec asserts `toolRef.inputSchema` is
  "used for argument form generation and validation" but specifies no validation scenario.

## Impact

**Code**
- `src/main/java/com/epam/aidial/evaluation/service/domain/McpArgumentValidator.java` — new.
- `src/main/java/com/epam/aidial/evaluation/service/domain/JsonSchemaPropertyExtractor.java` — new
  (extracted from `MetricDefinitionValidationService`).
- `SuiteValidationService.validateMcpSuite` — new constructor dependency + call.
- `MetricDefinitionValidationService` — private extraction helpers replaced by the shared component;
  behavior unchanged.

**Behavior surfaces reached for free** — `TestSuiteService.create/update`, `TestSuiteCloneService`, and
`RevalidationService.runPhase2` all recompute validity through `SuiteValidationService`, so all three
pick the check up without change.

**Not affected**
- No DB schema change, so no Flyway migration and no `docs/database-schema.md` update.
- No configuration property, so no `docs/configuration.md` update.
- No new dependency: `SchemaValidationService` (networknt) is deliberately *not* used here, because the
  argument template legitimately contains `${{var}}` placeholders that would fail type checks against
  the tool schema.
- No `config.yaml` update: the change follows the existing validator-component convention rather than
  altering it. No `specs/README.md` update: no spec folder added and the `test-suites` one-line summary
  stays accurate.

**Risk** — a suite that was silently `isValid = true` may flip to `false` on the next write. That is
the intended fix, but it is a visible state change for existing MCP suites, so the graceful-degradation
rules matter: a suite with no `toolRef`, a null `inputSchema`, or a schema with no `properties` must
produce no new warnings.

**Test plan** — TDD throughout: unit tests for `McpArgumentValidator`, a new nested class in
`SuiteValidationServiceTest` exercising the real collaborator wiring, and a functional test in
`McpTestSuiteFunctionalTests` proving the end-to-end HTTP path (which is also the wiring proof for the
new constructor-injected bean).
