## MODIFIED Requirements

### Requirement: FILE ref resolution before DIAL deployment invocation

The system SHALL resolve FILE-typed binding values (both constant values and values from test case data fields) to DIAL data ref format (`files/{realBucket}/path`) in `ResolvedRequestService` before passing to request body serializers.

Status: Implemented

This ensures `JsonRequestBodySerializer` forwards DIAL-resolvable paths (not `@ef` aliases) when embedding file references as string values in deployment request payloads.

A full-value placeholder SHALL be treated as FILE-typed per the `FilePlaceholderDetector` rule: it carries a `|file` type hint (e.g., `${{attachment|file}}`), **or** it carries no type hint and its binding's `dataField` names a schema field of type `FILE`. Resolution applies at any nesting depth in the JSON body — the resolver recurses into nested objects and arrays. Applies to JSON (structural `content` and `jsonataContent`) bodies and to `TEXT` multipart form parts. URL-encoded form values are always stringified (the `|file` hint is ignored there too), so a FILE-bound placeholder in a URL-encoded value is not converted.

Note: Multipart `FormPartDto` FILE parts (`FormPartDto.type == FILE`) are excluded from schema-type-driven resolution — they remain as short-format refs in `ResolvedFormPartDto.resolvedValue` and are materialized as file bytes directly by `MultipartFormDataRequestBodySerializer`.

#### Scenario: FILE-typed constant value resolved to DIAL format in JSON body
- **WHEN** a JSON request body has a binding `{templateVariable="attachment", constantValue="@ef/suites/abc/doc.pdf"}`
- **AND** the template variable carries a `|file` type hint
- **AND** `ResolvedRequestService.resolveRequest()` is called
- **THEN** the resolved JSON body SHALL contain `files/{realBucket}/suites/abc/doc.pdf` at the corresponding key

#### Scenario: FILE-typed test case data field resolved to DIAL format
- **WHEN** a binding references a FILE-typed test case field with value `public/datasets/eval.csv`
- **AND** `ResolvedRequestService.resolveRequest()` is called
- **THEN** the resolved body SHALL contain `files/public/datasets/eval.csv` at the corresponding key

#### Scenario: FILE-typed placeholder resolved at nested depth in JSON body
- **WHEN** a JSON body template is `{"outer": {"attachment": "${{doc|file}}"}}`
- **AND** the binding for `doc` resolves to `@ef/suites/abc/report.pdf`
- **AND** `ResolvedRequestService.resolveRequest()` is called
- **THEN** the resolved body SHALL contain `files/{realBucket}/suites/abc/report.pdf` at `outer.attachment`

#### Scenario: Hintless placeholder bound to a FILE column resolved to DIAL format
- **WHEN** a JSON body template is `{"custom_content": {"attachments": [{"url": "${{file}}"}]}}`
- **AND** the binding for `file` has `dataField: "attachment"` and the test case schema declares `attachment` as `FILE`
- **AND** the test case value of `attachment` is `@ef/datasets/abc/report.pdf`
- **THEN** the resolved body SHALL contain `files/{realBucket}/datasets/abc/report.pdf` at `custom_content.attachments[0].url` (identical to the result with `${{file|file}}`)

#### Scenario: Hintless placeholder bound to a FILE column resolved in a JSONata body
- **WHEN** a request body is authored via `jsonataContent` containing the full-value placeholder `${{file}}`
- **AND** the binding for `file` has `dataField` naming a `FILE` schema field with value `@ef/datasets/abc/report.pdf`
- **THEN** the evaluated body SHALL contain `files/{realBucket}/datasets/abc/report.pdf` at that position

#### Scenario: Hintless placeholder bound to a non-FILE column is not converted
- **WHEN** a JSON body template is `{"note": "${{text}}"}`
- **AND** the binding for `text` has `dataField` naming a `STRING` schema field whose value is `@ef/datasets/abc/report.pdf`
- **THEN** the resolved body SHALL contain the value unchanged (`@ef/datasets/abc/report.pdf`)

#### Scenario: Embedded placeholder bound to a FILE column is not converted
- **WHEN** a JSON body template is `{"text": "see ${{file}} please"}`
- **AND** `file` is bound to a `FILE` schema field with value `@ef/datasets/abc/report.pdf`
- **THEN** the resolved body SHALL contain `see @ef/datasets/abc/report.pdf please` (string interpolation, no DIAL ref conversion)

