## Why

Service clients need to obtain the resolved template parameter values for a specific test case — the concrete `variableName → resolvedValue` per variable. Currently, clients can get variable metadata (`/template-variables`) or the fully resolved HTTP request (`/resolved-request`), but neither returns what each individual variable resolved to. This is needed for pre-populating a "try with different values" UI and debugging variable resolution.

## What Changes

- Add a `resolvedValue` (Object, nullable) field to the existing `TemplateVariableDto` returned by both `/template-variables` endpoints.
- **Test-case-level** (`/test-cases/{id}/template-variables`): fully resolves all variables using effective template, bindings, and test case data. Resolution priority: constantValue → dataField from test case data → template default → null.
- **Suite-level** (`/test-suites/{id}/template-variables`): partially resolves variables — constant-value bindings and template defaults are populated; data-field-bound variables remain null (no test case data available).

## Capabilities

### New Capabilities

_(none — this extends an existing capability)_

### Modified Capabilities

- `request-template`: Adding `resolvedValue` field to `TemplateVariableDto` on both the suite-level and test-case-level `/template-variables` endpoints. No new endpoints.

## Impact

- **API**: One new field on existing `TemplateVariableDto` response — non-breaking, additive change.
- **Service layer**: Resolution logic added to `TemplateVariableService.resolveVariables()` method. Test-case-level method additionally loads test case data.
- **No database changes**: All data needed is already stored.
- **No breaking changes**: Existing clients continue to work; the new field is simply added to the response.
