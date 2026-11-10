## ADDED Requirements

### Requirement: Score metric reference validation
Suite configuration validation SHALL check the metric references in `overallScore` and `testCaseOverallScore` against the names of the suite's metric definitions (exact, case-sensitive; disabled and invalid definitions count as present). Findings SHALL be reported as suite `validationWarnings` and SHALL make `isValid = false`; they SHALL NOT cause HTTP 400.

- `weighted_mean`: every `weights[i].metricName` without a matching definition SHALL produce an `UNRESOLVED_REFERENCE` warning with `fieldName` = the metric name and `path` = `$.<overallScore|testCaseOverallScore>.weights[i].metricName`.
- `custom_function` in `overallScore`: the only supported shape is an expression with exactly one select item whose expression is `roc_auc(field, field)`. Each argument whose name starts with `metric::` names the TSMD as the text after `metric::` up to the last `::` (or the whole remainder when there is no further `::`); a TSMD name without a matching definition SHALL produce an `UNRESOLVED_REFERENCE` warning with `fieldName` = that name and `path` = `$.overallScore.expression.select[0].expr.args[i].name`. Other arguments (e.g. `data::`, `metricInfo::`) and the filter SHALL be ignored.
- Any other `custom_function` expression, and a stored `overallScore` that cannot be read as a score definition, SHALL produce one `UNSUPPORTED_SCORE_FUNCTION` warning with `path` = `$.overallScore.expression`. Validation SHALL NOT fail because of score content.
- `mean`, absent definitions, a (legacy) `custom_function` stored in `testCaseOverallScore`, and a stored `testCaseOverallScore` that cannot be read SHALL produce no warnings.

