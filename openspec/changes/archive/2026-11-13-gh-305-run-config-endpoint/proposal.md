## Why

The admin frontend (ai-dial-admin-frontend#4918) needs to bound the run form's `concurrencyLevel` input by the ceiling the backend actually enforces. Today that ceiling (`test-suite-run.execution.max-concurrency-level`) is only discoverable by submitting a run and reading the 400. GH #305. The current default of 50 is also too permissive for shared deployments.

## What Changes

- New read-only endpoint `GET /api/v1/test-suite-runs/config` returning `{"maxConcurrencyLevel": <int>}` — the currently configured value of `test-suite-run.execution.max-concurrency-level`.
- **BREAKING (default only)**: default of `test-suite-run.execution.max-concurrency-level` lowered from `50` to `5` in the main app `application.yml`. Deployments relying on run requests with `concurrencyLevel` > 5 must set `TEST_SUITE_RUN_EXECUTION_MAX_CONCURRENCY_LEVEL`.
- Env-var override stays via Spring relaxed binding (`TEST_SUITE_RUN_EXECUTION_MAX_CONCURRENCY_LEVEL`), already documented; no new placeholder.

Non-goals:
- No other limits in the response (timeouts, retries, `maxNumberOfRuns`) — can be added later as further fields.
- No MCP tool.
- `eval-cli` keeps its own default (`${TEST_SUITE_RUN_MAX_CONCURRENCY_LEVEL:50}`) — out of scope.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `test-suite-runs`: new "Get test suite run configuration" requirement; "Configuration properties" execution-defaults scenario changes `max-concurrency-level` default 50 → 5; "Run configuration model" full-runConfig example lowers `concurrencyLevel` 10 → 5 so it stays valid under the new default.

## Impact

- **API**: new `GET /api/v1/test-suite-runs/config` (same security as other run endpoints). New response DTO `TestSuiteRunConfigResponseDto` + OpenAPI example `api-v1-test-suite-runs-config-GET-response-200-minimal.json`.
- **Code**: `TestSuiteRunController` (new handler), service method exposing the configured value (web layer does not read properties directly).
- **Config**: `src/main/resources/application.yml` default change; `docs/configuration.md` row updated (default `5`).
- **DB**: none.
- **Risk**: deployments that relied on the 50 default reject runs with `concurrencyLevel` 6–50 with HTTP 400 after upgrade — call out in release notes "Deployment Changes".
- **Tests**: functional test for the endpoint (value from config, `/config` not routed to `/{id}`); existing `ExecutionSettingsFunctionalTests` still valid (uses `concurrencyLevel(5)`).
