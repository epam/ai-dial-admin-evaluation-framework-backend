## ADDED Requirements

### Requirement: Property table column schema
`docs/configuration.md` SHALL document every configurable property using tables with exactly these six columns, in this order: `Property`, `Environment Variable`, `Default`, `Required`, `Applied when`, `Description`. Every row MUST populate every column — blank cells are not allowed. When a cell has no applicable value, the author MUST use the sentinel `-`. The `Environment Variable` column SHALL be filled for every row; it MAY be set to `-` only when both (a) the env-var name is the trivial uppercase-dot-to-underscore conversion of the property key AND (b) the author has deliberately decided the env-var need not be called out. The preferred practice is to always spell out the env-var for grep-ability.

Status: **Planned**

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

Status: **Planned**

#### Scenario: Property with no default marked Yes
- **WHEN** a property has no default value and the application refuses to start when unset (e.g., `dial.api-key`)
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

Status: **Planned**

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
`docs/configuration.md` SHALL contain only operator-facing property reference content: property tables, post-table prose explaining the runtime effect of the properties in that section, and the Overview/Notes groups. Internal architectural narrative — how a component is implemented, caching strategies, AOP aspects, encoding choices, SQL optimisation tiers — MUST NOT appear in `docs/configuration.md`. Such narrative SHALL live in `docs/design/` or be removed where `docs/design/` already covers it. Behavioural notes that directly explain a property's effect on runtime behaviour (e.g., "when `dial.api-key` is set, the application authenticates as the EF service account") are permitted and SHALL be placed as prose subsections AFTER the table within their section — never interleaved with the table rows and never before the table.

Status: **Planned**

#### Scenario: Internal architecture content removed from configuration.md
- **WHEN** a reviewer encounters prose in `docs/configuration.md` describing component internals (e.g., "`AtomicReference`-cached bucket discovery", "`TransactionTimestampAspect` initializes via AOP `@Before`", "ORDER BY composite PK descending")
- **THEN** the reviewer requests the prose be moved to `docs/design/` or removed if already covered there

#### Scenario: Behavioural note placed after the table
- **WHEN** a section needs prose explaining what happens when a property is set (e.g., Azure AD token refresh flow for the Data Layer section)
- **THEN** the prose appears after the property table in that section, not before and not interleaved

### Requirement: Every configurable property has a documented row
Every property bound via `@ConfigurationProperties` or referenced by application code under `com.epam.aidial.evaluation` SHALL have a corresponding row in `docs/configuration.md`. A PR that introduces a new configurable property MUST update `docs/configuration.md` in the same PR with a compliant six-column row. Defaults listed in the `Default` column SHALL match the values actually defined in `application.yml`; when a default changes, the `Default` column SHALL be updated in the same PR.

Status: **Planned**

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

Status: **Planned**

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

- Target file: `docs/configuration.md`.
- Supporting rule surface: the `DO ✅` list in `AGENTS.md` — one bullet added in the same PR pointing at this spec, so the rule is always loaded into contributor context.
- Peer-service reference format: `github.com/epam/ai-dial-admin-backend/blob/development/docs/configuration.md` and `github.com/epam/ai-dial-admin-deployment-manager-backend/blob/development/docs/configuration.md`. This spec deliberately narrows the peer schema by introducing a fixed `Required` vocabulary and a fixed top-level grouping.
- No automated conformance check ships with this spec; enforcement is by human review.
- The spec is operator-facing docs governance — it does not prescribe Java code, application.yml structure, or any runtime behaviour.
