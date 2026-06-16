<!-- No new or modified functional capabilities are introduced by this change.
     Spring Boot 4.0 upgrade is a pure infrastructure dependency change.
     All existing API contracts, data models, and behavioral specs remain unchanged.
     One-time migration steps (starter renames, Jackson 3 import rewrite, version bumps)
     are captured in tasks.md and design.md, not here. -->

## ADDED Requirements

### Requirement: Application uses Jackson 3 for JSON serialization
The application SHALL use Jackson 3 (`tools.jackson`) as its JSON serialization library.
The `@Primary` `JsonMapper` bean SHALL be built via the Jackson 3 builder API and registered
for all JSON serialization and deserialization across REST controllers, HTTP clients,
and internal domain processing. No production code SHALL import from `com.fasterxml.jackson`
packages other than `jackson-annotations` (`com.fasterxml.jackson.annotation`), which retains
its original group ID in Jackson 3.

**Status:** Implemented

#### Scenario: REST API responses are serialized with Jackson 3
- **WHEN** a client calls any REST endpoint
- **THEN** the response body is serialized by the Jackson 3 `JsonMapper` bean
- **THEN** `null` fields are omitted from the response (NON_NULL inclusion policy preserved)
- **THEN** dates are serialized as epoch milliseconds (WRITE_DATES_AS_TIMESTAMPS preserved)

#### Scenario: Enum deserialization is case-insensitive
- **WHEN** a request body contains an enum field value in any casing (e.g., `"get"`, `"GET"`)
- **THEN** the value is accepted and deserialized correctly (ACCEPT_CASE_INSENSITIVE_ENUMS preserved)

#### Scenario: Unknown JSON fields are ignored on deserialization
- **WHEN** a request body contains fields not present in the target DTO
- **THEN** the fields are silently ignored and no error is returned (FAIL_ON_UNKNOWN_PROPERTIES=false preserved)

#### Implementation notes
- `JsonMapperConfiguration` — `@Primary JsonMapper` bean built via `JsonMapper.builder()` (Jackson 3); `changeDefaultPropertyInclusion(NON_NULL)` replaces `serializationInclusion`; `JavaTimeModule` removed (auto-registered in Jackson 3)
- `JacksonJsonHttpMessageConverter` bean registered from the `@Primary JsonMapper` (replaces `MappingJackson2HttpMessageConverter`)
- `HttpMethodSerializer` / `HttpMethodDeserializer` — migrated to `ValueSerializer<T>` / `ValueDeserializer<T>` with `SerializationContext` / `DeserializationContext`
- `JsonSchemaStringDeserializer` — migrated to `ValueDeserializer<String>` with `tools.jackson` parser types
- `com.fasterxml.jackson.annotation.*` imports retained (jackson-annotations keeps original group ID in Jackson 3)
