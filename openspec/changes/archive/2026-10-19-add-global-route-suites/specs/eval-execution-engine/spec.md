## MODIFIED Requirements

### Requirement: URL and method resolution from endpoint contract
The worker SHALL construct the full request URL and HTTP method from the suite's HTTP target (the `deploymentRef` of a DEPLOYMENT suite, or the DIAL Core root for a ROUTE suite) and `endpointRef`, using `DialCoreUrlBuilder` for URL construction.
Status: **Implemented**

#### Scenario: Standard OpenAI endpoint
- **WHEN** `endpointRef.relativeUrlPattern` is `/chat/completions` and `deploymentRef.id` is `gpt-4`
- **THEN** the worker SHALL construct URL as `{dialCoreBaseUrl}/openai/deployments/gpt-4/chat/completions`

#### Scenario: Custom endpoint
- **WHEN** `endpointRef.relativeUrlPattern` is `/custom/predict`
- **THEN** the worker SHALL construct URL as `{dialCoreBaseUrl}/v1/deployments/{id}/route/custom/predict`

#### Scenario: HTTP method from endpointRef
- **WHEN** `endpointRef.method` is `POST`
- **THEN** the worker SHALL use POST as the HTTP method for the deployment call

#### Scenario: Anthropic Messages endpoint
- **WHEN** `endpointRef.relativeUrlPattern` is `/anthropic/v1/messages`
- **THEN** the worker SHALL construct URL as `{dialCoreBaseUrl}/anthropic/v1/messages` (passed through unchanged — it is DIAL Core's real, deployment-less route), with the deployment ID NOT included anywhere in the URL. The suite author is responsible for including a `model` field identifying the deployment inside the JSON request body — DIAL Core resolves the deployment from that field, not from the URL. This mapping applies regardless of the value of `deploymentRef.id`.

#### Scenario: Global route path is invoked from the DIAL Core root
- **WHEN** a run executes a ROUTE suite whose resolved `urlTemplate` is `/validate/items/42`
- **THEN** the worker SHALL construct URL as `{dialCoreBaseUrl}/validate/items/42`
- **AND** no `/openai/deployments/...` or `/v1/deployments/.../route` segment, and no deployment ID, SHALL appear anywhere in the URL

## ADDED Requirements

### Requirement: ROUTE suite invocation path validation
For a ROUTE suite the resolved `urlTemplate` SHALL be the path sent to DIAL Core, appended to the DIAL Core base URL unchanged, and SHALL be a root-relative path. The `routesRef` of the suite is metadata only and SHALL NOT be used to build the URL. A resolved path that violates the rules below SHALL NOT be sent to DIAL Core. The rules SHALL apply identically to every request of a chain, to every turn, and to try-it-out.

Status: **Implemented**

#### Scenario: Path without a leading slash is rejected
- **WHEN** the resolved `urlTemplate` of a ROUTE suite is `validate/items`
- **THEN** URL construction SHALL fail with message `ROUTE urlTemplate must resolve to a root-relative DIAL Core path`
- **AND** no request SHALL be sent to DIAL Core

#### Scenario: Authority-form path is rejected
- **WHEN** the resolved `urlTemplate` of a ROUTE suite is `//host/path`
- **THEN** URL construction SHALL fail with message `ROUTE urlTemplate must resolve to a root-relative DIAL Core path`

#### Scenario: Blank path is rejected
- **WHEN** the resolved `urlTemplate` of a ROUTE suite is empty or blank
- **THEN** URL construction SHALL fail with message `ROUTE urlTemplate must resolve to a root-relative DIAL Core path`

#### Scenario: Query or fragment data in the path is rejected
- **WHEN** the resolved `urlTemplate` of a ROUTE suite contains `?` or `#` (e.g. `/validate/items?x=1`)
- **THEN** URL construction SHALL fail with message `ROUTE urlTemplate path must not contain query or fragment data; use queryParams instead`

#### Scenario: Backslash or control character in the path is rejected
- **WHEN** the resolved `urlTemplate` of a ROUTE suite contains `\` or an ISO control character
- **THEN** URL construction SHALL fail with message `ROUTE urlTemplate path contains an unsupported character`

#### Scenario: Valid root-relative path is passed through unchanged
- **WHEN** the resolved `urlTemplate` of a ROUTE suite is `/validate/items`
- **THEN** the path sent to DIAL Core SHALL be `/validate/items`

#### Scenario: Rejected path in a run records an ERROR result
- **WHEN** a run executes a test case of a ROUTE suite whose resolved `urlTemplate` violates the rules above
- **THEN** no request SHALL be sent to DIAL Core for that turn
- **AND** the turn SHALL record a result row with `executionStatus = ERROR` whose response body is the error envelope with code `REQUEST_RESOLUTION_ERROR` and the rejection message
- **AND** the remaining turns and chain requests of that test case SHALL NOT run (fail-fast)
- **AND** the run SHALL NOT be failed by this per-case error

### Requirement: ROUTE suites skip resolved deployment-model validation
The resolved deployment-model validation described in `Execution engine rejects invalid resolved deployment models` SHALL NOT be applied to a ROUTE suite, because a ROUTE suite has no deployment identifier to compare the body `model` against. For a ROUTE suite, a resolved body for `/openai/v1/responses` or `/anthropic/v1/messages` SHALL be sent as resolved regardless of its `model` value. The behaviour for DEPLOYMENT suites SHALL be unchanged.

Status: **Implemented**

#### Scenario: ROUTE suite with an arbitrary model in the body is invoked
- **WHEN** a ROUTE suite's resolved `urlTemplate` is `/openai/v1/responses` and its resolved JSON body is `{"model":"not-a-deployment"}`
- **THEN** the request SHALL be sent to DIAL Core at `/openai/v1/responses`
- **AND** the result row SHALL have `executionStatus = SUCCESS` when DIAL Core answers successfully
- **AND** no `REQUEST_BODY_VALIDATION_ERROR` SHALL be produced

#### Scenario: DEPLOYMENT suite validation is unchanged
- **WHEN** a DEPLOYMENT suite's resolved body for `/openai/v1/responses` carries a `model` that does not equal the effective deployment ID
- **THEN** the request SHALL NOT be sent and the turn SHALL record an `ERROR` result with code `REQUEST_BODY_VALIDATION_ERROR`, exactly as before

### Requirement: ROUTE suites share the HTTP execution pipeline
Apart from URL construction and the skipped deployment-model validation, a ROUTE suite SHALL execute exactly like a DEPLOYMENT suite: HTTP method from `endpointRef.method`, request headers and query parameters, request-body serialisation, streaming accumulation, response size limiting, retry policy, rate limiting, the multi-turn loop, the request chain, response-column extraction, result rows, chain-wide fail-fast and caller credential propagation.

Status: **Implemented**

#### Scenario: ROUTE run completes and persists results like a DEPLOYMENT run
- **WHEN** a run is created for a ROUTE suite with `urlTemplate` `/validate/${{path}}` and a test case whose `routePath` field is `items`
- **THEN** the run SHALL reach `COMPLETED`
- **AND** DIAL Core SHALL receive the request path `/validate/items`
- **AND** the run's result rows SHALL be persisted with the same shape as for a DEPLOYMENT run

#### Scenario: Caller credentials are propagated unchanged
- **WHEN** a ROUTE suite run invokes DIAL Core
- **THEN** the credential headers SHALL follow the same rules as for a DEPLOYMENT suite

## Implementation notes

- `DialCoreUrlBuilder` (`evaluation-runner-core`) builds and validates the ROUTE path; the turn loop (`TurnLoopExecutor`) skips the model validation for ROUTE and lets the URL-builder `ValidationException` surface through its resolution-error path (`REQUEST_RESOLUTION_ERROR` row, fail-fast).
- Tests: `DialCoreUrlBuilderTest`, `TurnLoopExecutorTest` ("ROUTE invokes the resolved Core path without deployment model validation"), `SuiteSnapshotFunctionalTests` ("ROUTE run snapshots routesRef and invokes the resolved root path").
