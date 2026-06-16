# Entity Filtering

## Purpose
This spec defines structured filtering and pagination behavior shared by list endpoints (TestSuites, TestCases, MetricDeclarations).

Status: **Implemented** (whitelist, AND semantics, HTTP 400 on invalid filter; `in` operator on STRING/UUID fields).

## Requirements

### Requirement: List endpoints covered by this spec
This spec applies to list endpoints that support pagination, structured filtering, and sort/filter parameter limits. The endpoints in scope SHALL be: TestSuites, TestCases, MetricDeclarations, and TestSuiteMetricDefinitions.

#### Scenario: MetricDeclarations list endpoint
- **WHEN** client calls a MetricDeclarations list endpoint (e.g. `GET /api/v1/metric-declarations`) with pagination, filter, or sort parameters
- **THEN** system SHALL apply the same pagination, filtering, and parameter-limit rules as for TestSuites and TestCases

#### Scenario: TestSuiteMetricDefinitions list endpoint
- **WHEN** client calls a TestSuiteMetricDefinitions list endpoint (e.g. `GET /api/v1/test-suites/{suiteId}/metric-definitions`) with pagination, filter, or sort parameters
- **THEN** system SHALL apply the same pagination, filtering, and parameter-limit rules as for other list endpoints

### Requirement: Pagination parameters are supported on list endpoints
All list endpoints SHALL support pagination parameters.

#### Scenario: Default pagination
- **WHEN** client calls a list endpoint without pagination parameters
- **THEN** system SHALL return `page=0` with `size=20`

#### Scenario: Bounds validation
- **WHEN** client calls a list endpoint with `page < 0` or `size` outside `[1..100]`
- **THEN** system SHALL respond with HTTP 400

### Requirement: Structured filtering via repeatable `filter` parameter
List endpoints SHALL support structured filtering via a repeatable query parameter: `filter=<field>:<op>:<value>`.

Supported operators SHALL include: `eq`, `ne`, `co` (case-insensitive substring), `gt`, `ge`, `lt`, `le`, `in` (set membership). The names `gte` and `lte` SHALL be accepted as **deprecated aliases** of `ge` and `le` for backwards compatibility with clients that have not yet migrated to the new operator vocabulary; aliases SHALL produce identical behavior to their canonical counterparts. Aliases are parser-only and SHALL NOT appear in the OpenAPI / Swagger-generated operator catalog or in per-entity whitelist definitions.

Per-entity whitelists define allowed fields and operators. The whitelist value for each allowed field SHALL be a typed `org.jooq.Field<?>` reference (not a `String` column name), so a schema rename or type change fails at compile time before the request can be served. The type system enforces column existence and column type at compile time; operator allowlisting remains a runtime check returning HTTP 400 with the existing `InvalidFilterException` payload on disallowed operator/field combinations.

The `eq` and `ne` operators on `STRING` and `JSONB_STRING` fields SHALL perform case-insensitive exact matching (`lower(column) = lower(:value)` / `lower(column) <> lower(:value)`). For all other field types (`UUID`, `LONG`, `BOOLEAN`, `JSONB_NUMERIC`), `eq`/`ne` perform exact (non-lowercased) matching.

The `in` operator is supported on `STRING` and `UUID` field types only. The value for `in` is a comma-separated list of values (e.g., `filter=testCaseName:in:name1,name2,name3`). Each element is URL-decoded individually. Literal commas within a value SHALL be percent-encoded as `%2C` by the caller.

**Filter key naming convention**: Filter field names SHALL match the JSON property names in the corresponding response DTO. For primitive `boolean` fields serialized by Jackson without the `is` prefix (e.g., `valid`, `enabled`), the filter key SHALL also omit the `is` prefix.

#### Scenario: AND semantics
- **WHEN** client provides multiple `filter` parameters
- **THEN** system SHALL apply them with AND semantics

