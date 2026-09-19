# TokenPropagationHelper for async operations

When spawning async tasks (e.g., `CompletableFuture.supplyAsync()`) that need user context, use `TokenPropagationHelper` to propagate the caller's credential to the new thread. ThreadLocal variables don't propagate automatically to pooled threads.

## Credential + kind

The caller's credential is a `CallerCredential(String value, CredentialKind kind)` (`runner.util`, `kind` is `BEARER` or `API_KEY`), built via `CallerCredential.bearer(value)` / `apiKey(value)` — both return `null` for a `null`/blank value rather than throwing, so a missing header never fails a request. `AuthorizationTokenHolder` keeps a single `ThreadLocal<CallerCredential>`: `setCredential`/`getCredential`/`clearToken`. Any consumer that forms an outbound header reads `getCredential()` and uses its `headerName()`/`headerValue()`, so a bearer token can never be emitted as `Api-Key` (or vice versa). `eval-cli`'s `TargetDialCoreClientConfiguration.apiKeyInterceptor()` reads `getCredential()` and emits `Api-Key` only when the credential's kind is `API_KEY`; any other kind (or no credential) sends no header.

## Capture precedence

`AuthorizationHeaderInterceptor` captures the credential from the inbound request, mirroring `ApiKeyAuthenticationFilter`'s own precedence check exactly: a non-blank `Authorization` header starting with `Bearer ` wins and is captured as `BEARER`; the `Api-Key` header is captured as `API_KEY` only when `Authorization` is blank (not merely non-`Bearer` — e.g. `Authorization: Basic …` plus `Api-Key` captures nothing, since that request isn't api-key-authenticated either); otherwise the holder is cleared. As an `AsyncHandlerInterceptor`, it clears in both `afterCompletion` and `afterConcurrentHandlingStarted` — the latter because an async dispatch hands the request thread back to the pool before `afterCompletion` runs, so without it a credential could outlive the request on that thread.

## Propagating across threads

```java
CallerCredential credential = AuthorizationTokenHolder.getCredential(); // capture BEFORE async
CompletableFuture.supplyAsync(TokenPropagationHelper.withCredential(credential, () -> dialCoreClient.getModels()));
```

Variants: `withCredential(Supplier)`, `withCredentialCallable(Callable)`, `withCredentialRunnable(Runnable)`. A `null` credential is a no-op set but the holder is still cleared in `finally`.

## Outbound rule: exactly one header per call

Every caller-scoped outbound call sends exactly one header, chosen from the credential's kind, never both and never a default: `DialCoreClientConfiguration#callerCredentialInterceptor()` (shared by `dialCoreRestClient`, `dialCoreTryOutRestClient`, `dialAdasRestClient`) sets `Authorization: Bearer` for `BEARER`, `Api-Key` for `API_KEY`, or nothing when the credential is absent. `McpToolInvoker#callTool`/`#listTools` take a `CallerCredential` parameter and apply the same rule via a shared `applyCredentialHeader` helper. A run carries its credential on `EvaluationContext#credential`, re-established on each worker thread the same way.
