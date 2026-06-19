# Datasets

## Purpose

This spec defines the `Dataset` entity — the centralized container for test case shape (`testCaseSchema`) and test case data. Datasets replace TestSuite as the system of record for these concerns: test suites become consumers of a dataset (many suites may share one dataset), and edits to test case data propagate to every suite that references the dataset.

Status: **Planned**

## Key Terms
- **Dataset**: An entity owning a `testCaseSchema` (list of `FieldDefinitionDto`) and the collection of `TestCase` rows that conform to that schema. Identity: `id` (UUID), `name` (globally unique on `LOWER(name)`).
- **testCaseSchema**: A JSONB list of `FieldDefinitionDto` (name, displayName, type ∈ `STRING`/`INTEGER`/`NUMBER`/`BOOLEAN`/`OBJECT`/`ARRAY`/`FILE`, required, description). Defines the shape every TestCase's `data` map must conform to. Owned by Dataset, sourced by TestSuite via `DatasetSchemaProvider` at validation/snapshot time.
- **DatasetSchemaProvider**: An injectable `@Component` returning `List<FieldDefinitionDto>` for a given `datasetId`. Used to break the would-be circular dependency between `DatasetService` and `TestSuiteService`.
- **Dataset-rooted RevalidationTask**: The async task spawned by a dataset PUT that mutates `testCaseSchema`. Runs two phases — Phase 1 (test cases, fail-fast) and Phase 2 (dependent suites, per-suite resilient). See `test-cases` spec for Phase 1 semantics and `test-suites` spec for Phase 2 semantics.

## Requirements

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
- **THEN** system SHALL update the dataset (ignoring any `visibility` field in the body), bump `version`, prune any data fields removed from the schema from every TestCase in the dataset, spawn an async `RevalidationTask` rooted at this dataset, and return HTTP 202 with `RevalidationTaskDto` (status `PENDING`); the persisted `visibility` SHALL remain unchanged

#### Scenario: Optimistic concurrency conflict
- **WHEN** client calls `PUT /api/v1/datasets/{id}` with an `If-Match` value that does not match the current `version`
- **THEN** system SHALL respond with HTTP 412 and error code `VERSION_CONFLICT`

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

### Requirement: Dataset visibility field
Every `Dataset` SHALL carry a `visibility` enum field with exactly two values: `PUBLIC` and `PRIVATE`. The field SHALL be stored in `datasets.visibility` as `VARCHAR(16) NOT NULL` with a `CHECK (visibility IN ('PUBLIC','PRIVATE'))` constraint, and SHALL be surfaced on every `DatasetResponseDto`.
Status: **Planned**

#### Scenario: DatasetResponseDto includes visibility
- **WHEN** client receives a `DatasetResponseDto`
- **THEN** the payload SHALL include `visibility` whose value is either `"PUBLIC"` or `"PRIVATE"`

#### Scenario: visibility is required on create
- **WHEN** client calls `POST /api/v1/datasets` with a body that omits `visibility` (or sends it as null)
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`; the dataset SHALL NOT be persisted

#### Scenario: visibility ignored on update
- **WHEN** client calls `PUT /api/v1/datasets/{id}` with a body whose `visibility` differs from the stored value (and only `visibility` differs)
- **THEN** system SHALL accept the request and persist all other fields unchanged; the stored `visibility` SHALL remain unchanged; the response SHALL reflect the stored (unchanged) `visibility`

#### Scenario: Invalid visibility value rejected
- **WHEN** client calls `POST /api/v1/datasets` with `visibility: "INTERNAL"` (or any value outside the enum)
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

### Requirement: List datasets hard-filters by visibility
`GET /api/v1/datasets` SHALL hard-filter results server-side to `visibility = 'PUBLIC'`. PRIVATE datasets SHALL NOT appear in any list response regardless of client-supplied filter parameters. The `visibility` field SHALL NOT be registered in `FilterWhitelists` for datasets; filters that name `visibility` SHALL be rejected. Direct retrieval via `GET /api/v1/datasets/{id}` SHALL NOT enforce visibility — any authenticated caller may fetch a PRIVATE dataset by id.
Status: **Planned**

#### Scenario: List excludes PRIVATE datasets
- **WHEN** client calls `GET /api/v1/datasets`
- **THEN** the response SHALL contain only datasets with `visibility = 'PUBLIC'`; PRIVATE datasets SHALL NOT appear in the response

#### Scenario: Client-supplied visibility filter is rejected
- **WHEN** client calls `GET /api/v1/datasets?filter=visibility:eq:PRIVATE`
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR` (field `visibility` not in the dataset filter whitelist)

#### Scenario: Get PRIVATE dataset by id succeeds
- **WHEN** client calls `GET /api/v1/datasets/{id}` for a dataset whose `visibility = 'PRIVATE'`
- **THEN** system SHALL return HTTP 200 with the `DatasetResponseDto` carrying `visibility: "PRIVATE"`; access is not blocked by visibility (visibility is a catalogue-versus-scratch distinction, not access control)

