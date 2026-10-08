## ADDED Requirements

### Requirement: Dataset schema field identity
Every field of a persisted dataset `testCaseSchema` SHALL carry a server-assigned `id` (UUID string). The `id` identifies a field within one dataset schema only; it is NOT a key for test-case data, bindings, filters, or CSV/ZIP columns — `name` remains the field's key everywhere. Clients SHALL NOT mint ids: on every schema write the server resolves each incoming field's `id` as follows:
- a field carrying an `id` keeps it, and that `id` MUST belong to a field of the dataset's current schema;
- a field without an `id` is the same field as the current-schema field with the exactly equal `name` (case-sensitive), unless that current field is already claimed by another entry of the same request; it takes that field's `id` (or a newly generated one when the stored field has none) and is not a removal;
- otherwise the field is new and receives a newly generated `id`.

Field names MUST be unique within a request (case-insensitive), with or without ids. A current field is **removed** when no request entry resolves to it, and **renamed** when its `id` resolves to an entry with a different `name`.

The `id` SHALL be returned on every `testCaseSchema` entry of `DatasetResponseDto`. Run snapshots SHALL carry the field `id` as captured; snapshots written before this requirement carry none and SHALL remain readable.
Status: **Planned**

#### Scenario: Create assigns ids
- **WHEN** a client creates a dataset with `testCaseSchema: [{name: "prompt", type: "STRING"}]`
- **THEN** the response's `testCaseSchema[0].id` SHALL be a UUID string and a subsequent `GET` SHALL return the same `id`

#### Scenario: Create rejects client-supplied id
- **WHEN** a client creates a dataset with any `testCaseSchema` entry carrying an `id`
- **THEN** the system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`; no dataset SHALL be persisted

#### Scenario: Update rejects unknown id
- **WHEN** a client updates a dataset with a `testCaseSchema` entry whose `id` is not the `id` of any field in the dataset's current schema
- **THEN** the system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`; neither the schema nor any test case SHALL change

#### Scenario: Update rejects duplicate id
- **WHEN** a client updates a dataset with two `testCaseSchema` entries carrying the same `id`
- **THEN** the system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`; neither the schema nor any test case SHALL change

#### Scenario: Field without id is matched by name
- **WHEN** the current schema has `{id: A, name: "prompt"}` and a client updates the dataset with `[{name: "prompt", type: "STRING"}]` (no `id`)
- **THEN** the persisted field SHALL keep `id: A` and no test-case data SHALL be removed

#### Scenario: Name match does not steal a claimed id
- **WHEN** the current schema has `{id: A, name: "a"}` and a client sends `[{id: A, name: "b"}, {name: "a"}]`
- **THEN** `b` SHALL keep `id: A` (a rename of `a`) and the second entry SHALL be a new field with a freshly generated `id`

#### Scenario: Update rejects duplicate names
- **WHEN** a client updates a dataset with `[{id: A, name: "x"}, {id: B, name: "X"}]`
- **THEN** the system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`; neither the schema nor any test case SHALL change

#### Scenario: Stored field without id is matched by name
- **WHEN** a stored schema field `prompt` carries no `id` and a client updates the dataset with `[{name: "prompt", type: "STRING"}]`
- **THEN** the persisted `prompt` SHALL carry a newly generated `id` and no test-case data SHALL be removed

#### Scenario: Run snapshot carries field ids
- **WHEN** a run is started for a suite bound to a dataset whose schema fields carry ids
- **THEN** the run's snapshot `testCaseSchema` SHALL contain the same `id` on every field

#### Scenario: New field gets a fresh id
- **WHEN** a client updates a dataset adding `{name: "newColumn", type: "STRING"}` (no `id`, no same-named current field)
- **THEN** the persisted `newColumn` SHALL carry a newly generated `id` distinct from every other field's `id`

### Requirement: Dataset schema field rename preserves data
On dataset update, a request field whose `id` equals a current-schema field's `id` but whose `name` differs SHALL be treated as a **rename** of that field. Before the update completes, the system SHALL move every value stored under the old name to the new name, in the shared `data` map and in every element of `multiTurnData`, for every test case of the dataset. All renames of one request SHALL be applied as one simultaneous mapping, so swaps (`a→b`, `b→a`) and chains (`a→b`, `b→c`) preserve every value. A rename SHALL NOT relocate a value between `data` and `multiTurnData`, even when the same request flips the field's `perTurn`; misplacement is reported by revalidation, as for any `perTurn` change. A rename that also changes the field's `type` SHALL move the values and leave coercion to revalidation.
Status: **Planned**

