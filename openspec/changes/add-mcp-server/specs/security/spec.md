## ADDED Requirements

### Requirement: MCP endpoint protected identically to the REST API
The service SHALL apply the same authentication and authorization rules to the MCP endpoint (the configured MCP path, default `/mcp`, and any sub-path) as to `/api/v1/**`: in `oidc` mode a valid JWT from a configured issuer or a valid DIAL `Api-Key` (when enabled) is required and role filtering applies; in `none` mode requests are permitted without authentication.
Status: **Planned**

#### Scenario: Unauthenticated MCP request in oidc mode
- **WHEN** `config.rest.security.mode=oidc` and a request to the MCP path carries neither a valid `Authorization` bearer token nor a valid `Api-Key`
- **THEN** the service SHALL respond with HTTP 401 and SHALL NOT process the JSON-RPC payload

#### Scenario: Authenticated MCP request in oidc mode
- **WHEN** `config.rest.security.mode=oidc` and a request to the MCP path carries a valid JWT (or valid `Api-Key`) with at least one allowed role
- **THEN** the service SHALL process the MCP request and tool executions SHALL see the same authenticated principal as a REST controller would

#### Scenario: MCP request in none mode
- **WHEN** `config.rest.security.mode=none`
- **THEN** requests to the MCP path SHALL be processed without authentication