### Requirement: Atomic create-and-bind for PRIVATE datasets
`POST /api/v1/datasets` SHALL accept an optional `bindToSuiteId` field. The field SHALL be **required** when `visibility = "PRIVATE"` (missing → HTTP 400 `PRIVATE_DATASET_REQUIRES_SUITE_BINDING`) and **forbidden** when `visibility = "PUBLIC"` (present → HTTP 400 `PUBLIC_DATASET_FORBIDS_SUITE_BINDING`). When supplied, the server SHALL — within a single meta transaction — insert the new dataset row AND update the named suite's `dataset_id` to the new dataset's id. The transaction SHALL fail atomically (no orphan dataset, no partial state) if the target suite does not exist, is already bound to a different PRIVATE dataset, or any other guard fires.
Status: **Planned**

#### Scenario: PRIVATE create without bindToSuiteId rejected
- **WHEN** client calls `POST /api/v1/datasets` with `visibility: "PRIVATE"` and no `bindToSuiteId`
- **THEN** system SHALL respond with HTTP 400 and error code `PRIVATE_DATASET_REQUIRES_SUITE_BINDING`; the dataset SHALL NOT be persisted

#### Scenario: PUBLIC create with bindToSuiteId rejected
- **WHEN** client calls `POST /api/v1/datasets` with `visibility: "PUBLIC"` and a non-null `bindToSuiteId`
- **THEN** system SHALL respond with HTTP 400 and error code `PUBLIC_DATASET_FORBIDS_SUITE_BINDING`; the dataset SHALL NOT be persisted

#### Scenario: PRIVATE create-and-bind succeeds atomically
- **WHEN** client calls `POST /api/v1/datasets` with `visibility: "PRIVATE"` and `bindToSuiteId: "<suite-uuid>"` referencing an existing unbound (or any rebindable) suite
- **THEN** system SHALL create the dataset, update `test_suites.dataset_id = <new-dataset-id>` for the named suite, and return HTTP 201 with `DatasetResponseDto`. Both writes SHALL commit in a single transaction; neither one alone is observable to other readers.

#### Scenario: PRIVATE create-and-bind to unknown suite fails atomically
- **WHEN** client calls `POST /api/v1/datasets` with `visibility: "PRIVATE"` and `bindToSuiteId` referencing a non-existent suite id
- **THEN** system SHALL respond with HTTP 404 (or HTTP 400 per FK pre-check convention); no dataset row SHALL be persisted

#### Scenario: PRIVATE create-and-bind to suite already bound to a PRIVATE dataset fails
- **WHEN** client calls `POST /api/v1/datasets` with `visibility: "PRIVATE"` and `bindToSuiteId` referencing a suite already bound to another PRIVATE dataset
- **THEN** system SHALL respond with HTTP 409 and error code `PRIVATE_DATASET_REBIND_FORBIDDEN`; no dataset row SHALL be persisted

### Requirement: Dataset visibility transition endpoint
The system SHALL provide `PATCH /api/v1/datasets/{id}/visibility` to transition a dataset between `PUBLIC` and `PRIVATE`. The endpoint accepts a body `{ "visibility": "PUBLIC" | "PRIVATE" }`. The service SHALL `SELECT ... FOR UPDATE` the datasets row and count its suite bindings under the same lock before applying the transition. `PRIVATE → PUBLIC` SHALL always succeed. `PUBLIC → PRIVATE` SHALL succeed only when the dataset has **exactly one** bound suite; otherwise HTTP 409 `PRIVATE_TRANSITION_INVALID_BINDING_COUNT`. The same visibility (no-op) SHALL return HTTP 200 with the current dataset (idempotent). `PUT /api/v1/datasets/{id}` SHALL NOT perform visibility transitions; the dedicated PATCH endpoint is the only way to change `visibility`.
Status: **Planned**

#### Scenario: PRIVATE to PUBLIC transition succeeds
- **WHEN** client calls `PATCH /api/v1/datasets/{id}/visibility` with body `{"visibility":"PUBLIC"}` on a dataset currently `PRIVATE` (with any binding count)
- **THEN** system SHALL update `visibility` to `PUBLIC`, bump `version`, and return HTTP 200 with the updated `DatasetResponseDto`

#### Scenario: PUBLIC to PRIVATE with exactly one binding succeeds
- **WHEN** client calls `PATCH /api/v1/datasets/{id}/visibility` with body `{"visibility":"PRIVATE"}` on a `PUBLIC` dataset bound to exactly one suite
- **THEN** system SHALL update `visibility` to `PRIVATE`, bump `version`, and return HTTP 200 with the updated `DatasetResponseDto`

