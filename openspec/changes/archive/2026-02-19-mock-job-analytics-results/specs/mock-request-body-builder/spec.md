# Mock Request Body Builder

## Purpose
Defines the behaviour of `MockRequestBodyBuilder`: a reusable injectable component that constructs a request body JSON string from a TestSuite's template configuration applied over TestCase data.

Status: **Planned** (introduced in `mock-job-analytics-results` change)

## Key Terms
- **requestTemplate**: Suite-level (or case-level override) JSON structure with a `body` object containing `${{variable}}` or `${{variable:default}}` placeholder tokens.
- **inputBindings**: Ordered list of bindings mapping template variables to either a `dataField` path (resolved from `TestCase.data`) or a `constantValue`.
- **effective template / bindings**: The per-case override if present, otherwise the suite-level value.

---

## ADDED Requirements

### Requirement: Resolve request body from template and bindings
The `MockRequestBodyBuilder` SHALL resolve a JSON request body string by applying effective `inputBindings` over `TestCase.data` into the effective `requestTemplate.body`, substituting all `${{variable}}` and `${{variable:default}}` placeholder tokens.

#### Scenario: Whole-placeholder node with dataField binding (type-preserving)
- **WHEN** a text node's entire value is a single placeholder `${{var}}`, a binding maps `var` to a `dataField`, and that field exists in `TestCase.data`
- **THEN** the builder SHALL replace the entire JSON node with the resolved `JsonNode` value, preserving its original type (array stays array, object stays object, string stays string)

#### Scenario: Successful resolution with constantValue binding
- **WHEN** a binding specifies `constantValue`
- **THEN** the builder SHALL substitute that constant as a text node into the corresponding placeholder

#### Scenario: Mixed-text placeholder with scalar value
- **WHEN** a text node contains a placeholder mixed with other text (e.g., `"Hello ${{name}}, welcome"`) and the resolved value is a scalar
- **THEN** the builder SHALL perform string substitution using the scalar's text representation

#### Scenario: Placeholder with default value, no binding provided
- **WHEN** a template contains `${{variable:defaultVal}}` and no binding resolves that variable
- **THEN** the builder SHALL substitute `defaultVal` in place of the placeholder

#### Scenario: Placeholder with no binding and no default
- **WHEN** a template contains `${{variable}}` and no binding resolves it and no default is defined
- **THEN** the builder SHALL leave the placeholder token as-is (no substitution, no error)

### Requirement: Respect per-case overrides
The builder SHALL use `TestCase.requestTemplateOverride` and `TestCase.inputBindingsOverride` when present, falling back to `TestSuite.requestTemplate` and `TestSuite.inputBindings` respectively.

#### Scenario: Case-level override takes precedence
- **WHEN** `TestCase.requestTemplateOverride` is non-null
- **THEN** the builder SHALL use the case-level template instead of the suite-level template

#### Scenario: Suite-level fallback when no override
- **WHEN** `TestCase.requestTemplateOverride` is null
- **THEN** the builder SHALL use `TestSuite.requestTemplate`

### Requirement: Graceful fallback when template is absent or invalid
The builder SHALL return the raw `TestCase.data` JSON string when the effective template is null, when `template.body` is absent, or when any parsing/substitution step fails.

#### Scenario: No template configured
- **WHEN** both `TestSuite.requestTemplate` and `TestCase.requestTemplateOverride` are null
- **THEN** the builder SHALL return the `TestCase.data` JSON string

#### Scenario: Template parsing error
- **WHEN** the template JSON cannot be parsed
- **THEN** the builder SHALL log a WARN and return `TestCase.data` JSON string without throwing

#### Scenario: Result re-parse error
- **WHEN** the resolved body string is no longer valid JSON after substitution
- **THEN** the builder SHALL log a WARN and return `TestCase.data` JSON string without throwing
