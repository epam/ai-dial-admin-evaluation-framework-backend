# configuration-docs Specification

## Purpose
Define the operator-facing configuration reference and its established maintenance rules: consistent property tables, requirement vocabulary, grouping, and code-backed defaults. These are documentation conventions, not runtime validation or an assertion that every property has already been exhaustively audited.
## Requirements
### Requirement: Property table column schema
Property-reference tables in `docs/configuration.md` SHALL use exactly these six columns, in this order: `Property`, `Environment Variable`, `Default`, `Required`, `Applied when`, `Description`. Every property-reference row MUST populate every column, using `-` when no value applies. Supplemental example/mapping tables are not property-reference tables. Environment names SHALL reflect explicit YAML aliases where present rather than assuming every property uses a mechanically derived name.

Status: **Implemented**

#### Scenario: Property documented with all six columns
- **WHEN** a contributor adds a row for a new configurable property to `docs/configuration.md`
- **THEN** the row populates `Property`, `Environment Variable`, `Default`, `Required`, `Applied when`, and `Description`, with `-` used for any cell that has no applicable value

#### Scenario: Table with fewer than six columns rejected at review
- **WHEN** a reviewer encounters a table in `docs/configuration.md` that omits any of the six required columns
- **THEN** the reviewer requests changes so the table conforms to the six-column schema before merge

#### Scenario: Unconditional property uses dash for Applied when
- **WHEN** a property applies regardless of any other property's value (e.g., `pagination.default-size`)
- **THEN** its row records `-` in the `Applied when` column

### Requirement: Required column uses the four-term vocabulary
The `Required` column of every property row in `docs/configuration.md` SHALL contain exactly one of four values — `Yes`, `No`, `Conditional`, or `Recommended` — with the meanings below. No other value (including free-text elaborations) is permitted in this column. Nuance MUST be expressed in the `Applied when` or `Description` columns or in a post-table prose subsection, not inside `Required`. `Yes` means the property must be set and has no sensible default (the application fails fast at startup when missing). `No` means the default is safe in any environment. `Conditional` means the property is required only when another property takes a specific value; the condition MUST be spelled out in the same row's `Applied when` column. `Recommended` means the property has a default, but the default is not suitable for production and operators SHOULD override it.

Status: **Implemented**

#### Scenario: Property with no default marked Yes
- **WHEN** a property has no default value and the application refuses to start when unset
- **THEN** the row records `Yes` in the `Required` column

#### Scenario: Property required only in a specific configuration marked Conditional
- **WHEN** a property is required only when another property takes a specific value (e.g., `postgres.meta.datasource.username` when `datasource.meta.auth.type=azure`)
- **THEN** the row records `Conditional` in `Required` and spells out the condition in `Applied when`

#### Scenario: Property with insecure default marked Recommended
- **WHEN** a property has a non-empty default that must be overridden before production use (e.g., `postgres.meta.datasource.password` defaulting to `postgres`)
- **THEN** the row records `Recommended` in `Required`

#### Scenario: Free-text Required value rejected
- **WHEN** a row records any value in `Required` other than `Yes`, `No`, `Conditional`, or `Recommended` (e.g., `"No (recommended to adjust for target environment)"` or `"Yes, if X is specified"`)
- **THEN** the reviewer requests changes so the row uses one of the four allowed terms

### Requirement: Top-level grouping and ordering
`docs/configuration.md` SHALL organise content into exactly nine top-level groups, in this order: (1) Overview, (2) Spring Framework Configuration, (3) Security, (4) Data Layer, (5) DIAL Integration, (6) Evaluation Engine, (7) Data Management, (8) Observability, (9) Notes. Every property section SHALL sit under exactly one top-level group. Sections within a group are ordered to move from foundational to derived (e.g., Meta datasource before Flyway within Data Layer; Core client before File Storage within DIAL Integration). The Overview group SHALL cover Spring config precedence (environment variables → YAML → defaults), the fail-fast `@ConfigurationProperties` validation guarantee, and the uppercase-dot-to-underscore environment-variable conversion rule; these topics SHALL be stated once, in the Overview, and not repeated elsewhere in the document. The Notes group SHALL carry operational callouts that are not themselves property rows: the Docker run example, security hardening reminders, and deployment cautions.

Status: **Implemented**

#### Scenario: New property placed under the correct top-level group
- **WHEN** a contributor adds a new DIAL-related property
- **THEN** the property's section sits under the `DIAL Integration` group, not at the top level or under another group

#### Scenario: Env-var conversion rule lives only in Overview
- **WHEN** a reader looks for the uppercase-dot-to-underscore env-var conversion rule
- **THEN** they find it once in the Overview group and nowhere else in the document

