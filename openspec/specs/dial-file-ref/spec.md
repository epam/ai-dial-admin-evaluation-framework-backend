# DIAL File Reference Model

## Purpose
This spec describes the DIAL file reference system used in test case data. Covers the relative path format, @ef bucket alias resolution, prefix whitelist validation, resolveToDialRef for embedding refs in DIAL payloads, and how FILE-type schema fields map to DIAL file paths.

Status: **Implemented**

## Requirements

### Requirement: DIAL file reference format

Status: Implemented

FILE-type field values in test case data SHALL be DIAL relative file paths. The format SHALL be `{bucket-or-alias}/{path-to-file}` (short format — no `files/` prefix).

Three reference sources are supported:
- **EF-managed dataset files**: `@ef/datasets/{datasetId}/{filename}` — files uploaded through the dataset API. This is the canonical shape for files referenced from test-case `data`.
- **EF-managed suite files**: `@ef/suites/{suiteId}/{filename}` — files uploaded through the suite API. This is the canonical shape for files referenced from suite-level fields (FormPartDto, typed constant bindings in request/argument templates). Refs of this shape MAY also appear in legacy test-case `data` predating the dataset split; both shapes remain valid in test-case `data`.
- **Public DIAL files**: `public/{path}/{filename}` — pre-existing files in the DIAL public/organization bucket.

The file reference is a relative path (no protocol, no host, no `files/` prefix). It is environment-independent when using the `@ef` alias.

#### Scenario: EF-managed dataset file reference
- **WHEN** a test case FILE field contains `@ef/datasets/abc-123/input.csv`
- **THEN** the system SHALL recognize this as an EF-managed dataset file and resolve `@ef` to the real bucket name at runtime

#### Scenario: EF-managed suite file reference
- **WHEN** a FormPartDto FILE value or a typed constant binding contains `@ef/suites/abc-123/input.csv`
- **THEN** the system SHALL recognize this as an EF-managed suite file and resolve `@ef` to the real bucket name at runtime

#### Scenario: Legacy suite-shaped ref in test-case data
- **WHEN** a test case FILE field contains `@ef/suites/abc-123/input.csv` (legacy from before the dataset split)
- **THEN** the system SHALL accept the ref for runtime resolution; ownership enforcement on the suite shape in test-case data is relaxed (see `file-ref-validation` spec)

#### Scenario: Public file reference
- **WHEN** a test case FILE field contains `public/datasets/eval-data.csv`
- **THEN** the system SHALL recognize this as a public DIAL file and use the path as-is (no alias resolution)

#### Scenario: Invalid file reference format — old format with files/ prefix
- **WHEN** a test case FILE field contains a value that starts with `files/` (old format)
- **THEN** validation SHALL produce a warning (the `files/` prefix is not part of the allowed client format)

#### Scenario: Invalid file reference format — disallowed prefix
- **WHEN** a test case FILE field contains a value with a prefix not in the allowed whitelist
- **THEN** validation SHALL produce a warning

### Requirement: DialFileRefResolver component

Status: Implemented

The system SHALL provide a `DialFileRefResolver` component (`service.domain`) that translates client-facing file references to resolved paths for different consumers.

The resolver SHALL expose two resolution methods:

**`resolveToRealPath(ref)`** — resolves to an API path for `DialFileClient` HTTP calls:
- Validates the short-format reference (`{prefix}/path`, no `files/` prefix expected)
- Replaces the `@ef` alias with the real EF bucket name (discovered lazily on first use)
- Returns `{realBucket}/path` suitable for use with `DialFileClient` methods (which construct URLs like `PUT /v1/files/{apiPath}`)
- Passes through `public/...` references with no transformation
- Works uniformly for both `@ef/suites/...` and `@ef/datasets/...` paths (the resolver does not inspect the segment after the alias)

