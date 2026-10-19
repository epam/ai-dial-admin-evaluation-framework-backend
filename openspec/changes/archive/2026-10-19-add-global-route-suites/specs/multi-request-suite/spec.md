## MODIFIED Requirements

### Requirement: A suite defines an ordered request chain

A test suite SHALL execute, for each test-case repetition, an ordered chain of requests against its single HTTP target — the suite's `deploymentRef` (DEPLOYMENT suite) or the global DIAL Core route addressed from the DIAL Core root (ROUTE suite). The chain SHALL be `[request #0] ++ additionalRequests` where request #0 is built from the suite's own `endpointRef`, `requestTemplate`, `responseColumns` and `inputBindings`, and `additionalRequests` is an ordered list of `RequestDefinitionDto`. The target SHALL remain suite-level — a chain SHALL NOT target more than one target. Requests SHALL execute strictly sequentially in list order under a single concurrency permit (concurrency applies across test cases, not within a chain). `totalRequests` SHALL be `1 + additionalRequests.size()`.

Status: **Implemented**

#### Scenario: Two-request chain executes in order
- **WHEN** a suite has one entry in `additionalRequests` and a test case runs
- **THEN** request #0 SHALL be invoked first and the additional request second, both against the suite's HTTP target

#### Scenario: Empty additionalRequests is a one-element chain
- **WHEN** a suite's `additionalRequests` is absent, null or `[]`
- **THEN** the chain SHALL contain exactly request #0 and execution SHALL be indistinguishable from a suite created before this capability existed

#### Scenario: Requests of one chain are not parallelised
- **WHEN** a chain of three requests executes for one test case
- **THEN** the three calls SHALL be issued sequentially, each completing before the next begins

#### Scenario: ROUTE suite chain executes every request from the DIAL Core root
- **WHEN** a ROUTE suite has one entry in `additionalRequests` and a test case runs
- **THEN** request #0 SHALL be invoked first and the additional request second
- **AND** each request SHALL be sent to its own resolved root-relative `urlTemplate` path on DIAL Core, with no deployment prefix, subject to the root-relative path rules of the engine requirement `ROUTE suite invocation path validation`

## Implementation notes

- `additionalRequests` is accepted on ROUTE suites; only `MCP_TOOL` suites reject it (`MCP suites reject additional requests` is unchanged).
- Chain execution goes through the shared turn loop (`TurnLoopExecutor`) and the DIAL Core URL builder (`DialCoreUrlBuilder`).
- Tests: no ROUTE-specific chain test exists at HEAD; the ROUTE chain scenario follows from the chain path sharing the suite-level HTTP target (follows from the chain sharing the suite-level HTTP target in both the run path and try-it-out; no dedicated ROUTE chain test).
