## Context

HTTP suites resolve their invocation path against a deployment (`/openai/deployments/{id}...`, passthrough, or `/v1/deployments/{id}/route{url}`). Some platform logic is exposed as **global DIAL Core Routes**: Core-level routes served at the Core ROOT with no deployment prefix, selected by Core via method + path (order/regex), not by name. This change adds a `ROUTE` suite type that reuses the whole HTTP suite pipeline and swaps only the target. Not to be confused with application routes (`/v1/deployments/{id}/route/...`, `app-schema-route-resolution`) or the App mode trigger route (`dial-app-auth`).

Status: **Implemented** (branch `feat/routes-support`).

## Goals / Non-Goals

**Goals:** DEPLOYMENT-parity evaluation of a global route (runs, try-it-out, snapshots, queries); additive schema; unchanged behaviour for existing suite types.

**Non-Goals:** route listing/lookup endpoint; checking `routesRef.name` against Core; deriving `urlTemplate` from the route's path regex; eval-cli execution; clone override of `routesRef`; new configuration; pattern doc / AGENTS.md changes.

## Data model changes

- `V1.37__AddRoutesRefToTestSuites.sql`: `ALTER TABLE test_suites ADD COLUMN routes_ref JSONB;` nullable, no default, no index, no backfill. `suite_type` DDL unchanged (`'ROUTE'` is a new value). `docs/database-schema.md` updated; jOOQ sources regenerated.
- `TestSuite.routesRef` is a JSON `String` (pure data carrier), mapped by `TestSuiteRecordMapper`, persisted on insert/update/clone by `PostgresTestSuiteRepository`. `JsonbMapper` (de)serialises `RouteReferenceDto`.
- `RouteReferenceDto { name }` in runner-core: `@NotBlank`, `@Size(max = 255)` (`RunnerValidationConstants.MAX_ROUTE_REFERENCE_NAME_LENGTH`), `@Pattern("^routes/platform/[A-Za-z0-9._%:@\[\]()-]+$")` with message "Name must be a canonical DIAL Core Route identity: routes/platform/<name>".
- Snapshot (`SuiteSnapshotDto`) gains `routesRef`; older snapshots deserialise unchanged (unknown/missing properties tolerated).

Snapshot JSON for a ROUTE run (abridged):

```json
{
  "suiteType": "ROUTE",
  "routesRef": { "name": "routes/platform/validate-route" },
  "endpointRef": { "method": "POST" },
  "requestTemplate": { "urlTemplate": "/validate/${{path}}" },
  "inputBindings": [],
  "responseColumns": [],
  "additionalRequests": []
}
```

`deploymentRef` is absent (null) for a ROUTE snapshot; MCP fields likewise.

## API contract details

- `POST /api/v1/test-suites`, `PUT /api/v1/test-suites/{id}`: optional `routesRef`; `suiteType` documented as "Suite type: DEPLOYMENT (default), MCP_TOOL, or ROUTE". Suite type is immutable on PUT (existing rule, applies to ROUTE).
- `GET /api/v1/test-suites/{id}`, list items, `GET /api/v1/test-suite-runs/{id}` (`suiteSnapshot`): return `routesRef` (null when not ROUTE).
- `POST /api/v1/test-suites/{id}/clone`: no `routesRef` in the clone request; inherited from the source.

Write-time validation (HTTP 400, `ErrorCode.VALIDATION_ERROR`), ROUTE checked in this order, first failure wins:

| # | Condition | Message |
|---|-----------|---------|
| 0 | `routesRef.name` fails bean validation | message contains `Field [routesRef.name]: Name must be a canonical DIAL Core Route identity: routes/platform/<name>` (or the `@NotBlank`/`@Size` messages for the same field) |
| 1 | `routesRef` null | `routesRef is required for ROUTE suites` |
| 2 | `endpointRef` null | `endpointRef is required for ROUTE suites` |
| 3 | `requestTemplate` null | `requestTemplate is required for ROUTE suites` |
| 4 | `deploymentRef` non-null | `deploymentRef must be empty for ROUTE suites` |
| 5 | `mcpDeploymentRef` non-null | `mcpDeploymentRef must be empty for ROUTE suites` |
| 6 | `toolRef` non-null | `toolRef must be empty for ROUTE suites` |
| 7 | `argumentTemplate` non-null | `argumentTemplate must be empty for ROUTE suites` |
| - | `routesRef` on DEPLOYMENT | `routesRef must be empty for DEPLOYMENT suites` |
| - | `routesRef` on MCP_TOOL | `routesRef must be empty for MCP_TOOL suites` |

`additionalRequests` is allowed on ROUTE (only MCP_TOOL rejects it). Soft validation (`isValid`/`validationWarnings`) follows the same HTTP path as DEPLOYMENT. Multi-turn test cases are allowed (only MCP_TOOL is rejected at run creation).

Execution-time path rules (DIAL Core URL builder; `ValidationException`):

