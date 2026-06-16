## ADDED Requirements

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

## Implementation notes
- The cloning write surface is `service.domain.DatasetCloneService` (depends only on `DatasetRepository`, `TestCaseRepository`, `FileService`, `RevalidationProperties`), keeping suite-domain code from writing dataset/test-case rows directly. Name collision pre-check via new `DatasetRepository.existsByNameIgnoreCase`. See the `test-suite-clone` capability for the orchestration and trigger interaction.
