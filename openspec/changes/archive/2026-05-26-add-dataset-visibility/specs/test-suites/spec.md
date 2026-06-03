## ADDED Requirements

### Requirement: Reject rebind/unbind when current dataset is PRIVATE
When a TestSuite is currently bound to a `PRIVATE` dataset, `PATCH /api/v1/test-suites/{id}` (or any other update mechanism) SHALL reject any request that changes `datasetId` — including setting it to a different dataset id OR to `null`. The rejection SHALL be HTTP 409 with error code `PRIVATE_DATASET_REBIND_FORBIDDEN`. The constraint exists because a PRIVATE dataset belongs exclusively to its one bound suite; unbinding it would orphan the dataset, and rebinding it elsewhere would violate the "one PRIVATE dataset, one suite" invariant. Users who want to swap the dataset of a PRIVATE-bound suite SHALL first delete the PRIVATE dataset (which unbinds the suite) and then PATCH the suite with the new `datasetId`.
Status: **Planned**

#### Scenario: Rebind PRIVATE-bound suite to different dataset rejected
- **WHEN** client calls `PATCH /api/v1/test-suites/{id}` with a `datasetId` differing from the stored value, and the stored dataset's `visibility = PRIVATE`
- **THEN** system SHALL respond with HTTP 409 and error code `PRIVATE_DATASET_REBIND_FORBIDDEN`; the suite's `datasetId` SHALL remain unchanged

#### Scenario: Unbind PRIVATE-bound suite (set to null) rejected
- **WHEN** client calls `PATCH /api/v1/test-suites/{id}` with `datasetId: null` on a suite whose stored dataset's `visibility = PRIVATE`
- **THEN** system SHALL respond with HTTP 409 and error code `PRIVATE_DATASET_REBIND_FORBIDDEN`; the suite's `datasetId` SHALL remain unchanged

#### Scenario: Rebind PUBLIC-bound suite to different dataset succeeds
- **WHEN** client calls `PATCH /api/v1/test-suites/{id}` with a `datasetId` differing from the stored value, and the stored dataset's `visibility = PUBLIC`
- **THEN** system SHALL update `datasetId`, re-run suite-level validation against the new dataset's schema, and return the updated suite (the new dataset's visibility does not influence acceptance; see "Concurrent PRIVATE-binding prevention" in the `datasets` spec for the trigger-side guard on the target side)

#### Scenario: Unbind PUBLIC-bound suite (set to null) succeeds
- **WHEN** client calls `PATCH /api/v1/test-suites/{id}` with `datasetId: null` on a suite whose stored dataset's `visibility = PUBLIC`
- **THEN** system SHALL set `datasetId = null` and return the updated suite; the suite enters the unbound state

### Requirement: Suite delete cascades a PRIVATE dataset
When `DELETE /api/v1/test-suites/{id}` is called on a suite bound to a `PRIVATE` dataset, the system SHALL — within the same meta transaction that removes the suite — delete the bound `PRIVATE` dataset row. Test cases under that dataset cascade via the existing FK. The suite's own runs, TSMDs, and eval-summaries cascade via existing FKs (no behavior change). The suite-delete on a `PUBLIC`-bound or unbound suite leaves the dataset untouched (unchanged baseline behavior).
Status: **Planned**

#### Scenario: Delete suite bound to PRIVATE dataset cascades dataset
- **WHEN** client calls `DELETE /api/v1/test-suites/{id}` on a suite bound to a PRIVATE dataset
- **THEN** the suite row SHALL be deleted; the bound PRIVATE dataset row SHALL be deleted; the PRIVATE dataset's test cases SHALL be cascade-deleted; all suite-owned children (TSMDs, runs, eval-summaries) SHALL be cascade-deleted via existing FKs; the response SHALL be HTTP 204 (or HTTP 200 with the deleted suite body per project convention)

#### Scenario: Delete suite bound to PUBLIC dataset preserves dataset
- **WHEN** client calls `DELETE /api/v1/test-suites/{id}` on a suite bound to a PUBLIC dataset
- **THEN** the suite row SHALL be deleted; the PUBLIC dataset SHALL remain intact (still discoverable via `GET /api/v1/datasets/{id}` and the list endpoint, possibly bound to other suites)

