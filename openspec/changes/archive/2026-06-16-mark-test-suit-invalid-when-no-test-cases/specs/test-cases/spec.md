## MODIFIED Requirements

### Requirement: Test case mutations do not trigger suite validity recalculation
Status: **Implemented**
Test-case create, update, patch, batch-update, batch-patch, bulk-patch, delete, deleteAll, and CSV import operations SHALL NOT trigger any recalculation or update of the owning suite's `isValid` / `validationWarnings` fields. Suite validity is config-only and is never recalculated from test-case mutations. Test-case presence is enforced at run-creation time only (see `test-suite-runs` spec).

#### Scenario: Creating a test case does not change suite validity
- **WHEN** a test case is created in a dataset
- **THEN** the `isValid` and `validationWarnings` of any suite bound to that dataset SHALL remain unchanged

#### Scenario: Deleting a test case does not change suite validity
- **WHEN** a test case is deleted from a dataset
- **THEN** the `isValid` and `validationWarnings` of any suite bound to that dataset SHALL remain unchanged
