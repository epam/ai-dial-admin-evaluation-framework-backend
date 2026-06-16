## Context

Commit `35965cc6` ("Introduce Dataset as a first-class, reusable entity decoupled from Test Suites") removed `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables` and `TemplateVariableService.getTestCaseTemplateVariables(...)`. The removal was collateral: the per-test-case template-variable preview is still useful (the UI shows how each variable resolves against a specific test case's data), and the sibling endpoint at the same route prefix — `GET .../test-cases/{testCaseId}/resolved-request` (`ResolvedRequestService`) — was *kept and adapted* to the dataset paradigm. Restoring template-variables closes that gap.

The dataset refactor changed the surrounding facts the restored method must respect:

- `TestCase` now carries `datasetId` (not `testSuiteId`) and **lost** its `requestTemplateOverride` / `inputBindingsOverride` fields. There are no per-test-case template/binding overrides anymore.
- The test-case schema lives on the `Dataset` (`dataset.testCaseSchema`), surfaced via `DatasetSchemaProvider.getSchema(datasetId)` — the suite no longer has `testCaseSchema`.
- A `TestSuite` references its dataset via `datasetId`; `requestTemplate` / `argumentTemplate` / `inputBindings` / `endpointRef` remain suite-owned.
- Test cases are fetched dataset-scoped via `TestCaseRepository.findByIdAndDatasetId` (the old `findByIdAndTestSuiteId` is gone).

The existing `TemplateVariableService` already has private `resolveVariables(template, bindings, schema, endpoint, data)` and `resolveMcpVariables(argumentTemplate, bindings, schema, data)` methods that accept a **nullable** `data` map. The suite-level entry point calls them with `data = null`. The restored test-case entry point reuses these unchanged, passing the test case's actual `data` — so resolution logic is not duplicated or modified.

## Goals / Non-Goals

**Goals:**
- Restore `GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables` with the same response contract (`List<TemplateVariableDto>`) as before removal.
- Support both HTTP (`requestTemplate`) and `MCP_TOOL` (`argumentTemplate`) suites.
- Resolve `resolvedValue` for each variable against the test case's `data` (the one behavioral difference from the suite-level endpoint).
- Source type-inference schema from the suite's dataset via `DatasetSchemaProvider`.
- Honor the cross-domain rule: fetch the test case through `TestCaseService` (dataset domain), not a foreign repository.
- Return `404` for a missing suite or a missing test case (within the suite's dataset).
- Update the stale `request-template` spec requirements that describe this endpoint; restore OpenAPI annotations; add unit + functional test coverage.

**Non-Goals:**
- **No reintroduction of per-test-case overrides.** `requestTemplateOverride` / `inputBindingsOverride` stay removed; the suite is the single source of truth for template and bindings.
- **Not reconciling the rest of the `request-template` spec's override staleness.** The spec still contains two whole requirements — "Per-test-case request template override" and "Per-test-case input bindings override" — plus override scenarios under the suite-level binding requirements, all describing fields commit `35965cc6` deleted. These describe the *TestCase entity model* (a different feature surface than this read-only preview endpoint) and are pre-existing debt from that commit not updating `request-template`. This change updates only the two requirements that describe the restored endpoint, and does not assert anything that newly contradicts the override requirements. Fully reconciling `request-template` (and any sibling specs) with the override removal is recommended as a dedicated follow-up cleanup change.
- **Not refactoring `ResolvedRequestService`.** It injects `TestCaseRepository` directly — a pre-existing deviation from the "own-domain repository only" rule. This change does not propagate that pattern, but also does not fix it here (out of scope; follow-up).
- No DB schema, migration, or configuration changes.

## Decisions

### 1. Fetch the test case through `TestCaseService`, not `TestCaseRepository`
`TemplateVariableService` will inject `TestCaseService` and call `testCaseService.getById(suite.getDatasetId(), testCaseId, /* includeWarnings */ false)`. Rationale:
- **Cross-domain rule (AGENTS.md / best-practices spec):** test cases belong to the dataset domain; a suite/template-domain service must reach them through the owning domain's service.
- **Simpler:** `TestCaseResponseDto.data` is already a `Map<String, Object>` — exactly the type the private resolvers take — so no manual JSON deserialization and no extra injected serializer (the repo path would have needed `ValidationWarningsSerializer.deserializeMap(tc.getData())`).
- **404 + dataset-scoping when the test case is simply absent (datasetId non-null):** `getById` does `findByIdAndDatasetId(id, datasetId)` and throws `EntityNotFoundException` when no row matches. (The separate *null*-datasetId case is handled by an explicit guard — see Decision 3.)
- **No dependency cycle:** `TestCaseService` does not depend (transitively) on `TemplateVariableService`, which is used only by `TemplateVariableController`.

Rejected alternative — inject `TestCaseRepository` to mirror `ResolvedRequestService`: violates the documented rule and is more code. Consistency with a pre-existing rule deviation is not a good enough reason to repeat it.

### 2. Reuse the existing private resolvers unchanged; pass test-case `data`
The restored `getTestCaseTemplateVariables(testSuiteId, testCaseId)` mirrors the structure of `getTemplateVariables(testSuiteId)` exactly, with two differences: it obtains `data` from the test case and passes that `data` (instead of `null`) into `resolveVariables(...)` / `resolveMcpVariables(...)`. No change to the resolver methods or to type-inference priority (declared > endpointRef schema > dataset schema via binding's dataField > STRING).

Sketch:
```java
TestSuite suite = testSuiteRepository.findById(testSuiteId)
        .orElseThrow(() -> new EntityNotFoundException("TestSuite not found: " + testSuiteId));
// Unbound suite owns no test cases — return 404. (Also avoids the NPE in Decision 3.)
if (suite.getDatasetId() == null) {
    throw new EntityNotFoundException("TestCase not found: " + testCaseId);
}
TestCaseResponseDto testCase = testCaseService.getById(suite.getDatasetId(), testCaseId, false);
Map<String, Object> data = testCase.getData();
List<FieldDefinitionDto> testCaseSchema = datasetSchemaProvider.getSchema(suite.getDatasetId());
// MCP_TOOL → resolveMcpVariables(argumentTemplate, bindings, testCaseSchema, data)
// else     → resolveVariables(template, bindings, testCaseSchema, endpoint, data)
```

### 3. Unbound suite (`datasetId == null`) → `404` via an explicit guard
An unbound suite has no dataset and therefore no test cases, so the endpoint MUST return `404`. This requires an **explicit guard**: `getTestCaseTemplateVariables` checks `suite.getDatasetId() == null` and throws `EntityNotFoundException("TestCase not found: " + testCaseId)` *before* calling `testCaseService.getById(...)`. Delegating to `getById` with a null datasetId would **not** yield a clean 404 — `PostgresTestCaseRepository.findByIdAndDatasetId` calls `datasetId.toString()` unconditionally (`...DATASET_ID.eq(datasetId.toString())`), throwing `NullPointerException` → HTTP 500 on a null datasetId, and `getById` has no null guard. (The sibling `ResolvedRequestService.resolveRequest` has the same latent NPE, so it is *not* a precedent for "404 for free".) Returning 404 is the correct consequence of the dataset paradigm (pre-refactor, test cases were suite-scoped, so an unbound suite could own test cases; that is no longer possible), not a regression. After the guard `datasetId` is non-null, so the schema lookup needs no further null check.

### 4. Constructor arity change — update existing unit-test call sites
Adding `TestCaseService` to the `@RequiredArgsConstructor`-generated constructor changes its arity. `TemplateVariableServiceTest` constructs the service directly at two sites (`new TemplateVariableService(...)`). Both must be updated to pass the new argument (a mock `TestCaseService`, or `null` where the test only exercises `resolveVariables`). This is a mechanical compile-fix, tracked in tasks.

### 5. Spec delta scoped to the two endpoint requirements
Modify exactly:
- "Template variables API for TestCase (effective template)" — rewrite body/scenarios to the dataset paradigm (suite template/bindings resolved against test-case `data`; dataset-scoped lookup; `resolvedValue` from data; `404`; empty list when the suite has no template). The `resolvedValue`-from-data scenarios already in the spec remain accurate and are kept; the override-specific scenarios are replaced. The requirement header is preserved verbatim so the MODIFIED block matches the existing requirement.
- "Template variables for MCP suites" — drop the `inputBindingsOverride` references and the "MCP test-case with `inputBindingsOverride`" scenario; keep the test-case endpoint behavior, direct-name-lookup, and MCP type-inference scenarios.

## Risks / Trade-offs

- **Spec remains partially inconsistent on overrides.** After this change, `request-template` still contains the two stale "Per-test-case … override" requirements. Mitigation: explicitly scoped out in Non-Goals, with a recommended follow-up; the restored-endpoint requirements themselves are accurate and self-consistent, and this change introduces no new contradiction.
- **Title "(effective template)" retained.** With overrides gone, "effective" now just means "the suite template/bindings applied to this test case's data." Kept verbatim for safe MODIFIED matching; the requirement body clarifies the meaning. A clean rename is deferred to the follow-up cleanup rather than risking a fragile rename+modify in one delta.
- **Coupling to `TestCaseService`.** Introduces a new compile-time dependency from the template/preview surface to the dataset domain service. Low risk: it is the prescribed cross-domain path, mirrors how other suite-scoped flows reach dataset data, and adds no cycle.
- **Behavior parity.** The endpoint's contract is unchanged from before removal except `resolvedValue` now derives from dataset-owned test-case data. Existing clients that depended on the endpoint regain identical behavior; type inference and the `TemplateVariableDto` shape are untouched.
