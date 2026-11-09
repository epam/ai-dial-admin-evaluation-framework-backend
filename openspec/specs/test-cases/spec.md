# Test Cases

## Purpose

Current dataset-scoped test-case model, CRUD, listing and single-case PATCH. Dataset schema/lifecycle is owned by [datasets](../datasets/spec.md); content checks by [test-case-validation](../test-case-validation/spec.md); batches by [test-case-bulk-update](../test-case-bulk-update/spec.md); transfer by [test-case-csv-transfer](../test-case-csv-transfer/spec.md) and [ZIP archive](../test-case-zip-archive/spec.md); strict schema-change processing by [test-case-revalidation](../test-case-revalidation/spec.md). Suite participation is determined by [testCaseFilter](../suite-test-case-filter/spec.md), not stored per-case state.

Status: **Implemented**

## Requirements

### Requirement: Dataset-scoped CRUD and listing

Status: **Implemented**

The service SHALL manage TestCases as children of a Dataset with full CRUD operations. TestCases carry their identity, shared `data` (Map<String, Object>), optional `multiTurnData`, and validity metadata. A test case carries no per-suite participation state: which of a dataset's test cases a given suite runs is decided solely by that suite's `testCaseFilter` (see `suite-test-case-filter`).

Test-case CRUD, batch PUT/PATCH, CSV/ZIP import/preview, and CSV/ZIP export endpoints SHALL be rooted at `/api/v1/datasets/{datasetId}/test-cases`; composite bulk PATCH SHALL use `/api/v1/datasets/{datasetId}/test-cases:bulk`. The `testCaseId` in nested paths SHALL belong to the specified `datasetId`.

All TestCase operations SHALL validate that the testCaseId belongs to the specified datasetId.

Array/composite mutation contracts belong to [test-case-bulk-update](../test-case-bulk-update/spec.md). CSV/ZIP transfer belongs to [test-case-csv-transfer](../test-case-csv-transfer/spec.md) and [test-case-zip-archive](../test-case-zip-archive/spec.md). Suite-scoped resolved-request preview belongs to [request-template](../request-template/spec.md).

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Create a test case | `POST /api/v1/datasets/{datasetId}/test-cases` with a valid body | SHALL create a TestCase linked to the Dataset; require `testCaseName`; default `data` to `{}`; calculate `valid` from the dataset's `testCaseSchema` |
| List test cases | `GET /api/v1/datasets/{datasetId}/test-cases` | SHALL return a paginated list of TestCases under the dataset |
| Sort and filter test cases | `GET .../test-cases?sort=...&filter=...` | SHALL apply sorting and filtering per entity-filtering spec; supported filter fields: `testCaseName`, `valid`, `createdAt`; supported sort fields: `id`, `testCaseName`, `createdAt`, `updatedAt`, `valid` |
| Pagination with optional total count | `GET .../test-cases?page=0&size=50&includeTotalCount=true` | SHALL return `totalElements` and `totalPages`; without param, omit them |
| Range filter with multiple conditions | `GET .../test-cases?filter=createdAt:ge:1000&filter=createdAt:le:2000` | SHALL return test cases in the time range |
| Invalid filter field | `GET .../test-cases?filter=unknownField:eq:value` | SHALL respond with HTTP 400 and include invalid field in error details |
| Default sort order | `GET .../test-cases` without sort parameter | SHALL return results sorted by `createdAt,desc` |
| Get test case by id | `GET /api/v1/datasets/{datasetId}/test-cases/{testCaseId}` for an existing TestCase | SHALL return the TestCase including shared `data` and, when present, `multiTurnData` |
| Update test case (full replacement) | `PUT .../datasets/{datasetId}/test-cases/{testCaseId}` with a valid body | SHALL replace the TestCase, recalculate `valid` against the dataset's schema, update `updatedAt` |
| Delete single test case | `DELETE .../datasets/{datasetId}/test-cases/{testCaseId}` | SHALL delete the TestCase and return HTTP 204 |
| Bulk delete test cases | `DELETE .../datasets/{datasetId}/test-cases` with optional filter | SHALL delete matching TestCases (or all if no filter) and return count of deleted items |
| URL scope matches dataset | any test-case endpoint under `/api/v1/datasets/{datasetId}/test-cases/*` | the implementation SHALL resolve `datasetId` from the path and reject requests whose `testCaseId` (when present) does not belong to that dataset (HTTP 404) |
| TestCase not in specified Dataset | any TestCase endpoint where testCaseId exists but belongs to a different datasetId | SHALL respond with HTTP 404 |
| Dataset not found | any TestCase endpoint where datasetId does not exist | SHALL respond with HTTP 404 |

### Requirement: Test-case data model and structural authoring

Status: **Implemented**

Each TestCase SHALL have: `testCaseName`, shared `data` (Map<String,Object>), optional `multiTurnData` (List<Map<String,Object>>), `valid` (boolean), and `validationWarnings` (structured list).