#### Scenario: Delete unbound suite touches no dataset
- **WHEN** client calls `DELETE /api/v1/test-suites/{id}` on a suite whose `datasetId IS NULL`
- **THEN** the suite row SHALL be deleted; no dataset SHALL be affected

#### Scenario: PRIVATE-cascade is atomic with suite delete
- **WHEN** any step of the PRIVATE-cascade delete path fails (dataset delete, test-case cascade, or any suite-owned cascade)
- **THEN** the entire transaction SHALL roll back; the suite, its bound PRIVATE dataset, and all cascaded children SHALL remain unchanged

## MODIFIED Requirements

### Requirement: Suite references a dataset (required `datasetId`)
A TestSuite SHALL reference at most one Dataset via an OPTIONAL `datasetId` field (UUID, NULLABLE FK in `test_suites.dataset_id` to `datasets.id`). The reference SHALL be optional on both create and update; suites with `datasetId = null` SHALL be persisted in an **unbound** state and SHALL be retrievable and editable, but SHALL NOT be runnable (see the "Trigger a test suite run" requirement in the `test-suite-runs` spec for the run-start guard). Many suites MAY share a single PUBLIC dataset; at most one suite MAY be bound to a given PRIVATE dataset at any time (enforced application-side and by the trigger defined in the `datasets` spec).
Status: **Planned**

#### Scenario: Create without datasetId allowed (unbound suite)
- **WHEN** client calls `POST /api/v1/test-suites` with a body that omits `datasetId` or sends it as `null`
- **THEN** system SHALL create the suite with `datasetId = null`; the suite is in the unbound state and can be configured further; it cannot run until a `datasetId` is set

#### Scenario: Create with valid datasetId succeeds
- **WHEN** client calls `POST /api/v1/test-suites` with a `datasetId` referring to an existing dataset whose visibility allows the binding (PUBLIC always allows; PRIVATE allows iff it has zero current bindings — enforced by the trigger)
- **THEN** system SHALL create the suite with the given `datasetId`

#### Scenario: Create rejects unknown datasetId
- **WHEN** client calls `POST /api/v1/test-suites` with `datasetId` referring to a non-existent dataset
- **THEN** system SHALL respond with HTTP 404 (or HTTP 400 with `VALIDATION_ERROR` per project convention for FK pre-checks); the suite SHALL NOT be persisted

#### Scenario: Update can rebind PUBLIC-bound suite to a different dataset
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a `datasetId` differing from the current value, the new dataset exists, AND the current dataset's `visibility = PUBLIC` (or current `datasetId` is `null`)
- **THEN** system SHALL update the suite's `datasetId`, recalculate suite-level `isValid` and `validationWarnings` against the new dataset's schema, and return the updated entity

#### Scenario: Update rejects rebind/unbind when current dataset is PRIVATE
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a `datasetId` differing from the current value (including `null`), and the stored dataset's `visibility = PRIVATE`
- **THEN** system SHALL respond with HTTP 409 and error code `PRIVATE_DATASET_REBIND_FORBIDDEN` (see "Reject rebind/unbind when current dataset is PRIVATE" requirement)

#### Scenario: Update rejects unknown datasetId
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a `datasetId` referring to a non-existent dataset
- **THEN** system SHALL respond with HTTP 404 (or HTTP 400 per FK pre-check convention)

#### Scenario: Suite delete cascade is visibility-conditional
- **WHEN** client deletes a TestSuite via `DELETE /api/v1/test-suites/{id}`
- **THEN** if the suite is bound to a PRIVATE dataset, the dataset SHALL be cascade-deleted in the same transaction (see "Suite delete cascades a PRIVATE dataset" requirement); if the suite is bound to a PUBLIC dataset or is unbound, the dataset SHALL remain intact; in all cases the suite row and its owned children (TSMDs, runs, eval-summaries) SHALL be removed

#### Scenario: Dataset delete behavior depends on visibility
- **WHEN** client calls `DELETE /api/v1/datasets/{id}` and one or more `TestSuite` rows reference this dataset
- **THEN** if the dataset's `visibility = PUBLIC`, system SHALL respond with HTTP 409 (FK RESTRICT) — users must rebind or delete those suites first; if the dataset's `visibility = PRIVATE`, system SHALL atomically unbind the single bound suite (`dataset_id := NULL`) and delete the dataset (see the PRIVATE delete requirement in the `datasets` spec)