#### Scenario: Whitelisted filter fields/operators
- **WHEN** client provides `filter` conditions
- **THEN** system SHALL accept only whitelisted fields/operators for that endpoint
- **AND THEN** SHALL translate each allowed condition to a typed `org.jooq.Condition` against the whitelisted `Field<?>`
- **AND THEN** SHALL bind values as typed DSL parameters — never inline them into SQL text

#### Scenario: Invalid filter
- **WHEN** client provides an invalid filter syntax, unsupported operator, or non-whitelisted field
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Filter key names match JSON response property names
- **WHEN** a response DTO has a boolean field serialized as `valid` in JSON
- **THEN** the filter key SHALL be `valid` (not `isValid`)

#### Scenario: Boolean filter for enabled field
- **WHEN** client sends `filter=enabled:eq:true`
- **THEN** system SHALL filter test cases where enabled is true

#### Scenario: `co` operator rejected for non-string fields
- **WHEN** client sends a `co` filter on a numeric or UUID field
- **THEN** system SHALL respond with HTTP 400

#### Scenario: `co` substring match is case-insensitive
- **WHEN** client sends `filter=name:co:test`
- **THEN** system SHALL return entities whose `name` contains `"test"` regardless of case (e.g., `"MyTest"`, `"TEST"`)

#### Scenario: `eq` on STRING field is case-insensitive
- **WHEN** client sends `filter=name:eq:Test`
- **THEN** system SHALL return entities whose `name` equals `"test"`, `"TEST"`, `"Test"`, etc. (case-insensitive exact match)

#### Scenario: `ne` on STRING field is case-insensitive
- **WHEN** client sends `filter=name:ne:Test`
- **THEN** system SHALL exclude entities whose `name` equals `"test"` in any casing

#### Scenario: `eq` on non-STRING field remains exact-match
- **WHEN** client sends `filter=id:eq:550e8400-E29B-41D4-A716-446655440000`
- **THEN** system SHALL perform exact byte-level comparison (UUID matching is normalized separately)

#### Scenario: `contains` operator is rejected
- **WHEN** client sends a filter with operator `contains` (former operator name, renamed to `co`)
- **THEN** system SHALL respond with HTTP 400 indicating unsupported operator

#### Scenario: `ge` operator on a numeric field
- **WHEN** client sends `filter=createdAt:ge:1700000000000` against an endpoint whose whitelist allows `ge` on `createdAt`
- **THEN** system SHALL return entities whose `createdAt` is greater than or equal to `1700000000000`

#### Scenario: `le` operator on a numeric field
- **WHEN** client sends `filter=createdAt:le:1800000000000` against an endpoint whose whitelist allows `le` on `createdAt`
- **THEN** system SHALL return entities whose `createdAt` is less than or equal to `1800000000000`

#### Scenario: Deprecated `gte` alias is accepted and behaves identically to `ge`
- **WHEN** client sends `filter=createdAt:gte:1700000000000` against the same endpoint
- **THEN** system SHALL behave identically to `filter=createdAt:ge:1700000000000` (same result set, same SQL semantics)
- **AND THEN** the alias SHALL be resolved at the parser layer; the downstream condition builder SHALL operate on the canonical `ge` operator

#### Scenario: Deprecated `lte` alias is accepted and behaves identically to `le`
- **WHEN** client sends `filter=createdAt:lte:1800000000000` against the same endpoint
- **THEN** system SHALL behave identically to `filter=createdAt:le:1800000000000`
- **AND THEN** the alias SHALL be resolved at the parser layer; the downstream condition builder SHALL operate on the canonical `le` operator

#### Scenario: Aliases are case-insensitive
- **WHEN** client sends `filter=createdAt:GTE:1` or `filter=createdAt:Lte:1`
- **THEN** system SHALL accept the request and resolve the operator to canonical `ge` / `le` respectively