**`resolveToDialRef(ref)`** — resolves to a DIAL data reference for embedding in DIAL request payloads:
- Calls `resolveToRealPath(ref)` internally
- Prepends `files/` to the result
- Returns `files/{realBucket}/path` — the format DIAL Core expects when a file reference appears as a string value inside a JSON or form body sent to a DIAL deployment

**`buildEfRef(suiteId, filename)`** — builds the client-facing short-format EF reference for a suite:
- Returns `@ef/suites/{suiteId}/{filename}` (no `files/` prefix)

**`buildDatasetEfRef(datasetId, filename)`** — builds the client-facing short-format EF reference for a dataset:
- Returns `@ef/datasets/{datasetId}/{filename}` (no `files/` prefix)

**`extractFilename(ref)`** — extracts the last path segment (unchanged behavior).

#### Scenario: resolveToRealPath — resolve @ef alias for suite-shaped ref
- **WHEN** `resolver.resolveToRealPath("@ef/suites/abc/data.csv")` is called
- **THEN** it SHALL return `{realBucket}/suites/abc/data.csv` where `{realBucket}` is the cached EF bucket name

#### Scenario: resolveToRealPath — resolve @ef alias for dataset-shaped ref
- **WHEN** `resolver.resolveToRealPath("@ef/datasets/xyz/data.csv")` is called
- **THEN** it SHALL return `{realBucket}/datasets/xyz/data.csv`

#### Scenario: resolveToRealPath — public path passthrough
- **WHEN** `resolver.resolveToRealPath("public/datasets/input.csv")` is called
- **THEN** it SHALL return `public/datasets/input.csv` (no transformation)

#### Scenario: resolveToRealPath — reject disallowed prefix
- **WHEN** `resolver.resolveToRealPath("user-bucket/private/data.csv")` is called
- **THEN** it SHALL throw a validation exception indicating the prefix is not in the allowed whitelist

#### Scenario: resolveToRealPath — reject old files/ format
- **WHEN** `resolver.resolveToRealPath("files/@ef/suites/abc/data.csv")` is called (old format)
- **THEN** it SHALL throw a validation exception (the `files/` prefix is not valid input)

#### Scenario: resolveToDialRef — suite EF ref becomes full DIAL ref
- **WHEN** `resolver.resolveToDialRef("@ef/suites/abc/data.csv")` is called
- **THEN** it SHALL return `files/{realBucket}/suites/abc/data.csv`

#### Scenario: resolveToDialRef — dataset EF ref becomes full DIAL ref
- **WHEN** `resolver.resolveToDialRef("@ef/datasets/xyz/data.csv")` is called
- **THEN** it SHALL return `files/{realBucket}/datasets/xyz/data.csv`

#### Scenario: resolveToDialRef — public ref becomes full DIAL ref
- **WHEN** `resolver.resolveToDialRef("public/datasets/input.csv")` is called
- **THEN** it SHALL return `files/public/datasets/input.csv`

#### Scenario: buildEfRef returns short format
- **WHEN** `resolver.buildEfRef(suiteId, "report.pdf")` is called
- **THEN** it SHALL return `@ef/suites/{suiteId}/report.pdf` (no `files/` prefix)

#### Scenario: buildDatasetEfRef returns short format
- **WHEN** `resolver.buildDatasetEfRef(datasetId, "report.pdf")` is called
- **THEN** it SHALL return `@ef/datasets/{datasetId}/report.pdf` (no `files/` prefix)

#### Scenario: extractFilename unchanged
- **WHEN** `resolver.extractFilename("@ef/datasets/xyz/data.csv")` is called
- **THEN** it SHALL return `data.csv`

### Requirement: FILE ref resolution before DIAL deployment invocation

Status: Implemented

The system SHALL resolve FILE-typed binding values (both constant values and values from test case data fields) to DIAL data ref format (`files/{realBucket}/path`) in `ResolvedRequestService` before passing to request body serializers.