Score-reference warnings are those whose `path` starts with `$.overallScore.` or `$.testCaseOverallScore.`. They SHALL be recomputed whenever suite validation runs (create, update, clone, dataset revalidation) and on metric definition create/update/delete (see `test-suite-metric-definitions`). On clone they SHALL be computed against the metric definition names the clone receives (the source suite's names).

Status: **Implemented**

#### Scenario: Weighted mean references a missing metric
- **WHEN** a suite with no metric definition named `Accuracy` is created with `overallScore = weighted_mean` containing `metricName = "Accuracy"` at index 0
- **THEN** the response SHALL have `valid = false` and a warning `{code: UNRESOLVED_REFERENCE, fieldName: "Accuracy", path: "$.overallScore.weights[0].metricName"}`

#### Scenario: Weighted mean in testCaseOverallScore references a missing metric
- **WHEN** a suite's `testCaseOverallScore` is `weighted_mean` with an unmatched `metricName` at index 1
- **THEN** the suite SHALL carry an `UNRESOLVED_REFERENCE` warning at `$.testCaseOverallScore.weights[1].metricName`

#### Scenario: Name match is case-sensitive
- **WHEN** the suite has a metric definition `accuracy` and the score references `Accuracy`
- **THEN** an `UNRESOLVED_REFERENCE` warning SHALL be produced

#### Scenario: Disabled metric definition counts as present
- **WHEN** the referenced metric definition exists but is disabled or invalid
- **THEN** no score-reference warning SHALL be produced for it

#### Scenario: roc_auc references a missing metric
- **WHEN** `overallScore` is `custom_function` `roc_auc(data::label, metric::Classifier::probability)` and the suite has no `Classifier` metric definition
- **THEN** the suite SHALL carry an `UNRESOLVED_REFERENCE` warning with `fieldName = "Classifier"` at `$.overallScore.expression.select[0].expr.args[1].name`, and no warning for `data::label`

#### Scenario: Unsupported custom function
- **WHEN** `overallScore` is a `custom_function` whose single select is `avg(metric::Relevancy::score)`, or that has more than one select item
- **THEN** the request SHALL succeed and the suite SHALL carry one `UNSUPPORTED_SCORE_FUNCTION` warning at `$.overallScore.expression` with `valid = false`

#### Scenario: All references resolved
- **WHEN** every score reference matches a suite metric definition and the rest of the configuration is valid
- **THEN** the suite SHALL be `valid = true` with no score-reference warnings

#### Scenario: metric:: argument without output field
- **WHEN** a `roc_auc` argument is named `metric::Classifier` and the suite has no `Classifier` metric definition
- **THEN** the suite SHALL carry an `UNRESOLVED_REFERENCE` warning with `fieldName = "Classifier"`

#### Scenario: Clone validates against copied metric definitions
- **WHEN** a suite whose score references only its own metric definitions is cloned
- **THEN** the clone response and a subsequent GET SHALL NOT carry score-reference warnings

## MODIFIED Requirements

### Requirement: Suite-level soft validation (`isValid` + `validationWarnings`)
The TestSuite response SHALL include `isValid` (boolean) and `validationWarnings` (structured list, same format as TestCase validation warnings). Suite-level validation covers **configuration correctness only**. Test-case presence is **not** a component of stored suite validity and does not affect `isValid` or `validationWarnings` in the suite GET response. A bound suite with zero runnable test cases MAY be `isValid = true`; the run path enforces the presence requirement at run-creation time (see `test-suite-runs` spec).

Suite-level configuration correctness covers: template + bindings, cross-checked against the **referenced dataset's** `testCaseSchema` (resolved via `DatasetSchemaProvider`) and the suite's `responseColumns` / `endpointRef`, plus score metric references in `overallScore` / `testCaseOverallScore` checked against the suite's metric definition names (see "Score metric reference validation"). This dimension is **independent of test case data**.

`isValid` SHALL be `true` only when the configuration dimension produces no warnings. `isValid` SHALL be recalculated on every suite create, update or clone, and on dataset bind/detach and dataset schema revalidation (Phase 2); score-reference warnings are additionally refreshed on metric definition create/update/delete. TestCase `isValid` (test-case **data** validation) is owned by the test-case domain and is never triggered by suite validation — the configuration and data layers remain independent.

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

### Requirement: Per-suite `overallScore` on the suite API
The suite create and update request bodies SHALL accept an optional `overallScore` field — a JSON object holding a structured-query `StructuredQuery` expression that defines the run-level `overall` metric score for the suite. The system SHALL persist it verbatim to `test_suites.overall_score` (JSONB) and SHALL return it, as a JSON object, on the suite read (`GET`) and in create/update responses. When omitted or `null`, the column SHALL be left/stored as NULL, preserving the built-in default behavior (see `metric-score-statistics`). `overallScore` SHALL affect suite validity only through score metric reference validation (see "Score metric reference validation"). The expression SHALL be stored opaquely and SHALL NOT be validated as a runnable query at write time (a malformed or non-runnable expression surfaces at run-level computation, not at suite persistence).
Status: **Implemented**

#### Scenario: Set overallScore on update and read it back
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with an `overallScore` object referencing one specific metric column (`metric::<metricName>::<outputField>`)
- **THEN** system SHALL respond HTTP 200 with the updated suite whose `overallScore` equals the submitted object, and a subsequent `GET /api/v1/test-suites/{id}` SHALL return the same `overallScore`

#### Scenario: Set overallScore on create
- **WHEN** client calls `POST /api/v1/test-suites` with a valid body including an `overallScore` object
- **THEN** system SHALL create the suite persisting `overall_score` and return it in the response body

#### Scenario: Omitted overallScore leaves the column null
- **WHEN** client creates or updates a suite without an `overallScore` field
- **THEN** system SHALL store `overall_score` as NULL and the suite response SHALL omit `overallScore` (or return it as null)

#### Scenario: overallScore does not affect suite validity
- **WHEN** client sets an `overallScore` whose metric references all resolve, on an otherwise valid suite
- **THEN** the suite's `isValid` and `validationWarnings` SHALL be unchanged by the presence of `overallScore`

### Requirement: Per-suite `testCaseOverallScore` on the suite API
The suite create and update request bodies SHALL accept an optional `testCaseOverallScore` field — a JSON object holding an `OverallScoreDefinition` shape, but restricted to `Mean` or `WeightedMean` only (a `CustomFunction` `testCaseOverallScore` SHALL be rejected with HTTP 400 `ValidationException`). When present, `testCaseOverallScore` SHALL be used instead of `overallScore` to drive per-test-case `score`/`passed` computation on a run's `EvalSummary` rows (see `eval-summary-scoring`); when absent, per-test-case scoring SHALL fall back to `overallScore` unless `overallScore` is a `CustomFunction` (in which case there is no effective per-test-case definition — see `eval-summary-scoring`). The system SHALL persist it verbatim to `test_suites.test_case_overall_score` (JSONB) and SHALL return it, as a JSON object, on the suite read (`GET`) and in create/update responses. When omitted or `null`, the column SHALL be left/stored as NULL. `testCaseOverallScore` SHALL affect suite validity only through score metric reference validation (see "Score metric reference validation"). `testCaseOverallScore` SHALL NOT affect Phase 3's run-level `overall` metric-score result, which SHALL always be computed from `overallScore` regardless of whether `testCaseOverallScore` is configured.
Status: **Implemented**

#### Scenario: Set testCaseOverallScore independently of overallScore
- **WHEN** client calls `PUT /api/v1/test-suites/{id}` with a `testCaseOverallScore` object (Mean or WeightedMean) that differs from the suite's `overallScore`
- **THEN** system SHALL persist and return both fields independently, and a subsequent `GET /api/v1/test-suites/{id}` SHALL return the same value for each

#### Scenario: testCaseOverallScore rejects CustomFunction
- **WHEN** client attempts to create or update a suite with `testCaseOverallScore` set to a `CustomFunction` definition
- **THEN** the request SHALL fail with HTTP 400 and include a validation error message; the suite SHALL NOT be created or updated

#### Scenario: Omitted testCaseOverallScore leaves the column null
- **WHEN** client creates or updates a suite without a `testCaseOverallScore` field
- **THEN** system SHALL store `test_case_overall_score` as NULL and the suite response SHALL omit `testCaseOverallScore` (or return it as null)

#### Scenario: testCaseOverallScore does not affect suite validity
- **WHEN** client sets a `testCaseOverallScore` whose metric references all resolve, on an otherwise valid suite
- **THEN** the suite's `isValid` and `validationWarnings` SHALL be unchanged by the presence of `testCaseOverallScore`

#### Scenario: Clone inherits the source testCaseOverallScore
- **WHEN** a suite carrying a `testCaseOverallScore` is cloned
- **THEN** the cloned suite SHALL inherit the same `testCaseOverallScore` (as with `overallScore`)

#### Scenario: testCaseOverallScore does not affect the run-level overall aggregate
- **WHEN** a suite has both `overallScore` and `testCaseOverallScore` configured with different definitions
- **THEN** Phase 3's run-level `overall` metric-score result SHALL be computed from `overallScore` only, unaffected by `testCaseOverallScore`

## Implementation notes

- Validator: `service.domain.ScoreReferenceValidator`; wired into `SuiteValidationService` (clone passes the source suite's metric names).
