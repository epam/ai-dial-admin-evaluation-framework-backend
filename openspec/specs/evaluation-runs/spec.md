# Evaluation Runs

## Purpose
This spec defines execution of TestSuites and storage/query of run history and per-case results.

Status: **Planned/Vision** (based on design docs; not fully implemented in current codebase)

## Key Terms
- **TestSuiteRun**: one execution of a suite.
- **TestCaseRunResult**: per-test-case outcome (response or error) for a run.

## Requirements

### Requirement: Trigger a TestSuite run
The system SHALL allow triggering execution of a TestSuite and create a corresponding TestSuiteRun record.
Status: **Planned**

#### Scenario: Start run
- **WHEN** user triggers a run for a TestSuite
- **THEN** system SHALL create a TestSuiteRun record and start execution via the runner

### Requirement: Store per-case results for a run
The system SHALL persist per-test-case outcomes for each TestSuiteRun.
Status: **Planned**

#### Scenario: Record endpoint outcome
- **WHEN** execution calls the target endpoint for a test case
- **THEN** system SHALL store response or error payload and timing data in TestCaseRunResult

### Requirement: Query run history
The system SHALL provide APIs to query TestSuiteRun history and retrieve run details.
Status: **Planned**

#### Scenario: List runs for a suite
- **WHEN** client lists runs for a given suite
- **THEN** system SHALL return runs with sorting/filtering suitable for UI browsing

## Implementation Notes
- Vision references: `docs/design/entity-relationship-model.md` (Run entities), `docs/design/infrastructure-architecture.md` (runner/jobs flow).

## Open Questions / TODO
- Define concrete REST API endpoints for runs (create/list/get) and how they map to runner job IDs.
- Decide storage strategy for large payloads (DB JSONB vs object store with references).