#### Scenario: Flat document restructured under nine groups
- **WHEN** `docs/configuration.md` is reviewed after the structural change lands
- **THEN** its Table of Contents shows the nine top-level groups in the prescribed order with every section nested under exactly one of them

### Requirement: Non-configuration narrative lives in design docs
`docs/configuration.md` SHALL present operator-facing property tables with short introductory or post-table prose explaining their runtime effects, plus the Overview/Notes groups. Behavioral introductions MAY precede a property's table; longer explanatory notes MAY follow it. Contributors SHALL keep detailed internal architecture, caching strategies, AOP implementation, encoding internals, and SQL optimization design in the design documentation rather than expanding them in the operator reference. This is a documentation/review boundary, not an automated assertion that every existing sentence is free of implementation detail.

Status: **Implemented**

#### Scenario: Internal architecture content removed from configuration.md
- **WHEN** a reviewer encounters prose in `docs/configuration.md` describing component internals (e.g., "`AtomicReference`-cached bucket discovery", "`TransactionTimestampAspect` initializes via AOP `@Before`", "ORDER BY composite PK descending")
- **THEN** the reviewer requests the prose be moved to `docs/design/` or removed if already covered there

#### Scenario: Behavioral context surrounds its property table
- **WHEN** a section needs prose explaining what its properties do (e.g., API-key authentication, SSE processing, or JSONata bounds)
- **THEN** a short introduction MAY appear before its property table and explanatory notes MAY appear after it, without interleaving prose with table rows

### Requirement: Configuration changes maintain the operator reference
A PR that introduces a new configurable property in the backend or its shared runner module MUST update `docs/configuration.md` in the same PR with a compliant six-column row. Defaults listed in the `Default` column SHALL match the applicable application YAML; when a default changes, the `Default` column SHALL be updated in the same PR. This is a contributor/review rule, not an automated runtime or documentation-completeness check.

Status: **Implemented**

#### Scenario: New property documented in the same PR that introduces it
- **WHEN** a PR adds a new field to a `@ConfigurationProperties` class with a binding in `application.yml`
- **THEN** the same PR adds a row to `docs/configuration.md` with all six columns populated and the correct `Default`, `Required`, and `Applied when` values

#### Scenario: Undocumented property caught in review
- **WHEN** a reviewer finds a configurable property in code or `application.yml` that has no row in `docs/configuration.md`
- **THEN** the reviewer requests a documentation row before approving the PR

#### Scenario: Default value drift caught in review
- **WHEN** a PR changes a default in `application.yml` without updating the `Default` column in `docs/configuration.md`
- **THEN** the reviewer requests the `Default` column be updated in the same PR

### Requirement: Applied-when expression syntax
The `Applied when` column SHALL use a small, recognisable expression syntax rather than free-form English. The permitted atoms are: `<property-path>=<value>`, `<property-path>!=<value>`, `<property-path> is set` (property has a non-null, non-empty value), and `<property-path> is not set`. Atoms are joined with capitalised `AND` or `OR`; when mixing operators, parentheses MUST be used to disambiguate (e.g., `(a=1 OR a=2) AND b=true`). The sentinel `-` SHALL be used when the property is unconditional. The `Applied when` column MUST NOT contain English prose; extended context (e.g., "only when running behind a reverse proxy") belongs in the `Description` column or the post-table prose subsection, not in `Applied when`. When a condition genuinely cannot be expressed in this grammar, the row SHALL record `see description` in `Applied when` and the `Description` column SHALL spell out the condition — this escape hatch is a last resort, not the default.

Status: **Implemented**

#### Scenario: Single-condition property
- **WHEN** a property applies only when `metric-providers.sync.enabled=true`
- **THEN** its row's `Applied when` column records `metric-providers.sync.enabled=true`

#### Scenario: Multi-condition property
- **WHEN** a property applies only when both `datasource.meta.vendor=POSTGRES` and `datasource.meta.auth.type=basic`
- **THEN** its row's `Applied when` column records `datasource.meta.vendor=POSTGRES AND datasource.meta.auth.type=basic`

#### Scenario: Unconditional property
- **WHEN** a property applies regardless of any other configuration
- **THEN** its row's `Applied when` column records `-`

#### Scenario: English prose in Applied-when rejected
- **WHEN** a row records free-text English in `Applied when` (e.g., `"only when Azure is enabled"`)
- **THEN** the reviewer requests the expression be rewritten in the prescribed syntax (e.g., `auth.azure.type=managed`)

## Implementation notes

- [Configuration reference](../../../docs/configuration.md) — current property-reference schema, vocabulary, nine-group structure, and supplemental provider mapping table.
- [AGENTS.md](../../../AGENTS.md) — same-PR configuration documentation rule.
- [application.yml](../../../src/main/resources/application.yml) — backend defaults and explicit environment aliases; examples do not imply that every `@ConfigurationProperties` class has identical validation annotations.