#### Scenario: Aliases are not advertised in OpenAPI documentation
- **WHEN** the OpenAPI / Swagger documentation lists allowed operators for a list endpoint
- **THEN** the catalog SHALL list only canonical names (`eq, ne, co, gt, ge, lt, le, in`)
- **AND THEN** the catalog SHALL NOT list `gte` or `lte`

#### Scenario: IN operator matches multiple values for STRING field
- **WHEN** client sends `filter=testCaseName:in:name1,name2`
- **THEN** system SHALL return (or act on) only entities whose `testCaseName` is exactly `name1` or `name2`

#### Scenario: IN operator matches multiple values for UUID field
- **WHEN** client sends a filter like `filter=testCaseId:in:<uuid1>,<uuid2>`
- **THEN** system SHALL match only entities whose ID equals one of the provided UUIDs

#### Scenario: IN operator with a single value
- **WHEN** client sends `filter=testCaseName:in:name1`
- **THEN** system SHALL behave identically to `filter=testCaseName:eq:name1`

#### Scenario: IN operator with empty or blank value rejected
- **WHEN** client sends `filter=testCaseName:in:` or `filter=testCaseName:in:,`
- **THEN** system SHALL respond with HTTP 400

#### Scenario: IN operator on unsupported field type rejected
- **WHEN** client sends an `in` filter on a field whose whitelist type is `BOOLEAN` or `LONG`
- **THEN** system SHALL respond with HTTP 400

#### Scenario: IN operator with invalid UUID element rejected
- **WHEN** client sends `filter=testCaseId:in:not-a-uuid,<validUuid>` where the first element is not a valid UUID
- **THEN** system SHALL respond with HTTP 400

#### Scenario: Comma in value is percent-encoded
- **WHEN** a value contains a literal comma and the client encodes it as `%2C`
- **THEN** system SHALL treat `%2C` as a literal comma within that element, not as a separator

### Requirement: Filter parameter binding preserves commas verbatim
List endpoints SHALL bind the `filter` query parameter from the raw HTTP parameter values without comma tokenization. A comma occurring inside a single `?filter=…` parameter value SHALL be preserved as a literal character of that filter expression. Multiple filter conditions SHALL be submitted only via repeated `?filter=…` parameters (e.g. `?filter=a:eq:x&filter=b:eq:y`). A single `?filter=a,b` parameter SHALL be treated as one filter expression whose value contains a literal comma, NOT as two filters.

**Implementation notes**: Enforced at the HTTP binding layer via a custom argument resolver (`FilterParamArgumentResolver` in `com.epam.aidial.evaluation.web.pagination`) that reads `HttpServletRequest.getParameterValues("filter")` directly and bypasses Spring's `StringToCollectionConverter`. Controllers declare the parameter with the `@FilterParam` annotation instead of `@RequestParam`. Percent-encoding of commas (`%2C`) still works and is no longer required when only one `filter=` parameter is submitted.

#### Scenario: IN filter with comma-separated values in a single parameter
- **WHEN** client sends `?filter=testCaseName:in:Delete1,Delete2`
- **THEN** system SHALL parse it as one IN filter with values `[Delete1, Delete2]` (no splitting of the parameter before `FilterParser` runs)

#### Scenario: Repeated filter parameters produce multiple conditions
- **WHEN** client sends `?filter=name:eq:a&filter=status:eq:active`
- **THEN** system SHALL parse two filter conditions and apply them with AND semantics

#### Scenario: Literal comma in a non-IN value is preserved
- **WHEN** client sends `?filter=name:eq:hello,world` (a single `filter` parameter whose value contains a comma)
- **THEN** system SHALL treat `hello,world` as the literal value of a single `name:eq:…` filter and MUST NOT split it into two filter expressions

### Requirement: Upper bound on filter and sort parameter count (separate limits)
List endpoints SHALL enforce **separate** upper bounds on the number of repeatable `filter` parameters (default 32) and `sort` parameters (default 32) per request. When the client exceeds either limit, the system SHALL respond with HTTP 400.

