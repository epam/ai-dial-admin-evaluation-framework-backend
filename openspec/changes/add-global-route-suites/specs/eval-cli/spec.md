## ADDED Requirements

### Requirement: ROUTE Suites Are Rejected Pre-Flight
The CLI SHALL fail fast when the suite to be run is a ROUTE suite (`suiteType = ROUTE`). The `run` command rejects it before anything else. The `evaluate` command rejects it before suite contract validation, deployment-reference resolution, runner setup or any target invocation, but after its clone and fetch steps have completed (a clone of the ROUTE suite may have been created or reused on the source EF); import is not reached, and the exception aborts the per-suite loop so remaining selected suites are not processed. The CLI executes test cases against a deployment reference only; a ROUTE suite targets a global DIAL Core route and is therefore excluded from `Test Case Execution Against a Configured Target Deployment`. The `clone`, `fetch` and `import` commands SHALL NOT be affected: the fetched suite JSON carries `routesRef` like any other suite field.

Status: **Implemented**

#### Scenario: ROUTE suite is rejected before execution
- **WHEN** the `run` or `evaluate` command targets a ROUTE suite
- **THEN** the CLI aborts with a non-zero exit code and the error `ROUTE suite execution is not supported by eval-cli`
- **AND** no suite contract validation, deployment resolution or runner setup happens, no target is invoked and no results CSV is written

#### Scenario: evaluate stops after clone and fetch
- **WHEN** the `evaluate` command processes a ROUTE suite among several selected suites
- **THEN** its clone and fetch steps have already completed, the ROUTE rejection error is raised, import is not reached, and the remaining selected suites are not processed

#### Scenario: ROUTE suite is rejected even with a target deployment supplied
- **WHEN** the `run` command targets a ROUTE suite and `--deployment-id` is supplied
- **THEN** the CLI SHALL still reject the suite with the same error

#### Scenario: Fetching a ROUTE suite still works
- **WHEN** the `fetch` command targets a ROUTE suite
- **THEN** the CLI fetches the suite and its test cases as for any other suite, and the stored suite JSON includes `routesRef`

## Implementation notes

- `RunOrchestrationService` performs the pre-flight check as the first step of `run`; `FetchService` performs no suite-type-specific handling.
- Tests: `RunOrchestrationServiceTest` ("rejects ROUTE before deployment resolution or runner setup"). The `--deployment-id`-supplied and `fetch` scenarios follow from the code path but have no dedicated test.
