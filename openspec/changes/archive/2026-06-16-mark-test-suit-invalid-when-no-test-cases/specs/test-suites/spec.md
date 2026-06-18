## MODIFIED Requirements

### Requirement: Suite-level soft validation (`isValid` + `validationWarnings`)
Status: **Implemented**
Suite-level validation covers **configuration correctness only**. Test-case presence is **not** a component of stored suite validity and does not affect `isValid` or `validationWarnings` in the suite GET response. A bound suite with zero runnable test cases MAY be `isValid = true`; the run path enforces the presence requirement at run-creation time (see `test-suite-runs` spec).

#### Scenario: Bound suite with no test cases is still config-valid
- **WHEN** a bound suite's configuration is valid but the referenced dataset has no test cases, or all are invalid, or all are excluded by `disabledTestCaseIds`
- **THEN** `isValid` SHALL be `true` and `validationWarnings` SHALL be empty (test-case presence is not a suite-validity concern; the run path enforces it separately)

#### Implementation notes
- `SuiteValidationService` (service/domain/SuiteValidationService.java) — config-only validation; test-case presence not evaluated here.
