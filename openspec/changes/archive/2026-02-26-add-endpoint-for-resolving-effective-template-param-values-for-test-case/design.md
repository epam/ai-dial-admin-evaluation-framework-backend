## Context

The system already has two template-variable endpoints:
- `GET .../test-suites/{id}/template-variables` — returns `List<TemplateVariableDto>` (name, sources, hasDefault, defaultValue, binding, inferredType)
- `GET .../test-suites/{id}/test-cases/{id}/template-variables` — same structure, but uses effective template/bindings (overrides if present)

Neither returns the **resolved value** of each variable. Service clients need this to pre-populate a "try with different values" UI and to debug which value each variable resolved to. The resolved values map is the same shape accepted by `POST .../try-it-out`.

Rather than adding a new endpoint, the natural extension is adding a `resolvedValue` field to `TemplateVariableDto`. The test-case-level endpoint has full data to resolve all variables. The suite-level endpoint can partially resolve variables that have constant-value bindings or template defaults (no test case data needed for those).

The core resolution priority logic (constantValue → dataField → default → null) already exists as a private method in `ResolvedRequestService.resolveVariable()`. To avoid duplicating this logic, it should be extracted into a shared injectable component.

## Goals / Non-Goals

**Goals:**
- Add `resolvedValue` (Object, nullable) field to `TemplateVariableDto`
- Extract variable resolution logic into a shared `TemplateVariableResolver` component — single source of truth for the resolution priority
- **Test-case-level** endpoint: resolve all variables using effective template, bindings, and test case data (full resolution)
- **Suite-level** endpoint: resolve variables that have constant-value bindings or template defaults (partial resolution — no test case data available)
- Follow the same resolution priority as runtime assembly: constantValue → dataField → template default → null

**Non-Goals:**
- No new endpoints — extends existing `/template-variables` responses
- No new DTOs — one field added to existing `TemplateVariableDto`
- No database changes
- No changes to existing resolution priority rules

## Decisions

### 1. Extract `TemplateVariableResolver` component

Extract the resolution priority logic from `ResolvedRequestService.resolveVariable()` into a new `TemplateVariableResolver` `@Component` in `service.domain`. Both `ResolvedRequestService` and `TemplateVariableService` inject this component.

```java
@Component
public class TemplateVariableResolver {
    /**
     * Resolution priority:
     * 1. Binding with constantValue → always wins
     * 2. Binding with dataField → use data[dataField] if present
     * 3. Template default → fallback
     * 4. No binding + no default → null
     *
     * @param data test case data map — nullable; when null (suite-level),
     *             data-field bindings resolve to null (fall through to default).
     *             Implementation MUST null-guard: treat null data as empty map
     *             for the purpose of data.get() calls.
     */
    public Object resolveVariable(String varName, String defaultValue,
                                  InputBindingDto binding,
                                  Map<String, Object> data,
                                  List<ValidationWarningDto> warnings) { ... }
}
```

**Rationale**: Per project conventions, shared logic should be in injectable components (not private/inner methods). This avoids duplicating the 4-step priority chain and ensures both services stay in sync. It also makes the resolution logic independently testable.

**Alternative considered**: Make `ResolvedRequestService.resolveVariable()` package-private — rejected because it couples the two services and the method signature includes warning accumulation logic that is specific to `ResolvedRequestService`'s internal flow. A dedicated component is cleaner.

### 2. Add `resolvedValue` field to `TemplateVariableDto`

```
TemplateVariableDto (existing fields preserved):
  + resolvedValue: Object  // resolved typed value, or null if unresolvable
```

**Rationale**: Minimal, non-breaking change. Existing clients already consume this DTO; the new field is additive. Clients get metadata + resolved value in one call per variable.

### 3. Update `TemplateVariableService` to populate `resolvedValue`

`resolveVariables()` gains an additional `Map<String, Object> data` parameter (nullable). For each extracted variable, it calls `TemplateVariableResolver.resolveVariable()` and sets `resolvedValue` on the DTO.

- `getTemplateVariables()` (suite-level) passes `null` as data
- `getTestCaseTemplateVariables()` (test-case-level) loads `testCase.data` and passes it

### 4. Refactor `ResolvedRequestService` to use `TemplateVariableResolver`

Replace the private `resolveVariable()` method in `ResolvedRequestService` with a call to the injected `TemplateVariableResolver`. The existing `resolve()` and `resolveObject()` methods call through to the shared component instead of the private method.

### 5. Suite-level partial resolution

At suite level (no `data` parameter), `resolvedValue` is populated for:
- Variables with constant-value bindings → the constant value
- Variables with template defaults and no data-field binding → the default string
- Variables with data-field bindings → `null` (no test case data to resolve against)

### 6. No new controller changes

Both endpoints already call `TemplateVariableService` and return `List<TemplateVariableDto>`. The `resolvedValue` field is automatically serialized. No controller changes needed beyond updating OpenAPI example files.

## Risks / Trade-offs

**[Minor] `resolvedValue` is null for data-field-bound variables at suite level**: Clients must understand that suite-level resolution is partial. The `binding` field already tells the client whether it's a constant or data-field binding, so the behavior is predictable.

**[None] Non-breaking API change**: Adding a new nullable field to an existing response DTO is backward-compatible.

**[None] Refactor risk**: Extracting `resolveVariable()` into a shared component is a pure refactor — behavior is unchanged, just the call site moves.