#### Scenario: Rename keeps the column's values
- **WHEN** the schema is `[{id: A, name: "column1"}, {id: B, name: "column2"}]`, a test case has `data: {column1: "x", column2: "y"}`, and a client updates the schema to `[{id: A, name: "column1"}, {id: B, name: "column3"}]`
- **THEN** the test case's `data` SHALL be `{column1: "x", column3: "y"}`

#### Scenario: Rename moves per-turn values in every turn
- **WHEN** field `{id: P, name: "q", perTurn: true}` is renamed to `question` and a test case has `multiTurnData: [{q: "1"}, {q: "2"}]`
- **THEN** the test case's `multiTurnData` SHALL be `[{question: "1"}, {question: "2"}]`

#### Scenario: Swap preserves both values
- **WHEN** fields `{id: A, name: "a"}` and `{id: B, name: "b"}` are updated to `{id: A, name: "b"}` and `{id: B, name: "a"}`, and a test case has `data: {a: 1, b: 2}`
- **THEN** the test case's `data` SHALL be `{b: 1, a: 2}`

#### Scenario: Rename with perTurn flip does not relocate
- **WHEN** shared field `{id: A, name: "ctx"}` is renamed to `context` and flipped to `perTurn: true` in the same request, and a multi-turn test case has `data: {ctx: "v"}`
- **THEN** the test case SHALL have `data: {context: "v"}` with `multiTurnData` unchanged, and revalidation SHALL report `context` as misplaced

#### Scenario: Rename with type change is coerced by revalidation
- **WHEN** field `{id: A, name: "score", type: "STRING"}` is renamed to `points` with `type: "INTEGER"` and a test case has `data: {score: "5"}`
- **THEN** the test case SHALL end with `data: {points: 5}` once the update's revalidation has run

#### Scenario: Old client without ids renames as delete + create
- **WHEN** a client sends the renamed field without an `id` (`[{name: "column3"}]` replacing `column2`)
- **THEN** the system SHALL treat it as removal of `column2` plus a new field `column3`, exactly as before field ids existed

### Requirement: Field rename restricted on shared datasets
A dataset update containing at least one rename (see "Dataset schema field rename preserves data") SHALL be allowed only when the dataset is `PRIVATE`, or is `PUBLIC` with no suite bound to it. A rename on a `PUBLIC` dataset with one or more bound suites SHALL be rejected, because renaming would silently invalidate suites owned by other users. A suite bound concurrently with the update SHALL NOT slip past this check. Updates without renames (adding, removing, or re-typing fields; metadata edits) are unaffected by this requirement.
Status: **Planned**

#### Scenario: Rename on PRIVATE dataset succeeds
- **WHEN** a client renames a field of a `PRIVATE` dataset bound to its suite
- **THEN** the update SHALL succeed, the data SHALL be moved, and the bound suite's validity SHALL reflect the renamed schema when the response is returned

#### Scenario: Rename on unbound PUBLIC dataset succeeds
- **WHEN** a client renames a field of a `PUBLIC` dataset that no suite references
- **THEN** the update SHALL succeed and the data SHALL be moved

#### Scenario: Rename on bound PUBLIC dataset is rejected
- **WHEN** a client renames a field of a `PUBLIC` dataset referenced by at least one suite
- **THEN** the system SHALL respond with HTTP 409 and error code `DATASET_FIELD_RENAME_FORBIDDEN`; neither the schema, the dataset version, nor any test case SHALL change

#### Scenario: Non-rename edit on bound PUBLIC dataset is allowed
- **WHEN** a client adds or removes a field of a `PUBLIC` dataset referenced by suites, without renaming any field
- **THEN** the update SHALL proceed as before this requirement

## MODIFIED Requirements

### Requirement: Dataset CRUD endpoints
The system SHALL provide CRUD endpoints for the `Dataset` entity under `/api/v1/datasets`. Dataset is the system of record for `testCaseSchema` and owns the collection of test cases under it. Every dataset carries a `visibility` enum (`PUBLIC` | `PRIVATE`) that is required on create, ignored on update via `PUT`, and changed only via the dedicated `PATCH /api/v1/datasets/{id}/visibility` endpoint.
Status: **Planned**

