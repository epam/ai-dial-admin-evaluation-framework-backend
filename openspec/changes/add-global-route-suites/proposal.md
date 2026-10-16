## Why

Suites can target a DIAL deployment (`DEPLOYMENT`) or an MCP tool (`MCP_TOOL`), but not a **global DIAL Core Route**: a Core-level route (`routes/...` in Core config) that Core serves at its ROOT, with no `/openai/deployments/{id}` or `/v1/deployments/{id}/route` prefix. Core selects such a route by HTTP method + path (order/regex), not by name. Teams that expose evaluable logic as global routes (validators, proxies, utility endpoints) cannot evaluate them today. This change adds `ROUTE` as a third suite type so that these routes get the same dataset, templating, multi-turn, request-chain, metric and analytics pipeline as deployments.

"Global DIAL Core Route" is NOT an application route (`/v1/deployments/{id}/route/...`, spec `app-schema-route-resolution`) and NOT the DIAL App mode trigger route (spec `dial-app-auth`). Those are unchanged.

Status: **Implemented** (branch `feat/routes-support`); this change documents the implementation after the fact.

## What Changes

All items are **Implemented**.

- `suiteType` gains `ROUTE` (values: `DEPLOYMENT` default, `MCP_TOOL`, `ROUTE`). A ROUTE suite is an HTTP suite shape (shares `endpointRef`, `requestTemplate`, `inputBindings`, `responseColumns`, `additionalRequests`, `requestName`) targeting a global route instead of a deployment.
- New optional `routesRef: { name }` on suite create/update requests, suite responses (detail and list items) and the run's `suiteSnapshot`. `name` is the canonical API-managed route identity `routes/platform/<name>`. It is metadata only (display, filtering, future lookup); the invocation path comes from the resolved `requestTemplate.urlTemplate`.
- Write-time hard validation (HTTP 400 `VALIDATION_ERROR`): ROUTE requires `routesRef`, `endpointRef`, `requestTemplate` and rejects `deploymentRef`, `mcpDeploymentRef`, `toolRef`, `argumentTemplate`; `routesRef` is rejected on DEPLOYMENT and MCP_TOOL suites.
- Clone inherits `routesRef` from the source (no override); the effective-suite validation applies.
- Persistence: nullable `test_suites.routes_ref JSONB` (Flyway `V1.37__AddRoutesRefToTestSuites.sql`).
- Run snapshot for a ROUTE suite carries `suiteType = ROUTE` and `routesRef`; `deploymentRef` is null.
- Execution (runs and try-it-out): the resolved `urlTemplate` is sent to DIAL Core unchanged as a root-relative path; the DIAL Core URL builder rejects non-root-relative paths, query/fragment data, backslashes and control characters. The resolved request-model validation (`model` in body must equal the deployment) is skipped for ROUTE.
- Try-it-out: ROUTE precondition "Route reference is required for try-it-out"; invocation at the Core root.
- eval-cli: `run`/`evaluate` reject ROUTE suites before contract validation, deployment resolution or any target invocation (for `evaluate`, after its clone and fetch steps).
- Query DSL: `routes_ref::name` on `test_suites` (live suite) and on `test_suite_runs` (run's own snapshot).
- OpenAPI: `route` examples for suite POST/PUT/GET and run GET.

## Capabilities

### New Capabilities

None. ROUTE is a third suite type inside existing capabilities; no new capability folder is warranted.

### Modified Capabilities

- `test-suites`: `ROUTE` suite type, `routesRef` field, ROUTE hard validation, rejection of `routesRef` on other types, `routes_ref` persistence, `route` OpenAPI examples.
- `test-suite-clone`: clone inherits `routesRef`; effective-suite validation rejects a `deploymentRef` override on a ROUTE clone.
- `suite-run-snapshot`: ROUTE snapshot carries `routesRef`, null `deploymentRef`.
- `eval-execution-engine`: ROUTE invokes the resolved root path with validation guard; deployment-model validation skipped.
- `try-it-out`: ROUTE precondition and root-path invocation; model validation skipped.
- `eval-cli`: `run`/`evaluate` reject ROUTE suites before contract validation, deployment resolution or any target invocation (for `evaluate`, after its clone and fetch steps).
- `multi-request-suite`: `additionalRequests` chains are allowed on ROUTE suites and target the same route namespace (Core root).
- `query-schema-discovery`: `routes_ref::name` published in the `test_suites` schema.
- `test-suite-runs-query-entity`: `routes_ref::name` read from the run's snapshot (field count 21 to 22).
- `structured-query-model`: `routes_ref::<key>` added as a third JSONB sub-field resolver prefix family on `test_suites` (sub-field `name`).
- `multi-turn-test-case`: turn-count rule and the MCP-only multi-turn guard are stated for HTTP suites (DEPLOYMENT and ROUTE).
- `request-template`: soft binding validation applies to all suite types incl. ROUTE (via `requestTemplate`).
- `file-ref-validation`: `|file` constant-binding validation applies to all suite types incl. ROUTE.

## Impact

### Code
- Main app: `TestSuiteRequestDto`, `TestSuiteRequestValidator`, `TestSuiteMapper`, `JsonbMapper`, `SuiteSnapshotBuilder`, `TryItOutService`, `TestSuite` (data model), `TestSuiteRecordMapper`, `PostgresTestSuiteRepository`, `TestSuiteRunQueryFields`, `TestSuitesSchemaProvider`, `PostgresTestSuiteEntityResolver`, `OpenApiExampleCustomizer`, regenerated jOOQ sources.
- runner-core (`evaluation-runner-core`): `SuiteType`, new `RouteReferenceDto`, `RunnerValidationConstants`, `SuiteSnapshotDto`, `TestSuiteResponseDto`, `DialCoreUrlBuilder`, `TurnLoopExecutor`.
- eval-cli: `RunOrchestrationService`.
- No new packages.

### Database
- Flyway `V1.37__AddRoutesRefToTestSuites.sql`: `ALTER TABLE test_suites ADD COLUMN routes_ref JSONB;` Nullable, no default, no index, no backfill. The `suite_type` column DDL is unchanged; `'ROUTE'` is just a new value. `docs/database-schema.md` updated.

### API
- `POST /api/v1/test-suites`, `PUT /api/v1/test-suites/{id}`, `GET /api/v1/test-suites/{id}`, `GET /api/v1/test-suites` (items), `POST /api/v1/test-suites/{id}/clone`, `GET /api/v1/test-suite-runs/{id}` (`suiteSnapshot`), both try-it-out endpoints, `GET /api/v1/queries/entities/schema/{entity}`. New OpenAPI examples `api-v1-test-suites-POST-request-route.json`, `...-POST-response-201-route.json`, `api-v1-test-suites-id-GET-response-200-route.json`, `api-v1-test-suites-id-PUT-request-route.json`, `...-PUT-response-200-route.json`, `api-v1-test-suite-runs-id-GET-response-200-route.json`.

### Configuration
- None. `docs/configuration.md` unchanged.

### Security
- None. Same caller credential propagation (`Authorization` / `Api-Key` header rules) as DEPLOYMENT. The root-path guard additionally blocks authority-form and control-character injection in `urlTemplate`.

### Dependencies
- DIAL Core global routes must be configured and reachable at the Core root.

## Goals

- Evaluate a global DIAL Core Route through the existing run, try-it-out, snapshot, analytics and query pipeline with DEPLOYMENT parity (multi-turn, request chain, response columns, streaming, retries).
- Keep existing DEPLOYMENT and MCP_TOOL behaviour unchanged.

## Non-Goals

- No route listing/lookup endpoint (`GET /api/v1/routes` or similar) and no validation that `routesRef.name` exists in Core; the reference is opaque metadata.
- No automatic derivation of `urlTemplate` from the route's Core path regex; authors supply a concrete root-relative path.
- eval-cli cannot execute ROUTE suites.
- No `routesRef` override on clone.
- No pattern doc or AGENTS.md change; no new configuration properties.

## Current State

Before this change `suiteType` is `DEPLOYMENT` or `MCP_TOOL`. HTTP suites always resolve to `/openai/deployments/{id}...`, a passthrough path, or `/v1/deployments/{id}/route{url}`; there is no way to reach a Core root path.

## Risks

- `routesRef` is not validated against Core: a typo is undetected until invocation (and since it is metadata only, never detected by the run itself).
- Core selects routes by method + path, so a wrong `urlTemplate` silently hits another route or returns 404.
- Model validation is skipped for ROUTE, so a `model` mismatch in the body cannot be caught pre-flight.
- eval-cli parity gap: ROUTE suites fetched with eval-cli cannot be run there.
- Foreign-field rejection for ROUTE is stricter than the "ignore foreign fields" behaviour of DEPLOYMENT/MCP_TOOL; clients that send both sets get 400.

## Rollout

- Migration is additive and nullable; old clients and old snapshots (no `routesRef`) are unaffected.
- Existing DEPLOYMENT/MCP_TOOL behaviour is unchanged, with one new 400: a stray `routesRef` on those suite types.

## Test Plan

- Unit: `TestSuiteRequestValidatorTest` (ROUTE nested class), `DialCoreUrlBuilderTest`, `SuiteSnapshotBuilderTest`, `TryItOutServiceTest`, `TestSuiteMapperCloneTest`, `TestSuitesSchemaProviderTest`, `TestSuiteRunsSchemaProviderTest`, `PostgresTestSuiteEntityResolverTest`, `PostgresTestSuiteRunEntityResolverTest`; runner-core `TurnLoopExecutorTest`; eval-cli `RunOrchestrationServiceTest`.
- Functional (`PostgresFunctionalTests` nested): `TestSuiteFunctionalTests`, `SuiteSnapshotFunctionalTests`, `TryItOutFunctionalTests`, `TestSuiteStructuredQueryFunctionalTests`, `TestSuiteRunStructuredQueryFunctionalTests`.

## Relationship to the follow-up refactor

Internal modelling (inline `SuiteType` checks today; a sealed target/definition model later) is intentionally not part of this change's contract. Only wire contract, persistence, snapshot JSON, Query DSL fields and runtime-observable behaviour are specified.
