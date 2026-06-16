## 1. Spec and documentation

- [x] 1.1 Add best-practices spec to `openspec/specs/best-practices/spec.md` (copy from change delta or sync after review)
- [x] 1.2 Update AGENTS.md with a Best practices subsection or link to the spec so practices are discoverable
- [x] 1.3 Update `openspec/config.yaml` to include the best-practices spec in the spec set for agents and workflows

## 2. Configuration properties partition

- [x] 2.1 List all `@ConfigurationProperties` classes and their properties that have default value initializers in Java
- [x] 2.2 Ensure every such default exists in `application.yml` (or profile YAML); add any missing keys with the same values
- [x] 2.3 Remove default value initializers from all properties classes; keep only structure, binding, and validation

## 3. Constants and mappers partition

- [x] 3.1 Introduce constants classes per bounded context where non-configurable constants are duplicated (e.g. pagination, validation, CSV, security); use naming like `PaginationConstants`, `ValidationConstants`
- [x] 3.2 Replace fully qualified names with imports in mapper interfaces and implementations (e.g. MapStruct method signatures and annotation attributes such as `NullValuePropertyMappingStrategy.IGNORE`)

## 4. Service layer partition

- [x] 4.1 Replace any fully qualified names with imports in service-layer classes
- [x] 4.2 Identify and extract duplicated method logic into shared methods or components; update call sites to use them
- [x] 4.3 Replace duplicated non-configurable literals/limits with references to the appropriate constants class

## 5. Web and data layers partition

- [x] 5.1 Replace any fully qualified names with imports in web (controllers, handlers) and data.db (repositories, mappers, models) packages
- [x] 5.2 Use constants classes and shared logic where applicable; remove duplicated constants or logic

## 6. Tests partition

- [x] 6.1 Align test code with best practices: use imports instead of FQNs, use constants where applicable, remove duplicated test logic where reasonable
