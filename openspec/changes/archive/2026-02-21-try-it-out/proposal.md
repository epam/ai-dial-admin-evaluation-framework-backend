## Why

Users need to test their test suite configuration against a real DIAL Core deployment before running full evaluation suites. Currently, the only way to verify that a request template, input bindings, and deployment configuration work together is to trigger a full test suite run (which is mock-only today). A "Try It Out" feature lets users send a single resolved request to the actual deployment and see the real response, enabling fast iteration on configuration and (later) auto-detection of response JSON schema.

## What Changes

- Add a **test-case-level try-it-out endpoint** (`POST /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/try-it-out`) that resolves the effective template using test case data and input bindings, then sends the request to the DIAL Core deployment and proxies the response (Option A).
- Add a **suite-level try-it-out endpoint** (`POST /api/v1/test-suites/{testSuiteId}/try-it-out`) that accepts a `variables` map (all template variables with direct constant values), resolves the template, and sends the request to DIAL Core (Option C — for suites in early configuration stage, before test cases exist).
- Add a **DIAL Core deployment invoker** — a new client component (`DialCoreDeploymentInvoker`) separate from the existing metadata-focused `DialCoreClient`, with its own `RestClient` bean, configurable timeout (default 120s), and no retry logic.
- Reuse existing `ResolvedRequestService` methods: the test-case path delegates to `resolveRequest(testSuiteId, testCaseId)` (already public, `@Transactional(readOnly=true)` — transaction scoped to that call only); the variables path calls the package-private `resolve()` directly (same package, no visibility change needed).
- Add **URL prefix routing logic**: resolved URL matching a known OpenAI-standard path (`/chat/completions`, `/embeddings`) → `/openai/deployments/{id}{resolvedUrl}`; all other paths → `/v1/deployments/{id}/route{resolvedUrl}` (custom application routes).
- Add **try-it-out-specific configuration** (`dial.components.core.try-out.*`) for read timeout.

## Capabilities

### New Capabilities
- `try-it-out`: Endpoints for sending a single resolved request to a DIAL Core deployment and proxying the response. Covers request DTOs, response DTOs, URL construction, timeout configuration, error proxying, and validation rules.

### Modified Capabilities
- `dial-core-client`: Add a new `DialCoreDeploymentInvoker` component for invoking deployment endpoints (POST/GET/etc.) with a separate RestClient and timeout configuration. Existing `DialCoreClient` (metadata) is unchanged.

## Impact

- **New endpoints**: Two REST endpoints under test-suites path
- **New client component**: `DialCoreDeploymentInvoker` with its own `RestClient` bean
- **New configuration**: `dial.components.core.try-out.read-timeout-ms` in `application.yml`
- **Reused service**: `ResolvedRequestService` — test-case path reuses `resolveRequest()`, variables path reuses package-private `resolve()` (same package, no visibility change)
- **Affected packages**: `.client.dialcore`, `.service.domain`, `.web.controller`, `.configuration.properties.dial`
- **No database changes**: No new tables or migrations required
- **No breaking changes**: All changes are additive
