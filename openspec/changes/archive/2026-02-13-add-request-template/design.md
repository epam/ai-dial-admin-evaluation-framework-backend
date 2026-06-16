## Context

The evaluation framework currently uses a flat schema extraction approach: `EndpointSchemaExtractor.flattenTopLevelProperties()` pulls top-level properties from `endpointRef.requestBodySchema` as `SchemaFieldDto` entries (the computed `parameterFields`). TestCases store inputs as flat `parameters: Map<String,Object>` and ground truth as `facts: Map<String,Object>`.

This works for simple endpoints but breaks down for complex REST APIs like OpenAI's `/chat/completions` where:
- The schema has 20+ fields with deeply nested structures (message arrays, response_format objects, tool definitions).
- Most test cases (80%+) only vary 1–3 fields (e.g., user prompt, temperature).
- Variable-length arrays (multi-turn conversations) cannot be represented in a flat column model.
- Users must repeat boilerplate or leave fields empty with no clear assembly semantics.

The design was explored in a dedicated brainstorming session, evolving through several iterations to arrive at a Postman-style embedded variable approach with explicit binding mapping.

## Goals / Non-Goals

**Goals:**
- Allow test suite authors to define a base request template once and vary only specific fields per test case.
- Support embedded `${{variable}}` / `${{variable:default}}` placeholder syntax (Postman-style) at any nesting depth in the request body, URL, query parameters, and headers.
- Require explicit bindings mapping template variables to test case data fields or constants (no implicit auto-mapping by name).
- Enable per-test-case template and binding overrides for complex/unusual cases (e.g., multi-turn conversations).
- Unify `parameters` and `facts` into a single `data` map to eliminate field duplication when a column serves both as request input and evaluation fact.
- Keep field schema, request configuration, and test case data as three independently manageable, importable/exportable concerns.
- Provide soft validation with comprehensive warnings (not hard rejection).
- Support endpoints without request bodies (template nullable, bindings target only URL/query/header).

**Non-Goals:**
- Separated binding list with JSONPath body paths — decided against in favor of embedded `${{variable}}` syntax for clarity and Postman UX alignment.
- Partial overlay for template overrides — full replacement only (partial overlay is future work).
- Role annotations on field definitions — roles are emergent from bindings and metric config.
- Runner/executor implementation — this change focuses on data model and management APIs.
- Client-side smart defaults for known schemas (e.g., auto-generating templates for OpenAI) — this is a frontend concern.
- Metric configuration integration — future work, but the data model is designed to accommodate it.

## Decisions

### D1: Embedded Template Placeholders (Postman-style)

**Decision**: Use `${{variable}}` and `${{variable:default}}` placeholder syntax embedded directly in the request template.

**Alternatives considered:**
- **Pure JSON template + separate binding list with JSONPath**: Template stays as valid JSON, bindings use JSONPath for body paths. Clean separation, but: requires JSONPath library, binding paths are opaque, and cannot naturally express URL/query/header variables.
- **Deep merge (suite defaults + test case overrides)**: No template language needed but tricky array merge semantics, JSON path in column names is awkward for UI/CSV.

**Rationale**: The embedded syntax is universally familiar from Postman/cURL/API tools. It naturally supports variables in any part of the request (URL path, query params, headers, body at any depth). The frontend can build a Postman-like UI where users see their template with highlighted variables. The trade-off is that the body is no longer pure valid JSON (it contains placeholder strings), but this is acceptable because the template is a configuration artifact, not a live request.

### D2: Explicit Bindings Required (No Auto-Mapping by Name)

**Decision**: `inputBindings` is always present on the model (never null; defaults to empty list when omitted in requests). It is the sole mapping mechanism — every template variable must be explicitly bound via an `InputBindingDto` entry, or have a `${{var:default}}` default. There is no implicit auto-mapping where `${{temperature}}` auto-resolves to `data["temperature"]`.

