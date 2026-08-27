## Context

See `proposal.md` — *Why*. The constraints that shape the approach:

- `SuiteValidationService.validateMcpSuite` is the single producer of `isValid` for MCP suites; the
  clone path (`TestSuiteCloneService`) and the dataset-rooted revalidation path
  (`RevalidationService.runPhase2`) both re-enter it, so one insertion point covers all three surfaces.
- Suite validity is binary and warning-derived: `valid = warnings.isEmpty()`. There is no severity
  split, so any new warning flips the suite invalid. That is the desired outcome here.
- `argumentTemplate.arguments` values are a mix of constants and `${{variable}}` /
  `${{variable|type:default}}` placeholders. Placeholder syntax and type hints are owned by
  `TemplateVariableExtractor`; binding coverage for those placeholders is owned by `BindingValidator`.
  The new check must not re-do either job.
- `MetricDefinitionValidationService` already implements the same shape (required-property coverage
  against a JSON Schema) using private helpers `extractSchemaPropertyNames` /
  `extractRequiredPropertyNames`.

## Goals / Non-Goals

**Goals:**
- One injectable validator owning argument-vs-tool-schema coverage, unit-testable without Spring.
- Cover both shapes the UI can persist for a filled-in argument: a constant in
  `argumentTemplate.arguments`, and a `${{var}}` placeholder whose value arrives through an
  `inputBindings` `constantValue`.
- Leave every existing MCP warning byte-identical, so existing tests and FE warning handling are
  unaffected.

**Non-Goals:**
- Full JSON Schema validation of arguments (types, enums, formats, `minLength`). The template holds
  placeholders whose runtime values are unknown at save time, so type checking here would be wrong.
- Nested-object `required`. Top level only.
- Run-time enforcement. `McpToolInvoker`'s no-op schema validator and `McpRequestResolver`'s
  empty-string substitution stay as they are; this change moves the failure earlier, to save time,
  not later.
- Turning any of this into a hard 400. Suite configuration errors are soft-validated by design.

## Decisions

### D1 — A dedicated `McpArgumentValidator` component, not inline logic in `SuiteValidationService`

`service.domain.McpArgumentValidator` (`@Component`, `@LogExecution`):

```
List<ValidationWarningDto> validate(Map<String, Object> inputSchema,
                                    Map<String, Object> arguments,
                                    List<InputBindingDto> bindings)
```

`SuiteValidationService.validateMcpSuite` calls it inside the existing `argumentTemplate != null`
branch, after `bindingValidator.validate(...)`, passing `dto.getToolRef().getInputSchema()`.

*Why:* the project's convention is specialized validators as top-level injectable classes rather than
private methods (AGENTS.md, `config.yaml` layering principle), and it keeps the schema-coverage
concern independently unit-testable. *Alternative rejected:* extending `BindingValidator` — its
contract is template-variables-to-dataset-schema and is shared with DEPLOYMENT suites, which have no
tool schema.

### D2 — Effective-value resolution instead of raw-value inspection

For each required property the validator resolves an *effective value* before deciding:

| Argument value | Resolution |
|---|---|
| non-string constant (number, boolean, object, array) | satisfied |
| string constant | unsatisfied iff null / blank |
| `${{var}}` with a binding carrying `dataField` | satisfied |
| `${{var}}` with a binding carrying only `constantValue` | apply the constant rule to that value |
| `${{var}}` with no binding, declaring no default | **no warning here** — `BindingValidator` already emits `REQUIRED` for the unbound variable |
| `${{var:main}}` with no binding | satisfied — the default supplies the value |
| `${{var:}}` with no binding (blank default) | unsatisfied — `BindingValidator` stays silent on any default, so this one is ours to catch |
| key absent from `arguments`, or value `null` | unsatisfied |