| Condition on resolved `urlTemplate` | Message |
|---|---|
| blank, not starting with `/`, or starting with `//` | `ROUTE urlTemplate must resolve to a root-relative DIAL Core path` |
| contains `?` or `#` | `ROUTE urlTemplate path must not contain query or fragment data; use queryParams instead` |
| contains `\` or an ISO control character | `ROUTE urlTemplate path contains an unsupported character` |

DEPLOYMENT URL rules are unchanged.

Query DSL: `test_suites.routes_ref::name` (string, JSONB text extraction); `test_suite_runs.routes_ref::name` (string, source `suite_snapshot`, `suite_snapshot -> 'routesRef' ->> 'name'`). Ref descriptor publication order: `deployment_ref`, `routes_ref`, `mcp_deployment_ref`. `test_suite_runs` field count 21 to 22.

## Transaction boundaries

No new transactions. Suite CRUD, clone and the run snapshot transaction are unchanged; `routes_ref` is just another column written inside them. The migration is a single additive `ALTER TABLE`.

## Error handling

- Write time and try-it-out preconditions: `ValidationException` mapped to HTTP 400 `VALIDATION_ERROR` by the default exception handler.
- Run execution: a URL-builder `ValidationException` is raised while the turn's request is being built; the turn loop's request-building failure path records an ERROR result row with a `REQUEST_RESOLUTION_ERROR` envelope and the rejection message, fails fast for that test case, and does not fail the run. No dedicated ROUTE test asserts this row.
- Try-it-out: the URL-builder `ValidationException` propagates (neither the single-call nor chain catch handles it) to the handler and surfaces as HTTP 400 `VALIDATION_ERROR` with the builder's message; no HTTP call is made.
- eval-cli: `IllegalStateException("ROUTE suite execution is not supported by eval-cli")` yields a non-zero exit.

## Component interaction flow

(a) Create/update: controller binds and bean-validates `routesRef`; the suite write-time validator applies the table above; the mapper/`JsonbMapper` serialises `routesRef` to JSONB; the repository writes `routes_ref`; soft validation runs the HTTP path.

(b) Run: run creation builds the snapshot via the snapshot builder (ROUTE: `routesRef`, `endpointRef`, `requestTemplate`, bindings; no `deploymentRef`). The worker resolves the request template per test case/turn; the turn loop skips the resolved request-model validation for ROUTE; the DIAL Core URL builder validates and returns the resolved path unchanged; the invoker calls `{coreBase}{path}` with `endpointRef.method`, headers and `queryParams` and the caller credential; response handling, extraction and persistence are identical to DEPLOYMENT.

(c) Try-it-out: loads the suite; ROUTE precondition (stored `routesRef` present) then the shared endpoint/template/chain checks; invocation uses the same URL builder and skips model validation; response shaping unchanged.

(d) eval-cli: `run`/`evaluate` reject ROUTE suites before contract validation, deployment resolution or any target invocation (for `evaluate`, after its clone and fetch steps). `fetch`/`clone`/`import` unaffected (shared response DTO carries `routesRef`).

(e) Query DSL: the `test_suites` schema provider and entity resolver expose `routes_ref::name` from the live column; the `test_suite_runs` provider/resolver read it from `suite_snapshot` only.

## Decisions

**D1: ROUTE is a third `suiteType` value, not a flag on DEPLOYMENT.** The discriminator exists already; immutability, filters, snapshots and clone inheritance come for free.

**D2: `routesRef.name` stores the canonical identity `routes/platform/<name>`.** Core's listing returns a leaf name while the detail returns the canonical identity; canonical avoids namespace ambiguity and permits future lookup. Pattern lives in `RunnerValidationConstants`.

**D3: `routesRef` is metadata only; dispatch uses `urlTemplate` + `endpointRef.method`.** Core selects global routes by order/method/path regex, not by name. Route path regexes are not converted into URL templates.

**D4: ROUTE hard-requires `endpointRef` and `requestTemplate`** (no soft fallback unlike DEPLOYMENT); without a concrete path there is nothing to invoke.

**D5: Foreign-type fields are rejected (400), not ignored; `routesRef` is rejected on non-ROUTE suites.** Prevents ambiguous suites. Deliberate asymmetry with the older DEPLOYMENT/MCP_TOOL "ignore foreign fields" behaviour.

**D6: Root-relative path guard** (leading `/`, no `//`, no `?`/`#`, no `\` or control chars) prevents SSRF-style authority injection and keeps query data in `queryParams`.

**D7: Resolved deployment-model validation skipped for ROUTE.** No deployment id to compare; Core resolves whatever the route proxies to.

**D8: Snapshot carries `routesRef` and null `deploymentRef`;** the `test_suite_runs` entity reads `routes_ref::name` from the snapshot only (same rule as `deployment_ref::*`), never from the live suite.

**D9: eval-cli rejects ROUTE before contract validation, deployment resolution or any target invocation (for `evaluate`, after its clone and fetch steps).** Its `--deployment-id` override model has no route analogue; parity deferred.

**D10: Clone inherits `routesRef`, no override.** Mirrors `suiteType` inheritance and keeps the clone DTO stable. A `deploymentRef` override on a ROUTE clone fails the effective-suite validation with 400.

Rationale note: global routes are invoked from the Core root without a deployment prefix, which is why the existing deployment-prefixed URL construction cannot be reused and the resolved `urlTemplate` is the path.

## Internal modelling is out of scope

v0 implements the ROUTE discriminator with inline `SuiteType` checks in the write-time validator, snapshot builder, URL builder, turn loop, try-it-out and eval-cli. A follow-up refactor may replace that with a sealed target/definition model without changing anything specified here: wire contract, validation messages and order, persistence, snapshot JSON, Query DSL fields and runtime behaviour stay as documented.

## Risks / Trade-offs

- Opaque `routesRef` (no Core lookup): typos surface only at invocation or never; accepted for v0, lookup can be added later thanks to canonical identity (D2).
- Wrong `urlTemplate` may hit another Core route; mitigated only by the path guard and try-it-out.
- Skipped model validation (D7) loses a pre-flight check.
- eval-cli parity deferred (D9).