**Alternatives considered:**
- **Auto-mapping by name as fallback**: If no binding exists for `${{var}}`, auto-map to `data[var]`. Convenient but introduces hidden behavior where name coincidence drives resolution logic.
- **Bindings as optional aliasing layer**: Bindings only needed when variable name differs from data field name. Simpler but ambiguous — unclear whether missing binding means "auto-map" or "use default."

**Rationale**: Explicit bindings are the single source of truth for how template variables connect to data. No hidden behavior, no name coincidence surprises. The binding list is always inspectable and tells you exactly what's connected to what. Variables with defaults that need no binding are also explicit (they simply have no binding entry).

### D3: Unified `data` Map Replacing `parameters` + `facts`

**Decision**: Single `data: Map<String,Object>` in TestCase replaces separate `parameters` and `facts` maps.

**Alternatives considered:**
- **Keep separate maps with `source` field on binding**: Minimal change but awkward semantics for dual-role fields, still requires duplication.
- **Unified API, separate DB storage**: Clean client API but server-side split/merge logic adds complexity with no clear benefit.

**Rationale**: Eliminates data duplication for fields that serve both as request input and evaluation fact (e.g., `target_language` used in both request building and metric evaluation). Single JSONB column in DB is simplest. Role is derived: fields referenced by bindings = inputs; fields referenced by metrics (future) = facts; fields referenced by both = dual-role.

### D4: No Roles in Field Definitions

**Decision**: `FieldDefinitionDto` contains only `name`, `type`, `required`, `description`. No `roles: INPUT|FACT`.

**Rationale**: Every consumer that needs to know a field's role can derive it:
- **Runner**: reads `inputBindings` to know which fields feed the request.
- **Evaluator**: reads metric config (future) to know which fields are ground truth.
- **Grid UI**: derives grouping — bound fields are inputs, unbound fields are fact candidates.
- **Stored roles would be redundant** with binding existence and could go stale if bindings change without role update.

### D5: Three Orthogonal Concerns

**Decision**: Test case schema, request configuration, and test case data are independently manageable.

```
Schema ─name──→ Binding ←templateVariable── Template (${{var}})
  ↑                                              ↑
  │            validates against                  │
  └──────────────────────────────────────────────┘
             (via data map)

Schema:    WHAT columns exist (testCaseSchema)
Bindings:  HOW data fields map to template variables (inputBindings)
Template:  WHERE variables sit in the request (requestTemplate with ${{var}})
Data:      VALUES per test case (testCase.data)
```

**Rationale**: Supports independent import/export:
- CSV import detects schema (columns, types) without needing binding info.
- Template+bindings can be exported as a reusable configuration package.
- Each concern has a different lifecycle and change trigger.

### D6: Per-Test-Case Override Strategy

**Decision**: Full replacement for both `requestTemplateOverride` and `inputBindingsOverride` (nullable, when present replaces suite defaults entirely).

**Alternatives considered:**
- **Merge/patch**: Only override specific bindings by variable name, rest fall through to suite. More convenient but harder to reason about and validate.

**Rationale**: Full replacement is simpler to implement and reason about. The per-case override is the 5% escape hatch for unusual structures; spelling out the full binding set is clearer. Override bindings must still reference existing `testCaseSchema` fields.

### D7: Default Value Strategy (Template-Embedded Defaults)

**Decision**: Default values are embedded in the template syntax itself via `${{var:default}}`. When a binding exists and provides a data field value or constant, it overrides the template default.

**Resolution priority:**
1. Binding with `constantValue` → always wins
2. Binding with `dataField` → use `data[field]` value if present
3. Template default (`${{var:default}}`) → fallback when no binding or data value is null
4. No binding + no default (`${{var}}`) → validation warning, variable is required

**Rationale**: Defaults are co-located with where the variable is used, making the template self-documenting. Reading the template tells you both where variables go and what the fallback is.

### D8: Flat TestSuite Structure (No Wrapper Object)

**Decision**: `testCaseSchema`, `requestTemplate`, and `inputBindings` are flat on TestSuite (remove `testCasesDefinition` wrapper).

**Rationale**: The `testCasesDefinition` wrapper was mainly for `parameterFields` + `factFields`. With the new model, these are three independent concerns that don't benefit from a grouping object. Flatter API is simpler.

