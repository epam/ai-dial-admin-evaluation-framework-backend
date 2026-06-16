# TokenPropagationHelper for async operations

When spawning async tasks (e.g., `CompletableFuture.supplyAsync()`) that need user context, use `TokenPropagationHelper` to propagate the authorization token to the new thread. ThreadLocal variables don't propagate automatically to pooled threads.

```java
String token = AuthorizationTokenHolder.getToken(); // capture BEFORE async
CompletableFuture.supplyAsync(TokenPropagationHelper.withToken(token, () -> dialCoreClient.getModels()));
```

Variants: `withToken(Supplier)`, `withTokenCallable(Callable)`, `withTokenRunnable(Runnable)`. Cleans up token after execution.
