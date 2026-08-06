## Why

The CSV export of evaluation summaries uses `:` (colon) as the family separator inside column shapes — `data:<field>`, `response:<column>`, `metric:<metricName>:<fieldName>`, `metricInfo:<metricName>:<fieldName>`, `metricError:<metricName>`. If any user-defined identifier itself contains a `:`, the resulting header is ambiguous and downstream tooling (and our own re-parse) cannot distinguish the family separator from identifier content. We need to keep the colon reserved at the create/update boundary so future data is well-formed.

## What Changes

- Reject names containing `:` (HTTP 400) on three identifier types at the API boundary:
  - Test case schema field names (`FieldDefinitionDto.name`, nested inside `TestSuiteRequestDto.testCaseSchema`)
  - Response column names (`ResponseColumnDefinitionDto.name`, nested inside `TestSuiteRequestDto.responseColumns`)
  - Test suite metric definition names (`TestSuiteMetricDefinitionRequestDto.name`)
- Validation applies uniformly to POST (create) and PUT (update) endpoints — a `@Pattern` constraint on each DTO name field. Pre-existing rows are NOT migrated; their owners must rename colon-bearing identifiers next time they update the suite.
- Add a shared `IDENTIFIER_NAME_NO_COLON_PATTERN` constant + message to `ValidationConstants` so the rule is defined once.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `test-suites`: test case schema field name MUST NOT contain `:`; applies to POST and PUT.
- `response-columns`: response column name MUST NOT contain `:`; applies to POST and PUT.
- `test-suite-metric-definitions`: metric definition name MUST NOT contain `:`; applies to POST and PUT.

## Impact

- **APIs affected:** `POST /api/v1/test-suites`, `PUT /api/v1/test-suites/{id}`, `POST /api/v1/test-suites/{testSuiteId}/metric-definitions`, `PUT /api/v1/test-suites/{testSuiteId}/metric-definitions/{id}` — return HTTP 400 with `code=VALIDATION_ERROR` when any covered name contains `:`.
- **DTOs touched:** `FieldDefinitionDto`, `ResponseColumnDefinitionDto`, `TestSuiteMetricDefinitionRequestDto` (one `@Pattern` annotation each).
- **Constants touched:** `com.epam.aidial.evaluation.constants.ValidationConstants` (two new `public static final String` constants).
- **No DB migration**, no Flyway changes, no schema doc updates.
- **No configuration property changes**, no `docs/configuration.md` updates.
- **Compatibility:** Legacy rows whose names already contain `:` remain readable from the DB. Any attempt to PUT such a suite/metric-definition without first renaming will fail validation — this is acceptable because no migration ships and the resulting failure is loud, not silent.
- **Risk:** Owners of legacy colon-bearing suites get a 400 the first time they touch the suite after this change ships. Mitigated by clear error message naming the offending field.
- **Test plan:** unit tests on the three DTOs (validator-driven, no Spring context); functional tests for the 4 endpoints × 2 verbs covered above.
