## 1. Configuration

- [x] 1.1 Create `com.epam.aidial.evaluation.configuration.properties.security.ApiKeyProperties` (`@ConfigurationProperties(prefix = "config.rest.security.api-key")`, `@Validated`) with fields `enabled`, `coreUrl`, `cacheTtlSeconds`, `cacheMaxSize`, `requestTimeoutMs`, `rolesMapping`, `defaultRolesMapping`, `userClaimsRoleClaim`, `startupProbe`; parse both JSON mapping strings into `Map<String, List<String>>` and validate in `@PostConstruct` (fail fast: enabled+blank core-url, invalid JSON, or both mappings empty)
- [x] 1.2 Add `config.rest.security.api-key.*` block to `application.yml` with `${ENV_VAR:default}` values matching the design
- [x] 1.3 Add each new property as a row to `docs/configuration.md` (six columns: Property, Environment Variable, Default, Required, Applied when, Description)
- [x] 1.4 Write `ApiKeyPropertiesTest`: disabled short-circuits validation; missing core-url when enabled throws; invalid JSON in either mapping throws naming the property; both mappings blank/empty-object throws; valid single/both mappings parse correctly
- [x] 1.5 Run `./gradlew test --tests "com.epam.aidial.evaluation.configuration.properties.security.ApiKeyPropertiesTest"` and confirm it passes

## 2. DIAL Core introspection client

- [x] 2.1 Create `com.epam.aidial.evaluation.client.apikey.ApiKeyIntrospectionClientConfiguration` with `@Bean("apiKeyIntrospectionRestClient")`, mirroring `DialCoreClientConfiguration`'s `SimpleClientHttpRequestFactory` + `RestClient.builder().baseUrl(...)` pattern, timeouts from `ApiKeyProperties.requestTimeoutMs`, no auth interceptor
- [x] 2.2 Create `IntrospectionResult` record (`principal`, `rawRoles`, `fromProjectKey`) in `web.security.apikey`
- [x] 2.3 Create `CoreApiKeyIntrospector` (`@Component`, gated by the `enabled` `@ConditionalOnProperty`): `introspect(apiKey)` calls `GET {coreUrl}/v1/user/info` with header `Api-Key: <key>` via the injected RestClient, parses `project` vs `userClaims` shapes (principal for the `userClaims` shape via injected `JwtSecurityProperties.getUserClaim()`, roles via `ApiKeyProperties.userClaimsRoleClaim`), maps `RestClientResponseException` → `BadCredentialsException` and `ResourceAccessException` → `AuthenticationServiceException`
- [x] 2.4 Add `@PostConstruct probeCore()` gated by `startupProbe`: 4xx passes, unreachable/5xx fails boot
- [x] 2.5 Write `CoreApiKeyIntrospectorTest` covering: project-key shape; userClaims shape (scalar + list-valued claims); project-shape precedence when both present; malformed/neither-shape body → `BadCredentialsException`; non-2xx → `BadCredentialsException`; unreachable → `AuthenticationServiceException`; missing `roles` field tolerated as empty list; startup probe 4xx-passes/unreachable-or-5xx-fails

## 3. Role mapping and caching

- [x] 3.1 Create `ApiKeyAuthorityResolver` (`@Component`): builds two independent authority maps from `ApiKeyProperties.rolesMapping`/`defaultRolesMapping`; `resolve(List<String> rawRoles, boolean fromProjectKey)` maps unmapped names to nothing (dropped, not rejected)
- [x] 3.2 Write `ApiKeyAuthorityResolverTest`: project-key roles resolve via `rolesMapping`; JWT-rooted roles resolve via `defaultRolesMapping`; no cross-over between the two; unmapped/empty role lists → empty authorities
- [x] 3.3 Create `ApiKeyCache` (`@Component`): Caffeine `Cache<String, Authentication>` (`expireAfterWrite`/`maximumSize` from properties); cache key = `MessageDigest.getInstance("SHA-256")` hex digest via `HexFormat.of()` (JDK-only, no new dependency); `getOrAuthenticate(apiKey, Supplier<Authentication>)` via `Cache.get(key, mappingFunction)`
- [x] 3.4 Write `ApiKeyCacheTest`: same key hits cache (loader invoked once); distinct keys get distinct entries; a thrown exception from the loader is not cached (next call re-invokes loader)
- [x] 3.5 Run `./gradlew test --tests "com.epam.aidial.evaluation.web.security.apikey.ApiKeyAuthorityResolverTest" --tests "com.epam.aidial.evaluation.web.security.apikey.ApiKeyCacheTest"` and confirm both pass

