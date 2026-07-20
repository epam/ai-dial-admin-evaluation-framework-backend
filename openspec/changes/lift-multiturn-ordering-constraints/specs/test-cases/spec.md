## MODIFIED Requirements

### Requirement: Multi-turn grouping fields on a TestCase
A TestCase SHALL carry two optional top-level fields — `multiTurnId` (a client-supplied UUID string) and `turnIndex` (an integer) — that group multiple rows into a single ordered multi-turn. Both fields are top-level columns (`multi_turn_id VARCHAR(36)`, `turn_index INTEGER`) and SHALL NEVER be stored inside the `data` map. Both NULL means a single-turn test case (the default; every pre-existing row is single-turn). A multi-turn is the set of rows sharing the same `multiTurnId` within a dataset, ordered by `turnIndex`. Multi-turn is emergent from the presence of grouped rows; there is no suite-level `multiTurn` flag.

The service SHALL apply the following per-row write-time validation on create (`POST`), full replace (`PUT`), and CSV import, returning HTTP 400 on any violation:
- **Both-or-neither**: `multiTurnId` and `turnIndex` MUST be both present or both absent.
- **Well-formed id**: when present, `multiTurnId` MUST be a syntactically valid UUID.
- **Non-negative index**: when present, `turnIndex` MUST satisfy `turnIndex >= 0`.

There SHALL be no upper-bound (`turnIndex < MAX_MULTI_TURN_TURNS`) validation at write time. Because a multi-turn's runnable turns may be non-contiguous (turns disabled or filtered out at the start, middle, or end), a high authored `turnIndex` no longer implies a large turn count; the turn-count limit is enforced later, at snapshot/run time, against the number of surviving turns (see `suite-run-snapshot`).

Contiguity and completeness of a multi-turn (turn 0 present, no gaps, no missing tail) SHALL NOT be validated at write time — multi-turn integrity is client-managed at authoring and is enforced later at snapshot/run time (see `test-suites` / snapshot specs). Validation is strictly per row.

Status: **Planned**

#### Scenario: Create a single-turn test case (both fields absent)
- **WHEN** client calls `POST /api/v1/datasets/{datasetId}/test-cases` with neither `multiTurnId` nor `turnIndex`
- **THEN** system SHALL create the TestCase with both columns NULL (single-turn)

#### Scenario: Create a multi-turn-turn test case (both fields present)
- **WHEN** client calls `POST .../test-cases` with a valid `multiTurnId` UUID and `turnIndex = 0`
- **THEN** system SHALL persist both top-level columns and store neither inside `data`

#### Scenario: Only one of the two fields present
- **WHEN** client sends a create/replace body with `multiTurnId` but no `turnIndex` (or `turnIndex` but no `multiTurnId`)
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Malformed multiTurnId
- **WHEN** client sends `multiTurnId = "not-a-uuid"` with any `turnIndex`
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Negative turnIndex
- **WHEN** client sends a valid `multiTurnId` and `turnIndex = -1`
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Large turnIndex is accepted at write time
- **WHEN** client sends a valid `multiTurnId` and a large `turnIndex` (e.g. `turnIndex >= MAX_MULTI_TURN_TURNS`)
- **THEN** system SHALL accept the write (HTTP 201) without an upper-bound error; the turn-count limit is applied later against the surviving-turn count at snapshot time

#### Scenario: Contiguity not enforced at write
- **WHEN** client creates multi-turn rows with `turnIndex` values `0` and `2` (a gap) under the same `multiTurnId`
- **THEN** system SHALL accept both writes (HTTP 201) without a contiguity error; gap detection is deferred to snapshot/run time

#### Scenario: data validated uniformly as scalars
- **WHEN** a multi-turn-turn test case is created or replaced
- **THEN** system SHALL validate its `data` against the dataset's `testCaseSchema` exactly as for a single-turn row (scalar values per schema type); no array-shape-per-column interpretation applies to multi-turn rows