#### Scenario: PUBLIC to PRIVATE with zero bindings rejected
- **WHEN** client calls `PATCH /api/v1/datasets/{id}/visibility` with body `{"visibility":"PRIVATE"}` on a `PUBLIC` dataset with no bound suites
- **THEN** system SHALL respond with HTTP 409 and error code `PRIVATE_TRANSITION_INVALID_BINDING_COUNT`; visibility SHALL NOT change

#### Scenario: PUBLIC to PRIVATE with multiple bindings rejected
- **WHEN** client calls `PATCH /api/v1/datasets/{id}/visibility` with body `{"visibility":"PRIVATE"}` on a `PUBLIC` dataset bound to two or more suites
- **THEN** system SHALL respond with HTTP 409 and error code `PRIVATE_TRANSITION_INVALID_BINDING_COUNT`; visibility SHALL NOT change

#### Scenario: No-op transition is idempotent
- **WHEN** client calls `PATCH /api/v1/datasets/{id}/visibility` with a value equal to the dataset's current visibility
- **THEN** system SHALL return HTTP 200 with the unchanged `DatasetResponseDto`; `version` SHALL NOT be bumped

#### Scenario: PATCH on unknown dataset
- **WHEN** client calls `PATCH /api/v1/datasets/{id}/visibility` for an unknown id
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`

#### Scenario: Invalid visibility value in PATCH body
- **WHEN** client calls `PATCH /api/v1/datasets/{id}/visibility` with a body whose `visibility` is missing or outside the enum
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`

### Requirement: Concurrent PRIVATE-binding prevention
A PostgreSQL `BEFORE INSERT OR UPDATE OF dataset_id` constraint trigger on `test_suites` SHALL guarantee that no two suites can be concurrently bound to the same PRIVATE dataset. The trigger SHALL lock the referenced `datasets` row (`SELECT ... FOR UPDATE`), inspect `visibility`, count current bindings, and reject any second binding to a PRIVATE dataset by raising `ERRCODE='P0001'` with MESSAGE TEXT `'PRIVATE_DATASET_ALREADY_BOUND'`. The trigger SHALL early-return when `NEW.dataset_id IS NULL` so that unbind paths (rebind-to-null, PRIVATE-delete cascade) are not blocked. The global exception handler SHALL inspect `SQLException.getSQLState()` and map `'P0001'` to HTTP 409 with error code `PRIVATE_DATASET_ALREADY_BOUND`.
Status: **Planned**

#### Scenario: Concurrent second PRIVATE binding rejected
- **WHEN** two transactions concurrently set the same PRIVATE dataset's id as `dataset_id` on two different suites
- **THEN** exactly one transaction SHALL commit successfully; the other SHALL fail and the client SHALL receive HTTP 409 with error code `PRIVATE_DATASET_ALREADY_BOUND`

#### Scenario: Unbind path not blocked
- **WHEN** a suite bound to a PRIVATE dataset is rebound to `dataset_id = NULL` (e.g., via the PRIVATE-dataset delete cascade)
- **THEN** the trigger SHALL NOT raise; the update SHALL succeed

#### Scenario: Standard 23505 mapping untouched
- **WHEN** any other unique-constraint violation (PostgreSQL ERRCODE 23505) is raised in the application
- **THEN** the global handler SHALL continue to map it to HTTP 409 with error code `UNIQUE_CONSTRAINT_VIOLATION`; the new P0001 mapping SHALL NOT affect this path

### Requirement: PRIVATE dataset delete atomically unbinds and deletes
`DELETE /api/v1/datasets/{id}` on a `PRIVATE` dataset SHALL — within a single meta transaction — set `test_suites.dataset_id := NULL` for the bound suite and then delete the dataset row. Test cases under the dataset SHALL be cascade-deleted via the existing FK. After the operation completes, the previously-bound suite SHALL remain alive in an unbound state (see `test-suites` spec).
Status: **Planned**

#### Scenario: PRIVATE delete unbinds suite and removes dataset
- **WHEN** client calls `DELETE /api/v1/datasets/{id}` for a PRIVATE dataset bound to suite `S`
- **THEN** system SHALL respond with HTTP 204; the dataset row SHALL be removed; all of the dataset's test cases SHALL be removed (cascade); suite `S` SHALL still exist with `datasetId = null`

#### Scenario: PRIVATE delete is atomic
- **WHEN** any step of the PRIVATE delete path fails (unbind, test-case cascade, or the dataset delete itself)
- **THEN** the entire transaction SHALL roll back; the dataset, its test cases, and the suite's `dataset_id` SHALL all remain unchanged

