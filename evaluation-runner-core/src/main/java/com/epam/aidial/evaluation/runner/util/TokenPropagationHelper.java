package com.epam.aidial.evaluation.runner.util;

import java.util.concurrent.Callable;
import java.util.function.Supplier;
import lombok.experimental.UtilityClass;

/**
 * Utility for propagating the caller's credential (bearer token or API key) to new threads.
 *
 * <p>When executing code asynchronously (e.g., via {@code CompletableFuture.supplyAsync()}),
 * the new thread does not have access to the request thread's ThreadLocal credential stored in
 * {@link AuthorizationTokenHolder}. Use this helper to capture and propagate it.</p>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * // Capture the credential before spawning async tasks
 * CallerCredential credential = AuthorizationTokenHolder.getCredential();
 *
 * CompletableFuture.supplyAsync(TokenPropagationHelper.withCredential(credential, () -> {
 *     // Credential is now available in this thread via AuthorizationTokenHolder.getCredential()
 *     return dialCoreClient.getModels();
 * }));
 * }</pre>
 *
 * <p><b>Important:</b> Always capture the credential in the request thread before creating async
 * tasks, then pass it to this helper. The helper ensures proper cleanup after execution.</p>
 *
 * <p>Propagates the full {@link CallerCredential} (value + {@link CredentialKind}), so a bearer
 * token can never be re-sent as an API key (or vice versa) on the new thread.</p>
 */
@UtilityClass
public class TokenPropagationHelper {

    /**
     * Wraps a {@link Supplier} to propagate the caller credential to the executing thread.
     * Sets the credential before execution and clears it after completion (success or failure).
     * A {@code null} credential is a no-op set but the holder is still cleared in {@code finally}.
     *
     * @param credential the credential to propagate (may be null)
     * @param supplier   the supplier to wrap
     * @param <T>        the return type
     * @return a wrapped supplier that propagates the credential
     */
    public static <T> Supplier<T> withCredential(CallerCredential credential, Supplier<T> supplier) {
        return () -> {
            try {
                if (credential != null) {
                    AuthorizationTokenHolder.setCredential(credential);
                }
                return supplier.get();
            } finally {
                AuthorizationTokenHolder.clearToken();
            }
        };
    }

    /**
     * Wraps a {@link Callable} to propagate the caller credential to the executing thread.
     * Sets the credential before execution and clears it after completion (success or failure).
     * A {@code null} credential is a no-op set but the holder is still cleared in {@code finally}.
     *
     * @param credential the credential to propagate (may be null)
     * @param callable   the callable to wrap
     * @param <T>        the return type
     * @return a wrapped callable that propagates the credential
     */
    public static <T> Callable<T> withCredentialCallable(CallerCredential credential, Callable<T> callable) {
        return () -> {
            try {
                if (credential != null) {
                    AuthorizationTokenHolder.setCredential(credential);
                }
                return callable.call();
            } finally {
                AuthorizationTokenHolder.clearToken();
            }
        };
    }

    /**
     * Wraps a {@link Runnable} to propagate the caller credential to the executing thread.
     * Sets the credential before execution and clears it after completion (success or failure).
     * A {@code null} credential is a no-op set but the holder is still cleared in {@code finally}.
     *
     * @param credential the credential to propagate (may be null)
     * @param runnable   the runnable to wrap
     * @return a wrapped runnable that propagates the credential
     */
    public static Runnable withCredentialRunnable(CallerCredential credential, Runnable runnable) {
        return () -> {
            try {
                if (credential != null) {
                    AuthorizationTokenHolder.setCredential(credential);
                }
                runnable.run();
            } finally {
                AuthorizationTokenHolder.clearToken();
            }
        };
    }
}
