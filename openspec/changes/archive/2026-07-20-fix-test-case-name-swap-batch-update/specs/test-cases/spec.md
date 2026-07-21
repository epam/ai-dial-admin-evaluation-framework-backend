## ADDED Requirements

### Requirement: Batch name permutation within a single operation succeeds
When a single batch operation reassigns `testCaseName` values among test cases in the same dataset such that the operation's **final state** contains no duplicate names (case-insensitive), the service SHALL apply the update successfully with HTTP 200 (or the endpoint's normal success status), even when an intermediate assignment would momentarily duplicate a name. This covers arbitrary permutations — pairwise swaps and longer rename cycles. This applies to batch PUT (`PUT /api/v1/datasets/{datasetId}/test-cases`), batch PATCH (`PATCH /api/v1/datasets/{datasetId}/test-cases`), and the `itemOperations` of composite bulk patch (`PATCH /api/v1/datasets/{datasetId}/test-cases:bulk`). Final-state duplicate detection is unchanged: genuine collisions (two items ending with the same name, or an item colliding with a name outside the batch) still return HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) and roll back the whole transaction.
Status: **Planned**

#### Scenario: Pairwise name swap via batch PUT succeeds
- **WHEN** a dataset has test cases named `A` and `B`, and client calls `PUT /api/v1/datasets/{datasetId}/test-cases` with the first item renaming `A → B` and the second renaming `B → A`
- **THEN** system SHALL return HTTP 200 and the two test cases SHALL have their names swapped (no HTTP 409)

#### Scenario: Pairwise name swap via batch PATCH succeeds
- **WHEN** a dataset has test cases named `A` and `B`, and client calls `PATCH /api/v1/datasets/{datasetId}/test-cases` with the first item renaming `A → B` and the second renaming `B → A`
- **THEN** system SHALL return HTTP 200 and the two test cases SHALL have their names swapped (no HTTP 409)

#### Scenario: Multi-way rename cycle succeeds
- **WHEN** a dataset has test cases named `A`, `B`, and `C`, and a single batch operation renames `A → B`, `B → C`, and `C → A`
- **THEN** system SHALL return HTTP 200 and the three test cases SHALL reflect the rotated names (no HTTP 409)

#### Scenario: Name swap via composite bulk patch itemOperations succeeds
- **WHEN** a dataset has test cases named `A` and `B`, and client calls `PATCH /api/v1/datasets/{datasetId}/test-cases:bulk` with two `itemOperations`, one renaming `A → B` and the other renaming `B → A`
- **THEN** system SHALL return HTTP 200 and the two test cases SHALL have their names swapped (no HTTP 409)

#### Scenario: Genuine final-state duplicate within a permutation still rejected
- **WHEN** a batch operation assigns the same final `testCaseName` to two items (a real duplicate, not a permutation)
- **THEN** system SHALL respond with HTTP 409 (UNIQUE_CONSTRAINT_VIOLATION) and roll back all changes

## Implementation Notes
- The transient collision arises because names are persisted via sequential per-row `UPDATE`s while the unique index `(dataset_id, LOWER(test_case_name))` is non-deferrable and checked after each statement.
- Fix is a two-phase write inside the existing `@Transactional` boundary: phase 1 parks every affected row's `test_case_name` at a collision-proof temporary value, phase 2 applies the final names.
- Code: `data.db.repository.PostgresTestCaseRepository.parkTestCaseNames` + two-phase `batchUpdate` (covers batch PUT/PATCH via `TestCaseService.persistBatch`); `service.domain.TestCaseService.bulkPatch` item-operations loop restructured into prepare → park → apply.
- Final-state uniqueness gate is unchanged: `TestCaseService.validateBatchNameUniqueness` still rejects genuine duplicates before any write.
