## ADDED Requirements

### Requirement: `eval_summaries` response fields span the suite's request chain

The `eval_summaries` instance-specific detailed schema SHALL derive its `response::<column>` virtual fields from the **suite-wide union** of the run snapshot's response columns — the snapshot's own `responseColumns` followed by each `additionalRequests[i].responseColumns` in chain order — not from the snapshot's own `responseColumns` alone. Because response-column names are globally unique across a suite's chain, the union SHALL yield no duplicate field name, and each field SHALL keep its declared type and its `extracted_columns` physical source exactly as a suite-level column does. A run of a suite without `additionalRequests` SHALL produce the identical field list it produced before this change.

The new physical columns `request_index` and `total_requests` on `test_case_eval_summaries` SHALL become queryable base fields automatically, through the existing rule that the base schema is derived from the entity's generated jOOQ table; no per-entity field enumeration SHALL be added for them.

Status: **Implemented**

#### Scenario: Detailed schema lists an additional request's response column
- **WHEN** `GET /api/v1/queries/entities/schema/eval_summaries/{runId}` is called for a run whose snapshot declares `configId` on the suite and `answer` on one additional request
- **THEN** the response SHALL list both `response::configId` and `response::answer`, each sourced from `extracted_columns`

#### Scenario: Single-request run's field list is unchanged
- **WHEN** the detailed schema is requested for a run of a suite with no `additionalRequests`
- **THEN** the `response::*` field list SHALL be exactly the snapshot's `responseColumns`, as before this change

#### Scenario: Request columns appear in the base schema without a code-level field list
- **WHEN** `GET /api/v1/queries/entities/schema/eval_summaries` is called after the analytics migration and jOOQ regeneration
- **THEN** `request_index` and `total_requests` SHALL be listed as integer base fields, derived from the generated table