### D9: `requestTemplate` Nullable

**Decision**: `requestTemplate` can be null for endpoints without a request body (e.g., `GET /models?api-version=...`). However, `requestTemplate: null` produces a suite-level validation warning (same as null `urlTemplate`) because request assembly is not possible without a template.

**Rationale**: Allowing null keeps the create flow simple — authors can save a suite before configuring the template. The warning signals that the suite is incomplete for request assembly. Test cases that only provide fact data for evaluation can still be created, but the suite's `isValid` will be `false` until a template is provided.

### D10: RequestTemplate as Structured DTO (Not Raw JSON)

**Decision**: `RequestTemplateDto` has explicit fields: `urlTemplate`, `queryParams`, `headers`, `body` — matching Postman's request structure.

**Alternatives considered:**
- **Single raw JSON blob**: Simpler storage, but no structured support for URL/query/header variables.
- **Markdown/string template**: Maximum flexibility but no structure for validation or UI rendering.

**Rationale**: The structured DTO maps directly to Postman's tabs (URL, params, headers, body). Frontend can render each section independently. Variable extraction knows which section each variable comes from (the `TemplateVariableSource` enum: BODY, URL, QUERY, HEADER). Validation can be per-section.

**Limitation**: `body` is typed as `Map<String,Object>` and cannot represent a top-level JSON array (e.g. batch endpoints). This covers the vast majority of REST APIs; top-level array body support is deferred as a future improvement.

### D11: TemplateVariableSource as Enum

**Decision**: `TemplateVariableSource` is a proper enum with values: `BODY`, `URL`, `QUERY`, `HEADER`.

**Rationale**: Type-safe, IDE-friendly, serialization-safe. Avoids stringly-typed bugs.

### D12: Convenience API for Template Variables

**Decision**: `GET /api/v1/test-suites/{id}/template-variables` returns a list of `TemplateVariableDto` with extracted metadata.

