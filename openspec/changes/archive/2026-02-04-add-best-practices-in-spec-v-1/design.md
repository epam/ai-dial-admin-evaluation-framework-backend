## Context

The codebase has no single written set of code-quality practices. Inconsistencies exist: full package names in code (e.g. MapStruct method signatures, annotations), configuration defaults split between Java `@ConfigurationProperties` and `application.yml`, duplicated constants, and duplicated method logic. The proposal defines four practices and an incremental implementation approach (split codebase into parts, scan, fix). This design covers how to implement the practices spec, how to partition the codebase for scanning, and how to update docs and tooling (AGENTS.md, OpenSpec config.yaml, optional checkstyle).

**Current state:** Java 21, Spring Boot, JDBC-only data layer, multiple packages (web, service, data.db, configuration). Configuration properties use field initializers for defaults; some defaults also appear in `application.yml`. No project-wide constants convention; no automated checks for FQNs or config defaults in properties classes.

**Stakeholders:** Developers, reviewers, AI agents (via AGENTS.md and OpenSpec).

## Goals / Non-Goals

**Goals:**

- Define a single best-practices spec (requirements and how to check them).
- Implement the spec incrementally by partitioning the codebase, scanning each part, and fixing violations.
- Update AGENTS.md, OpenSpec `config.yaml`, and the new spec so practices are discoverable and applied.
- Establish one constants class (or small set) per bounded context and move all config defaults to YAML.

**Non-Goals:**

- Automating every check (e.g. full FQN detection or duplicate-method detection) in this change; tooling can be added later.
- Changing behavior of existing features; this is code-quality and documentation only.
- Big-bang refactor of the entire codebase in one pass.

## Decisions

1. **Partitioning for incremental application**  
   **Choice:** Split by package/layer: `configuration.properties`, `*.mapper`, `*.service`, `*.web`, `data.db` (repositories, mappers, models), then tests.  
   **Rationale:** Aligns with existing architecture; each part has clear ownership and can be scanned and fixed in a few PRs. Alternatives: by feature (would cross layers and complicate scanning) or by file count (arbitrary).  
   **Order:** Start with configuration.properties and mappers (high impact, smaller surface), then service, web, data.db, then tests.

2. **Config defaults: YAML only**  
   **Choice:** Remove all default value initializers from `@ConfigurationProperties` classes; define every default in `application.yml` (or profile-specific YAML). Properties classes keep structure, binding, and validation only.  
   **Rationale:** Single source of truth; operators and docs see all defaults in one place. Alternative (defaults in Java with YAML override) was rejected per proposal decision.  
   **Validation:** Allowed in Java (e.g. `@Valid`, `@Min`, custom validators on properties classes). No default values in Java.

3. **Constants: one class per bounded context**  
   **Choice:** For each bounded context (e.g. pagination, validation, CSV, security), introduce one constants class (or a small set if one file would be too large). Name consistently (e.g. `PaginationConstants`, `ValidationConstants`). Non-configurable literals and limits live only there.  
   **Rationale:** Avoids duplication and scattered magic values; keeps constants discoverable. Alternative (one project-wide constants class) was rejected to avoid a single large file and to keep context boundaries clear.

4. **Detection approach**  
   **Choice:** Manual scan plus optional checkstyle/scripts. First pass: manual review per partition using the spec as a checklist. Optionally add checkstyle rules or scripts later to flag FQNs in source, or field initializers in `@ConfigurationProperties`.  
   **Rationale:** Gets results without blocking on tooling; tooling can be added incrementally. Full duplicate-method detection is out of scope for v1 (manual only).

5. **Docs and OpenSpec**  
   **Choice:** Update AGENTS.md with a “Best practices” subsection (or link to the spec). Update OpenSpec `config.yaml` so the best-practices spec is part of the spec set and agents are guided to it. The canonical practices text lives in the new `openspec/specs/best-practices/spec.md` (and in the change delta until synced).  
   **Rationale:** Single spec as source of truth; AGENTS.md and config.yaml point agents and developers to it.

## Risks / Trade-offs

- **Risk:** Moving defaults from Java to YAML could miss a property and leave it null at runtime.  
  **Mitigation:** List every property in each `@ConfigurationProperties` class and ensure a corresponding key and default exist in `application.yml`; add tests or startup checks if needed.

- **Risk:** “Duplicate method logic” is subjective; different reviewers may disagree.  
  **Mitigation:** Spec defines a simple rule (e.g. “same logic in two or more call sites → extract”) and examples; accept that some cases will be judgment calls.

- **Trade-off:** No automated FQN or default-in-Java checks in v1 increases reliance on manual review.  
  **Acceptable:** Reduces scope and allows incremental tooling later.

## Migration Plan

1. **Add the spec and docs**  
   Create `openspec/specs/best-practices/spec.md` (from change delta after review). Update AGENTS.md and `openspec/config.yaml` to reference it.

2. **Apply practices by partition**  
   For each partition (configuration.properties → mappers → service → web → data.db → tests):  
   - Scan files in that partition against the spec.  
   - Fix violations (imports, constants, config defaults, duplicate logic).  
   - Move defaults from properties classes to `application.yml` where applicable.  
   - Commit/PR per partition or per logical group to keep diffs reviewable.

3. **Rollback**  
   No runtime contract change; rollback is reverting commits. If only docs/spec are merged first, no code rollback needed.

## Appendix: Task 2.1 — Configuration properties with Java default initializers

| Class | Prefix | Properties with default initializers |
|-------|--------|--------------------------------------|
| DialCoreProperties | dial.components.core | baseUrl, connectTimeoutMs, readTimeoutMs, retry (nested Retry: maxAttempts, delayMs, multiplier) |
| ValidationProperties | validation | maxWarningsPerCase = 5 |
| RevalidationProperties | validation.revalidation | batchSize = 500, timeoutMinutes = 5 |
| JwtSecurityProperties | security.jwt | userClaim = "sub" |
| PaginationProperties | pagination | defaultSize = 100, maxSize = 1000 |
| CsvImportProperties | csv.import | maxFileSize = 10MB, maxRows = 100000, batchSize = 1000 |
| CsvExportProperties | csv.export | pageSize = 500 |
| JwtProvidersProperties | (none) | providers = new HashMap<>() only; no configurable field defaults |
| LoggerConfigProperties | logger.configuration | (none; path has no initializer) |
| CustomizableTraceInterceptorProperties | app.customizable-trace-interceptor | messages = new HashMap<>() |

## Open Questions

- Exact checkstyle rules or script locations if we add automated checks in a follow-up.
- Whether to add a “Best practices” section to AGENTS.md inline vs. a short pointer to the spec only.
