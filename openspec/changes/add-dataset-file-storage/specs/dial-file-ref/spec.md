# DIAL File Reference Model — Delta

## MODIFIED Requirements

### Requirement: DIAL file reference format

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
- Returns `files/{realBucket}/path`

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

### Requirement: Prefix whitelist validation

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
