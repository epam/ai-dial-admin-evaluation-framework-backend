## ADDED Requirements

### Requirement: Deployment invocation paths encoded exactly once on the wire

The DIAL Core deployment client SHALL ensure that the path component of every deployment invocation request (`GET`/`POST`/`PUT`/`PATCH`/`DELETE` to `/openai/deployments/{id}/…` and `/v1/deployments/{id}/route/…`) is URL-encoded **exactly once** on the wire, regardless of whether the `deploymentRef.id` arrives pre-encoded (the form DIAL Core's `GET /v1/deployments` returns) or as a raw literal value.

Concretely, for an application id whose canonical DIAL Core resource URL is `applications/public/Quick App with RAG__0.0.1`, the client SHALL produce the wire path `/v1/deployments/applications/public/Quick%20App%20with%20RAG__0.0.1/route/{relativePath}` — never `Quick%2520App…` (double-encoded) and never `Quick App…` (unencoded, which the underlying HTTP client would reject as illegal URI).

The behaviour SHALL be idempotent under repeated decoding: feeding either `Quick App with RAG__0.0.1` or `Quick%20App%20with%20RAG__0.0.1` as the segment to the client MUST result in the identical wire path. Inner `/` characters within a single id segment, if any, SHALL be encoded as `%2F` (per-segment encoding), consistent with DIAL Core's `UrlUtil.encodePathSegment` contract.

Status: Implemented.

Implementation notes:
- Encoding is centralized in `client.dialcore.DialCoreDeploymentInvoker` so that every caller of the invoker (e.g. `service.domain.TryItOutService`, `service.domain.job.EvaluationWorker`) benefits without per-caller awareness of the encoding contract.
- Mirrors the pattern already used by `client.mcp.McpToolInvoker.buildMcpEndpoint` / `buildSseEndpoint` — decode each segment once, then use `UriComponentsBuilder.pathSegment(...).build().encode()`.

#### Scenario: Public application id with spaces in display name

- **GIVEN** DIAL Core returns a deployment with `id = "applications/public/Quick%20App%20with%20RAG__0.0.1"` from `GET /v1/deployments`
- **AND** a test suite stores this id verbatim in `deployment_ref.id`
- **WHEN** Try Out or an evaluation run invokes the deployment via `POST /v1/deployments/{id}/route/chat/completions`
- **THEN** the wire request path SHALL be `/v1/deployments/applications/public/Quick%20App%20with%20RAG__0.0.1/route/chat/completions`
- **AND** the wire path SHALL NOT contain `%2520`

#### Scenario: Public application id with parentheses or other reserved characters

- **GIVEN** DIAL Core returns a deployment with `id = "applications/public/Quick%20App%20(v2)__0.0.1"`
- **WHEN** the client invokes the deployment
- **THEN** the wire request path SHALL contain `Quick%20App%20(v2)__0.0.1` — the space is single-encoded as `%20`, and `(`/`)` are preserved literally because RFC 3986 lists them as `sub-delims` and therefore as valid `pchar` characters in a path segment (Spring's `HierarchicalUriComponents$Type.PATH_SEGMENT` rule honors this and does not percent-encode sub-delims)
- **AND** the wire path SHALL NOT contain any double-encoded form — i.e., no `%2520` (double-encoded space) and no `%2528`/`%2529` (double-encoded parentheses)

#### Scenario: Model id with no special characters (regression guard)

- **GIVEN** a model deployment with `id = "gpt-4"`
- **WHEN** the client invokes `POST /openai/deployments/gpt-4/chat/completions`
- **THEN** the wire request path SHALL be `/openai/deployments/gpt-4/chat/completions` (unchanged from prior behaviour)

#### Scenario: Idempotency under raw-vs-encoded input

- **GIVEN** the input deployment id is `"applications/public/Quick App with RAG__0.0.1"` (raw, with literal space)
- **AND** a second input deployment id is `"applications/public/Quick%20App%20with%20RAG__0.0.1"` (already URL-encoded)
- **WHEN** the client builds the wire path for each
- **THEN** both inputs SHALL produce the identical wire path `/v1/deployments/applications/public/Quick%20App%20with%20RAG__0.0.1/route/{relativePath}`

#### Scenario: Query parameters remain single-encoded

- **GIVEN** the invocation includes query parameters such as `?model=gpt-4&temperature=0.7`
- **WHEN** the client builds the request URI
- **THEN** query parameter values SHALL be encoded exactly once
- **AND** path and query encoding SHALL be composed in a single `UriComponentsBuilder` so encoding is consistent between components