#### Scenario: PRIVATE dataset not found
- **WHEN** client calls `DELETE /api/v1/datasets/{id}` for an unknown id
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`

### Requirement: Force delete dataset unbinds all referencing suites
`DELETE /api/v1/datasets/{id}` SHALL accept an optional `force` boolean query parameter (default `false`). When `force=true`, the system SHALL — within a single meta transaction — set `test_suites.dataset_id := NULL` for **every** suite referencing the dataset (regardless of count or dataset `visibility`), delete the dataset row, and cascade-delete the dataset's test cases via the existing FK; it SHALL return HTTP 204. The previously-bound suites SHALL remain alive in an unbound state. When `force` is omitted or `false`, the existing delete behavior SHALL be preserved unchanged: a `PUBLIC` dataset still referenced by one or more suites SHALL return HTTP 409 (RESTRICT), and a `PRIVATE` dataset SHALL unbind its single suite and delete as today.

#### Scenario: Force delete with one referencing suite
- **WHEN** client calls `DELETE /api/v1/datasets/{id}?force=true` on a dataset referenced by exactly one suite `S`
- **THEN** system SHALL respond with HTTP 204; the dataset row SHALL be removed; the dataset's test cases SHALL be removed (cascade); suite `S` SHALL still exist with `datasetId = null`

#### Scenario: Force delete with two referencing suites
- **WHEN** client calls `DELETE /api/v1/datasets/{id}?force=true` on a `PUBLIC` dataset referenced by two suites `S1` and `S2`
- **THEN** system SHALL respond with HTTP 204; the dataset row SHALL be removed; both `S1` and `S2` SHALL still exist with `datasetId = null`

#### Scenario: Default delete (force omitted) preserves RESTRICT
- **WHEN** client calls `DELETE /api/v1/datasets/{id}` (no `force`, or `force=false`) on a `PUBLIC` dataset referenced by one or more suites
- **THEN** system SHALL respond with HTTP 409 and list the dependent suite names, exactly as in the existing RESTRICT behavior; no suite SHALL be unbound and the dataset SHALL NOT be deleted

#### Scenario: Force delete is atomic
- **WHEN** any step of the `force=true` delete path fails (unbind of any suite, test-case cascade, or the dataset delete itself)
- **THEN** the entire transaction SHALL roll back; the dataset, its test cases, and every suite's `dataset_id` SHALL all remain unchanged

#### Scenario: Force delete of unknown dataset
- **WHEN** client calls `DELETE /api/v1/datasets/{id}?force=true` for an unknown id
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`

### Requirement: Dataset name uniqueness
The system SHALL enforce that `name` is globally unique on `LOWER(name)` across all datasets (case-insensitive).
Status: **Planned**

#### Scenario: Duplicate name on create
- **WHEN** client calls `POST /api/v1/datasets` with a `name` that already exists in another dataset (case-insensitive match)
- **THEN** system SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`, and message including the duplicated name

#### Scenario: Duplicate name on update
- **WHEN** client calls `PUT /api/v1/datasets/{id}` with a `name` that already exists in another dataset (case-insensitive match)
- **THEN** system SHALL respond with HTTP 409 and error code `UNIQUE_CONSTRAINT_VIOLATION`

#### Scenario: Case variation is duplicate
- **WHEN** a dataset named `"CustomerSupport"` exists and client creates one named `"customersupport"`
- **THEN** system SHALL respond with HTTP 409

### Requirement: Internal dataset cloning for suite clone
The system SHALL support creating a PRIVATE dataset as an independent clone of an existing dataset as an internal side effect of cloning a test suite (no public dataset-clone endpoint). The cloned dataset SHALL have `visibility = PRIVATE`, a unique name derived from the source dataset name, and SHALL copy the source dataset's `testCaseSchema`, `valid`, and `validationWarnings` verbatim along with all of its test cases. The clone-name derivation SHALL respect the existing dataset name uniqueness rule (`uq_datasets_name`, case-insensitive).
Status: **Planned**

#### Scenario: Cloned dataset is independent and PRIVATE
- **WHEN** a dataset is cloned during a suite clone
- **THEN** the new dataset SHALL have `visibility = PRIVATE` and SHALL be a separate row with its own id
- **AND** mutating or deleting the source dataset later SHALL NOT affect the cloned dataset or its test cases

#### Scenario: Cloned dataset name is unique
- **WHEN** the derived clone name would collide with an existing dataset name (case-insensitive)
- **THEN** system SHALL derive an alternative unique name rather than violating the uniqueness constraint, falling back to the existing `UNIQUE_CONSTRAINT_VIOLATION` (HTTP 409) only if a unique name cannot be established

### Requirement: Dataset request/response DTOs
The system SHALL expose `DatasetRequestDto` and `DatasetResponseDto` with the listed fields and validation rules. `DatasetRequestDto` is used for both create and update; the `visibility` field is required on create and ignored on update (handled in `DatasetService`).
Status: **Planned**

#### Scenario: DatasetRequestDto field validation
- **WHEN** client sends a `DatasetRequestDto`
- **THEN** the system SHALL validate: `name` (`@NotBlank @Size(max = 255)`), `description` (`@Size(max = 2000)`, nullable), `testCaseSchema` (list of `FieldDefinitionDto`, default `[]`, each entry conforming to the field-definition validation rules defined in this spec), `visibility` (`@NotNull` on create — one of `PUBLIC`/`PRIVATE`; ignored on update), `bindToSuiteId` (optional UUID — required when `visibility = PRIVATE`, forbidden when `visibility = PUBLIC`; see "Atomic create-and-bind for PRIVATE datasets" requirement)

#### Scenario: Dataset description exceeds max length
- **WHEN** client calls `POST /api/v1/datasets` or `PUT /api/v1/datasets/{id}` with a `description` longer than 2000 characters
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`; the dataset SHALL NOT be persisted or updated

