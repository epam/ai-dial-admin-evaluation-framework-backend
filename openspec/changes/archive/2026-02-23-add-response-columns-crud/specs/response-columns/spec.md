# Response Columns

## Purpose

Defines the storage, validation, and evaluation of user-defined response column definitions scoped to a TestSuite. Each column specifies a JSONata expression to extract a named value from a test result's response body. Column definitions are stored as JSONB on the TestSuite (like `testCaseSchema`). At run time, expressions are evaluated against each result's `responseBody` and extracted values are persisted in the analytics DB for downstream sort/filter/analytics.

Status: **Planned**

## Key Terms

- **ResponseColumn**: A named column definition attached to a TestSuite that describes how to extract a display value from a test result's response body using a JSONata expression.
- **name**: Stable identifier for the column. Used as key in `extracted_columns`, CSV headers, and FE references. Must be unique within the suite's response columns array.
- **displayName**: Optional human-friendly UI label. FE falls back to `name` if not set.
- **SchemaFieldType**: Shared type enum (`STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`, `OBJECT`, `ARRAY`) used for both testCaseSchema fields and response columns.

## Data Shape

**ResponseColumn definition** (element in `test_suites.response_columns` JSONB array):
```json
{
  "name": "answer",
  "displayName": "Response Content",
  "expression": "choices[0].message.content",
  "type": "STRING"
}
```

**Minimal definition** (displayName and type optional):
```json
{
  "name": "token_count",
  "expression": "usage.total_tokens"
}
```

Defaults when omitted: `displayName` = null (FE uses `name`), `type` = `STRING`.