**Rationale**: Clients (especially the frontend) need to know what variables exist, where they appear, whether they have defaults, what type they should be, and which binding (if any) is connected. Computing this on every client is wasteful; the server has all the context. Type inference uses priority: `endpointRef` schema → `testCaseSchema` (via binding's `dataField`) → fallback `STRING`.

## Data Model Summary

```
┌─── TestSuite ──────────────────────────────────────────────────────┐
│                                                                     │
│  id, name, description, version, createdBy, createdAt, updatedAt    │
│  isValid, validationWarnings                                        │
│                                                                     │
│  endpointRef:  EndpointContractDto                                  │
│    method, relativeUrlPattern, operationId                          │
│    parameters:          List<ParameterDefinitionDto>?               │
│    requestBodySchema:   Map<String,Object>?       ← OPTIONAL        │
│    responseBodySchema:  Map<String,Object>?       ← OPTIONAL        │
│                                                                     │
│  deploymentRef:  DeploymentReferenceDto                             │
│                                                                     │
│  // ① Test case schema (pure column definitions)                   │
│  testCaseSchema:  List<FieldDefinitionDto>                          │
│    name, type, required, description                                │
│                                                                     │
│  // ② Request template (Postman-style ${{var}} placeholders)       │
│  requestTemplate:  RequestTemplateDto?                              │
│    urlTemplate:   String?                                           │
│    queryParams:   List<KeyValueTemplateDto>?                        │
│    headers:       List<KeyValueTemplateDto>?                        │
│    body:          Map<String,Object>?                               │
│                                                                     │
│  // ③ Input bindings (REQUIRED explicit mapping)                   │
│  inputBindings:  List<InputBindingDto>                              │
│    templateVariable:  String      @NotBlank                         │
│    dataField:         String?     (mutually exclusive)              │
│    constantValue:     Object?     (mutually exclusive)              │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘

┌─── TestCase ───────────────────────────────────────────────────────┐
│                                                                     │
│  id, testCaseName, testSuiteId                                      │
│  isEnabled, isValid, validationWarnings                             │
│  createdAt, updatedAt                                               │
│                                                                     │
│  data:                       Map<String,Object>                     │
│  requestTemplateOverride:    RequestTemplateDto?                    │
│  inputBindingsOverride:      List<InputBindingDto>?                 │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘

┌─── Supporting DTOs ────────────────────────────────────────────────┐
│                                                                     │
│  KeyValueTemplateDto { key: String, value: String }                 │
│                                                                     │
│  TemplateVariableDto (response-only, from convenience API):         │
│    name:          String                                            │
│    sources:       Set<TemplateVariableSource>                       │
│    hasDefault:    boolean                                           │
│    defaultValue:  String?                                           │
│    binding:       InputBindingDto?                                  │
│    inferredType:  SchemaFieldType?                                  │
│                                                                     │
│  enum TemplateVariableSource { BODY, URL, QUERY, HEADER }           │
│                                                                     │
│  ValidationWarningDto (CHANGED — replaces source+property model):   │
│    fieldName:    String         (was: source + property)             │
│    path:         String                                             │
│    message:      String                                             │
│    code:         ValidationWarningCode?                             │
│                                                                     │
│  NOTE: ValidationWarningSource enum (PARAMETERS, FACTS) removed —   │
│        unified data map eliminates the distinction.                  │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## Validation Matrix

```
┌─── ON TEST SUITE SAVE ────────────────────────────────────────────┐
│  → Stored in TestSuite.isValid + TestSuite.validationWarnings      │
│                                                                    │
│  URL TEMPLATE VALIDATION:                                          │
│    requestTemplate is null? → WARN "urlTemplate is required …"     │
│    urlTemplate is null? → WARN "urlTemplate is required …"         │
│    urlTemplate is invalid path? → WARN "urlTemplate is invalid"    │
│                                                                    │
│  TEMPLATE → BINDING VALIDATION:                                    │
│  Extract all ${{vars}} from requestTemplate                        │
│                                                                    │
│  For each ${{var}} WITHOUT default (required):                     │
│    binding exists? ── NO → WARN "Required variable '$var'          │
│                              has no binding"                       │
│    binding.dataField in testCaseSchema? ── NO → WARN "Binding     │
│                              maps to unknown field '$field'"       │
│                                                                    │
│  For each ${{var:default}} WITH default (optional):                │
│    binding exists? ── NO → OK (uses default, no data needed)       │
│    binding exists? ── YES → OK (binding overrides default)         │
│                                                                    │
│  BINDING → TEMPLATE VALIDATION:                                    │
│  For each binding:                                                 │
│    ${{templateVariable}} in template? ── NO → WARN "Binding for    │
│                              '$var' but no ${{$var}} in template"  │
│                                                                    │
│  TEMPLATE → ENDPOINT VALIDATION (if schemas present):              │
│    Resolve with defaults only, soft-validate against schema        │
│                                                                    │
│  Suite isValid = (no warnings produced)                            │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘

┌─── ON TEST CASE SAVE ─────────────────────────────────────────────┐
│  → Stored in TestCase.isValid + TestCase.validationWarnings        │
│  → Independent of suite isValid (data-level checks only)           │
│                                                                    │
│  Effective template  = requestTemplateOverride ?? suite.template    │
│  Effective bindings  = inputBindingsOverride   ?? suite.bindings   │
│                                                                    │
│  IF test case HAS overrides (template or bindings):                │
│    For each ${{var}} in effective template (no default):           │
│      binding = effective bindings.find(templateVariable == var)     │
│      binding exists? ── NO → WARN "Required variable unbound"      │
│    For each override binding:                                      │
│      ${{templateVariable}} in template? ── NO → WARN "orphan"      │
│      binding.dataField in testCaseSchema? ── NO → WARN             │
│  (When no overrides, these checks already done at suite level)     │
│                                                                    │
│  For each ${{var}} with binding to dataField (effective):          │
│    binding.dataField? ── data[field] has value? ── NO → WARN      │
│                           "Required field '$field' empty in data"  │
│                                                                    │
│  For each data key:                                                │
│    key in testCaseSchema? ── NO → WARN "Unknown data field '$key'" │
│                                                                    │
│  TYPE VALIDATION:                                                  │
│    data values match testCaseSchema types → soft warnings          │
│                                                                    │
│  SCHEMA VALIDATION (if endpoint schemas present):                  │
│    Resolve full request, validate against requestBodySchema        │
│                                                                    │
│  TestCase isValid = (no warnings produced)                         │
│  NOTE: suite-level warnings are NOT duplicated here                │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

## Resolution Flow

```
resolve(${{varName}}, data, bindings):

  binding = bindings.find(b → b.templateVariable == varName)

  ┌─────────────────────────────────────────────────────────┐
  │                                                         │
  │  BINDING FOUND + constantValue:                         │
  │    → return constantValue                               │
  │                                                         │
  │  BINDING FOUND + dataField:                             │
  │    → return data[binding.dataField]                     │
  │    → if data[field] is null:                            │
  │        if ${{var:default}} → return typedDefault(default)│
  │        else → warning: "Bound field 'X' has no value"   │
  │                                                         │
  │  NO BINDING + ${{var:default}}:                         │
  │    → return typedDefault(default)  (per schema type)     │
  │    (optional var, no binding needed)                    │
  │                                                         │
  │  NO BINDING + ${{var}} (no default):                    │
  │    → validation warning/error:                          │
  │      "Required template variable 'var' has no binding"  │
  │    → isValid = false                                    │
  │                                                         │
  └─────────────────────────────────────────────────────────┘
```

## Risks / Trade-offs

- **[Breaking API changes]** → All clients must update. Mitigated by: backward incompatibility is explicitly accepted (no existing production clients). Migration script handles DB transformation.
- **[Template not valid JSON]** → The `body` field contains `${{var}}` strings that are not valid JSON values. Mitigated by: template is a configuration artifact stored as JSONB, not a live request. Server handles parsing/extraction. Frontend renders with variable highlighting.
- **[Template parsing]** → Prefer an existing library for `${{var}}` / `${{var:default}}`; otherwise a lightweight template engine over regex if not much more complex (see D13).
- **[Per-case override complexity]** → Users with override templates must maintain bindings separately. Mitigated by: this is the 5% escape hatch; the 95% case uses suite defaults with no override.
- **[Validation complexity]** → Cross-validating template variables, bindings, data fields, and endpoint schemas creates a validation matrix. Mitigated by: all validation is soft (warnings), implemented incrementally.
- **[CSV import/export changes]** → Unified `data` map changes column handling in CSV. Mitigated by: column disambiguation shifts from param/fact prefixes to binding-based derivation; simpler overall.

## Migration Plan

1. **Flyway migration**: Drop existing data from `test_suites` and `test_cases` tables. Restructure columns: replace `test_cases_definition` with `test_case_schema` (JSONB), `request_template` (JSONB), `input_bindings` (JSONB), add `is_valid` (BOOLEAN) and `validation_warnings` (JSONB) on `test_suites`; replace `parameters` and `facts` with `data` (JSONB), add `request_template_override` (JSONB) and `input_bindings_override` (JSONB) on `test_cases`.
2. **No data migration**: Existing test suite and test case data is dropped — no transformation of old `parameters`/`facts`/`testCasesDefinition` structures. This is acceptable as there are no production clients.
3. **API versioning**: Since backward incompatibility is accepted, no v2 needed. Update all DTOs in place.
4. **No rollback**: Rollback is out of scope.

## Decisions (continued)

### D13: Template parsing — prefer existing library; lightweight engine over regex

**Decision**: Prefer an existing Java library for parsing `${{var}}` and `${{var:default}}` placeholders. This format is not unique (e.g. Postman, env substitution). If no suitable library is found, prefer a **lightweight template engine** over a regex-based extractor, provided the engine is not MUCH MORE complex than regex.

**Rationale**: Existing libraries (e.g. Apache Commons Text `StringSubstitutor` with custom prefix/suffix, or similar) reduce maintenance and improve error handling. Regex is acceptable for extraction but brittle for nested structures; a small engine gives clearer semantics. If the only option is "heavy" engine vs regex, choose regex.

### D14: Type coercion on resolution — typed defaults in template for now

**Decision**: Use **typed defaults in the template** for now. The default in `${{var:default}}` is interpreted according to schema type where known (e.g. number for a number field, string otherwise). No separate type-coercion phase at resolution beyond that.

**Rationale**: Keeps resolution simple and avoids ambiguous coercion rules. Can be revisited if typed defaults prove insufficient.

### D15: CSV column ordering — by schema order

**Decision**: CSV export/import column order SHALL follow **testCaseSchema field order**. Header row: fixed columns (e.g. `testCaseName`, `isEnabled` if requested), then schema fields in the order they appear in `testCaseSchema`.

**Rationale**: Deterministic, schema-driven order; no separate "inputs first, then facts" rule.

### D16: Maximum template size — configurable limit, 64KB default

**Decision**: The service SHALL enforce a **configurable maximum size** for the serialized `requestTemplate` (e.g. URL + query + headers + body). Default SHALL be **64KB**. Requests exceeding the limit SHALL be rejected with HTTP 400.

**Rationale**: Prevents abuse and ensures predictable storage; 64KB is sufficient for typical request templates.

### D17: Maximum bindings count — configurable limit, 64 default

**Decision**: The service SHALL enforce a **configurable maximum count** for `inputBindings` (and per-case overrides). Default SHALL be **64**. Requests exceeding the limit SHALL be rejected with HTTP 400.

**Rationale**: Bounded validation and storage; 64 bindings covers real-world suites.

### D18: urlTemplate validation and resolved URL matching

**Decision**: `urlTemplate` SHALL be a valid path (literal segments and `${{var}}` placeholders only). When null, a soft validation warning is added. After resolving all placeholders, the **final request URL SHALL match** `endpointRef.relativeUrlPattern` using Java `Pattern.matches()` semantics.

`relativeUrlPattern` accepts either a literal relative path (e.g. `/chat/completions`) or a Java regex pattern (e.g. `/api/v[\\d]+/client/.*`). A literal path works as a regex that matches only itself.

**Rationale**: Renaming `relativeUrl` → `relativeUrlPattern` clarifies that the endpoint field is a matching pattern, not necessarily a concrete path. The resolved URL is validated against this pattern using standard Java regex matching to ensure the assembled request targets the correct endpoint. The author must provide `urlTemplate` for request assembly.

### D19: Test-case convenience APIs — template-variables and resolved-request

**Decision**: The service SHALL provide:
- **`GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/template-variables`** — returns template variables for the **effective** template and bindings of that test case (same `TemplateVariableDto` as suite endpoint). Useful when the test case has `requestTemplateOverride` and/or `inputBindingsOverride`.
- **`GET /api/v1/test-suites/{testSuiteId}/test-cases/{testCaseId}/resolved-request`** — returns the **resolved request** (URL, query params, headers, body) after applying effective template, bindings, and test case `data`. Supports debugging and UI preview without executing the request.

**Rationale**: Per-test-case overrides require a way to inspect effective variables and the resulting request; these endpoints mirror the suite-level template-variables and add a dedicated resolved-request preview.

### D20: Suite-level `isValid` + `validationWarnings` — layered validation

**Decision**: TestSuite SHALL have its own `isValid` (boolean) and `validationWarnings` (structured list, same format as TestCase warnings). Suite-level validation covers template + bindings configuration correctness (independent of test case data). TestCase `isValid` covers data-specific checks only. The two layers are **independent** — a test case in an invalid suite can be "valid from a data perspective"; the client/UI combines both flags to determine overall readiness.

**Suite-level warnings (produced on suite create/update):**
- `requestTemplate` is null (same warning as urlTemplate null)
- `urlTemplate` is null
- `urlTemplate` is invalid path
- Required `${{var}}` (no default) has no binding
- Binding references variable not in template
- Binding `dataField` not in `testCaseSchema`
- Template doesn't conform to endpoint schema (resolved with defaults only)

**Test-case-level warnings (produced on test case create/update):**
- Missing required field in data
- Type mismatch in data
- Required template variable with bound data field missing value
- Override-specific checks (override binding refs unknown field, override template re-validation)
- Unknown fields in data (not in schema)
- Resolved URL doesn't match `endpointRef.relativeUrlPattern` (needs resolved values)

**Rationale**: Suite-level validation is valuable even when no test cases exist — the author sees immediately whether their template+bindings setup is correct. Avoids redundant repetition of the same suite-level warning on every test case. Each layer has its own lifecycle: suite validation triggers on suite save; test-case validation triggers on test case save or suite re-validation.

### D21: Full-value vs Embedded Placeholder Resolution

**Decision**: When a string value in the template consists of exactly one `${{var}}` or `${{var:default}}` placeholder with no surrounding text, resolution uses **full-value replacement** — the string is replaced with the resolved value preserving its original type (number, boolean, object, array, string). When a string contains multiple placeholders or any text outside placeholders, resolution uses **string interpolation** — all resolved values are stringified and concatenated.

**Detection rule**: A string is "full-value" iff it matches the pattern `^\$\{\{[^}]+\}\}$` (the entire string is a single placeholder, nothing before or after).

**Examples**:
- `"temperature": "${{temp:0.7}}"` → full-value → `"temperature": 0.7` (number)
- `"stream": "${{stream:true}}"` → full-value → `"stream": true` (boolean)
- `"messages": "${{messages}}"` → full-value → `"messages": [...]` (array/object from data)
- `"prompt": "Hello ${{name}}"` → embedded → `"prompt": "Hello John"` (string)
- `"info": "${{a}} and ${{b}}"` → embedded → `"info": "X and Y"` (string)

**Rationale**: This pattern is well-established in Terraform, GitHub Actions, and other template systems. It allows type-preserving substitution for JSON values (numbers, booleans, objects, arrays) while naturally falling back to string concatenation when text mixing is needed. The detection rule is simple and unambiguous. Combined with D14 (typed defaults), full-value defaults are interpreted per schema type (e.g. `"0.7"` → number `0.7` when type is NUMBER).

### D22: Null Template Equivalence

**Decision**: `requestTemplate: null` and `requestTemplate: { urlTemplate: null, queryParams: null, headers: null, body: null }` are **semantically equivalent**. Both produce the same validation warnings and behavior.

**Rationale**: Simplifies client logic — there's only one "no template" state to reason about. The system normalizes both forms to the same internal representation.

### D23: CSV Schema Auto-Detection

**Decision**: When `testCaseSchema` is empty at CSV import time, the system SHALL auto-detect field definitions from CSV columns. All headers except reserved names (`testCaseName`, `isEnabled`) become `FieldDefinitionDto` entries. Type inference scans all row values per column:
1. All non-empty values parse as JSON objects → `OBJECT`
2. All non-empty values parse as JSON arrays → `ARRAY`
3. All non-empty values are `true`/`false` (case-insensitive) → `BOOLEAN`
4. All non-empty values parse as whole numbers → `INTEGER`
5. All non-empty values parse as numbers (incl. decimals) → `NUMBER`
6. Otherwise (mixed types, all strings, or all empty) → `STRING`

Auto-detected fields use `required: false` and `description: null`. Schema field order follows CSV column order (left to right). The auto-detected schema is **persisted** to the TestSuite's `testCaseSchema`, bumping `version` and triggering suite-level re-validation. The `preview()` endpoint includes the auto-detected schema so the frontend can display it before commit. No `inputBindings` are auto-created — binding configuration remains the user's responsibility.

**Alternatives considered:**
- **Default everything to STRING**: Simpler but worse UX; users must manually fix types for numeric/boolean columns.
- **Require manual schema creation before import**: Safest but blocks the "start from CSV" workflow.

**Rationale**: Leverages existing `CsvCellParser.inferTypeName()` logic. The scan-all-rows approach avoids misclassification from small samples. Persisting the schema enables subsequent imports/exports and validation to work immediately. The preview step gives users a chance to review before committing.