#### Scenario: DatasetResponseDto fields
- **WHEN** client receives a `DatasetResponseDto`
- **THEN** the payload SHALL include `id` (UUID), `name`, `description`, `visibility` (one of `PUBLIC`/`PRIVATE`), `testCaseSchema` (list of `FieldDefinitionDto`), `isValid` (boolean), `validationWarnings` (list of structured warnings), `version` (Long), `createdBy`, `createdAt` (epoch ms), `updatedAt` (epoch ms)

### Requirement: Dataset testCaseSchema structure and validation
The system SHALL validate the `testCaseSchema` on every dataset create/update: schema is a list of `FieldDefinitionDto` entries where each entry's `name` is non-blank, unique within the schema (case-insensitive), at most 255 characters, and matches the identifier pattern that prohibits the `:` character; `type` is one of `STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`, `OBJECT`, `ARRAY`, `FILE`; `displayName` is at most 255 characters; `description` is at most 2000 characters; `required` is a boolean.
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

### Requirement: Schema-driven data cleanup on dataset schema change
When a dataset PUT removes one or more fields from `testCaseSchema`, the system SHALL strip those keys from the `data` map of every TestCase under the dataset before completing the update. This cleanup runs synchronously within the dataset update transaction so that no TestCase carries orphan fields by the time the dataset PUT returns 202.
Status: **Planned**

#### Scenario: Schema field removal prunes orphan data
- **WHEN** client updates a dataset removing field `legacyColumn` from `testCaseSchema`
- **THEN** every TestCase whose `data` contained `legacyColumn` SHALL have that key removed from `data` before the dataset PUT returns

#### Scenario: Schema field addition does not modify test case data
- **WHEN** client updates a dataset adding a new field `newColumn` (required=false)
- **THEN** existing TestCases' `data` maps SHALL remain unchanged; subsequent Phase-1 revalidation may emit warnings for required fields but does not synthesize values

#### Scenario: Schema field type change leaves data untouched at cleanup phase
- **WHEN** client updates a dataset changing field `score` from `INTEGER` to `STRING`
- **THEN** the cleanup phase SHALL NOT modify TestCase data; Phase 1 of the spawned RevalidationTask invokes `SchemaChangeCoercer` to coerce existing values per the rules defined in the `test-cases` spec

### Requirement: Dataset schema cache invalidation
The system SHALL invalidate any cached schema-derived validator entry keyed by `datasetId` whenever a dataset PUT mutates `testCaseSchema` or a dataset is deleted. `SchemaValidationService` (re-keyed by `datasetId` per design §D10) SHALL ensure that the next validation operation against the dataset uses the post-PUT schema, with no possibility of a stale cached validator producing results based on the pre-PUT schema.
Status: **Planned**

#### Scenario: Cache uses updated schema after dataset PUT
- **WHEN** a client issues `PUT /api/v1/datasets/{id}` that changes `testCaseSchema`, and immediately afterward a suite that references the dataset is re-validated (synchronously via `SuiteValidationService` on a suite PUT, or via the dataset-rooted RevalidationTask Phase 2)
- **THEN** the validation SHALL use the post-PUT `testCaseSchema` — no stale cached validator built off the pre-PUT schema may be consulted; cache invalidation MUST run within the dataset PUT transaction boundary so concurrent suite validations cannot read a stale entry after the PUT completes

#### Scenario: Cache eviction on dataset delete
- **WHEN** a client successfully deletes a dataset (HTTP 204; no dependent suites)
- **THEN** any cached `SchemaValidationService` entry keyed by that `datasetId` SHALL be evicted as part of the delete operation; subsequent lookups for the now-deleted id SHALL return a miss (not a stale validator)

#### Scenario: No eviction on suite PUT
- **WHEN** a client issues `PUT /api/v1/test-suites/{id}` (with or without `datasetId` rebind)
- **THEN** the `SchemaValidationService` cache SHALL NOT be evicted; the suite path is no longer the schema owner; if the suite is rebound to a different dataset, the lookup is a cache miss for the new key (populated on first use) and the prior key remains populated for other suites that still reference it