**Implementation notes**: The `filter` limit is enforced at the HTTP binding layer via the `@FilterParam` annotation's `max` attribute, validated by `FilterParamArgumentResolver` before the filter parser runs. Preserves the existing `ValidationConstants.MAX_LIST_FILTER_PARAMS` default.

#### Scenario: Filter list over limit
- **WHEN** client calls a list endpoint with more than 32 `filter` parameters
- **THEN** system SHALL respond with HTTP 400 and indicate the filter limit was exceeded

#### Scenario: Sort list over limit
- **WHEN** client calls a list endpoint with more than 32 `sort` parameters
- **THEN** system SHALL respond with HTTP 400 and indicate the sort limit was exceeded

#### Scenario: Within limits accepted
- **WHEN** client calls a list endpoint with filter count ≤ 32 AND sort count ≤ 32
- **THEN** system SHALL process the request normally

#### Scenario: Both at limit accepted
- **WHEN** client calls a list endpoint with exactly 32 filters AND 32 sorts
- **THEN** system SHALL process the request normally (limits are inclusive)

### Requirement: JSONB path access goes through a dialect seam
JSONB-typed filter fields (`JSONB_STRING`, `JSONB_NUMERIC`) SHALL traverse JSONB paths via the injectable `JsonPathAccessor` component, not via plain-SQL templating in `WhereBuilder`. Behavior for callers is unchanged: dotted field syntax (`testCaseData.someKey`, `metricValues.<metric>.<output>`) is still parsed at the filter layer, validated against the field type's path-depth rule, and bound as named parameters.

Status: **Implemented**

#### Scenario: Single-level JSONB path
- **WHEN** a client sends `filter=testCaseData.userId:eq:abc`
- **THEN** the filter layer SHALL invoke `JsonPathAccessor.jsonbAtAsText(testCaseData, val("userId"))` and compare the result to `val("abc")` via a typed `Condition`

#### Scenario: Two-level JSONB numeric path
- **WHEN** a client sends `filter=metricValues.Accuracy.score:ge:0.8`
- **THEN** the filter layer SHALL invoke `JsonPathAccessor.jsonbAtAsNumeric(metricValues, val("Accuracy"), val("score"))` and compare the result to `val(new BigDecimal("0.8"))` via a typed `Condition`

#### Scenario: Path depth validation unchanged
- **WHEN** a client sends a three-level path on a `JSONB_NUMERIC` field (e.g. `metricValues.a.b.c:ge:1`)
- **THEN** the system SHALL respond with HTTP 400 with the existing "nested JSONB paths deeper than two levels not supported" message

#### Scenario: JSONB path keys bound as DSL parameters
- **WHEN** a filter targets a `JSONB_NUMERIC` field (e.g. `metricValues.Accuracy.score:ge:0.8`)
- **THEN** the SQL SHALL bind each JSONB path key (`"Accuracy"`, `"score"`) as a parameter via `DSL.val(String)`, NOT inline them via `DSL.inline(String)` and NOT pass them as identifier fragments via `DSL.field(String)`
- **AND THEN** the path-key parameters SHALL flow through the same prepared-statement binding pipeline as the comparison value
- **AND THEN** SQL injection via path keys SHALL be impossible by construction, even though path keys are already constrained by configured whitelists

## Open Questions / Follow-up Task

### Requirement: Filtering DSL refinement (future)
The filtering DSL SHALL be reviewed and refined in a separate task after initial UI/usage feedback.

#### Scenario: Future extension areas
- **WHEN** filtering requirements expand
- **THEN** system MAY introduce OR groups, nested jsonb-path filters, `isnull` operator, and/or `q` search (case-sensitive or case-insensitive), or adopt a standard query language (e.g., RSQL/OData) while preserving backward compatibility where possible