#### Scenario: Multipart FILE part with hintless placeholder keeps the short-format ref
- **WHEN** a multipart body has a `FormPartDto` with `type = FILE` and `value = "${{doc}}"`
- **AND** `doc` is bound to a `FILE` schema field with value `@ef/datasets/abc/report.pdf`
- **THEN** `ResolvedFormPartDto.resolvedValue` SHALL be `@ef/datasets/abc/report.pdf` (not a `files/` ref), so `MultipartFormDataRequestBodySerializer` can download and upload the file bytes

#### Scenario: Multipart TEXT part bound to a FILE column resolved to DIAL format
- **WHEN** a multipart body has a `FormPartDto` with `type = TEXT` and `value = "${{doc}}"`
- **AND** `doc` is bound to a `FILE` schema field with value `@ef/datasets/abc/report.pdf`
- **THEN** `ResolvedFormPartDto.resolvedValue` SHALL be `files/{realBucket}/datasets/abc/report.pdf`

## ADDED Requirements

### Requirement: Single FILE-placeholder decision rule shared by all template resolvers

The system SHALL decide whether a full-value `${{...}}` placeholder is a FILE reference in exactly one component, `FilePlaceholderDetector` (`runner.service`, `evaluation-runner-core`), used by `TemplateContentResolver`, `JsonataSourcePreprocessor` and `McpRequestResolver`. The rule SHALL be:

Status: Implemented

1. If the placeholder carries a type hint, the hint decides (case-insensitive `file` ⇒ FILE; any other hint ⇒ not FILE).
2. Otherwise the placeholder is FILE iff its binding has a non-blank `dataField` and the test case schema declares that field with type `FILE`.
3. A `null`/empty schema, a binding without `dataField` (including `constantValue` bindings), or an unknown field yields "not FILE" (only an explicit hint converts).

Only a `String` resolved value SHALL be converted via `DialFileRefResolver.resolveToDialRef`; non-String values pass through unchanged. The test case schema SHALL be supplied by every resolution entry point (run path via `EvaluationContext.snapshotTestCaseSchema`; preview/try-it-out via the dataset schema).

#### Scenario: Explicit non-file hint wins over a FILE column
- **WHEN** a placeholder is `${{doc|string}}` and `doc` is bound to a `FILE` schema field
- **THEN** the detector SHALL report "not FILE" and the value SHALL be passed through unconverted

#### Scenario: constantValue binding without hint is not converted
- **WHEN** a placeholder is `${{doc}}` and its binding has only `constantValue: "@ef/suites/abc/doc.pdf"`
- **THEN** the detector SHALL report "not FILE" (a constant file ref still requires `|file`)

#### Scenario: Missing schema falls back to explicit hints only
- **WHEN** the schema supplied to a resolver is `null` (e.g., an eval-cli run without a schema)
- **AND** a placeholder is `${{doc}}` bound to a field
- **THEN** no conversion SHALL occur and no exception SHALL be thrown
- **AND** a placeholder `${{doc|file}}` SHALL still be converted

#### Scenario: Non-string value in a FILE column is not converted
- **WHEN** a hintless placeholder is bound to a `FILE` schema field whose test case value is not a `String`
- **THEN** the value SHALL be returned as-is without calling `DialFileRefResolver`

#### Scenario: Invalid reference propagates the existing error
- **WHEN** a hintless placeholder is bound to a `FILE` schema field whose value is `invalid-no-prefix`
- **THEN** resolution SHALL propagate the `ValidationException` from `DialFileRefResolver.resolveToDialRef()` exactly as for an explicit `|file` hint

#### Implementation notes
- `evaluation-runner-core`: `runner.service.FilePlaceholderDetector` (new), `TemplateContentResolver.resolveObject`, `JsonataSourcePreprocessor.resolveTypedValue`, `McpRequestResolver.resolveFileRef`, `RequestResolver.resolveMultipartBody` (passes no schema for `FILE` parts).
- Call sites receiving the schema: `TurnLoopExecutor`, `EvaluationWorker`, `TryItOutService`, `ResolvedRequestService`.