#### Scenario: List datasets (paginated)
- **WHEN** client calls `GET /api/v1/datasets`
- **THEN** system SHALL return a paginated list of `DatasetResponseDto` items hard-filtered to `visibility = 'PUBLIC'`; default page=0, size=100, max size 1000; default sort `createdAt,desc`; supports `filter`/`sort`/`includeTotalCount` per entity-filtering spec; PRIVATE datasets SHALL NOT appear regardless of client-supplied filters

#### Scenario: Sort datasets by version
- **WHEN** client calls `GET /api/v1/datasets?sort=version,desc`
- **THEN** system SHALL return datasets ordered by `version` descending; the `version` field is registered in `SortWhitelists` for datasets

#### Scenario: Filter datasets by name substring
- **WHEN** client calls `GET /api/v1/datasets?filter=name:co:customer`
- **THEN** system SHALL return only datasets whose `name` contains the substring `customer` (case-sensitivity follows the underlying `CO` operator semantics defined in the entity-filtering spec)

#### Scenario: Filter datasets by updatedAt range
- **WHEN** client calls `GET /api/v1/datasets?filter=updatedAt:gte:1700000000000&filter=updatedAt:lte:1800000000000`
- **THEN** system SHALL return only datasets whose `updatedAt` lies within the inclusive epoch-ms range

#### Scenario: Filter datasets by createdBy
- **WHEN** client calls `GET /api/v1/datasets?filter=createdBy:eq:alice@example.com`
- **THEN** system SHALL return only datasets whose `createdBy` equals the supplied value

#### Scenario: Get dataset by id
- **WHEN** client calls `GET /api/v1/datasets/{id}` with a valid id (PUBLIC or PRIVATE)
- **THEN** system SHALL return `DatasetResponseDto` with an `ETag` header carrying the entity's `version`; visibility SHALL NOT block retrieval