TestCase SHALL not expose `createdBy`; dataset/suite attribution belongs to [security](../security/spec.md).

The test-case request, response, and batch-put DTOs SHALL expose an optional `multiTurnData` (`List<Map<String,Object>>`); the model and `test_cases` table carry a nullable `multi_turn_data JSONB` column. The field is omitted (`@JsonInclude(NON_NULL)`) for single-turn cases. A test case MAY populate `data` **and** `multiTurnData` together: `data` carries the dataset's **shared** (`perTurn=false`) fields — test-case-level values constant across turns — while each `multiTurnData[i]` carries the **per-turn** (`perTurn=true`) fields. Both fields SHALL be accepted together. The multi-turn discriminator is `multiTurnData != null` (independent of whether `data` is empty).

On a write carrying `multiTurnData`, a field **declared in the dataset schema** and placed in the wrong scope bucket — a per-turn field present in `data`, or a shared field present in any turn map — SHALL be rejected with HTTP 400 `VALIDATION_ERROR` at create/PUT/PATCH/batch (a structural placement error, distinct from content warnings). Scope placement SHALL NOT be checked when `multiTurnData` is absent: a single-turn case has no turn bucket, so no placement can be violated. A key that is **not declared** in the dataset schema SHALL NOT be rejected: it has no scope to violate, and is reported as a content warning that invalidates the case (see *Per-turn validation against the dataset schema*).

`multiTurnData`, when present, SHALL have at least one element; an empty array SHALL be rejected with HTTP 400. There is no suite-level multi-turn flag. Content limits and warnings belong to [test-case-validation](../test-case-validation/spec.md); binding-driven execution belongs to [multi-turn-test-case](../multi-turn-test-case/spec.md).

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Default data map | a TestCase is created without `data` | SHALL default `data` to an empty map `{}` |
| Round-trip a multi-turn case | a test case is created with a `multiTurnData` array and read back | the response includes `multiTurnData` with the same ordered turns and omits it for single-turn cases |
| Shared and per-turn data coexist | a case is created with `data` carrying the dataset's shared fields and a `multiTurnData` array carrying the per-turn fields | the write SHALL succeed, both are persisted, and the case is treated as multi-turn (`multiTurnData != null`) |
| Misplaced field rejected | a write carrying `multiTurnData` places a per-turn field inside `data`, or a shared field inside a turn map, and that field is declared in the dataset schema | the request is rejected with HTTP 400 `VALIDATION_ERROR` |
| Single-turn write is not scope-checked | a write omits `multiTurnData` (single-turn case) | no scope-placement rejection SHALL occur, whatever the declared scope of the fields present in `data` |
| Undeclared field is warned about, not rejected | a write places a key that no dataset schema field declares into `data` or into a turn map | the write SHALL succeed and the case SHALL be stored with `is_valid=false` and an unknown-field warning whose `path` identifies the bucket the key was found in |
| Multi-turn case is identified by multiTurnData | a test case is stored with a non-empty `multiTurnData` array | it is treated as a multi-turn test case whose turns are the array elements in order (`turn_index` = array position, `0..N-1`), regardless of whether `data` is empty or carries shared fields |
| Single-turn case is unaffected | a test case has `multiTurnData` absent/null | it behaves exactly as today, using its `data` map as a single turn |
| Shared data coexists with turns | a multi-turn case has `data` carrying shared fields and `multiTurnData` carrying per-turn fields | both are stored and the case is multi-turn; the shared fields are visible to every turn (see the merged-view execution requirement) |
| Empty multiTurnData rejected | a request supplies `multiTurnData: []` | the request is rejected with HTTP 400 |

### Requirement: Single-case PATCH contract

Status: **Implemented**

The service SHALL allow partial updates of `testCaseName`, `data`, and `multiTurnData`. A non-null `testCaseName` SHALL be converted to a string; null SHALL leave the name unchanged. An object-valued `data` SHALL merge into the stored map: null members remove keys, and nested maps merge recursively only where the stored and submitted values are both maps; a submitted map replacing a missing or non-map value SHALL be assigned directly, retaining its null members. Top-level null or non-object `data` SHALL be ignored. `multiTurnData:null` SHALL clear the turn array, an array SHALL replace it, and other types SHALL be ignored. Unknown top-level fields SHALL be ignored; identity, validity, and timestamps SHALL not be directly patchable. PATCH uses these map-update rules, not complete RFC 7396 semantics; typed POST/PUT name constraints are not reapplied to the untyped PATCH name.

