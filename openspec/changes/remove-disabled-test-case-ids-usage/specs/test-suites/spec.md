## REMOVED Requirements

### Requirement: Per-suite `disabledTestCaseIds`
**Reason**: Superseded by the per-suite `testCaseFilter` (run conditions — see `suite-test-case-filter`).
Keeping both meant two independent selection predicates that provably disagreed: the FE stopped authoring
`disabledTestCaseIds`, while the `test_cases` query-DSL surface the UI counts against never applied the
exclusion. Legacy suites therefore executed fewer test cases than the UI reported, and a run condition
combined with stale exclusions produced an empty runnable set and a spurious 409 (GH #151).

**Migration**: Express exclusions as a suite `testCaseFilter` — a Structured Query DSL filter over the bound
dataset's test-case fields (e.g. a predicate on `test_case_name` or a `data::<field>` value). The
`disabledTestCaseIds` field is removed from the suite create/update request body and from the suite
response; a request that still carries it is ignored rather than rejected (unknown properties are not
strict-bound). No client action is required for existing suites to keep running: exclusions already stored
in `test_suites.disabled_test_case_ids` are no longer read, so an affected suite runs every valid test case
matching its `testCaseFilter` — the count the UI already reports. The `ValidationConstants.MAX_DISABLED_TC_IDS`
cap (10000) is removed along with the field.

## MODIFIED Requirements

### Requirement: Create a TestSuite
The service SHALL allow creating a new TestSuite. The request body SHALL accept `suiteType` (optional, defaults to `DEPLOYMENT`) and `datasetId` (required — FK to `datasets.id`). For `DEPLOYMENT` suites: `requestTemplate`, `inputBindings`, `deploymentRef`, `endpointRef` (existing behavior — `deploymentRef` hard-required, `endpointRef`/`requestTemplate` soft-validated). For `MCP_TOOL` suites: `inputBindings`, `mcpDeploymentRef` (hard-required), `toolRef` (hard-required), `argumentTemplate` (soft-validated — null produces warning). `testCaseSchema` SHALL NOT appear on the suite request — it lives on the referenced dataset. `disabledTestCaseIds` SHALL NOT appear on the suite request — the runnable subset is narrowed by `testCaseFilter` (see `suite-test-case-filter`). The system SHALL perform type-specific validation and suite-level soft validation, sourcing the dataset's schema via `DatasetSchemaProvider` for binding cross-checks. Additionally, the system SHALL support cloning an existing suite via `POST /api/v1/test-suites/{sourceId}/clone` (see `test-suite-clone` spec).
Status: **Planned**

#### Scenario: Valid DEPLOYMENT payload
- **WHEN** client calls `POST /api/v1/test-suites` with a valid body including `datasetId`, `deploymentRef`, `requestTemplate`, and `inputBindings` (see "Type-specific field validation" requirement for `deploymentRef` hard-requirement and `endpointRef`/`requestTemplate` soft-validation rules)
- **THEN** system SHALL create a new TestSuite with `suiteType = DEPLOYMENT`, perform suite-level soft validation against the dataset's schema, and return the created entity including `isValid` and `validationWarnings`

#### Scenario: Valid MCP_TOOL payload
- **WHEN** client calls `POST /api/v1/test-suites` with `"suiteType": "MCP_TOOL"`, valid `datasetId`, `mcpDeploymentRef`, `toolRef`, and `inputBindings`
- **THEN** system SHALL create a new TestSuite with `suiteType = MCP_TOOL`, perform MCP-specific validation against the dataset's schema, and return the created entity

#### Scenario: testCaseSchema in body is rejected or ignored
- **WHEN** client sends a create body containing a `testCaseSchema` field
- **THEN** system SHALL ignore the field (per Jackson default) or respond with HTTP 400 if strict-binding is enabled; in either case the field SHALL NOT influence the persisted suite (schema is owned by the dataset)

#### Scenario: disabledTestCaseIds in body is ignored
- **WHEN** client sends a create or update body containing a `disabledTestCaseIds` field
- **THEN** system SHALL ignore the unknown field (unknown-property failure is disabled) and SHALL respond with the normal success status; the field SHALL NOT appear in the response and SHALL NOT influence which test cases the suite runs

#### Scenario: CreatedBy attribution
- **WHEN** `config.rest.security.mode=oidc` and an authenticated client creates a TestSuite
- **THEN** system SHALL store `createdBy` from JWT subject

#### Scenario: Missing author is rejected in OIDC mode
- **WHEN** `config.rest.security.mode=oidc` and a request is not authenticated (no user detected)
- **THEN** system SHALL reject the request with HTTP 401

#### Scenario: Anonymous author allowed only in no-security mode
- **WHEN** `config.rest.security.mode=none` and an unauthenticated client creates a TestSuite
- **THEN** system SHALL store `createdBy` as `anonymous`

#### Scenario: Embedded deployment and endpoint references (DEPLOYMENT only)
- **WHEN** client calls `POST /api/v1/test-suites` with `suiteType = DEPLOYMENT` and valid `deploymentRef` and `endpointRef`
- **THEN** system SHALL persist those objects as part of the TestSuite and return them in the response

#### Scenario: Clone via dedicated endpoint
- **WHEN** client calls `POST /api/v1/test-suites/{sourceId}/clone` with a valid clone request body
- **THEN** system SHALL deep-copy suite-level configuration (the cloned suite references the same dataset as the source by default; see `test-suite-clone` spec)

### Requirement: Suite-level soft validation (`isValid` + `validationWarnings`)
The TestSuite response SHALL include `isValid` (boolean) and `validationWarnings` (structured list, same format as TestCase validation warnings). Suite-level validation covers **configuration correctness only**. Test-case presence is **not** a component of stored suite validity and does not affect `isValid` or `validationWarnings` in the suite GET response. A bound suite with zero runnable test cases MAY be `isValid = true`; the run path enforces the presence requirement at run-creation time (see `test-suite-runs` spec).

Suite-level configuration correctness covers: template + bindings, cross-checked against the **referenced dataset's** `testCaseSchema` (resolved via `DatasetSchemaProvider`) and the suite's `responseColumns` / `endpointRef`. This dimension is **independent of test case data**.

`isValid` SHALL be `true` only when the configuration dimension produces no warnings. `isValid` SHALL be recalculated on every suite create or update, and on dataset bind/detach and dataset schema revalidation (Phase 2). TestCase `isValid` (test-case **data** validation) is owned by the test-case domain and is never triggered by suite validation — the configuration and data layers remain independent.

Status: **Implemented**

#### Scenario: Create returns suite validation result
- **WHEN** client creates a TestSuite with `requestTemplate` and `inputBindings`
- **THEN** the response SHALL include `isValid` and `validationWarnings` reflecting suite-level configuration checks (urlTemplate, binding coverage, binding references against the dataset's schema, template conformance to endpoint schema)

#### Scenario: Update recalculates suite validation
- **WHEN** client updates `requestTemplate`, `inputBindings`, `endpointRef`, `responseColumns`, or `datasetId`
- **THEN** system SHALL recalculate `isValid` and `validationWarnings` for the suite against the (possibly newly-bound) dataset's schema

#### Scenario: Suite valid — no warnings
- **WHEN** all suite-level configuration checks pass (urlTemplate valid, all required variables bound, all bindings reference fields in the dataset's schema and template variables, template conforms to endpoint schema)
- **THEN** `isValid` SHALL be `true` and `validationWarnings` SHALL be empty

#### Scenario: Suite invalid — configuration warnings produced
- **WHEN** any suite-level configuration check fails (e.g., urlTemplate null, required variable unbound, binding references field not in dataset schema)
- **THEN** `isValid` SHALL be `false` and `validationWarnings` SHALL contain structured warning objects (`fieldName`, `path`, `message`, optional `code`)

#### Scenario: Suite with no request template produces warning
- **WHEN** TestSuite has `requestTemplate: null`
- **THEN** `isValid` SHALL be `false` and `validationWarnings` SHALL include a warning ("urlTemplate is required for request assembly") — same as when `requestTemplate` is non-null but `urlTemplate` is null

#### Scenario: Bound suite with no test cases is still config-valid
- **WHEN** a bound suite's configuration is valid but the referenced dataset has no test cases, or all are invalid, or none match the suite's `testCaseFilter`
- **THEN** `isValid` SHALL be `true` and `validationWarnings` SHALL be empty (test-case presence is not a suite-validity concern; the run path enforces it separately)

#### Scenario: Unbound suite is not subject to the runnable-test-case rule
- **WHEN** a suite has `datasetId == null`
- **THEN** `isValid` SHALL reflect configuration checks only and SHALL NOT carry a `NO_TEST_CASES` warning

#### Scenario: Suite revalidated when dataset schema changes
- **WHEN** the referenced dataset's `testCaseSchema` is updated (via dataset PUT) and the dataset-rooted `RevalidationTask` runs Phase 2
- **THEN** the suite's `isValid` and `validationWarnings` SHALL be refreshed by the task's per-suite handler, reflecting configuration correctness against the new schema (see `datasets` spec for Phase 2 semantics)

## Implementation notes
- The field is removed from `service/domain/dto/TestSuiteRequestDto.java`, from
  `evaluation-runner-core`'s `runner/dto/TestSuiteResponseDto.java`, from `data/db/model/TestSuite.java`,
  and from every mapping site in `service/domain/mapper/TestSuiteMapper.java`
  (`serializeDisabledIds` / `deserializeDisabledIds` / `remapDisabledIds` are deleted).
- `constants/ValidationConstants.MAX_DISABLED_TC_IDS` is deleted.
- The `test_suites.disabled_test_case_ids` column is intentionally left in place, unread and unwritten:
  `data/db/mapper/TestSuiteRecordMapper.java` no longer maps it and
  `data/db/repository/PostgresTestSuiteRepository.java` no longer sets it, so inserts fall back to the
  column's `DEFAULT '[]'::jsonb`. Dropping the column is a follow-up change.
