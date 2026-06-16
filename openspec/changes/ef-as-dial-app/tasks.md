## 1. Configuration and Properties

- [ ] 1.1 Create `DialAppProperties` (`@ConfigurationProperties(prefix = "ef.dial-app")`, `@Validated`) in `configuration.properties` with fields: `enabled` (boolean), `deploymentName` (String), `heartbeatIntervalMs` (long), `verifyPerRequestKey` (boolean)
- [ ] 1.2 Add defaults in `application.yml`: `ef.dial-app.enabled: false`, `ef.dial-app.deployment-name: ef-eval`, `ef.dial-app.heartbeat-interval-ms: 30000`, `ef.dial-app.verify-per-request-key: true`
- [ ] 1.3 Register `DialAppProperties` in `@EnableConfigurationProperties` (or `@ConfigurationPropertiesScan`)

## 2. Per-Request Key Store and Run ID Holder

- [ ] 2.1 Create `PerRequestKeyStore` `@Component` (`@ConditionalOnProperty(name = "ef.dial-app.enabled", havingValue = "true")`) in `service.domain` with `ConcurrentHashMap<UUID, String>` and `put(UUID runId, String prk)` / `get(UUID runId) → String` / `remove(UUID runId)` methods; annotate with `@LogExecution`
- [ ] 2.2 Create `RunIdHolder` `@UtilityClass` in `configuration.security` — `ThreadLocal<UUID>` with `setRunId(UUID)` / `getRunId() → UUID` / `clearRunId()` (mirrors `AuthorizationTokenHolder` pattern)
- [ ] 2.3 Set `RunIdHolder.setRunId(context.getRunId())` / `clearRunId()` in a try-finally block in `EvaluationWorker.execute()` around the test case invocation (so invoker can look up PRK by current runId on the same virtual thread)

## 3. DIAL User Info Client

- [ ] 3.1 Create `DialUserInfoClient` `@Component` (`@LogExecution`) in `client.dialcore` with `getUserInfo(String apiKey) → Optional<UserInfoDto>` — calls `GET {dial.url}/v1/user/info` with `Api-Key: <apiKey>` header; returns empty on non-2xx; use dedicated `RestClient` (reuse or configure alongside existing DIAL clients)
- [ ] 3.2 Create `UserInfoDto` record in `client.dialcore` with `Map<String, Object> userClaims` and `List<String> roles` fields (Jackson-deserialized from DIAL Core response)

## 4. DIAL Route Trigger Client

- [ ] 4.1 Create `DialRouteTriggerClient` `@Component` (`@ConditionalOnProperty(name = "ef.dial-app.enabled", havingValue = "true")`, `@LogExecution`) in `client.dialcore` with `triggerEvalRun(UUID runId, String jwt)` — calls `POST {dial.url}/v1/deployments/{deploymentName}/route/eval/runs/{runId}/execute` with `Authorization: <jwt>`, consumes SSE response stream until close, runs on virtual thread
- [ ] 4.2 Configure a dedicated `RestClient` for `DialRouteTriggerClient` with a long read timeout (e.g., configurable `ef.dial-app.trigger-read-timeout-ms: 43200000` — 12 hours) to accommodate long-running eval connections; do NOT share with `dialCoreTryOutRestClient`
- [ ] 4.3 Ensure `DialRouteTriggerClient.triggerEvalRun(...)` consumes the SSE stream in a loop (reads until EOF / stream close) and logs the runId when the stream terminates; catches `IOException` on stream read and logs at WARN (normal on connection close or eval cancellation)

## 5. Internal Eval Execute Endpoint and SSE Streaming

- [ ] 5.1 Create `EvalExecuteInternalController` `@RestController` (`@ConditionalOnProperty(name = "ef.dial-app.enabled", havingValue = "true")`, `@LogExecution`) in `web.controller` with `POST /internal/eval/runs/{runId}/execute`; extract `Api-Key` header (return 401 if missing); check for duplicate trigger via `PerRequestKeyStore` (return 409 if already present); call `DialUserInfoClient` for PRK validation (when `verifyPerRequestKey=true`, return 401 on failure); verify run exists (return 404 if not); store PRK; start async eval; return `ResponseEntity<StreamingResponseBody>` with `Content-Type: text/event-stream`
- [ ] 5.2 Implement the SSE `StreamingResponseBody` returned by the controller: write a heartbeat event (`data: {}\n\n`, flushed) every `heartbeatIntervalMs` milliseconds using a scheduled virtual thread; emit progress events (test case completion count, phase: EVALUATION / METRIC_EVALUATION) on the same stream; on eval terminal state write `data: [DONE]\n\n` and close; on `IOException` (client disconnected) cancel the eval and remove PRK from store
- [ ] 5.3 Wire async eval invocation inside the controller: call `TestSuiteEvaluationJob.execute(runId)` (or equivalent service method) on a virtual thread, passing the already-stored PRK context via `PerRequestKeyStore`; the SSE stream lifetime must encompass the entire eval execution