### Requirement: Dataset-rooted RevalidationTask trigger
The system SHALL spawn an async `RevalidationTask` exactly when a dataset PUT mutates `testCaseSchema` (any diff in the field list — name, type, required flag, displayName, description, or ordering). Dataset PUTs that change only `name` or `description` SHALL NOT spawn a task. The dataset PUT response is HTTP 202 with `RevalidationTaskDto` when a task is spawned, HTTP 200 with `DatasetResponseDto` otherwise.
Status: **Planned**

#### Scenario: Metadata-only edit returns 200
- **WHEN** client updates a dataset changing `description` only
- **THEN** system SHALL respond with HTTP 200 and return `DatasetResponseDto` with bumped `version` and updated `ETag`; no `RevalidationTask` is spawned

#### Scenario: Schema edit returns 202 with task
- **WHEN** client updates a dataset adding a new field to `testCaseSchema`
- **THEN** system SHALL respond with HTTP 202 and return `RevalidationTaskDto` with `status: PENDING`; the task is rooted at the dataset (FK `dataset_id`)

#### Scenario: Concurrent dataset PUT while task is RUNNING
- **WHEN** a `RevalidationTask` is in status `RUNNING` for a dataset and a new schema-changing PUT arrives
- **THEN** system SHALL process the new PUT (updating the schema and bumping version) and spawn a second task with status `PENDING`; the in-flight task continues against the schema it was started with, and the new task runs after the in-flight one completes (sequential per-dataset task scheduling: the executor gate filters `WHERE dataset_id = ? AND status IN ('PENDING','RUNNING')` and skips pickup of a new PENDING while any RUNNING task exists for the same dataset; queued PENDINGs are FIFO by `created_at`. See `design.md` for the mechanism rationale)

#### Scenario: RevalidationTaskDto JSON wire shape
- **WHEN** a client receives a `RevalidationTaskDto` (from POST/PUT /datasets/{id} returning 202, or from GET /datasets/{id}/revalidation-tasks*)
- **THEN** the JSON SHALL include `datasetId` (UUID) and SHALL NOT include `testSuiteId`; the prior `testSuiteId` wire field is removed without alias to make the breaking rename explicit

#### Scenario: PUT /datasets/{id} schema-edit returns 202 with datasetId-rooted task
- **WHEN** client calls `PUT /api/v1/datasets/{id}` with a body whose `testCaseSchema` differs from the stored value
- **THEN** system SHALL respond with HTTP 202; the response body SHALL be a `RevalidationTaskDto` JSON containing `"datasetId": "<id>"` matching the path parameter and SHALL NOT contain a `testSuiteId` field at all (not even as `null`); the `task.status` value SHALL be `"PENDING"`

#### Scenario: POST /datasets/{id}/test-cases CSV import returns 202 with datasetId-rooted task
- **WHEN** client calls `POST /api/v1/datasets/{id}/test-cases/import` (or `.../import.csv`) with an importMode that results in a schema change on the dataset (OVERRIDE with auto-detected schema, MERGE with new columns, or APPEND against an empty schema)
- **THEN** the response SHALL include a `RevalidationTaskDto` JSON containing `"datasetId": "<id>"` matching the path parameter and SHALL NOT contain a `testSuiteId` field at all; HTTP status SHALL be 202 when the response payload is purely the task, or 200 with an embedded task object when the response also carries import counts (exact wrapper shape is left to implementation but the `datasetId`/`testSuiteId` wire-shape rule is binding)

### Requirement: List and detail revalidation tasks under datasets
The system SHALL expose subresources under datasets to list and inspect revalidation tasks rooted at this dataset.
Status: **Planned**

#### Scenario: List revalidation tasks for a dataset
- **WHEN** client calls `GET /api/v1/datasets/{id}/revalidation-tasks?page=0&size=20`
- **THEN** system SHALL return a paginated list of `RevalidationTaskDto` items rooted at this dataset, sorted by `startedAt,desc`

#### Scenario: Get revalidation task by id
- **WHEN** client calls `GET /api/v1/datasets/{id}/revalidation-tasks/{taskId}`
- **THEN** system SHALL return the `RevalidationTaskDto` if `taskId` belongs to the specified `datasetId`; otherwise HTTP 404

### Requirement: Dataset soft validation (`isValid` + `validationWarnings`)
The system SHALL maintain `isValid` and `validationWarnings` on every Dataset. The flags reflect the dataset's schema-level soft validation: structural validity is hard-validated synchronously at PUT time (returning HTTP 400 on structural errors); the soft flags surface non-blocking concerns such as fields that no longer have any data populated, or schema versions that are flagged for follow-up. `isValid` SHALL default to `true` and `validationWarnings` to `[]` for newly-created datasets.
Status: **Planned**

