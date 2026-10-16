## MODIFIED Requirements

### Requirement: URL construction for DIAL Core invocation
The system SHALL construct the full DIAL Core URL by combining the base URL, a deployment prefix (DEPLOYMENT suites only), and the resolved URL template. `DialCoreUrlBuilder` SHALL maintain a `Set<String>` of known OpenAI-standard paths to determine the routing prefix.

Known standard paths (V1): `/chat/completions`, `/embeddings`

Status: **Implemented**

#### Scenario: Standard chat completions endpoint
- **WHEN** the resolved URL from the template equals `/chat/completions`
- **THEN** the full URL SHALL be `{coreBaseUrl}/openai/deployments/{deploymentRef.id}/chat/completions`

#### Scenario: Standard embeddings endpoint
- **WHEN** the resolved URL from the template equals `/embeddings`
- **THEN** the full URL SHALL be `{coreBaseUrl}/openai/deployments/{deploymentRef.id}/embeddings`

#### Scenario: Custom application route
- **WHEN** the resolved URL from the template does NOT match any known OpenAI-standard path (e.g., `/my-custom-endpoint`)
- **THEN** the full URL SHALL be `{coreBaseUrl}/v1/deployments/{deploymentRef.id}/route{resolvedUrl}`

#### Scenario: HTTP method from endpoint reference
- **WHEN** constructing the DIAL Core request
- **THEN** the system SHALL use `endpointRef.method` as the HTTP method

#### Scenario: Global route (ROUTE suite)
- **WHEN** the suite is a ROUTE suite and the resolved URL from the template is `/validate/items`
- **THEN** the full URL SHALL be `{coreBaseUrl}/validate/items`, with no deployment prefix
- **AND** the root-relative path rules of the engine requirement `ROUTE suite invocation path validation` SHALL apply

#### Scenario: Invalid global route path is rejected
- **WHEN** the suite is a ROUTE suite and the resolved URL violates the root-relative path rules (e.g. `validate/items`)
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and the message defined by the engine requirement `ROUTE suite invocation path validation` (e.g. `ROUTE urlTemplate must resolve to a root-relative DIAL Core path`)
- **AND** DIAL Core SHALL NOT be invoked for that request

---

### Requirement: Validation before invocation
The system SHALL validate the test suite configuration before invoking the deployment, the global route or the MCP tool. The request SHALL be rejected if preconditions are not met. Validation SHALL be type-aware: DEPLOYMENT suites validate HTTP preconditions including `deploymentRef`, ROUTE suites validate the same HTTP preconditions with `routesRef` in place of `deploymentRef`, and MCP_TOOL suites validate MCP preconditions.

Status: **Implemented**

#### Scenario: Missing deployment reference (DEPLOYMENT suite)
- **WHEN** a DEPLOYMENT suite has `deploymentRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that deployment reference is required

#### Scenario: Missing route reference (ROUTE suite)
- **WHEN** a ROUTE suite has no stored `routesRef` (null or blank)
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message `Route reference is required for try-it-out`
- **AND** DIAL Core SHALL NOT be invoked

#### Scenario: ROUTE suite does not require deploymentRef
- **WHEN** a ROUTE suite has a `routesRef` and no `deploymentRef`
- **THEN** the "Deployment reference is required" check SHALL NOT be applied and validation SHALL continue with the endpoint reference and request template checks

#### Scenario: Missing request template (DEPLOYMENT suite)
- **WHEN** a DEPLOYMENT suite has `requestTemplate` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that request template is required

#### Scenario: Missing request template (ROUTE suite)
- **WHEN** a ROUTE suite has `requestTemplate` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that request template is required

#### Scenario: Missing endpoint reference (DEPLOYMENT suite)
- **WHEN** a DEPLOYMENT suite has `endpointRef` as null or `endpointRef.method` is null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that endpoint reference with HTTP method is required

#### Scenario: Missing endpoint reference (ROUTE suite)
- **WHEN** a ROUTE suite has `endpointRef` as null or `endpointRef.method` is null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that endpoint reference with HTTP method is required

#### Scenario: Missing MCP deployment reference (MCP_TOOL suite)
- **WHEN** an MCP_TOOL suite has `mcpDeploymentRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that MCP deployment reference is required

