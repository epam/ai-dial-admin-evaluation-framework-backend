# Datasets

## Purpose

This spec defines the `Dataset` entity — the centralized container for test case shape (`testCaseSchema`) and test case data. Datasets replace TestSuite as the system of record for these concerns: test suites become consumers of a dataset (many suites may share one dataset), and edits to test case data propagate to every suite that references the dataset.

Status: **Planned**

## Key Terms
- **Dataset**: An entity owning a `testCaseSchema` (list of `FieldDefinitionDto`) and the collection of `TestCase` rows that conform to that schema. Identity: `id` (UUID), `name` (globally unique on `LOWER(name)`).
- **testCaseSchema**: A JSONB list of `FieldDefinitionDto` (name, displayName, type ∈ `STRING`/`INTEGER`/`NUMBER`/`BOOLEAN`/`OBJECT`/`ARRAY`/`FILE`, required, description). Defines the shape every TestCase's `data` map must conform to. Owned by Dataset, sourced by TestSuite via `DatasetSchemaProvider` at validation/snapshot time.
- **DatasetSchemaProvider**: An injectable `@Component` returning `List<FieldDefinitionDto>` for a given `datasetId`. Used to break the would-be circular dependency between `DatasetService` and `TestSuiteService`.
- **Dataset-rooted RevalidationTask**: The async task spawned by a dataset PUT that mutates `testCaseSchema`. Runs two phases — Phase 1 (test cases, fail-fast) and Phase 2 (dependent suites, per-suite resilient). See `test-cases` spec for Phase 1 semantics and `test-suites` spec for Phase 2 semantics.

## ADDED Requirements

### Requirement: Dataset CRUD endpoints
The system SHALL provide CRUD endpoints for the `Dataset` entity under `/api/v1/datasets`. Dataset is the system of record for `testCaseSchema` and owns the collection of test cases under it.
Status: **Planned**

#### Scenario: List datasets (paginated)
- **WHEN** client calls `GET /api/v1/datasets`
- **THEN** system SHALL return a paginated list of `DatasetResponseDto` items; default page=0, size=100, max size 1000; default sort `createdAt,desc`; supports `filter`/`sort`/`includeTotalCount` per entity-filtering spec

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
- **WHEN** client calls `GET /api/v1/datasets/{id}` with a valid id
- **THEN** system SHALL return `DatasetResponseDto` with an `ETag` header carrying the entity's `version`

#### Scenario: Dataset not found
- **WHEN** client calls `GET /api/v1/datasets/{id}` for an unknown id
- **THEN** system SHALL respond with HTTP 404 and error code `NOT_FOUND`

#### Scenario: Create dataset
- **WHEN** client calls `POST /api/v1/datasets` with a valid `DatasetRequestDto` (`name`, optional `description`, optional `testCaseSchema` defaulting to `[]`)
- **THEN** system SHALL create the dataset; assign a fresh UUID; set `version = 1`; set `createdBy` from JWT subject (or `"anonymous"` in no-security mode); set `createdAt`/`updatedAt` from the injected `Clock`; return HTTP 201 with `DatasetResponseDto` and `ETag` header

#### Scenario: Update dataset (metadata only)
- **WHEN** client calls `PUT /api/v1/datasets/{id}` with `If-Match: <version>` header and a body where `testCaseSchema` is unchanged compared to the stored value
- **THEN** system SHALL update the dataset, bump `version`, return HTTP 200 with the new `DatasetResponseDto` and updated `ETag`

#### Scenario: Update dataset (schema change)
- **WHEN** client calls `PUT /api/v1/datasets/{id}` with `If-Match: <version>` and a body where `testCaseSchema` differs from the stored value
- **THEN** system SHALL update the dataset, bump `version`, prune any data fields removed from the schema from every TestCase in the dataset, spawn an async `RevalidationTask` rooted at this dataset, and return HTTP 202 with `RevalidationTaskDto` (status `PENDING`)

#### Scenario: Optimistic concurrency conflict
- **WHEN** client calls `PUT /api/v1/datasets/{id}` with an `If-Match` value that does not match the current `version`
- **THEN** system SHALL respond with HTTP 412 and error code `VERSION_CONFLICT`

#### Scenario: Missing If-Match header
- **WHEN** client calls `PUT /api/v1/datasets/{id}` without an `If-Match` header
- **THEN** system SHALL respond with HTTP 428 (or 400 per project convention) and error code `VALIDATION_ERROR`

#### Scenario: Delete dataset with no dependents
- **WHEN** client calls `DELETE /api/v1/datasets/{id}` and no `TestSuite` references this dataset
- **THEN** system SHALL delete the dataset and (via `ON DELETE CASCADE`) all its test cases; return HTTP 204

#### Scenario: Delete dataset rejected by RESTRICT
- **WHEN** client calls `DELETE /api/v1/datasets/{id}` and one or more `TestSuite` rows reference this dataset
- **THEN** system SHALL respond with HTTP 409 and error code `UNIQUE_CONSTRAINT_VIOLATION` (or a dedicated `DATASET_IN_USE` error code if added in implementation); response body SHALL list the dependent suite IDs and names

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

### Requirement: Dataset request/response DTOs
The system SHALL expose `DatasetRequestDto` and `DatasetResponseDto` with the listed fields and validation rules.
Status: **Planned**

#### Scenario: DatasetRequestDto field validation
- **WHEN** client sends a `DatasetRequestDto`
- **THEN** the system SHALL validate: `name` (`@NotBlank @Size(max = 255)`), `description` (`@Size(max = 2000)`, nullable), `testCaseSchema` (list of `FieldDefinitionDto`, default `[]`, each entry conforming to the field-definition validation rules defined in this spec)

#### Scenario: Dataset description exceeds max length
- **WHEN** client calls `POST /api/v1/datasets` or `PUT /api/v1/datasets/{id}` with a `description` longer than 2000 characters
- **THEN** system SHALL respond with HTTP 400 and error code `VALIDATION_ERROR`; the dataset SHALL NOT be persisted or updated

#### Scenario: DatasetResponseDto fields
- **WHEN** client receives a `DatasetResponseDto`
- **THEN** the payload SHALL include `id` (UUID), `name`, `description`, `testCaseSchema` (list of `FieldDefinitionDto`), `isValid` (boolean), `validationWarnings` (list of structured warnings), `version` (Long), `createdBy`, `createdAt` (epoch ms), `updatedAt` (epoch ms)

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
