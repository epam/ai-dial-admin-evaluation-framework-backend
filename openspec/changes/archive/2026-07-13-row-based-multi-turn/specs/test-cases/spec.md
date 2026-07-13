# Test Cases

## ADDED Requirements

### Requirement: Conversation grouping fields on a TestCase
A TestCase SHALL carry two optional top-level fields — `conversationId` (a client-supplied UUID string) and `turnIndex` (an integer) — that group multiple rows into a single ordered multi-turn conversation. Both fields are top-level columns (`conversation_id VARCHAR(36)`, `turn_index INTEGER`) and SHALL NEVER be stored inside the `data` map. Both NULL means a single-turn test case (the default; every pre-existing row is single-turn). A conversation is the set of rows sharing the same `conversationId` within a dataset, ordered by `turnIndex`. Multi-turn is emergent from the presence of grouped rows; there is no suite-level `multiTurn` flag.

The service SHALL apply the following per-row write-time validation on create (`POST`), full replace (`PUT`), and CSV import, returning HTTP 400 on any violation:
- **Both-or-neither**: `conversationId` and `turnIndex` MUST be both present or both absent.
- **Well-formed id**: when present, `conversationId` MUST be a syntactically valid UUID.
- **Turn bounds**: when present, `turnIndex` MUST satisfy `turnIndex >= 0` AND `turnIndex < MAX_CONVERSATION_TURNS`.

Contiguity and completeness of a conversation (turn 0 present, no gaps, no missing tail) SHALL NOT be validated at write time — conversation integrity is client-managed at authoring and is enforced later at snapshot/run time (see `test-suites` / snapshot specs). Validation is strictly per row.

Status: **Planned**

#### Scenario: Create a single-turn test case (both fields absent)
- **WHEN** client calls `POST /api/v1/datasets/{datasetId}/test-cases` with neither `conversationId` nor `turnIndex`
- **THEN** system SHALL create the TestCase with both columns NULL (single-turn)

#### Scenario: Create a conversation-turn test case (both fields present)
- **WHEN** client calls `POST .../test-cases` with a valid `conversationId` UUID and `turnIndex = 0`
- **THEN** system SHALL persist both top-level columns and store neither inside `data`

#### Scenario: Only one of the two fields present
- **WHEN** client sends a create/replace body with `conversationId` but no `turnIndex` (or `turnIndex` but no `conversationId`)
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Malformed conversationId
- **WHEN** client sends `conversationId = "not-a-uuid"` with any `turnIndex`
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Negative turnIndex
- **WHEN** client sends a valid `conversationId` and `turnIndex = -1`
- **THEN** system SHALL respond with HTTP 400

#### Scenario: turnIndex at or above the cap
- **WHEN** client sends a valid `conversationId` and `turnIndex >= MAX_CONVERSATION_TURNS`
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Contiguity not enforced at write
- **WHEN** client creates conversation rows with `turnIndex` values `0` and `2` (a gap) under the same `conversationId`
- **THEN** system SHALL accept both writes (HTTP 201) without a contiguity error; gap detection is deferred to snapshot/run time

#### Scenario: data validated uniformly as scalars
- **WHEN** a conversation-turn test case is created or replaced
- **THEN** system SHALL validate its `data` against the dataset's `testCaseSchema` exactly as for a single-turn row (scalar values per schema type); no array-shape-per-column interpretation applies to multi-turn rows

### Requirement: Conversation fields exposed on TestCase DTOs
The `conversationId` and `turnIndex` fields SHALL be present on `TestCaseRequestDto`, `TestCaseResponseDto`, and `TestCaseBatchPutItemDto`. On response DTOs they SHALL be `null` for single-turn rows. The bulk-patch whitelist SHALL remain `{testCaseName, data}` — `conversationId` and `turnIndex` are NOT patchable via bulk PATCH (nor via the single-case merge patch, which already restricts patchable fields to `{testCaseName, data}`).
Status: **Planned**

