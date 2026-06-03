## ADDED Requirements

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
- **THEN** system SHALL atomically set the bound suite's `dataset_id := NULL` and delete the dataset row (test cases cascade); return HTTP 204 (see "PRIVATE dataset delete atomically unbinds and deletes" requirement for atomicity and error scenarios)

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

## Implementation notes

- Schema change: `datasets.visibility VARCHAR(16) NOT NULL CHECK (visibility IN ('PUBLIC','PRIVATE'))`; introduced via in-place modification of the existing `V1.22__IntroduceDataset.sql` Flyway migration. Backfill all pre-existing rows to `PRIVATE`. Re-run `./gradlew generateJooq` and commit the diff.
- New PostgreSQL constraint trigger `tg_test_suites_private_binding_guard` on `test_suites` (`BEFORE INSERT OR UPDATE OF dataset_id`) raises `ERRCODE='P0001'` MESSAGE TEXT `'PRIVATE_DATASET_ALREADY_BOUND'` on a second concurrent PRIVATE binding; early-returns when `NEW.dataset_id IS NULL`.
- New domain model addition: `data.db.model.DatasetVisibility` enum; `data.db.model.Dataset` adds `visibility` field.
- New repository methods: `PostgresDatasetRepository#countBoundSuites(UUID)`, `PostgresDatasetRepository#unbindAllSuites(UUID)`; list query adds `WHERE visibility = 'PUBLIC'`.
- New service responsibilities on `DatasetService`: atomic create-and-bind, visibility transition (PATCH endpoint with `SELECT ... FOR UPDATE`), PRIVATE delete path.
- New DTO fields on `DatasetRequestDto`: `visibility @NotNull` (create-only enforcement in service), `bindToSuiteId` (UUID, optional). `DatasetResponseDto` adds `visibility`.
- New web endpoint: `PATCH /api/v1/datasets/{id}/visibility` on `DatasetController`.
- New error codes added to the `ErrorCode` enum: `PRIVATE_DATASET_REQUIRES_SUITE_BINDING`, `PUBLIC_DATASET_FORBIDS_SUITE_BINDING`, `PRIVATE_DATASET_ALREADY_BOUND`, `PRIVATE_TRANSITION_INVALID_BINDING_COUNT`.
- Global `DefaultExceptionHandler` adds a branch on `SQLException.getSQLState() == "P0001"` that maps to HTTP 409 + `PRIVATE_DATASET_ALREADY_BOUND`. The existing `23505 → UNIQUE_CONSTRAINT_VIOLATION` mapping is untouched.
- OpenAPI: `DatasetController` adds operation annotations + examples for the new PATCH endpoint and the updated POST body (`visibility`, `bindToSuiteId`). Auto-generated query-param descriptions on `GET /api/v1/datasets` reflect that `visibility` is NOT in the filter whitelist.
