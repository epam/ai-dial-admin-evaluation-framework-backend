## Why

Commit `35965cc6` ("Introduce Dataset as a first-class, reusable entity decoupled from Test Suites") removed the `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables` endpoint and its `TemplateVariableService.getTestCaseTemplateVariables(...)` method. This removal was unnecessary collateral of the dataset refactor: the per-test-case template-variable preview is still needed by the UI to show variables resolved against a specific test case's data. The sibling preview endpoint at the *same route prefix* — `GET /test-suites/{testSuiteId}/test-cases/{testCaseId}/resolved-request` (`ResolvedRequestService`) — was **kept and adapted** to the dataset paradigm, leaving an inconsistent gap. We restore the template-variables endpoint and adapt it the same way.

## What Changes

- **Restore** `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables` on `TemplateVariableController` (suite-type aware: HTTP and MCP_TOOL suites).
- **Restore** `TemplateVariableService.getTestCaseTemplateVariables(testSuiteId, testCaseId)`, adapted to the dataset paradigm rather than reverted verbatim:
  - The test case is fetched **through the dataset domain's service** — `testCaseService.getById(suite.getDatasetId(), testCaseId, false)` — honoring the "cross-domain access goes through the owning domain's service" rule (AGENTS.md / best-practices spec). This is dataset-scoped (replacing the removed suite-scoped `findByIdAndTestSuiteId`) and returns `404` for a missing test case via `getById`.
  - `TestCaseResponseDto.data` is already a `Map<String, Object>`, so it is passed straight into resolution — no manual JSON deserialization and no extra injected serializer.
  - The test-case schema for type inference is sourced from the suite's dataset via the existing `DatasetSchemaProvider.getSchema(suite.getDatasetId())` (already used by the suite-level method), not from the removed `suite.getTestCaseSchema()`.
  - The removed override branches (`requestTemplateOverride` / `inputBindingsOverride`) are **dropped** — those fields no longer exist on `TestCase`. The suite's `requestTemplate` / `argumentTemplate` / `inputBindings` are the single source of truth.
  - The only behavioral difference from the suite-level endpoint: the test case's `data` map is passed into resolution, so each variable's `resolvedValue` reflects actual test-case data instead of falling through to template defaults.
  - Returns `404` when the suite (or the test case within the suite's dataset) is not found.
- **Update** the `request-template` spec, whose requirements for this endpoint are now stale: they still reference `requestTemplateOverride` / `inputBindingsOverride` and a suite-scoped test-case lookup. Rewrite them to the dataset paradigm (no overrides; dataset-scoped lookup; schema from dataset; `resolvedValue` from test-case `data`).
- **Update** OpenAPI annotations/examples for the restored operation.

No DB schema changes. No configuration changes. No breaking changes (this restores a previously available endpoint).

## Capabilities

### New Capabilities

_None._ This restores an endpoint already owned by an existing capability spec.

### Modified Capabilities

- `request-template`: The requirement "Template variables for a specific test case (effective template)" and its MCP_TOOL scenario currently describe the pre-dataset behavior (per-test-case overrides, suite-owned `testCaseSchema`, suite-scoped test-case lookup). Update them to the dataset paradigm: the endpoint resolves the **suite** template/bindings against the **test case's data**, sources the test-case schema from the suite's dataset, looks the test case up dataset-scoped via `suite.datasetId`, and no longer references the removed override fields.

## Impact

- **Code (restored / modified):**
  - `web/controller/TemplateVariableController.java` — re-add the `{testCaseId}/template-variables` mapping + OpenAPI annotations.
  - `service/domain/TemplateVariableService.java` — re-add `getTestCaseTemplateVariables`; inject `TestCaseService` (dataset domain) to fetch the test case. The existing private `resolveVariables` / `resolveMcpVariables` already accept a nullable `data` map, so they are reused as-is with the test case's data.
- **APIs:** Adds back one GET endpoint; HTTP contract identical to the pre-removal version except `resolvedValue` is now sourced from dataset-owned test-case data. OpenAPI/Swagger updated.
- **Tests:** Add functional coverage (`TemplateVariableFunctionalTests`) for the restored endpoint — data-field bindings resolve to actual test-case values, constant-value bindings resolve to constants, type inference uses the dataset schema, MCP_TOOL path, and 404 for missing suite / missing test case. Add unit coverage in `TemplateVariableServiceTest` for the dataset-scoped lookup.
- **Specs/docs:** `openspec/specs/request-template/spec.md` requirements updated; `openspec/specs/README.md` summary unaffected (no folder/status change).
- **Cross-domain compliance:** `TemplateVariableService` fetches the test case through `TestCaseService` (the owning dataset domain's service), satisfying the "own-domain repository only" rule. The sibling `ResolvedRequestService` currently injects `TestCaseRepository` directly — a pre-existing deviation from the rule that this change does **not** propagate; aligning it is noted as out-of-scope follow-up.
