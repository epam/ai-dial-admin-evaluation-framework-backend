# Proposal: Add Best Practices Spec (v1)

## Why

The codebase needs a single, explicit set of code-quality practices so that reviews, refactors, and new code stay consistent. Without a written spec, practices drift and inconsistencies accumulate (e.g. full package names in code, duplicated constants, config defaults split between Java and YAML). Defining practices in a spec and applying them incrementally by scanning parts of the codebase will improve readability, maintainability, and onboarding.

## What Changes

- **Define a best-practices spec** that lists concrete, checkable practices (see below).
- **Document the implementation approach**: split the codebase into parts (e.g. by package or layer), scan each part for violations, fix inconsistencies, then move to the next part. No big-bang rewrite.
- **Add or adjust tooling/docs** as needed so the practices are enforceable (e.g. checkstyle rules, AGENTS.md, or scripts that flag violations).
- Optionally extend the practice set later (e.g. method length, naming, or test conventions).

## Capabilities

### New Capabilities

- **best-practices**: A spec that defines the set of practices, how to detect violations, and how implementation is done incrementally (split codebase into parts, scan each part, fix, then proceed). The spec is the single reference for “how we write and refactor code” in this repo.

### Modified Capabilities

- None. Existing feature specs (test-suites, dial-core-client, etc.) are unchanged; this change only adds a new, cross-cutting practices spec.

## Practices (Initial Set)

The following are the initial practices to be specified and applied. The spec will make each one precise (e.g. where to put defaults, what “duplication” means).

1. **Prefer imports over fully qualified names**
  Avoid using full package names in code (e.g. `com.epam.aidial.evaluation.data.db.model.pagination.PageRequest.builder()`). Use imports and short names (e.g. `PageRequest.builder()`). Applies to method bodies, method signatures (e.g. MapStruct), and annotations (e.g. use `NullValuePropertyMappingStrategy.IGNORE` with an import).
2. **Configuration defaults in application.yml**  
   Do not define default values for configuration in `@ConfigurationProperties` classes. Define all defaults in `application.yml` (or the relevant profile) so the YAML is the single source of truth. Properties classes hold only the structure, binding, and validation (e.g. `@Valid`, `@Min`, custom validators in Java are allowed).
3. **Non-configurable constants defined once**  
   Constants that are not configuration (magic numbers, literals, technical limits) must be defined in one place only. No duplication across classes. **Placement:** one constants class (or small set) per bounded context; the spec will define naming and structure.
4. **No duplicated method logic**
  The same behavior must not be implemented in multiple places. Prefer extracting shared logic into a single method or component and reusing it. The spec will give guidance on when extraction is required (e.g. same logic in two or more call sites) and where to place shared code (e.g. service, util, or mapper).

## Implementation Approach (to be detailed in the spec)

- **Incremental application**: Do not refactor the whole codebase at once.
- **Split**: Divide the codebase into parts (e.g. by package: `configuration.properties`, `*.mapper`, `*.service`, `*.web`, `data.db`, etc.).
- **Scan**: For each part, check code against the practices (manually and/or with tooling).
- **Fix**: Resolve violations in that part, then move to the next part.
- **Repeat**: Continue until all parts are aligned with the spec. New code must follow the practices from the start.

## Impact

- **Code**: All Java code is in scope for eventual alignment; highest impact in configuration properties, mappers (e.g. MapStruct method signatures and annotations), services, and any place with constants or duplicated logic.
- **Config**: `application.yml` (and profile-specific YAML) will hold all configuration defaults; some defaults may move from property classes into YAML.
- **Docs**: AGENTS.md, OpenSpec `config.yaml`, and the new best-practices spec will reference these practices so agents and developers apply them consistently.
- **Tooling**: Optional checkstyle or custom checks to flag FQNs, duplicated constants, or config defaults in Java; scope to be decided in design/tasks.

## Decisions (from open points)

- **Constants placement:** One (or a small set of) constants class per bounded context. The spec will define naming and structure.
- **Config and validation:** No defaults in Java. All configuration defaults live in YAML. Validation (e.g. `@Valid`, `@Min`, custom validators) may be implemented in Java in the configuration/properties classes.
- **Future practices:** The spec may reserve space for later practices (e.g. max method length, naming conventions, test structure) so the set can grow without changing the process.