#### Scenario: Dataset not found
- **WHEN** client calls `GET /api/v1/datasets/{id}` for an unknown id
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`

#### Scenario: Create PUBLIC dataset
- **WHEN** client calls `POST /api/v1/datasets` with a valid `DatasetRequestDto` carrying `visibility: "PUBLIC"` and no `bindToSuiteId`
- **THEN** system SHALL create the dataset; assign a fresh UUID; set `version = 1`; set `visibility = 'PUBLIC'`; set `createdBy` from JWT subject (or `"anonymous"` in no-security mode); set `createdAt`/`updatedAt` from the injected `Clock`; return HTTP 201 with `DatasetResponseDto` and `ETag` header

#### Scenario: Create PRIVATE dataset with atomic suite binding
- **WHEN** client calls `POST /api/v1/datasets` with `visibility: "PRIVATE"` and a valid `bindToSuiteId` referencing an existing suite
- **THEN** system SHALL — in a single transaction — create the dataset (`visibility = 'PRIVATE'`, `version = 1`) and update the target suite's `dataset_id` to the new dataset's id; return HTTP 201 with `DatasetResponseDto` and `ETag` header (see "Atomic create-and-bind for PRIVATE datasets" requirement for the full set of error scenarios)

#### Scenario: Update dataset (metadata only)
- **WHEN** client calls `PUT /api/v1/datasets/{id}` with `If-Match: <version>` header and a body where `testCaseSchema` is unchanged compared to the stored value
- **THEN** system SHALL update the dataset (ignoring any `visibility` field in the body), bump `version`, return HTTP 200 with the new `DatasetResponseDto` and updated `ETag`; the persisted `visibility` SHALL remain unchanged

#### Scenario: Update dataset (schema change)
- **WHEN** client calls `PUT /api/v1/datasets/{id}` with `If-Match: <version>` and a body where `testCaseSchema` differs from the stored value
- **THEN** system SHALL update the dataset (ignoring any `visibility` field in the body), bump `version`, prune any data fields removed from the schema from every TestCase in the dataset, rename the data keys of renamed fields (see "Dataset schema field rename preserves data"), run the `RevalidationTask` rooted at this dataset synchronously (see "Dataset-rooted RevalidationTask trigger"), and return HTTP 202 with `RevalidationTaskDto` (status `PENDING` as created); the persisted `visibility` SHALL remain unchanged

#### Scenario: Optimistic concurrency conflict
- **WHEN** client calls `PUT /api/v1/datasets/{id}` with an `If-Match` value that does not match the current `version`
- **THEN** system SHALL respond with HTTP 409 and error code `VERSION_CONFLICT`

#### Scenario: Missing If-Match header
- **WHEN** client calls `PUT /api/v1/datasets/{id}` without an `If-Match` header
- **THEN** system SHALL respond with HTTP 428 (or 400 per project convention) and error code `VALIDATION_ERROR`

#### Scenario: Delete PUBLIC dataset with no dependents
- **WHEN** client calls `DELETE /api/v1/datasets/{id}` on a `PUBLIC` dataset and no `TestSuite` references this dataset
- **THEN** system SHALL delete the dataset and (via `ON DELETE CASCADE`) all its test cases; return HTTP 204

#### Scenario: Delete PUBLIC dataset rejected by RESTRICT
- **WHEN** client calls `DELETE /api/v1/datasets/{id}` on a `PUBLIC` dataset and one or more `TestSuite` rows reference this dataset
- **THEN** system SHALL respond with HTTP 409 and error code `UNIQUE_CONSTRAINT_VIOLATION` (or a dedicated `DATASET_IN_USE` error code if added in implementation); response body SHALL list the dependent suite IDs and names

#### Scenario: Delete PRIVATE dataset atomically unbinds and removes
- **WHEN** client calls `DELETE /api/v1/datasets/{id}` on a `PRIVATE` dataset (which by invariant has exactly one bound suite at deletion time, or zero if the suite was deleted earlier in the same transaction)
- **THEN** system SHALL atomically set the bound suite's `dataset_id := NULL` and delete the dataset row (test cases cascade); return HTTP 204 (see "PRIVATE dataset delete atomically unbinds and removes" requirement for atomicity and error scenarios)

### Requirement: Dataset testCaseSchema structure and validation
The system SHALL validate the `testCaseSchema` on every dataset create/update: schema is a list of `FieldDefinitionDto` entries where each entry's `name` is non-blank, unique within the schema (case-insensitive), at most 255 characters, and matches the identifier pattern that prohibits the `:` character; `type` is one of `STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`, `OBJECT`, `ARRAY`, `FILE`; `displayName` is at most 255 characters; `description` is at most 2000 characters; `required` is a boolean; `perTurn` is a boolean (default `false`) that marks the field's **scope** — `true` = per-turn (the field's value may vary between turns of a multi-turn case and lives in each `multiTurnData[i]` map), `false`/absent = shared (test-case-level, constant across turns, lives in the `data` map). Scope is a schema-level declaration and applies uniformly to every test case in the dataset. A missing `perTurn` SHALL be treated as `false`, so schemas authored before this field are unchanged. `id` is optional on input and governed by the "Dataset schema field identity" requirement.
Status: **Planned**

#### Scenario: Empty schema accepted
- **WHEN** client creates a dataset with `testCaseSchema: []`
- **THEN** the request SHALL succeed and the dataset stores an empty schema

#### Scenario: Duplicate field name (case-insensitive)
- **WHEN** client sends a `testCaseSchema` with two fields named `"prompt"` and `"Prompt"`
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: Field name contains colon
- **WHEN** client sends a field with `name: "foo:bar"`
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR` because `:` is reserved as the filter operator separator

#### Scenario: Unknown field type
- **WHEN** client sends a field with `type: "TIMESTAMP"`
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: Field exceeds max length
- **WHEN** client sends a field with `name` longer than 255 characters or `description` longer than 2000 characters
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

#### Scenario: perTurn defaults to shared when absent
- **WHEN** client sends a field with no `perTurn` attribute
- **THEN** the field SHALL be persisted and treated as shared (`perTurn=false`), and existing pre-change schemas SHALL behave identically to before

#### Scenario: perTurn marks a field per-turn
- **WHEN** client sends a field with `perTurn: true`
- **THEN** the request SHALL succeed and that field's values SHALL be expected in each turn's `multiTurnData[i]` map (not in the shared `data` map) for multi-turn cases in this dataset

### Requirement: Schema-driven data cleanup on dataset schema change
When a dataset PUT removes one or more fields from `testCaseSchema`, the system SHALL strip those keys from the `data` map and from every `multiTurnData` element of every TestCase under the dataset before completing the update. Removal is determined per "Dataset schema field identity"; a renamed field is not removed. This cleanup runs synchronously within the dataset update transaction so that no TestCase carries orphan fields by the time the dataset PUT returns.
Status: **Planned**

#### Scenario: Schema field removal prunes orphan data
- **WHEN** client updates a dataset removing field `legacyColumn` from `testCaseSchema`
- **THEN** every TestCase whose `data` contained `legacyColumn` SHALL have that key removed from `data` before the dataset PUT returns

