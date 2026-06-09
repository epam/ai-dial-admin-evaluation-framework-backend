## Why

Spring Boot 3.5.x reaches end of OSS support, requiring an upgrade to Spring Boot 4.0.x to remain on a supported release line. Spring Boot 4.0 is a major version that brings Spring Framework 7.0, Jakarta EE 11 (Servlet 6.1 baseline), Jackson 3, and a new modular starter structure — all of which require migration work.

## What Changes

- **BREAKING**: Bump Spring Boot plugin from `3.5.14` → `4.0.x` in `build.gradle`
- **BREAKING**: Replace deprecated/renamed starters:
  - `spring-boot-starter-web` → `spring-boot-starter-webmvc`
  - `spring-boot-starter-aop` → `spring-boot-starter-aspectj`
  - `spring-boot-starter-oauth2-resource-server` → `spring-boot-starter-security-oauth2-resource-server`
- **BREAKING**: Replace `spring-boot-starter-test` with `spring-boot-starter-test-classic` (interim) or adopt per-technology test starters
- **BREAKING**: Jackson 3 replaces Jackson 2 as default; group IDs change from `com.fasterxml.jackson` → `tools.jackson` and package names change accordingly. `mcp-json-jackson2:1.1.0` uses Jackson 2 — compatibility path needed (`spring-boot-jackson2` module or Jackson 2 compat layer)
- **BREAKING**: `spring-boot-starter-flyway` is now the required way to bring in Flyway auto-configuration (direct `flyway-core` dependency no longer sufficient for auto-config)
- Add explicit `spring-boot-starter-flyway` starter alongside existing `flyway-core`/`flyway-database-postgresql`
- Update springdoc-openapi to a Spring Boot 4-compatible release (`springdoc-openapi-starter-webmvc-ui` / `springdoc-openapi-starter-webmvc-api`)
- Verify OpenTelemetry instrumentation BOM compatibility with Spring Boot 4 / Spring Framework 7
- Verify jOOQ 3.20.x compatibility; upgrade if required for Spring Boot 4 BOM alignment
- Verify MapStruct 1.6.x compatibility with Spring Framework 7 and Jakarta EE 11
- **BREAKING**: Upgrade `com.networknt:json-schema-validator` `1.5.9` → `3.0.3` (Jackson 3-native line). The `1.5.9` line accepts only `com.fasterxml.jackson.databind.JsonNode`, so it stops compiling once production code moves to `tools.jackson`. The `3.x` major API restructure forces a rewrite of `SchemaValidationService` (`SchemaRegistry`/`Schema`/`Error` API). Force-pin in `resolutionStrategy` too, since the MCP SDK otherwise drags in a Jackson 2 build.
- Pin `org.testcontainers:testcontainers` to `1.21.4` against the Spring Boot 4 BOM's `2.0.5` (Testcontainers 2.x renamed module artifacts; staying on 1.x is a separate follow-up)
- Add `org.slf4j:jcl-over-slf4j` Commons-Logging → SLF4J bridge (Spring Framework 7 dropped the repackaged `spring-jcl`)
- Pin `io.grpc:grpc-netty-shaded` to `1.81.0` (Spring Boot 4 / OTel 2.28.1 BOMs no longer manage `io.grpc`)
- Bump `io.modelcontextprotocol.sdk:mcp-core` `1.1.0` → `1.1.1` (`mcp-json-jackson2` stays at `1.1.0`, covered by the `spring-boot-jackson2` shim)
- Update `docs/configuration.md` AGENTS.md Quick Reference table to reflect new Spring Boot version
- Update `openspec/config.yaml` (tech stack version bump)

## Capabilities

### New Capabilities

_None._ This is a dependency upgrade; no new functional capabilities are introduced.

### Modified Capabilities

_None._ No spec-level API or behavior requirements are changing — all existing specs remain valid. Implementation changes are confined to dependency declarations, starter names, and any code-level adjustments forced by removed/renamed Spring Boot 4 APIs (e.g., Jackson 3 package changes, `HttpMessageConverters` deprecation path).

## Impact

**Build (`build.gradle`)**
- Spring Boot plugin version bump
- Starter renames: `spring-boot-starter-web` → `spring-boot-starter-webmvc`, `spring-boot-starter-aop` → `spring-boot-starter-aspectj`, `spring-boot-starter-oauth2-resource-server` → `spring-boot-starter-security-oauth2-resource-server`
- Add `spring-boot-starter-flyway` (Flyway auto-config now requires a starter)
- Jackson 3 migration: update any direct `com.fasterxml.jackson` imports to `tools.jackson`; evaluate `mcp-json-jackson2` Jackson 2 dependency (use `spring-boot-jackson2` transitional module if MCP SDK requires Jackson 2)
- Test starters: adopt `spring-boot-starter-test-classic` as interim, or switch to targeted test starters (`spring-boot-starter-jdbc-test`, etc.)
- Changed (not merely verified) third-party dependencies forced by the upgrade:
  - `com.networknt:json-schema-validator` `1.5.9` → `3.0.3` (Jackson 3-native; rewrites `SchemaValidationService`; also force-pinned in `resolutionStrategy`)
  - `org.testcontainers:testcontainers` pinned to `1.21.4` (Spring Boot 4 BOM manages `2.0.5`; stay on 1.x via `testcontainers-bom:1.21.4`)
  - `org.slf4j:jcl-over-slf4j` added (Spring Framework 7 dropped repackaged `spring-jcl`)
  - `io.grpc:grpc-netty-shaded` pinned `1.81.0` (no longer BOM-managed by Spring Boot 4 / OTel 2.28.1)
  - `io.modelcontextprotocol.sdk:mcp-core` `1.1.0` → `1.1.1`

**Application code**
- Any import of `com.fasterxml.jackson` classes (other than `jackson-annotations`) must migrate to `tools.jackson`
- `@JsonComponent` → `@JacksonComponent`, `@JsonMixin` → `@JacksonMixin` if used
- `Jackson2ObjectMapperBuilderCustomizer` → `JsonMapperBuilderCustomizer` if present
- Review `HttpMessageConverters` usage (deprecated in 4.0)
- Remove any calls to deprecated Spring Boot 3.x APIs surfaced by a compile pass

**Third-party compatibility (requires verification)**
- `springdoc-openapi` 2.8.x → needs version that supports Spring Boot 4 / Spring MVC from Spring Framework 7
- `opentelemetry-spring-boot-starter` BOM `2.12.0` → verify Spring Boot 4 support; upgrade if needed
- `jOOQ 3.20.4` → verify no Spring Boot 4 BOM conflict; codegen task uses Flyway classpath (may need `spring-boot-starter-flyway` on the buildscript classpath)
- `MapStruct 1.6.3` → confirm Jakarta EE 11 annotation processor compatibility

**Documentation**
- `docs/configuration.md`: no property changes expected; review for any renamed `spring.*` keys
- `AGENTS.md` Quick Reference table: update Spring Boot version
- `openspec/config.yaml`: update Spring Boot version in tech stack