## 6. Deployment Invoker — PRK Support

- [ ] 6.1 Inject `Optional<PerRequestKeyStore>` (or `@Autowired(required = false)`) into `DialCoreDeploymentInvoker`
- [ ] 6.2 Modify the request-building section of `DialCoreDeploymentInvoker.invokeWithStreaming(...)` and `invoke(...)`: read `RunIdHolder.getRunId()` → if non-null and `perRequestKeyStore.get(runId)` is non-null → add `Api-Key: <prk>` header and omit `Authorization`; else fall back to existing `AuthorizationTokenHolder.getToken()` for `Authorization` header

## 7. Author Resolver — PRK Mode

- [ ] 7.1 Inject `Optional<PerRequestKeyStore>` and `Optional<DialUserInfoClient>` into `AuthorResolver`
- [ ] 7.2 Modify `AuthorResolver` resolution logic: if `PerRequestKeyStore` is present AND `PerRequestKeyStore.get(RunIdHolder.getRunId())` is non-null → call `DialUserInfoClient.getUserInfo(prk)` and extract `userClaims.get(userClaimName)` (configured via `security.jwt.user-claim`); fall back to existing JWT claim extraction if PRK unavailable; return `"anonymous"` if both fail

## 8. Spring Security — Internal Endpoint Exclusion

- [ ] 8.1 Add `requestMatchers("/internal/**").permitAll()` to the Spring Security HTTP configuration — placed BEFORE the catch-all authenticated matcher — so `/internal/eval/runs/*/execute` is reachable without a JWT (auth is handled by PRK validation in the controller)

## 9. Eval Dispatch Branching

- [ ] 9.1 Locate the eval dispatch site (run creation: where `TokenPropagationHelper.withToken(jwt, evalTask)` is called) and introduce a branch: when `ef.dial-app.enabled=true`, call `DialRouteTriggerClient.triggerEvalRun(runId, AuthorizationTokenHolder.getToken())` on a virtual thread instead of the `CompletableFuture` dispatch; when `false`, keep existing behavior unchanged

## 10. Tests

- [ ] 10.1 Unit test `PerRequestKeyStore`: put/get/remove correctness; concurrent put from multiple threads; get returns null for unknown runId
- [ ] 10.2 Unit test `DialRouteTriggerClient`: verify correct route URL construction using `deploymentName` config; verify `Authorization: <jwt>` header set; verify SSE stream consumed until EOF (mock server)
- [ ] 10.3 Unit test `EvalExecuteInternalController`: 401 when `Api-Key` header missing; 404 when runId not found; 409 when runId already in `PerRequestKeyStore`; 401 when PRK validation fails (mock `DialUserInfoClient` returning 401); 200 + SSE stream initiated on valid trigger
- [ ] 10.4 Unit test `DialCoreDeploymentInvoker` PRK path: when `PerRequestKeyStore` returns non-null PRK for current runId → `Api-Key` header set, `Authorization` absent; when no PRK → `Authorization` set, `Api-Key` absent
- [ ] 10.5 Unit test `AuthorResolver` in PRK mode: when PRK available → `DialUserInfoClient` called, claim extracted; when `/v1/user/info` returns no matching claim → `"anonymous"` returned; when PRK absent → JWT fallback used
- [ ] 10.6 Unit test `DialUserInfoClient`: `getUserInfo` returns `UserInfoDto` on 200 with matching JSON; returns empty `Optional` on 401/500

## 11. Documentation and Spec Updates

- [ ] 11.1 Update `docs/configuration.md`: add `ef.dial-app.*` properties table (enabled, deployment-name, heartbeat-interval-ms, verify-per-request-key, trigger-read-timeout-ms); add DIAL Core Application Route registration config block (JSON snippet); add note that `/internal/**` must not be exposed on public ingress
- [ ] 11.2 Update `openspec/specs/README.md` per Spec Index Maintenance Policy: add `dial-app-auth` entry (Status: Planned — DIAL Application Route integration for per-request key auth and file auto-sharing)
- [ ] 11.3 Update `AGENTS.md` per AGENTS.md Maintenance guidelines: add `PerRequestKeyStore` + `RunIdHolder` to Unique Patterns section (PRK-based auth pattern, dual-mode auth); add `client.dialcore.DialUserInfoClient` and `service.domain.PerRequestKeyStore` to Key Packages Reference