**TestSuite with response columns** (partial, showing new field):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "My Suite",
  "testCaseSchema": [
    {"name": "question", "type": "STRING", "required": true}
  ],
  "responseColumns": [
    {"name": "answer", "displayName": "Answer", "expression": "choices[0].message.content", "type": "STRING"},
    {"name": "token_count", "expression": "usage.total_tokens", "type": "NUMBER"}
  ]
}
```

**Extracted values** (stored in `test_case_run_results.extracted_columns` JSONB):
```json
{
  "answer": "Paris is the capital of France.",
  "token_count": 25
}
```

**Extraction warnings** (stored in `test_case_run_results.extraction_warnings` JSONB):
```json
[
  {
    "column": "token_count",
    "expression": "usage.total_tokens",
    "error": "Path 'usage' not found in response body"
  }
]
```

## Requirements

### Requirement: Store response column definitions on TestSuite

Response column definitions SHALL be stored as a JSONB array on the `test_suites` table, managed via existing suite create/update endpoints.

Status: **Planned**

#### Scenario: Create suite with response columns
- **WHEN** client calls `POST /api/v1/test-suites` with `responseColumns` in the request body
- **THEN** system SHALL persist the definitions and return them in the response

#### Scenario: Update suite response columns
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with updated `responseColumns`
- **THEN** system SHALL replace the existing definitions with the new array

#### Scenario: Suite with no response columns
- **WHEN** `responseColumns` is omitted or null on create/update
- **THEN** system SHALL default to an empty array `[]`

#### Scenario: Get suite returns response columns
- **WHEN** client calls `GET /api/v1/test-suites/{id}`
- **THEN** response SHALL include `responseColumns` array (empty if none defined)

### Requirement: Validate JSONata expressions on save

The service SHALL validate each response column's `expression` as syntactically valid JSONata when the suite is created or updated. Invalid expressions SHALL be rejected with HTTP 400.

Status: **Planned**

#### Scenario: Valid expression
- **WHEN** suite is saved with `expression: "choices[0].message.content"`
- **THEN** system SHALL accept and persist the column

#### Scenario: Invalid expression
- **WHEN** suite is saved with `expression: "choices[0.message.content"` (syntax error)
- **THEN** system SHALL return HTTP 400 with a validation error indicating which column and what the parse error is

#### Scenario: Duplicate column names
- **WHEN** suite is saved with two columns having the same `name`
- **THEN** system SHALL return HTTP 400

### Requirement: Evaluate expressions at result write time

When a test case run result is stored, the system SHALL evaluate all of the suite's response column expressions against the result's `responseBody` and persist the extracted values.

Status: **Planned**

#### Scenario: Successful extraction
- **WHEN** a result is written with a non-null `responseBody` and the suite has response columns
- **THEN** `extracted_columns` SHALL contain a key for each column `name` with the evaluated value
- **AND** `extraction_warnings` SHALL be empty

#### Scenario: Expression evaluation failure
- **WHEN** a JSONata expression fails to evaluate (path not found, type error, etc.)
- **THEN** `extracted_columns[columnName]` SHALL be `null`
- **AND** `extraction_warnings` SHALL contain an entry with `column`, `expression`, and `error`

#### Scenario: Null response body
- **WHEN** `responseBody` is null (e.g., timeout, connection error)
- **THEN** all `extracted_columns` values SHALL be `null`
- **AND** `extraction_warnings` SHALL contain entries for each column

#### Scenario: No response columns defined
- **WHEN** a result is written and the suite has no response columns
- **THEN** `extracted_columns` SHALL be `{}` and `extraction_warnings` SHALL be `[]`

### Requirement: displayName on testCaseSchema fields

`FieldDefinitionDto` SHALL support an optional `displayName` field for UI label purposes.

Status: **Planned**

#### Scenario: Field with displayName
- **WHEN** testCaseSchema includes `{"name": "q", "displayName": "Question", "type": "STRING"}`
- **THEN** system SHALL persist and return the displayName

#### Scenario: Field without displayName
- **WHEN** testCaseSchema includes `{"name": "q", "type": "STRING"}` (no displayName)
- **THEN** system SHALL persist it as-is; FE uses `name` as display label

## Validation Rules

**Response columns array:**

| Constraint          | Rules |
|---------------------|-------|
| `responseColumns`   | Max 50 elements per suite |

**Response column definition (each element):**

| Field        | Rules |
|--------------|-------|
| `name`       | Required, not blank, max 255 characters, unique within the array |
| `displayName`| Optional, max 255 characters |
| `expression` | Required, not blank, max 2000 characters, must be valid JSONata |
| `type`       | Optional; one of `SchemaFieldType` values; defaults to `STRING` via DTO `@Builder.Default` |

**testCaseSchema field (modified):**

| Field        | Rules |
|--------------|-------|
| `displayName`| Optional (new), max 255 characters |
| _(other fields unchanged)_ | |

## Implementation Notes

- **Meta DB migration** `V1.8__AddResponseColumnsToTestSuites.sql`:
  - `ALTER TABLE test_suites ADD COLUMN response_columns JSONB NOT NULL DEFAULT '[]'::jsonb;`
- **Analytics DB migration** `V1.2__AddExtractedColumnsToTestCaseRunResults.sql`:
  - `ALTER TABLE test_case_run_results ADD COLUMN extracted_columns JSONB NOT NULL DEFAULT '{}'::jsonb;`
  - `ALTER TABLE test_case_run_results ADD COLUMN extraction_warnings JSONB NOT NULL DEFAULT '[]'::jsonb;`
- **New DTO**: `ResponseColumnDefinitionDto` in `service.domain.dto` — `name`, `displayName`, `expression`, `type` (SchemaFieldType).
- **Modified DTO**: `FieldDefinitionDto` — add `displayName` (String, optional, `@Size(max=255)`).
- **Modified DTOs**: `TestSuiteRequestDto`, `TestSuiteResponseDto` — add `List<ResponseColumnDefinitionDto> responseColumns`.
- **Modified model**: `TestSuite` — add `String responseColumns` (JSONB string).
- **Modified model**: `TestCaseRunResult` — add `String extractedColumns`, `String extractionWarnings`.
- **New component**: `JsonataEvaluationService` in `service.domain` — validates expressions (parse-only), evaluates expressions against JSON data.
- **Modified service**: `TestSuiteService.validateTestSuiteSchemas()` — add JSONata validation for response columns.
- **Modified service**: Result write path — evaluate response columns after building result. Extraction is a **job-layer concern**: `MockResultsGenerator` (and future real runners) load the suite's `responseColumns` from meta DB and evaluate expressions before calling the analytics batch writer. The analytics batch write API (`POST /api/v1/analytics/results`) is a pure persistence endpoint and does NOT trigger extraction — external callers must pre-populate `extractedColumns`/`extractionWarnings` or accept empty defaults.
- **JSONB serialization**: Uses existing `JsonbMapper` pattern for responseColumns. Uses `ValidationWarningsSerializer` pattern for extraction warnings.
- **JSONata library**: `com.dashjoin:jsonata` or `com.ibm.jsonata4java:JSONata4Java` (choice deferred to implementation spike).
- **Transaction**: Response columns are part of `test_suites` — uses `@Transactional("metaTransactionManager")`. Extraction happens in the analytics write path — uses `@Transactional("analyticsTransactionManager")`.

## Deferred

- Re-evaluation of historical results when expressions change.
- Eval results CSV export with extracted columns.
- Unified schema at suite level (merging testCaseSchema + responseColumns).
- Import/export of response columns as part of suite CSV import (suite JSON export includes them naturally).
