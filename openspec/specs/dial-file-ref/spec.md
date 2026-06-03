# DIAL File Reference Model

## Purpose
This spec describes the DIAL file reference system used in test case data. Covers the relative path format, @ef bucket alias resolution, prefix whitelist validation, resolveToDialRef for embedding refs in DIAL payloads, and how FILE-type schema fields map to DIAL file paths.

Status: **Implemented**

## Requirements

### Requirement: DIAL file reference format

Status: Implemented

FILE-type field values in test case data SHALL be DIAL relative file paths. The format SHALL be `{bucket-or-alias}/{path-to-file}` (short format — no `files/` prefix).

Two reference sources are supported in v1:
- **EF-managed files**: `@ef/suites/{suiteId}/{filename}` — files uploaded through the EF API
- **Public DIAL files**: `public/{path}/{filename}` — pre-existing files in the DIAL public/organization bucket

The file reference is a relative path (no protocol, no host, no `files/` prefix). It is environment-independent when using the `@ef` alias.

#### Scenario: EF-managed file reference
- **WHEN** a test case FILE field contains `@ef/suites/abc-123/input.csv`
- **THEN** the system SHALL recognize this as an EF-managed file and resolve `@ef` to the real bucket name at runtime

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

**`resolveToDialRef(ref)`** — resolves to a DIAL data reference for embedding in DIAL request payloads:
- Calls `resolveToRealPath(ref)` internally
- Prepends `files/` to the result
- Returns `files/{realBucket}/path` — the format DIAL Core expects when a file reference appears as a string value inside a JSON or form body sent to a DIAL deployment

**`buildEfRef(suiteId, filename)`** — builds the client-facing short-format EF reference:
- Returns `@ef/suites/{suiteId}/{filename}` (no `files/` prefix)

**`extractFilename(ref)`** — extracts the last path segment (unchanged behavior).

#### Scenario: resolveToRealPath — resolve @ef alias
- **WHEN** `resolver.resolveToRealPath("@ef/suites/abc/data.csv")` is called
- **THEN** it SHALL return `{realBucket}/suites/abc/data.csv` where `{realBucket}` is the cached EF bucket name

#### Scenario: resolveToRealPath — public path passthrough
- **WHEN** `resolver.resolveToRealPath("public/datasets/input.csv")` is called
- **THEN** it SHALL return `public/datasets/input.csv` (no transformation)

#### Scenario: resolveToRealPath — reject disallowed prefix
- **WHEN** `resolver.resolveToRealPath("user-bucket/private/data.csv")` is called
- **THEN** it SHALL throw a validation exception indicating the prefix is not in the allowed whitelist

#### Scenario: resolveToRealPath — reject old files/ format
- **WHEN** `resolver.resolveToRealPath("files/@ef/suites/abc/data.csv")` is called (old format)
- **THEN** it SHALL throw a validation exception (the `files/` prefix is not valid input)

#### Scenario: resolveToDialRef — EF ref becomes full DIAL ref
- **WHEN** `resolver.resolveToDialRef("@ef/suites/abc/data.csv")` is called
- **THEN** it SHALL return `files/{realBucket}/suites/abc/data.csv`

#### Scenario: resolveToDialRef — public ref becomes full DIAL ref
- **WHEN** `resolver.resolveToDialRef("public/datasets/input.csv")` is called
- **THEN** it SHALL return `files/public/datasets/input.csv`

#### Scenario: buildEfRef returns short format
- **WHEN** `resolver.buildEfRef(suiteId, "report.pdf")` is called
- **THEN** it SHALL return `@ef/suites/{suiteId}/report.pdf` (no `files/` prefix)

#### Scenario: extractFilename unchanged
- **WHEN** `resolver.extractFilename("@ef/suites/abc/data.csv")` is called
- **THEN** it SHALL return `data.csv`

### Requirement: FILE ref resolution before DIAL deployment invocation

Status: Implemented

The system SHALL resolve FILE-typed binding values (both constant values and values from test case data fields) to DIAL data ref format (`files/{realBucket}/path`) in `ResolvedRequestService` before passing to request body serializers.

This ensures `JsonRequestBodySerializer` and `UrlEncodedFormRequestBodySerializer` forward DIAL-resolvable paths (not `@ef` aliases) when embedding file references as string values in deployment request payloads.

FILE-typed values are identified by the `|file` type hint in the placeholder syntax (e.g., `${{attachment|file}}`). Resolution applies at any nesting depth in the JSON body — the resolver recurses into nested objects and arrays. Applies to JSON and URL-encoded form bodies only.

Note: Multipart `FormPartDto` FILE parts (`FormPartDto.type == FILE`) are excluded — they remain as short-format refs in `ResolvedFormPartDto.resolvedValue` and are materialized as file bytes directly by `MultipartFormDataRequestBodySerializer`.

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

### Requirement: Prefix whitelist validation

Status: Implemented

The system SHALL enforce a strict whitelist of allowed file reference prefixes. For v1, the allowed prefixes are `@ef` (configurable alias) and `public`.

#### Scenario: Allowed prefix accepted
- **WHEN** a FILE field value starts with `@ef/` or `public/`
- **THEN** validation SHALL accept the value (format is valid)

#### Scenario: EF file reference must match owning suite
- **WHEN** a FILE field value starts with `@ef/suites/{differentSuiteId}/`
- **AND** the test case belongs to a different test suite
- **THEN** validation SHALL produce a warning indicating the file reference points to a different suite's files

#### Scenario: Disallowed prefix rejected
- **WHEN** a FILE field value starts with any prefix not in the whitelist (e.g., `some-user-bucket/`)
- **THEN** validation SHALL produce a warning

#### Scenario: Whitelist is extensible
- **WHEN** future phases add support for user-personal buckets
- **THEN** the whitelist SHALL be extensible to include additional prefixes
