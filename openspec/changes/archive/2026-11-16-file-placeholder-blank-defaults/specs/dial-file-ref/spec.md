## ADDED Requirements

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
