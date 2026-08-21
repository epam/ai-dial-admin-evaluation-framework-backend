## MODIFIED Requirements

### Requirement: Store response column definitions on TestSuite

Response column definitions SHALL be stored as a JSONB array on the `test_suites` table, managed via existing suite create/update endpoints. A suite MAY additionally define response columns **per request** of its chain, inside each `additionalRequests[i].responseColumns`, persisted within the `additional_requests` JSONB column.

All response columns of a suite — the suite-level array plus every additional request's array — SHALL form ONE flat namespace: names SHALL be globally unique across the whole chain, and column names SHALL NOT be qualified by request anywhere (not in metric bindings, not in export headers, not in the query schema). A column defined on any request in the chain SHALL be referenced by its bare name exactly as a suite-level column is. The maximum-50 column count SHALL apply to the **union** across the chain rather than to any single array.

Status: **Implemented**

#### Scenario: Create suite with response columns
- **WHEN** client calls `POST /api/v1/test-suites` with `responseColumns` in the request body
- **THEN** system SHALL persist the definitions and return them in the response

#### Scenario: Update suite response columns
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with updated `responseColumns`
- **THEN** system SHALL replace the existing definitions with the new array

#### Scenario: Suite with no response columns
- **WHEN** `responseColumns` is omitted or null on create/update
- **THEN** system SHALL default to an empty array `[]`

#### Scenario: Get suite returns response columns
- **WHEN** client calls `GET /api/v1/test-suites/{id}`
- **THEN** response SHALL include `responseColumns` array (empty if none defined)

#### Scenario: Response columns on an additional request round-trip
- **WHEN** client creates a suite whose `additionalRequests[0]` declares a response column `answer`
- **THEN** system SHALL persist it inside `additional_requests` and return it in the same position on read

#### Scenario: Name collision across requests is rejected
- **WHEN** the suite-level `responseColumns` and an additional request's `responseColumns` both declare `answer`
- **THEN** system SHALL respond with HTTP 400 (`VALIDATION_ERROR`) naming the duplicate, and SHALL NOT persist the suite

#### Scenario: Union over 50 columns is rejected
- **WHEN** the total number of response columns across the suite and all additional requests exceeds 50
- **THEN** system SHALL respond with HTTP 400 (`VALIDATION_ERROR`)

## ADDED Requirements

### Requirement: Extracted values feed the chain-wide accumulated frame

A response column's extracted, type-reconciled value SHALL be merged into the execution's accumulated frame and SHALL therefore be available, bound by name, to every later JSONata request-template evaluation in the same test-case execution — later turns of the same request and every turn of every later request. A column's own extraction expression SHALL continue to evaluate against the raw response body with only the additive `$request` / `$response` frame bindings; the accumulated frame is a consumer of extraction output, not an input to it. A column whose extraction failed SHALL be bound as an explicit JSONata null in the accumulated frame rather than left unbound.

Each result row's persisted `extracted_columns` SHALL be the accumulated union of all columns extracted up to and including that row, so a single row read is sufficient for metric binding resolution and `response::<column>` rendering.

Status: **Implemented**

#### Scenario: A column extracted by an earlier request is bound in a later one
- **WHEN** request #0 extracts `configId` and request #1's body expression references `$configId`
- **THEN** the value SHALL be bound during request #1's resolution

#### Scenario: Extraction expressions do not see other columns
- **WHEN** an additional request declares a response column whose expression references another column's name
- **THEN** that reference SHALL resolve as JSONata undefined — extraction expressions read the response body plus `$request`/`$response` only

#### Scenario: Row carries the accumulated union
- **WHEN** a 2-request chain extracts `configId` on request #0 and `answer` on request #1
- **THEN** request #1's row's `extracted_columns` SHALL contain both keys and request #0's row's SHALL contain only `configId`