*Why:* the FE can legitimately persist a filled argument either way, and a validator that only looked
at `arguments` would miss the blank-constant-binding case while one that only looked at bindings would
miss the plain-constant case. The last row is what prevents double-reporting a single mistake as two
warnings.

*Alternative rejected:* reusing `McpRequestResolver.resolve(...)` to compute effective values. It
belongs to `evaluation-runner-core`, needs `testCaseData` that does not exist at save time, and
substitutes `""` for unresolved placeholders — the exact blindness this change is fixing.

### D3 — Hand-read `properties` / `required`; do not use `SchemaValidationService`

`SchemaValidationService` (networknt) validates a data instance against a schema. The argument template
is not a data instance — `{"limit": "${{max|number}}"}` would fail `type: integer` on every suite.
Reading `properties` and `required` directly is both correct and what the metric-definition validator
already does.

To avoid a second copy of that reading, extract `service.domain.JsonSchemaPropertyExtractor`
(`@Component`) exposing `Set<String> propertyNames(...)` and `Set<String> requiredNames(...)`, and have
`MetricDefinitionValidationService` delegate to it. The extractor takes the parsed `Map` form (what
`ToolReferenceDto.inputSchema` already is) and a `String` overload for the metric side's JSONB text, so
neither caller changes shape.

*Trade-off:* touching `MetricDefinitionValidationService` widens the blast radius slightly. It is
guarded by keeping `MetricDefinitionValidationServiceTest` green and by doing the extraction as a
refactor step **after** the new behavior is green, never before.

### D3a — No unknown-argument check

An argument the stored `inputSchema` does not declare is **not** warned about. JSON Schema's default is
`additionalProperties: true`, `toolRef.inputSchema` is a client-supplied snapshot rather than a live
read, and any warning flips `isValid = false`, which `TestSuiteRunService` turns into a hard 409 on run
creation. Flagging extra arguments would let a stale snapshot make a working suite un-runnable — a
regression well outside GH #69, which is about *required* arguments only.

### D4 — Graceful degradation on absent or malformed schema

`toolRef == null`, `inputSchema == null`, or `properties` absent/empty ⇒ no warnings. With D3a in
place this needs no early return: an empty `declaredProperties` set makes the per-required-name
`contains` guard skip everything, so the degradation is the same code path as "a `required` entry that
`properties` never declares". This
mirrors `MetricDefinitionValidationService`'s "only when schema has properties" guard, and it is what
keeps suites created before tool-schema capture (and every existing `McpBindingValidation` test
fixture, which builds suites with no `toolRef`) from flipping invalid for a reason the author cannot
act on.

### D5 — Warning shape

`code = REQUIRED`, `fieldName` = the argument name, `path =
"$.argumentTemplate.arguments"`. The path matches what `McpRequestResolver` already reports at run time
for the same class of problem, so the FE can highlight the same form control from either source.

## Risks / Trade-offs

- **Existing MCP suites flip to `isValid = false` on their next write.** → That is the fix, but it is
  a visible state change. It only materializes on suite update, clone, or dataset revalidation — no
  bulk backfill runs, so nobody's dashboard changes without an author action. D4 bounds it to suites
  that actually carry a tool schema.
- **A required argument whose value is a placeholder resolving to empty *test-case data* still passes
  save-time validation.** → Out of scope by construction: test-case data is per-row and not known at
  save time. `McpRequestResolver` already emits a run-time `REQUIRED` warning for that case.
- **Double-warning on an unbound placeholder** (one from `BindingValidator`, one from the new check).
  → Prevented by the last row of the D2 table; covered by a dedicated test.
- **Refactoring `MetricDefinitionValidationService` regresses metric validation.** → The extraction is
  a pure move, done only after the new tests are green, with
  `MetricDefinitionValidationServiceTest` run before and after.

## Migration Plan

No schema change, no config change, no data migration. The behavior takes effect on deploy for every
subsequent suite write. Rollback is a plain revert — validity is recomputed on the next write either
way, so no stored state needs repair.