Both `data` and `multiTurnData` SHALL be part of the merge-PATCH whitelist alongside `testCaseName`. Patching `data` SHALL update only the shared bucket, and patching `multiTurnData` SHALL update only the per-turn bucket; neither SHALL implicitly clear the other. Setting `multiTurnData: null` SHALL revert the case to single-turn. Placement and per-scope validation SHALL run after the merge (misplaced **declared** field → 400; content issues, including a misplaced undeclared key → invalidating warnings).

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Patch data only | `PATCH .../datasets/{datasetId}/test-cases/{testCaseId}` with `{ "data": { "user_prompt": "new prompt" } }` | SHALL apply the map-update rules to `data`, recalculate `valid` against the dataset's schema, and return the updated TestCase |
| Patch testCaseName | PATCH with `{ "testCaseName": "Renamed" }` | SHALL update the name (subject to dataset-scoped uniqueness) and return the updated TestCase |
| PATCH recomputes validation | any single-case PATCH, including ignored or unchanged values | SHALL recompute valid against the dataset schema; validationWarnings SHALL be returned only when `includeWarnings=true` |
| Null shared data patch is ignored | client PATCHes a case with `data:null` | shared data SHALL remain unchanged and validation SHALL run on that unchanged content |
| Nested object replacing a non-object retains supplied null members | a data patch assigns an object containing a null member to a key whose existing value is absent or not an object | that object SHALL be assigned directly rather than recursively applying null-member deletion |
| PATCH updates shared data without clearing turns | a multi-turn case is PATCHed with a `data` object updating a shared field | the shared field is merged into `data`, `multiTurnData` is unchanged, and the case stays multi-turn |
| PATCH updates per-turn data without clearing shared | a multi-turn case is PATCHed with a `multiTurnData` array | the turns are replaced and the existing shared `data` is unchanged |
| PATCH reverts to single-turn | a multi-turn case is PATCHed with `multiTurnData: null` | the case becomes single-turn and its `data` is retained as the single-turn map |

### Requirement: Unique testCaseName within Dataset

Status: **Implemented**

The service SHALL enforce that `testCaseName` is unique within a Dataset (case-insensitive). `"TestA"` and `"testa"` are considered duplicates within the same dataset. Create, update (PUT/PATCH when changing name), and CSV import SHALL enforce uniqueness. CSV import `conflictStrategy` continues to govern collision behavior (FAIL/SKIP/OVERRIDE) for both cross-import collisions and within-CSV duplicates. The DB unique constraint is `(dataset_id, LOWER(test_case_name))`; upsert-based strategies leverage it directly.

#### Scenario: Contract cases
- **WHEN** an input or operation matches a case below
- **THEN** its expected result SHALL hold, including the stated failure and rollback behavior

| Case | When | Expected |
|---|---|---|
| Duplicate testCaseName on create | `POST /api/v1/datasets/{datasetId}/test-cases` with a `testCaseName` that already exists in that Dataset (case-insensitive match) | SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION`, and message including the duplicated name |
| Duplicate testCaseName on update | `PUT /api/v1/datasets/{datasetId}/test-cases/{testCaseId}` with a body whose `testCaseName` already exists for another TestCase in the same Dataset (case-insensitive match) | SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION` |
| Duplicate testCaseName on PATCH | `PATCH .../datasets/{datasetId}/test-cases/{testCaseId}` with a `testCaseName` that already exists for another TestCase in the same Dataset (case-insensitive match) | SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION` |
| Same testCaseName in different datasets succeeds | client creates a TestCase with `testCaseName` that exists in a different Dataset (but not in the target dataset) | SHALL create it successfully (HTTP 201, no 409) |
| Case variation is duplicate within dataset | a TestCase named `"CaseOne"` exists in a dataset and client creates a TestCase named `"caseone"` in the same dataset | SHALL respond with HTTP 409, error code `UNIQUE_CONSTRAINT_VIOLATION` |

### Requirement: Standard error response format

Status: **Implemented**

Handled REST API errors SHALL use a standardized format with machine-readable code (e.g. VALIDATION_ERROR, NOT_FOUND, VERSION_CONFLICT).

#### Scenario: Missing test case returns a structured error
- **WHEN** a test-case GET addresses an unknown case in an existing dataset
- **THEN** the handled HTTP 404 response SHALL include path, method, status, code `NOT_FOUND`, error, and message in the standard error envelope

## Implementation notes

- [TestCaseController.java](../../../src/main/java/com/epam/aidial/evaluation/web/controller/TestCaseController.java) and [TestCaseService.java](../../../src/main/java/com/epam/aidial/evaluation/service/domain/TestCaseService.java): scoped CRUD/PATCH.
- [MultiTurnFieldsValidator.java](../../../src/main/java/com/epam/aidial/evaluation/service/domain/MultiTurnFieldsValidator.java): structural placement and non-empty turns.
- [PostgresTestCaseRepository.java](../../../src/main/java/com/epam/aidial/evaluation/data/db/repository/PostgresTestCaseRepository.java): dataset-scoped persistence and uniqueness.
