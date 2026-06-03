## ADDED Requirements

### Requirement: Schema-driven data cleanup on TestSuite schema change
When a TestSuite's `testCaseSchema` is updated via the API (PUT/PATCH) and fields are removed from the schema, the system SHALL synchronously remove the corresponding keys from the `data` field of all TestCases in that suite within the same transaction as the schema update. This ensures `data` never contains keys that are not defined in the current schema.

#### Scenario: Removed schema field is cleaned from all TestCases
- **WHEN** client updates a TestSuite and the new `testCaseSchema` removes one or more fields that were present before
- **THEN** system SHALL remove those keys from the `data` JSONB of every TestCase in the suite within the same transaction as the schema update

#### Scenario: Added or unchanged schema fields are unaffected
- **WHEN** client updates a TestSuite and the new `testCaseSchema` adds fields or leaves existing fields unchanged
- **THEN** system SHALL NOT modify any TestCase `data` fields (no keys to remove)

#### Scenario: No orphaned keys after cleanup
- **WHEN** schema cleanup has run following a schema update
- **THEN** no TestCase in the suite SHALL have a `data` key that is absent from the current `testCaseSchema`

#### Scenario: Schema cleared entirely
- **WHEN** client updates a TestSuite setting `testCaseSchema` to empty/null
- **THEN** system SHALL remove all previously schema-defined keys from every TestCase's `data` in the suite