This ensures `JsonRequestBodySerializer` forwards DIAL-resolvable paths (not `@ef` aliases) when embedding file references as string values in deployment request payloads.

A full-value placeholder SHALL be treated as FILE-typed per the `DialFileRefResolver.isFileReference` rule: it carries a `|file` type hint (e.g., `${{attachment|file}}`), **or** it carries no type hint and its binding's `dataField` names a schema field of type `FILE`. Resolution applies at any nesting depth in the JSON body — the resolver recurses into nested objects and arrays. Applies to JSON (structural `content` and `jsonataContent`) bodies and to `TEXT` multipart form parts. URL-encoded form values are always stringified (the `|file` hint is ignored there too), so a FILE-bound placeholder in a URL-encoded value is not converted.

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

### Requirement: Single FILE-placeholder decision rule shared by all template resolvers

The system SHALL decide whether a full-value `${{...}}` placeholder is a FILE reference in exactly one component, `DialFileRefResolver.isFileReference` (`runner.client.dialcore`, `evaluation-runner-core`, on top of `runner.util.BindingTypeResolver`), used by `TemplateContentResolver`, `JsonataSourcePreprocessor` and `McpRequestResolver`. The rule SHALL be:

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
- `evaluation-runner-core`: `runner.client.dialcore.DialFileRefResolver.isFileReference` (new, over `runner.util.BindingTypeResolver`), `TemplateContentResolver.resolveObject`, `JsonataSourcePreprocessor.resolveTypedValue`, `McpRequestResolver.resolveFileRef`, `RequestResolver.resolveMultipartBody` (passes no schema for `FILE` parts).
- Call sites receiving the schema: `TurnLoopExecutor`, `EvaluationWorker`, `TryItOutService`, `ResolvedRequestService`.

### Requirement: Prefix whitelist validation

Status: Implemented

The system SHALL enforce a strict whitelist of allowed file reference prefixes. The allowed prefixes are `@ef` (configurable alias) and `public`. Refs MUST start with one of these prefixes. Enforcement of the inner segment after `@ef/` (`suites/`, `datasets/`, …) is performed by `FileRefValidator` (see `file-ref-validation` spec) and is reported as a validation warning; the resolver (`DialFileRefResolver`) remains segment-agnostic and resolves any `@ef/{anySegment}/...` ref uniformly.

#### Scenario: Allowed prefix accepted
- **WHEN** a FILE field value starts with `@ef/` or `public/`
- **THEN** prefix validation SHALL accept the value (format is valid at the prefix level; segment validation is delegated to `FileRefValidator`)

#### Scenario: Dataset EF file reference must match owning dataset
- **WHEN** a FILE field value in test-case `data` starts with `@ef/datasets/{differentDatasetId}/`
- **AND** the test case belongs to a different dataset
- **THEN** validation SHALL produce a warning indicating the file reference points to a different dataset's files

#### Scenario: Suite EF file reference must match owning suite (suite-level fields)
- **WHEN** a FILE field value in a suite-level field (e.g., FormPartDto, typed constant binding) starts with `@ef/suites/{differentSuiteId}/`
- **AND** the field is being validated against a different suite
- **THEN** validation SHALL produce a warning indicating the file reference points to a different suite's files

#### Scenario: Suite-shaped ref in test-case data — ownership relaxed
- **WHEN** a FILE field in test-case `data` starts with `@ef/suites/{anySuiteId}/`
- **THEN** validation SHALL accept the value without enforcing suite ownership (this preserves legacy refs that predate the dataset split; new uploads use `@ef/datasets/...`)

#### Scenario: Disallowed prefix rejected
- **WHEN** a FILE field value starts with any prefix not in the whitelist (e.g., `some-user-bucket/`)
- **THEN** validation SHALL produce a warning

#### Scenario: Whitelist is extensible
- **WHEN** future phases add support for user-personal buckets
- **THEN** the whitelist SHALL be extensible to include additional prefixes