## 4. Authentication filter

- [x] 4.1 Create `ApiKeyAuthenticationToken extends AbstractAuthenticationToken` (principal = resolved identity string)
- [x] 4.2 Create `ApiKeyAuthenticationFilter extends OncePerRequestFilter` (`@Component`, gated by the `enabled` `@ConditionalOnProperty`): skip-and-continue when `Authorization` non-blank or `Api-Key` blank; otherwise `cache.getOrAuthenticate(...)`, populate `SecurityContextHolder`; empty resolved authorities → log warning + `setAuthenticated(false)` rather than throw
- [x] 4.3 Map `BadCredentialsException` → `401` and `AuthenticationServiceException` → `503`, both written via the existing `com.epam.aidial.evaluation.web.handler.ErrorView` (`ErrorCode.AUTHENTICATION_REQUIRED` / `ErrorCode.UPSTREAM_AUTH_ERROR`), serialized with the injected `ObjectMapper`, clearing `SecurityContextHolder` on both paths
- [x] 4.4 Write `ApiKeyAuthenticationFilterTest`: `Authorization` present → pass-through, no Core call; blank/absent `Api-Key` → pass-through; successful project-key and JWT-rooted flows populate `SecurityContextHolder`; cache hit avoids a second introspector call; `BadCredentialsException` → 401 `ErrorView` body + cleared context; `AuthenticationServiceException` → 503 `ErrorView` body + cleared context; empty resolved authorities → token present but `isAuthenticated() == false`
- [x] 4.5 Run `./gradlew test --tests "com.epam.aidial.evaluation.web.security.apikey.ApiKeyAuthenticationFilterTest"` and confirm it passes

## 5. Wiring into the security chain

- [x] 5.1 Update `SecurityConfiguration` (oidc mode): inject `ObjectProvider<ApiKeyAuthenticationFilter>`, add `apiKeyFilter.ifAvailable(f -> http.addFilterBefore(f, BearerTokenAuthenticationFilter.class));`
- [x] 5.2 Add `@Bean @ConditionalOnBean(ApiKeyAuthenticationFilter.class) FilterRegistrationBean<ApiKeyAuthenticationFilter>` in `SecurityConfiguration` with `setEnabled(false)`, to stop Spring Boot auto-registering the filter as a raw servlet filter
- [x] 5.3 Confirm `NoSecurityConfiguration` (mode=none) is untouched — the api-key filter must never be wired there
- [x] 5.4 Add a functional test booting the full context with `config.rest.security.mode=oidc` and `config.rest.security.api-key.enabled=true` (stub or mock DIAL Core) verifying: valid key → 200/expected role-gated response; invalid key → 401; Core unreachable → 503; request with `Authorization` header ignores `Api-Key`
- [x] 5.5 Run `./gradlew test` (full suite) and `./gradlew checkstyleMain checkstyleTest` and confirm both pass

## 6. Wrap-up

- [x] 6.1 Run `./gradlew spotlessApply` then `./gradlew build` (full build: compile, checkstyle, spotless-check, tests) and confirm it's green
- [x] 6.2 Update `openspec/specs/security/spec.md` main spec via delta sync (or `openspec-sync-specs`) so the new requirements land as **Implemented** with implementation-notes entries pointing at the new classes
- [x] 6.3 Check `openspec/config.yaml`'s Config Maintenance Policy — this change follows existing patterns (new `@ConfigurationProperties` class, new filter following the existing `SecurityConfiguration` wiring pattern), so `config.yaml` should NOT need updating; confirmed and skipped
- [x] 6.4 Check `openspec/specs/README.md` per its Spec Index Maintenance Policy — the `security` spec's one-line summary may need a small update to mention API-Key auth; updated (was materially inaccurate — omitted the new capability entirely)
- [x] 6.5 Update `AGENTS.md` only if this introduced a new project-wide convention not already covered (e.g., a new package under `client.*`) — otherwise skip, per AGENTS.md's own maintenance rule; added `.client.apikey` and `.web.security.apikey` rows to the Key Packages Reference table
