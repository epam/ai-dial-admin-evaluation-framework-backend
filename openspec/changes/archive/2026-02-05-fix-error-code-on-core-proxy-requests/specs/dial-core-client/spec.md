# DIAL Core Client – Delta Spec

Change: fix-error-code-on-core-proxy-requests

## MODIFIED Requirements

### Requirement: Error mapping

The system SHALL map DIAL Core errors to appropriate HTTP responses for clients. Upstream errors that indicate a service-to-service or upstream configuration issue SHALL be returned as HTTP 502 Bad Gateway with a specific error code so clients do not misinterpret them as client-side failures (e.g., invalid credentials or invalid endpoint).

#### Scenario: DIAL Core returns 401

- **WHEN** DIAL Core returns HTTP 401 (e.g., token rejected by Core after our service accepted it)
- **THEN** system returns HTTP 502 Bad Gateway with error code `UPSTREAM_AUTH_ERROR`
- **AND** response body indicates the failure originated from the upstream service

#### Scenario: DIAL Core returns 403

- **WHEN** DIAL Core returns HTTP 403
- **THEN** system returns HTTP 403 Forbidden with error code `ACCESS_DENIED`
- **AND** client may interpret this as resource-level access denial (e.g., no permission to use this deployment in Core)

#### Scenario: DIAL Core not found error

- **WHEN** DIAL Core returns HTTP 404 (resource not found in Core)
- **THEN** system returns HTTP 502 Bad Gateway with error code `UPSTREAM_NOT_FOUND`
- **AND** response body indicates the failure originated from the upstream service

#### Scenario: DIAL Core client error

- **WHEN** DIAL Core returns HTTP 4xx (other than 401, 403, 404)
- **THEN** system returns HTTP 400 Bad Request with error details

#### Scenario: DIAL Core server error

- **WHEN** DIAL Core returns HTTP 5xx (other than 504) after all retries
- **THEN** system returns HTTP 502 Bad Gateway with error code `UPSTREAM_ERROR` and error details

#### Scenario: DIAL Core timeout or 504

- **WHEN** DIAL Core returns HTTP 504 or connection/read to DIAL Core times out
- **THEN** system returns HTTP 504 Gateway Timeout with error code `UPSTREAM_TIMEOUT`

#### Scenario: Upstream error codes clarify failure source

- **WHEN** system returns an error due to DIAL Core (upstream) failure
- **THEN** response uses one of: `UPSTREAM_AUTH_ERROR` (502), `UPSTREAM_NOT_FOUND` (502), `UPSTREAM_ERROR` (502), or `UPSTREAM_TIMEOUT` (504) so clients understand the failure is on the upstream side, not this service