### Requirement: Blank data-derived FILE placeholders use explicit defaults before conversion

Status: Implemented

Where existing full-value FILE conversion applies, the system SHALL select an explicit template default for a data-derived String value satisfying `String.isBlank()`, before converting the selected value. FILE classification SHALL retain the existing explicit `|file`/hintless FILE-schema rule and explicit non-FILE opt-out. This SHALL cover structural JSON, full-value or bare JSONata source placeholders, MCP arguments, and applicable multipart values, in runs and data-backed previews, for shared and effective per-turn data.

Explicit non-null constant overrides SHALL retain precedence. Nonblank values SHALL NOT be trimmed or replaced. Existing missing/null selection, caller-specific default parsing, reference validation, and transport behavior SHALL remain unchanged; only String selected values SHALL undergo reference conversion. Fallback SHALL NOT recurse or automatically omit a value.

#### Scenario: Blank FILE fallback matrix
- **WHEN** a data-derived full-value placeholder matches a case below and its explicit default is `public/no-file`
- **THEN** the resolved placeholder SHALL be `files/public/no-file` for each applicable caller and data scope

| Placeholder classification | Data value |
|---|---|
| Explicit `|file`, case-insensitive | Empty string |
| Explicit `|file` | Whitespace-only string (spaces, tabs, or line breaks) |
| Hintless binding to FILE schema field | Empty or whitespace-only string |

#### Scenario: Compatibility matrix
- **WHEN** resolution matches a case below
- **THEN** the system SHALL preserve the stated behavior

| Case | Expected behavior |
|---|---|
| Blank FILE, no explicit default | Existing conversion failure |
| Blank FILE, invalid or empty String default | Selected default rejected by existing reference validation |
| Blank FILE, MCP numeric/boolean default | Existing parsed numeric/boolean type retained; no String reference conversion |
| Explicit non-null constant, including blank | Constant wins; existing conversion/result or failure retained |
| Missing/null value | Existing default/required-warning behavior retained |
| Nonblank String, including invalid refs | No trimming or fallback; existing conversion/validation retained |
| Non-String value, including `false`, `0`, empty array/object | No new fallback or reference conversion |
| Non-FILE placeholder, including SKILL or explicit `|string` on FILE binding | No new fallback; existing behavior retained |
| Embedded/mixed-text placeholder | Existing interpolation retained; no new fallback/conversion |

#### Scenario: Multipart conversion boundary
- **WHEN** a multipart value matches a case below
- **THEN** default selection SHALL follow that boundary without changing serializer behavior

| Multipart part/value | Expected behavior |
|---|---|
| TEXT part, full-value explicit `|file` or hintless FILE binding, blank data and default | New fallback applies before existing DIAL conversion |
| FILE part, full-value explicit `|file`, blank data and default | New fallback applies before existing DIAL conversion; existing serializer incompatibility with DIAL-formatted refs is not repaired |
| FILE part, hintless FILE binding | No schema-driven conversion or new fallback; raw-reference transport behavior retained |

#### Scenario: JSONata filters the resolved sentinel after preprocessing
- **WHEN** a JSONata attachment list mixes valid public FILE references and blank FILE data with `public/no-file` defaults, then filters references containing `no-file`
- **THEN** preprocessing SHALL succeed and JSONata SHALL exclude sentinel references while retaining valid attachments in DIAL format

#### Implementation notes

- Shared selection: `evaluation-runner-core/src/main/java/com/epam/aidial/evaluation/runner/service/ResourceRefPlaceholderResolver.java`, called by structural JSON, JSONata preprocessing, and MCP full-value resolution.
- Regressions: `evaluation-runner-core/src/test/java/com/epam/aidial/evaluation/runner/service/BlankFileDefaultTest.java` and `src/test/java/com/epam/aidial/evaluation/functional/tests/TryItOutFunctionalTests.java`.
