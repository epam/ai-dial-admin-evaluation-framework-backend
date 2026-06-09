## MODIFIED Requirements

### Requirement: TSMD cloning
The system SHALL clone all test suite metric definitions from the source suite into the new suite. Each cloned TSMD SHALL receive a new UUID, the new suite's ID as `testSuiteId`, and fresh timestamps. The `name`, `metricDeclarationId`, `metricDeclarationVersionId`, `enabled`, `configBindings`, and `inputBindings` fields SHALL be copied.

The cloned TSMD's `isValid` and `validationWarnings` SHALL be determined by whether **TSMD revalidation is required**:

- **TSMD revalidation is required** when the clone request supplies a `datasetId` override that differs from the source suite's `datasetId`, OR supplies a `responseColumns` override. In this case the cloned TSMD's `isValid` and `validationWarnings` SHALL be recomputed synchronously, inside the clone transaction, against the resolved dataset's `testCaseSchema` and the cloned suite's `responseColumns`.
- **TSMD revalidation is NOT required** when the clone request supplies neither a differing `datasetId` override nor a `responseColumns` override (including the private-dataset auto-clone, where the cloned dataset's `testCaseSchema` is copied verbatim). In this case every input to TSMD validation is identical to the source, and the cloned TSMD's `isValid` and `validationWarnings` SHALL be copied verbatim from the source TSMD.

In both cases validation is synchronous and within the clone transaction; the clone SHALL NOT spawn an async `RevalidationTask` for TSMDs. Status: **Planned**

#### Scenario: TSMDs are deep-copied
- **WHEN** source suite has 2 TSMDs
- **THEN** cloned suite SHALL have 2 TSMDs with identical configuration but new UUIDs

#### Scenario: Paginated TSMD copying
- **WHEN** source suite has more TSMDs than the configured batch size
- **THEN** system SHALL read and insert TSMDs in paginated batches

#### Scenario: TSMD referencing deleted metric declaration is skipped
- **WHEN** source suite has a TSMD whose `metricDeclarationId` no longer exists in `metric_declarations`
- **THEN** that TSMD SHALL be silently excluded from the cloned suite (the INNER JOIN on `metric_declarations` excludes the orphaned row)
- **AND** no error SHALL be thrown; remaining valid TSMDs are cloned normally

#### Scenario: Validity is copied verbatim on a vanilla clone
- **WHEN** client clones with `{"name": "Copy"}` only (no `datasetId` or `responseColumns` override) and a source TSMD has `isValid = true` with empty `validationWarnings`
- **THEN** the corresponding cloned TSMD SHALL have `isValid = true` and the same `validationWarnings`, with no recompute performed

#### Scenario: Invalid source TSMD validity is preserved on a vanilla clone
- **WHEN** client clones with no `datasetId` or `responseColumns` override and a source TSMD has `isValid = false` with one or more `validationWarnings`
- **THEN** the corresponding cloned TSMD SHALL have `isValid = false` and the same `validationWarnings` copied verbatim

#### Scenario: Validity is copied verbatim on a private-dataset auto-clone
- **WHEN** the source suite is bound to a PRIVATE dataset, the client supplies no `datasetId` override, and the dataset is auto-cloned with its `testCaseSchema` copied verbatim
- **THEN** each cloned TSMD SHALL have its `isValid` and `validationWarnings` copied verbatim from the source TSMD (no recompute)

#### Scenario: Validity is recomputed when datasetId is overridden
- **WHEN** client clones with `{"name": "Copy", "datasetId": "<other-id>"}` where the supplied dataset's `testCaseSchema` differs such that a TSMD's test-case-bound parameter no longer resolves
- **THEN** that cloned TSMD's `isValid` and `validationWarnings` SHALL reflect synchronous revalidation against the supplied dataset's schema, independent of the source TSMD's stored validity

#### Scenario: Validity is recomputed when responseColumns is overridden
- **WHEN** client clones with a `responseColumns` override that removes a column referenced by a TSMD's response-bound parameter
- **THEN** that cloned TSMD's `isValid` SHALL be `false` with a corresponding unresolved-reference warning, reflecting synchronous revalidation against the overridden response columns

## Implementation notes

- `TestSuiteCloneService.executeDbWrites` performs the conditional copy-vs-recompute; the recompute branch delegates to `TestSuiteMetricDefinitionService.revalidateAllForSuite(suiteId, testCaseSchemaJson, responseColumnsJson)` after the TSMD inserts, within the same `TransactionTemplate` execution.
- TSMD validation logic itself is unchanged (`MetricDefinitionValidationService.validate`); only the orchestration in the clone path changes.
- The "TSMD revalidation required" decision is computed in `TestSuiteCloneService.clone` from `dto.getDatasetId()` (vs. the source `datasetId`) and `dto.getResponseColumns()`.
