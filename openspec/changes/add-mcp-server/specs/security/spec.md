## ADDED Requirements

### Requirement: MCP endpoint protected identically to the REST API
The service SHALL apply the same authentication and authorization rules to the MCP endpoint (exactly the configured MCP path, default `/mcp` — the `STREAMABLE` and `STATELESS` transports serve no sub-path) as to `/api/v1/**`: in `oidc` mode a valid JWT from a configured issuer or a valid DIAL `Api-Key` (when enabled) is required and role filtering applies; in `none` mode requests are permitted without authentication. The deprecated `SSE` transport (`/sse`, `/mcp/message`) is out of scope and deliberately unsupported by this matcher.
Status: **Implemented** — `SecurityConfiguration`'s matcher authenticates exactly the configured `mcpEndpoint` path, read verbatim (no normalisation) via `@Value` from the same Spring AI property Spring AI itself serves; verified by `McpServerSecurityFunctionalTests` (401 without credentials in `oidc` mode, malformed bearer → 401, JWT and `Api-Key` callers both authenticated and reach a tool) and `McpServerDisabledOidcFunctionalTests`; a non-default trailing-slash endpoint (`/agent/mcp/`) is verified by `McpServerCustomEndpointOidcFunctionalTests`.

#### Scenario: Unauthenticated MCP request in oidc mode
- **WHEN** `config.rest.security.mode=oidc` and a request to the MCP path carries neither a valid `Authorization` bearer token nor a valid `Api-Key`
- **THEN** the service SHALL respond with HTTP 401 and SHALL NOT process the JSON-RPC payload

#### Scenario: Authenticated MCP request in oidc mode
- **WHEN** `config.rest.security.mode=oidc` and a request to the MCP path carries a valid JWT (or valid `Api-Key`) with at least one allowed role
- **THEN** the service SHALL process the MCP request and tool executions SHALL see the same authenticated principal as a REST controller would

#### Scenario: MCP request in none mode
- **WHEN** `config.rest.security.mode=none`
- **THEN** requests to the MCP path SHALL be processed without authentication