#### Scenario: Response includes conversation fields
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-cases/{testCaseId}` for a conversation-turn row
- **THEN** the response SHALL include `conversationId` and `turnIndex` with their stored values

#### Scenario: Single-turn response shows null fields
- **WHEN** client fetches a single-turn TestCase
- **THEN** the response SHALL carry `conversationId = null` and `turnIndex = null`

#### Scenario: Batch PUT item carries conversation fields
- **WHEN** client submits a batch-put item with `conversationId` and `turnIndex`
- **THEN** system SHALL persist the item as a conversation-turn row subject to the same per-row validation (both-or-neither, UUID form, turn bounds)

#### Scenario: Bulk patch cannot change conversation fields
- **WHEN** client submits a bulk PATCH body containing `conversationId` or `turnIndex`
- **THEN** system SHALL NOT apply those fields (whitelist is `{testCaseName, data}`); the stored `conversationId`/`turnIndex` SHALL remain unchanged

### Requirement: CSV import/export reserved conversation columns
CSV import and export SHALL recognize `conversationId` and `turnIndex` as reserved column headers using the same reserved-column mechanism as `testCaseName` (the `TEST_CASE_NAME_HEADER` pattern). Reserved columns map to the corresponding top-level TestCase columns and SHALL NOT be placed into the `data` map or contribute to schema auto-detection. All non-reserved columns continue to map into `data`. Import applies the same per-row validation as the API (both-or-neither, valid UUID, turn bounds) and reports violations per row; export emits `conversationId` and `turnIndex` columns alongside `testCaseName`.
Status: **Planned**

#### Scenario: Import maps reserved conversation columns to top-level columns
- **WHEN** client imports a CSV whose header contains `testCaseName`, `conversationId`, `turnIndex`, and data columns
- **THEN** system SHALL populate the top-level `conversationId`/`turnIndex` columns from the reserved headers and map only the remaining columns into `data`

#### Scenario: Reserved columns excluded from schema auto-detection
- **WHEN** a CSV with `conversationId`/`turnIndex` headers is imported into a dataset with empty schema
- **THEN** the auto-detected `testCaseSchema` SHALL NOT contain fields named `conversationId` or `turnIndex`

#### Scenario: Import per-row validation of conversation columns
- **WHEN** a CSV row has a `conversationId` value but a blank `turnIndex` (or a malformed UUID, or an out-of-range `turnIndex`)
- **THEN** system SHALL treat that row as invalid per the per-row validation rules (HTTP 400 / row error, per the import's existing error-reporting contract)

#### Scenario: Export includes reserved conversation columns
- **WHEN** client exports test cases via `GET /api/v1/datasets/{datasetId}/test-cases/export.csv`
- **THEN** the CSV SHALL include `conversationId` and `turnIndex` columns (empty for single-turn rows) in addition to `testCaseName`, and non-reserved data columns follow schema order

### Requirement: Per-turn uniqueness within a conversation
In addition to the existing case-insensitive `(dataset_id, LOWER(test_case_name))` uniqueness (which is unchanged — every turn still needs a globally distinct name within its dataset), the service SHALL enforce a defensive per-turn uniqueness on `(dataset_id, conversation_id, turn_index)` for grouped rows (i.e. where `conversation_id IS NOT NULL`). A create, replace, or import that would produce a duplicate `turnIndex` within the same `(dataset_id, conversationId)` SHALL be rejected with HTTP 409.
Status: **Planned**

#### Scenario: Duplicate turn index rejected
- **WHEN** a conversation already has a row at `turnIndex = 1` and client creates another row with the same `conversationId` and `turnIndex = 1`
- **THEN** system SHALL respond with HTTP 409

#### Scenario: Distinct turn indices in same conversation succeed
- **WHEN** client creates rows with the same `conversationId` and distinct `turnIndex` values `0`, `1`, `2`
- **THEN** system SHALL create all three rows successfully

#### Scenario: Existing name uniqueness unaffected
- **WHEN** two rows in the same conversation are created with distinct `turnIndex` values but the same `testCaseName`
- **THEN** system SHALL reject the second with HTTP 409 under the existing `(dataset_id, LOWER(test_case_name))` constraint (each turn requires a distinct name)

## Implementation notes
- `TestCaseController` — accepts/returns `conversationId`/`turnIndex` on create, replace, get, and list; bulk-patch payload binding stays limited to `{testCaseName, data}`.
- `TestCaseRequestDto` / `TestCaseResponseDto` / `TestCaseBatchPutItemDto` — add the two top-level fields (`conversationId`, `turnIndex`); response DTOs surface `null` for single-turn rows.
- `TestCaseValidationService` — per-row checks: both-or-neither, UUID well-formedness, `0 <= turnIndex < MAX_CONVERSATION_TURNS`; contiguity/completeness explicitly out of scope here.
- `CsvImportService` — extend the reserved-column set beyond `TEST_CASE_NAME_HEADER` to include `conversationId` and `turnIndex`; reserved headers route to top-level columns, are excluded from `data` and schema auto-detection, and are validated per row on import/export.