#### Scenario: Renamed field is not pruned
- **WHEN** client updates a dataset sending field `{id: B, name: "column3"}` where the current schema has `{id: B, name: "column2"}`
- **THEN** no value SHALL be pruned; the values under `column2` SHALL be available under `column3`

#### Scenario: Schema field addition does not modify test case data
- **WHEN** client updates a dataset adding a new field `newColumn` (required=false)
- **THEN** existing TestCases' `data` maps SHALL remain unchanged; subsequent Phase-1 revalidation may emit warnings for required fields but does not synthesize values

#### Scenario: Schema field type change leaves data untouched at cleanup phase
- **WHEN** client updates a dataset changing field `score` from `INTEGER` to `STRING`
- **THEN** the cleanup phase SHALL NOT modify TestCase data; Phase 1 of the spawned RevalidationTask invokes `SchemaChangeCoercer` to coerce existing values per the rules defined in the `test-cases` spec

### Requirement: Dataset-rooted RevalidationTask trigger
The system SHALL run a `RevalidationTask` exactly when a dataset PUT mutates `testCaseSchema` (any difference in the stored field list — e.g. name, type, required flag, displayName, description, `perTurn`, a newly assigned field `id`, or ordering). Dataset PUTs that change only `name` or `description` SHALL NOT run a task. The task SHALL run synchronously within the dataset update — both phases (test-case coercion/validation and refresh of every referencing suite's validity) complete before the PUT returns. The dataset PUT response is HTTP 202 with `RevalidationTaskDto` when a task is run, HTTP 200 with `DatasetResponseDto` otherwise.
Status: **Planned**

#### Scenario: Metadata-only edit returns 200
- **WHEN** client updates a dataset changing `description` only
- **THEN** system SHALL respond with HTTP 200 and return `DatasetResponseDto` with bumped `version` and updated `ETag`; no `RevalidationTask` is spawned

#### Scenario: Schema edit returns 202 with task
- **WHEN** client updates a dataset adding a new field to `testCaseSchema`
- **THEN** system SHALL respond with HTTP 202 and return `RevalidationTaskDto` with `status: PENDING`; the task is rooted at the dataset (FK `dataset_id`)

#### Scenario: Revalidation results visible when the PUT returns
- **WHEN** a client's schema-changing PUT on a dataset bound to a suite returns
- **THEN** the persisted task row SHALL already be in a terminal status and the bound suite's `isValid` / `validationWarnings` SHALL already reflect the new schema

#### Scenario: Concurrent dataset PUT while task is RUNNING
- **WHEN** two schema-changing PUTs for the same dataset arrive concurrently, each carrying the dataset's then-current version
- **THEN** exactly one SHALL succeed with its own task; the other SHALL be rejected with HTTP 409 `VERSION_CONFLICT` and SHALL change nothing

#### Scenario: RevalidationTaskDto JSON wire shape
- **WHEN** a client receives a `RevalidationTaskDto` (from POST/PUT /datasets/{id} returning 202, or from GET /datasets/{id}/revalidation-tasks*)
- **THEN** the JSON SHALL include `datasetId` (UUID) and SHALL NOT include `testSuiteId`; the prior `testSuiteId` wire field is removed without alias to make the breaking rename explicit

#### Scenario: PUT /datasets/{id} schema-edit returns 202 with datasetId-rooted task
- **WHEN** client calls `PUT /api/v1/datasets/{id}` with a body whose `testCaseSchema` differs from the stored value
- **THEN** system SHALL respond with HTTP 202; the response body SHALL be a `RevalidationTaskDto` JSON containing `"datasetId": "<id>"` matching the path parameter and SHALL NOT contain a `testSuiteId` field at all (not even as `null`); the `task.status` value SHALL be `"PENDING"`

#### Scenario: POST /datasets/{id}/test-cases CSV import returns 202 with datasetId-rooted task
- **WHEN** client calls `POST /api/v1/datasets/{id}/test-cases/import` (or `.../import.csv`) with an importMode that changes the dataset's schema
- **THEN** a dataset-rooted task (`dataset_id` = the path id, no `testSuiteId`) SHALL be recorded and listed by `GET /api/v1/datasets/{id}/revalidation-tasks`; the import response itself carries import counts only (see the `test-cases` requirement "Import persists the schema through the dataset update rules")
