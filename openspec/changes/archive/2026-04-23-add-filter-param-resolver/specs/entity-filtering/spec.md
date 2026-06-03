## ADDED Requirements

### Requirement: Filter parameter binding preserves commas verbatim
List endpoints SHALL bind the `filter` query parameter from the raw HTTP parameter values without comma tokenization. A comma occurring inside a single `?filter=…` parameter value SHALL be preserved as a literal character of that filter expression. Multiple filter conditions SHALL be submitted only via repeated `?filter=…` parameters (e.g. `?filter=a:eq:x&filter=b:eq:y`). A single `?filter=a,b` parameter SHALL be treated as one filter expression whose value contains a literal comma, NOT as two filters.

Status: **Planned**.

**Implementation notes**: Enforced at the HTTP binding layer via a custom argument resolver (`FilterParamArgumentResolver` in `com.epam.aidial.evaluation.web.pagination`) that reads `HttpServletRequest.getParameterValues("filter")` directly and bypasses Spring's `StringToCollectionConverter`. Controllers declare the parameter with the `@FilterParam` annotation instead of `@RequestParam`.

#### Scenario: IN filter with comma-separated values in a single parameter
- **WHEN** client sends `?filter=testCaseName:in:Delete1,Delete2`
- **THEN** system SHALL parse it as one IN filter with values `[Delete1, Delete2]` (no splitting of the parameter before `FilterParser` runs)

#### Scenario: Repeated filter parameters produce multiple conditions
- **WHEN** client sends `?filter=name:eq:a&filter=status:eq:active`
- **THEN** system SHALL parse two filter conditions and apply them with AND semantics

#### Scenario: Literal comma in a non-IN value is preserved
- **WHEN** client sends `?filter=name:eq:hello,world` (a single `filter` parameter whose value contains a comma)
- **THEN** system SHALL treat `hello,world` as the literal value of a single `name:eq:…` filter and MUST NOT split it into two filter expressions

### Requirement: Filter parameter count limit is enforced at the binding layer
The per-request upper bound on the number of `filter` parameters (defined in the existing "Upper bound on filter and sort parameter count" requirement) SHALL be enforced at the HTTP binding layer before the filter parser runs. The system SHALL respond with HTTP 400 when the number of repeated `?filter=` parameters exceeds the configured maximum.

Status: **Planned**.

**Implementation notes**: Enforced by the `@FilterParam` annotation's `max` attribute, validated by the argument resolver; preserves the existing `ValidationConstants.MAX_LIST_FILTER_PARAMS` default.

#### Scenario: Too many filter parameters rejected
- **WHEN** client sends more than `MAX_LIST_FILTER_PARAMS` repeated `?filter=` parameters
- **THEN** system SHALL respond with HTTP 400 and a validation error indicating the filter limit was exceeded
