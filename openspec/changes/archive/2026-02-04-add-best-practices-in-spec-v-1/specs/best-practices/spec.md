# Best Practices (Code Quality)

This spec defines code-quality practices for the repository. New code MUST follow these practices from the start.

## ADDED Requirements

### Requirement: Prefer imports over fully qualified names

**Status: Planned**

Source code MUST NOT use fully qualified class names (FQNs) where an import and short name can be used. This SHALL apply in method bodies, method signatures (e.g. MapStruct interface methods), and annotation attributes (e.g. `nullValuePropertyMappingStrategy`).

#### Scenario: No FQN in method body

- **WHEN** a developer writes code that references a type from another package
- **THEN** the type SHALL be referenced via an import and short name (e.g. `PageRequest.builder()`)
- **AND THEN** the code MUST NOT use the full package path (e.g. `com.epam.aidial.evaluation.data.db.model.pagination.PageRequest.builder()`)

#### Scenario: No FQN in method signature

- **WHEN** a method signature (e.g. in a MapStruct mapper interface) uses a type from another package
- **THEN** that type SHALL be declared via an import and short name in the signature
- **AND THEN** the signature MUST NOT use the full package path for the type

#### Scenario: No FQN in annotation attributes

- **WHEN** an annotation requires a class or enum value (e.g. `NullValuePropertyMappingStrategy.IGNORE`)
- **THEN** the value SHALL be referenced via an import and short name
- **AND THEN** the code MUST NOT use the full package path for the annotation attribute value

### Requirement: Configuration defaults only in YAML

**Status: Planned**

All default values for configuration properties MUST be defined in `application.yml` (or the applicable profile YAML). Classes annotated with `@ConfigurationProperties` MUST NOT define default values in Java (no field initializers for configurable properties). Properties classes SHALL hold only structure, binding, and validation. Validation SHALL be used where applicable (e.g. `@Validated` on the class, `@Min`, `@NotNull`, `@NotBlank`, `@Valid` on nested types, custom validators).

#### Scenario: No default in properties class

- **WHEN** a `@ConfigurationProperties` class defines a configurable property
- **THEN** the property field SHALL NOT have an initializer that provides a default value
- **AND THEN** the default value SHALL be defined in the corresponding YAML key under the property prefix

#### Scenario: Single source of truth for defaults

- **WHEN** an operator or developer looks up the default value for a configuration property
- **THEN** the canonical default SHALL be found in `application.yml` or profile-specific YAML
- **AND THEN** there SHALL be no duplicate default in the Java properties class

#### Scenario: Validation in properties classes

- **WHEN** a configuration property has meaningful constraints (e.g. range, non-null, format)
- **THEN** validation SHALL be implemented in the Java properties class (e.g. `@Validated` on the class, `@Min`, `@NotNull`, `@NotBlank`, `@Valid` on nested types, or custom validators)
- **AND THEN** this does not constitute a "default" and is required so that invalid YAML fails fast at startup

### Requirement: Non-configurable constants defined once per bounded context

**Status: Planned**

Non-configurable constants (magic numbers, string literals, technical limits) MUST be defined in exactly one place. Duplication across classes is forbidden. Each bounded context (e.g. pagination, validation, CSV, security) SHALL have one constants class or a small set of constants classes; naming SHALL be consistent (e.g. `PaginationConstants`, `ValidationConstants`).

#### Scenario: Constant defined in one place

- **WHEN** a constant value (e.g. a limit, a literal, a format string) is used in more than one class
- **THEN** the constant SHALL be defined in the constants class for that bounded context
- **AND THEN** all usages SHALL reference that single definition

#### Scenario: No duplicate constant definitions

- **WHEN** two or more classes need the same non-configurable value
- **THEN** the value SHALL NOT be redefined in each class
- **AND THEN** it SHALL be defined once in the appropriate constants class and imported where used

#### Scenario: Constants class per bounded context

- **WHEN** constants are introduced for a given area (e.g. pagination, CSV, validation)
- **THEN** they SHALL be placed in a constants class (or small set) for that bounded context
- **AND THEN** the class name SHALL follow the project naming convention (e.g. `<Context>Constants`)

### Requirement: No duplicated method logic

**Status: Planned**

The same behavior MUST NOT be implemented in multiple places. When the same logic appears in two or more call sites, it SHALL be extracted into a single method or reusable component and invoked from those call sites. Shared logic SHALL be placed in an appropriate layer (e.g. service, util, mapper) as defined by the architecture.

#### Scenario: Same logic in multiple call sites

- **WHEN** two or more methods or classes implement the same logical behavior (same inputs, same transformation or side effect)
- **THEN** the behavior SHALL be implemented once in a shared method or component
- **AND THEN** all call sites SHALL invoke that shared implementation

#### Scenario: Shared logic placement

- **WHEN** extracted logic is placed in the codebase
- **THEN** it SHALL be placed in an appropriate package (e.g. service layer, util, mapper) consistent with the project architecture
- **AND THEN** it SHALL be reusable and tested as needed

### Requirement: Practices referenced in AGENTS.md and OpenSpec config

**Status: Planned**

The best-practices spec and these requirements SHALL be referenced so that agents and developers apply them consistently. AGENTS.md and OpenSpec `config.yaml` SHALL be updated to point to this spec (or a summary of practices) so that it is the single reference for "how we write and refactor code" in this repo.

#### Scenario: AGENTS.md references practices

- **WHEN** a developer or agent reads AGENTS.md for project conventions
- **THEN** AGENTS.md SHALL reference the best-practices spec or list these practices (or a link to the spec)
- **AND THEN** the practices SHALL be discoverable without reading the full spec if a short summary is provided

#### Scenario: OpenSpec config includes best-practices

- **WHEN** OpenSpec is used to guide changes or implementation
- **THEN** `openspec/config.yaml` (or equivalent) SHALL include the best-practices spec in the spec set
- **AND THEN** agents and workflows that use OpenSpec SHALL have access to this spec for guidance

## Implementation Notes

*This section will be updated as requirements are implemented.*


| Requirement                                    | Status  | Code Paths |
| ---------------------------------------------- | ------- | ---------- |
| Prefer imports over FQNs                       | Planned | —          |
| Configuration defaults only in YAML            | Planned | —          |
| Non-configurable constants per bounded context | Planned | —          |
| No duplicated method logic                     | Planned | —          |
| Practices referenced in AGENTS.md and OpenSpec | Planned | —          |


### Constants Classes (to be created / maintained)

- Pagination default and max size are **configurable** via `PaginationProperties` / `application.yml`; do not duplicate them in a constants class.
- `ValidationConstants` — field length limits for list params and fact fields.
- `CsvConstants` — CSV format settings (if non-configurable).
- `SecurityConstants` — security-related constants (e.g. correlation ID length bounds).