#### Scenario: Missing tool reference (MCP_TOOL suite)
- **WHEN** an MCP_TOOL suite has `toolRef` as null
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR` and message indicating that tool reference is required

#### Scenario: Null resolved URL (DEPLOYMENT suite only)
- **WHEN** the resolved URL is null after template resolution
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

> **Note:** This scenario applies only to DEPLOYMENT suites. MCP_TOOL suites do not resolve URLs.

#### Scenario: Null resolved URL (ROUTE suite)
- **WHEN** the resolved URL of a ROUTE suite is null after template resolution
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Unresolvable required template variables
- **WHEN** template/argument resolution produces warnings with `REQUIRED` code
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`
- **AND** the error message SHALL list the unresolved variable names
- **AND** the `resolvedRequest` SHALL be included in the error response details field

> **Note:** This scenario applies to all suite types: DEPLOYMENT and ROUTE suites check URL template variables, MCP_TOOL suites check argument template variables.

#### Scenario: JSON request body fails JSONata evaluation (DEPLOYMENT suite only)
- **WHEN** the resolved request produces a warning with `REQUEST_BODY_EVALUATION_ERROR` code (the JSON body's JSONata evaluation failed and the resolved body content was downgraded to `null`)
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`, the same way as an unresolvable `REQUIRED` warning
- **AND** the deployment SHALL NOT be invoked

> **Note:** This is distinct from the preview/GET resolved-request endpoint, which keeps its existing graceful degradation (a `null` body content with a warning, no abort) — only the try-it-out invocation paths (test case and variables) abort before calling the live deployment or global route.

#### Scenario: JSON request body fails JSONata evaluation (ROUTE suite)
- **WHEN** the resolved request of a ROUTE suite produces a warning with `REQUEST_BODY_EVALUATION_ERROR` code
- **THEN** the system SHALL return HTTP 400 with error code `VALIDATION_ERROR`, the same way as for a DEPLOYMENT suite
- **AND** the global route SHALL NOT be invoked

---

## ADDED Requirements

### Requirement: Try it out for ROUTE suites
Both try-it-out endpoints (`POST /api/v1/test-suites/{id}/test-cases/{tcId}/try-it-out` and `POST /api/v1/test-suites/{id}/try-it-out`) SHALL support ROUTE suites. The invocation SHALL go to the DIAL Core root path given by the resolved `urlTemplate`, and the resolved deployment-model validation described in `Try-It-Out validates the resolved deployment model` SHALL NOT be applied to a ROUTE suite. The response shape, request-chain and multi-turn semantics, response-column extraction, SSE handling and invocation span SHALL be unchanged from DEPLOYMENT suites.

Status: **Implemented**

#### Scenario: Test-case try-it-out invokes the Core root path
- **WHEN** an authenticated user sends POST to `/api/v1/test-suites/{id}/test-cases/{tcId}/try-it-out` for a ROUTE suite whose `urlTemplate` is `/validate/items`
- **THEN** DIAL Core SHALL be invoked at path `/validate/items`
- **AND** the system SHALL return HTTP 200 with the standard try-it-out response

#### Scenario: Variables-based try-it-out invokes the Core root path
- **WHEN** an authenticated user sends POST to `/api/v1/test-suites/{id}/try-it-out` with a `variables` object for a ROUTE suite
- **THEN** DIAL Core SHALL be invoked at the resolved root-relative path, with no deployment prefix

#### Scenario: Model mismatch on a ROUTE suite is not rejected
- **WHEN** a ROUTE suite's resolved `urlTemplate` is `/openai/v1/responses` and its resolved body carries `{"model":"not-a-deployment"}`
- **THEN** Try-It-Out SHALL invoke DIAL Core at `/openai/v1/responses`
- **AND** the system SHALL NOT return a `REQUEST_BODY_VALIDATION_ERROR`

## Implementation notes

- `TryItOutService` holds the type-aware preconditions and invocation; the URL is built by `DialCoreUrlBuilder`.
- Tests: `TryItOutServiceTest` (ROUTE cases in test-case and variables modes), `TryItOutFunctionalTests` ("Should try out a ROUTE suite against the DIAL Core root path"), `DialCoreUrlBuilderTest`.
- A URL-builder rejection surfaces as an uncaught validation exception mapped to HTTP 400 `VALIDATION_ERROR` (message only, no `resolvedRequest` details), in both single-invocation and chain modes; earlier chain invocations already made are not rolled back. Unit coverage of this path is via `DialCoreUrlBuilderTest` only.