#### Scenario: Newly-created dataset is valid with no warnings
- **WHEN** client creates a dataset with a structurally-valid schema
- **THEN** `isValid` SHALL be `true` and `validationWarnings` SHALL be `[]`

#### Scenario: Structural schema error returns 400, not soft-invalid
- **WHEN** client submits a dataset PUT with a schema containing a duplicate field name
- **THEN** system SHALL respond with HTTP 400 and `VALIDATION_ERROR` (hard fail); the dataset SHALL NOT be persisted in a soft-invalid state

### Requirement: OpenAPI documentation for dataset endpoints
The dataset CRUD and revalidation-task endpoints SHALL have OpenAPI annotations including operation summary, request/response schemas, and example JSON files under `src/main/resources/openapi/examples/`. The dataset-scoped query-param descriptions for `filter`/`sort`/`page`/`size` SHALL be auto-generated by `OpenApiQueryParamCustomizer` from new entries in `FilterWhitelists`/`SortWhitelists`.
Status: **Planned**

#### Scenario: Swagger UI shows dataset endpoints
- **WHEN** user opens Swagger UI
- **THEN** the dataset CRUD endpoints SHALL appear under a "Datasets" tag with descriptions, request body schemas, and response examples

#### Scenario: Filter/sort field descriptions are auto-generated
- **WHEN** user inspects the `GET /api/v1/datasets` operation in Swagger UI
- **THEN** the `filter` and `sort` query parameter descriptions SHALL enumerate the supported fields registered in `FilterWhitelists`/`SortWhitelists` for datasets

### Requirement: Dataset publish endpoint
The system SHALL provide `POST /api/v1/datasets/{id}/publish` to promote a dataset to `PUBLIC` visibility with an optional name and description update in a single atomic operation. The endpoint SHALL accept a body `{ "name": "<string>", "description": "<string>" }` where both fields are optional — when omitted the current values are preserved. The service SHALL acquire a `SELECT ... FOR UPDATE` row lock on the dataset row before reading and writing (same locking discipline as `PATCH /datasets/{id}/visibility`). The response SHALL be HTTP 200 with the updated `DatasetResponseDto`. When neither visibility nor name nor description change, the call is a no-op: the system SHALL return the current dataset without writing to the database and without bumping `version`.
Status: **Implemented**

#### Scenario: Publish PRIVATE dataset without metadata update
- **WHEN** client calls `POST /api/v1/datasets/{id}/publish` with an empty body `{}` on a PRIVATE dataset
- **THEN** system SHALL set `visibility` to `PUBLIC`, preserve existing `name` and `description`, bump `version`, update `updatedAt`, and return HTTP 200 with the updated `DatasetResponseDto`

#### Scenario: Publish PRIVATE dataset with name and description
- **WHEN** client calls `POST /api/v1/datasets/{id}/publish` with `{ "name": "My Dataset", "description": "For catalogue" }` on a PRIVATE dataset
- **THEN** system SHALL set `visibility` to `PUBLIC`, persist the provided `name` and `description`, bump `version`, and return HTTP 200 with the updated `DatasetResponseDto` reflecting all three changes

#### Scenario: Publish already-PUBLIC dataset with no metadata change is a no-op
- **WHEN** client calls `POST /api/v1/datasets/{id}/publish` with an empty body `{}` on a dataset already `PUBLIC`
- **THEN** system SHALL return HTTP 200 with the current `DatasetResponseDto` unchanged; `version` SHALL NOT be incremented

#### Scenario: Publish already-PUBLIC dataset with new name updates metadata
- **WHEN** client calls `POST /api/v1/datasets/{id}/publish` with `{ "name": "New Name" }` on a dataset already `PUBLIC`
- **THEN** system SHALL update `name`, bump `version`, update `updatedAt`, and return HTTP 200 with the updated `DatasetResponseDto`; `visibility` SHALL remain `PUBLIC`

#### Scenario: Publish with duplicate name returns 409
- **WHEN** client calls `POST /api/v1/datasets/{id}/publish` with a `name` that already exists on another dataset (case-insensitive)
- **THEN** system SHALL return HTTP 409 with error code `UNIQUE_CONSTRAINT_VIOLATION`

#### Scenario: Publish name exceeds maximum length returns 400
- **WHEN** client calls `POST /api/v1/datasets/{id}/publish` with a `name` longer than 263 characters
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Publish description exceeds maximum length returns 400
- **WHEN** client calls `POST /api/v1/datasets/{id}/publish` with a `description` longer than 2000 characters
- **THEN** system SHALL return HTTP 400 with error code `VALIDATION_ERROR`

#### Scenario: Publish non-existent dataset returns 404
- **WHEN** client calls `POST /api/v1/datasets/{id}/publish` for an unknown id
- **THEN** system SHALL return HTTP 404 with error code `NOT_FOUND`

