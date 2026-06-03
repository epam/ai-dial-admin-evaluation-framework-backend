## MODIFIED Requirements

### Requirement: Output mapping to metricValues and metricInfos
The system SHALL map `EvaluationResponse` output fields to EvalSummary's `metricValues` and `metricInfos` JSONB columns, keyed by TSMD name. Output field names SHALL always come from the metric's actual output schema, never from synthetic placeholder keys.
Status: **Planned**

#### Scenario: Value output without details
- **WHEN** an output field has `{type: "value", value: 1}` with no details
- **THEN** `metricValues[tsmdName][fieldName]` SHALL be `1` and no entry SHALL be added to metricInfos for that field

#### Scenario: Value output with details
- **WHEN** an output field has `{type: "value", value: 0.8, details: {"reason": "..."}}`
- **THEN** `metricValues[tsmdName][fieldName]` SHALL be `0.8` and `metricInfos[tsmdName][fieldName]` SHALL be `{"reason": "..."}`

#### Scenario: Error output
- **WHEN** an output field has `{type: "error", message: "Invalid pattern"}`
- **THEN** `metricValues[tsmdName][fieldName]` SHALL be `null` (explicit JSON null, preserved via ObjectNode.putNull) and `metricInfos[tsmdName][fieldName]` SHALL be `{"error": "Invalid pattern"}`

#### Scenario: Transport failure for a TSMD
- **WHEN** the `/evaluate` call for a TSMD fails with a transport error (HTTP 500, timeout, all retries exhausted) and the TSMD's output schema has field names `["recall", "precision", "f1", "mrr"]`
- **THEN** `metricValues[tsmdName]` SHALL contain `{"recall": null, "precision": null, "f1": null, "mrr": null}` (all output fields set to null) and `metricInfos[tsmdName]` SHALL contain `{"recall": {"error": "<exception message>"}, "precision": {"error": "<exception message>"}, "f1": {"error": "<exception message>"}, "mrr": {"error": "<exception message>"}}` (error entry per output field)

#### Scenario: Transport failure with empty output schema (fallback)
- **WHEN** the `/evaluate` call for a TSMD fails with a transport error AND the TSMD's output schema has no extractable field names (null, malformed, or empty properties)
- **THEN** `metricValues[tsmdName]` SHALL be an empty object `{}` and `metricInfos[tsmdName]` SHALL contain `{"error": "<exception message>"}`. The system SHALL log a WARN indicating the TSMD has an invalid output schema.

> **Clarifying note**: This fallback is the sole remaining exception to the per-field error format in `metricInfos`. It uses `{"error": "message"}` directly under the TSMD name (without a field-name wrapper) because no output field names are available. This case is defense-in-depth only — output schema validation (see tsmd-validation spec) prevents TSMDs with invalid schemas from entering evaluation under normal operation.

#### Scenario: Multiple TSMDs merged into single EvalSummary
- **WHEN** a test case has TSMDs "Accuracy" and "RAG Quality" both evaluated
- **THEN** `metricValues` SHALL contain keys for both TSMD names: `{"Accuracy": {...}, "RAG Quality": {...}}`

## ADDED Requirements

### Requirement: Output schema field extraction
The system SHALL provide an injectable `OutputSchemaFieldExtractor` component (in `service.domain`) that extracts output field names from a metric's output schema JSON string. This component SHALL be used by both the metric evaluation executor (to resolve field names for transport failure mapping) and the TSMD validation service (to validate output schema structure).
Status: **Planned**

#### Scenario: Valid output schema with multiple fields
- **WHEN** `extractFieldNames()` is called with an output schema containing `{"properties": {"recall": {...}, "precision": {...}, "f1": {...}}}`
- **THEN** the method SHALL return `["recall", "precision", "f1"]`

#### Scenario: Valid output schema with single field
- **WHEN** `extractFieldNames()` is called with an output schema containing `{"properties": {"exact_match": {...}}}`
- **THEN** the method SHALL return `["exact_match"]`

#### Scenario: Null or blank schema string
- **WHEN** `extractFieldNames()` is called with a null or blank string
- **THEN** the method SHALL return an empty list

#### Scenario: Schema without properties key
- **WHEN** `extractFieldNames()` is called with a JSON string that has no `"properties"` key or where `"properties"` is not an object
- **THEN** the method SHALL return an empty list

#### Scenario: Malformed JSON schema
- **WHEN** `extractFieldNames()` is called with invalid JSON
- **THEN** the method SHALL log a WARN and return an empty list (graceful degradation)

### Requirement: Typed TSMD evaluation result carrier
The system SHALL replace the untyped `Map<String, Object>` (where values are `EvaluationResponseDto | Exception`) with a sealed interface `TsmdEvaluationResult` in `service.domain.job`. Both variants SHALL carry `outputFieldNames` (`List<String>`) extracted from the TSMD's output schema.
Status: **Planned**

#### Scenario: Sealed interface with two variants
- **WHEN** a TSMD evaluation completes
- **THEN** the result SHALL be represented as either `TsmdEvaluationResult.Success(EvaluationResponseDto response, List<String> outputFieldNames)` or `TsmdEvaluationResult.Failure(Exception error, List<String> outputFieldNames)`

#### Scenario: Output field names extracted before evaluation dispatch
- **WHEN** the metric evaluation executor starts execution
- **THEN** it SHALL extract output field names for each TSMD using `OutputSchemaFieldExtractor` before dispatching async evaluations, and include them in every `TsmdEvaluationResult` (both success and failure)

#### Scenario: MetricOutputMapper consumes typed results
- **WHEN** `MetricOutputMapper.buildMetricValues()` and `buildMetricInfos()` are called
- **THEN** they SHALL accept `Map<String, TsmdEvaluationResult>` and use pattern matching on the sealed type (no `instanceof Object` checks)

#### Scenario: checkForErrors uses typed results
- **WHEN** `checkForErrors()` determines whether any TSMD evaluation failed
- **THEN** it SHALL accept `Map<String, TsmdEvaluationResult>` and check for `Failure` instances or `Success` instances containing error-type metric outputs

#### Scenario: TSMD with empty field names (defense-in-depth)
- **WHEN** a TSMD's output schema yields an empty field name list (should not happen after validation)
- **THEN** the output mapper SHALL produce an empty object `{}` in `metricValues` for that TSMD and record the error only in `metricInfos`

#### Scenario: Timeout fallback produces Failure with field names
- **WHEN** a TSMD evaluation times out and no result was recorded
- **THEN** the executor SHALL record a `Failure` with a `RuntimeException` and the pre-extracted output field names for that TSMD
