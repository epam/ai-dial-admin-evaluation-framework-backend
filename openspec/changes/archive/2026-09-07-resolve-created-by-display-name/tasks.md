## 1. DIAL Core client

- [x] 1.1 Add `DialCoreClient#getUserInfo()` in `client.dialcore` — `GET /v1/user/info` via `dialCoreRestClient`, body read as `String` and parsed to `JsonNode`, wrapped in `withRetry`, parse failure → `DialCoreClientException(502)` (done: `DialCoreClientTest` `getUserInfo*` cases pass — octet-stream body parsed, empty body → `null`, 401 → `DialCoreClientException`)

## 2. Configuration

- [x] 2.1 Add `resolveUserName` to `configuration.properties.security.JwtSecurityProperties` and default `security.jwt.resolve-user-name: ${SECURITY_JWT_RESOLVE_USER_NAME:false}` in `application.yml` (done: property binds; no Java field initializer)
- [x] 2.2 Add the `security.jwt.resolve-user-name` row to `docs/configuration.md` §3.3 with all six columns and the snapshot/fallback behaviour note (done: row present, `Required = No`, `Applied when = config.rest.security.mode=oidc`)

## 3. Author resolution

- [x] 3.1 Extend `service.domain.AuthorResolver#getCreatedBy(Jwt)` — keep `anonymous` short-circuits for null JWT / missing claim before any Core call; flag off → claim; flag on → `userDisplayName` from `DialCoreClient#getUserInfo()`, falling back to the claim on missing/blank/non-string name, empty body, `DialCoreClientException`, or `RestClientException`; warn with exception for errors, debug for missing name (done: `AuthorResolverTest` 9 cases pass covering every branch, including "Core never called" verifications)
- [x] 3.2 Confirm existing consumers compile and behave unchanged with the flag off (done: `TestSuiteServiceTest`, `DatasetServiceTest`, `TestSuiteCloneServiceTest`, `TestSuiteServiceResponseColumnsChangedTest` pass)

## 4. Verification

- [x] 4.1 Boot the application context with the new `AuthorResolver` constructor dependency and verify default-off attribution end-to-end (done: `PostgresFunctionalTests$TestSuiteTests` 49/49 and `$DatasetCrudTests` 14/14 pass)
- [x] 4.2 Static checks (done: `./gradlew spotlessApply`, `:checkstyleMain`, `:checkstyleTest`, `architectural.*` — layering + logging convention — pass)
- [x] 4.3 Remove the throwaway diagnostic pass-through (`UserInfoController`, `UserInfoDiagnosticsService`, test) used to explore Core's response shape (done: no references remain in `src/` or `docs/`)

## 5. Documentation and specs

- [x] 5.1 Update the `AuthorResolver` bullet in AGENTS.md "Inline conventions" to mention the `security.jwt.resolve-user-name` opt-in and its fallback (done: bullet reflects both modes)
- [x] 5.2 At archive, sync delta specs into `openspec/specs/security/spec.md` and `openspec/specs/dial-core-client/spec.md` via `/opsx:sync` (done: main specs gained the new requirements; `git diff` shows no lost content)
