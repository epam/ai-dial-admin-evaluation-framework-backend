# DIAL File Reference Model

## Purpose
This spec describes the DIAL file reference system used in test case data. Covers the relative path format, @ef bucket alias resolution, prefix whitelist validation, and how FILE-type schema fields map to DIAL file paths.

Status: **Planned**

## ADDED Requirements

### Requirement: DIAL file reference format
FILE-type field values in test case data SHALL be DIAL relative file paths. The format SHALL be `files/{bucket-or-alias}/{path-to-file}`.

Two reference sources are supported in v1:
- **EF-managed files**: `files/@ef/suites/{suiteId}/{filename}` — files uploaded through the EF API
- **Public DIAL files**: `files/public/{path}/{filename}` — pre-existing files in the DIAL public/organization bucket

The file reference is a relative path (no protocol, no host). It is environment-independent when using the `@ef` alias.

#### Scenario: EF-managed file reference
- **WHEN** a test case FILE field contains `files/@ef/suites/abc-123/input.csv`
- **THEN** the system SHALL recognize this as an EF-managed file and resolve `@ef` to the real bucket name at runtime

#### Scenario: Public file reference
- **WHEN** a test case FILE field contains `files/public/datasets/eval-data.csv`
- **THEN** the system SHALL recognize this as a public DIAL file and use the path as-is (no alias resolution)

#### Scenario: Invalid file reference format
- **WHEN** a test case FILE field contains a value that does not match the `files/{prefix}/...` pattern
- **THEN** validation SHALL produce a warning

### Requirement: DialFileRefResolver component
The system SHALL provide a `DialFileRefResolver` component (`service.domain`) that translates client-facing file references to fully resolved DIAL paths.

The resolver SHALL:
- Strip the `files/` prefix from the input reference
- Replace the `@ef` alias with the real EF bucket name (discovered lazily on first use)
- Pass through references to other allowed prefixes (e.g., `public`) with only the `files/` prefix stripped
- Reject references with disallowed prefixes
- Return an **API path** (without `files/` prefix) suitable for direct use with `DialFileClient` methods

#### Scenario: Resolve @ef alias
- **WHEN** `resolver.resolveToRealPath("files/@ef/suites/abc/data.csv")` is called
- **THEN** it SHALL return `{realBucket}/suites/abc/data.csv` where `{realBucket}` is the cached EF bucket name
- **NOTE**: The `files/` prefix is stripped — the return value is an **API path** suitable for passing directly to `DialFileClient` methods (which construct URLs like `PUT /v1/files/{apiPath}`)

#### Scenario: Resolve public path (passthrough)
- **WHEN** `resolver.resolveToRealPath("files/public/datasets/input.csv")` is called
- **THEN** it SHALL return `public/datasets/input.csv` (stripped `files/` prefix, no alias replacement)

#### Scenario: Reject disallowed prefix
- **WHEN** `resolver.resolveToRealPath("files/user-bucket-xyz/private/data.csv")` is called
- **THEN** it SHALL throw a validation exception indicating the prefix is not in the allowed whitelist

#### Scenario: Build EF file reference from suite and filename
- **WHEN** `resolver.buildEfRef(suiteId, filename)` is called
- **THEN** it SHALL return `files/@ef/suites/{suiteId}/{filename}` (client-facing alias, not real bucket)

#### Scenario: Extract filename from file reference
- **WHEN** `resolver.extractFilename("files/@ef/suites/abc/data.csv")` is called
- **THEN** it SHALL return `data.csv`

### Requirement: Prefix whitelist validation
The system SHALL enforce a strict whitelist of allowed file reference prefixes. For v1, the allowed prefixes are `@ef` and `public`.

#### Scenario: Allowed prefix accepted
- **WHEN** a FILE field value starts with `files/@ef/` or `files/public/`
- **THEN** validation SHALL accept the value (format is valid)

#### Scenario: EF file reference must match owning suite
- **WHEN** a FILE field value starts with `files/@ef/suites/{suiteId}/`
- **AND** the test case belongs to a test suite with a different ID
- **THEN** validation SHALL produce a warning indicating the file reference points to a different suite's files
- **NOTE**: This prevents cross-suite file references that would break when the referenced suite is deleted

#### Scenario: Disallowed prefix rejected
- **WHEN** a FILE field value starts with `files/some-user-bucket/` or any prefix not in the whitelist
- **THEN** validation SHALL produce a warning indicating the file reference uses a disallowed prefix

#### Scenario: Whitelist is extensible
- **WHEN** future phases add support for user-personal buckets
- **THEN** the whitelist SHALL be extensible to include additional prefixes (e.g., user bucket names)