### Requirement: List test suites depending on a dataset
The system SHALL provide `GET /api/v1/datasets/{datasetId}/test-suites` that returns the test suites bound to the dataset — every suite whose `dataset_id` equals the path id. The response SHALL be a plain JSON array of `DatasetDependentSuiteDto` items, each carrying exactly `id` (UUID), `name` (String), and `description` (String, nullable). The endpoint SHALL NOT be paginated, filterable, or sortable. Visibility SHALL NOT affect this endpoint — it lists dependents for both PUBLIC and PRIVATE datasets.
Status: **Planned**

#### Scenario: Dataset with bound suites returns their summaries
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-suites` for an existing dataset that has one or more bound test suites
- **THEN** system SHALL respond with HTTP 200 and a JSON array containing one `DatasetDependentSuiteDto` per bound suite, each with `id`, `name`, and `description` matching the suite, and SHALL NOT include any other suite fields

#### Scenario: Dataset with no bound suites returns empty array
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-suites` for an existing dataset that has no bound test suites
- **THEN** system SHALL respond with HTTP 200 and an empty JSON array `[]`

#### Scenario: Unknown dataset returns 404
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-suites` for a dataset id that does not exist
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`

#### Scenario: Lists dependents of a PRIVATE dataset
- **WHEN** client calls `GET /api/v1/datasets/{datasetId}/test-suites` for an existing PRIVATE dataset bound to a suite
- **THEN** system SHALL respond with HTTP 200 and include the bound suite's summary; visibility SHALL NOT block the listing

### Requirement: DatasetDependentSuiteDto wire shape
The system SHALL expose `DatasetDependentSuiteDto` as the response element type for `GET /api/v1/datasets/{datasetId}/test-suites`. The DTO SHALL contain exactly three fields — `id` (UUID), `name` (String), `description` (String, nullable) — and SHALL NOT expose the full `TestSuiteResponseDto` field set.
Status: **Planned**

#### Scenario: DatasetDependentSuiteDto fields
- **WHEN** client receives a `DatasetDependentSuiteDto`
- **THEN** the payload SHALL include `id`, `name`, and `description`, and SHALL NOT include suite fields such as `suiteType`, `datasetId`, `version`, `responseColumns`, `inputBindings`, `validationWarnings`, `createdBy`, `createdAt`, or `updatedAt`

#### Scenario: Null suite description serializes as null
- **WHEN** a bound suite has no `description` and client receives its `DatasetDependentSuiteDto`
- **THEN** the `description` field SHALL be present with value `null`

### Requirement: OpenAPI documentation for the dataset dependent-suites endpoint
`GET /api/v1/datasets/{datasetId}/test-suites` SHALL carry OpenAPI annotations including an operation summary, the `DatasetDependentSuiteDto` array response schema, a response example, and documented 200 and 404 responses, under the existing "Datasets" tag.
Status: **Planned**

#### Scenario: Swagger UI shows the dependent-suites endpoint
- **WHEN** user opens Swagger UI
- **THEN** the `GET /api/v1/datasets/{datasetId}/test-suites` operation SHALL appear under the "Datasets" tag with a summary describing the listing of dependent suites, an array-of-`DatasetDependentSuiteDto` response schema with an example, and documented 200 and 404 responses

## Implementation notes

- New table: `datasets` (`src/main/resources/db/migration/meta/POSTGRES/V1.<next>__IntroduceDataset.sql`); unique index `uq_datasets_name` on `LOWER(name)`.
- New domain model: `data.db.model.Dataset`. New mapper: `data.db.mapper.DatasetRecordMapper`.
- New repository: `data.db.repository.DatasetRepository` + `PostgresDatasetRepository` (jOOQ DSL).
- New service: `service.domain.DatasetService`. New helper: `service.domain.DatasetSchemaProvider`.
- New mapper: `service.domain.mapper.DatasetMapper`.
- New controller: `web.controller.DatasetController`.
- New DTOs: `service.domain.dto.{DatasetRequestDto, DatasetResponseDto, DatasetReferenceDto}`.
- New filter/sort whitelists: registered with `FilterWhitelists` and `SortWhitelists`; new entry in `OpenApiQueryParamCustomizer` for the list endpoint.
- Revalidation-task subresources reuse the existing `RevalidationTaskRepository` (rebound from `test_suite_id` to `dataset_id`) and `RevalidationService`.
- Dependent-suites endpoint: new `@GetMapping("/{id}/test-suites")` on `web.controller.DatasetController`; new `DatasetService.getDependentSuites(UUID)` (dataset existence check via `getById`, then delegates to `TestSuiteService.getDependentSuiteSummaries`); new `TestSuiteRepository.findSuiteSummariesReferencingDataset(UUID)` with selective column projection (`id`, `name`, `description` only) to avoid TOAST decompression; new pure-carrier `data.db.model.TestSuiteSummary` record; new DTO `service.domain.dto.DatasetDependentSuiteDto`.

