package com.epam.aidial.evaluation.runner.util;

import java.util.concurrent.Callable;
import java.util.function.Supplier;
import lombok.experimental.UtilityClass;

/**
 * Utility for propagating the user's authorization token to new threads.
 *
 * <p>When executing code asynchronously (e.g., via {@code CompletableFuture.supplyAsync()}),
 * the new thread does not have access to the request thread's ThreadLocal token stored in
 * {@link AuthorizationTokenHolder}. Use this helper to capture and propagate the token.</p>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * // Capture token before spawning async tasks
 * String token = AuthorizationTokenHolder.getToken();
 *
 * CompletableFuture.supplyAsync(TokenPropagationHelper.withToken(token, () -> {
 *     // Token is now available in this thread via AuthorizationTokenHolder.getToken()
 *     return dialCoreClient.getModels();
 * }));
 * }</pre>
 *
 * <p><b>Important:</b> Always capture the token in the request thread before creating async tasks,
 * then pass it to this helper. The helper ensures proper cleanup after execution.</p>
 */
@UtilityClass
public class TokenPropagationHelper {

    /**
     * Wraps a {@link Supplier} to propagate the authorization token to the executing thread.
     * Sets the token before execution and clears it after completion (success or failure).
     *
     * @param token    the token to propagate (may be null)
     * @param supplier the supplier to wrap
     * @param <T>      the return type
     * @return a wrapped supplier that propagates the token
     */
    public static <T> Supplier<T> withToken(String token, Supplier<T> supplier) {
        return () -> {
            try {
                if (token != null) {
                    AuthorizationTokenHolder.setToken(token);
                }
                return supplier.get();
            } finally {
                AuthorizationTokenHolder.clearToken();
            }
        };
    }

    /**
     * Wraps a {@link Callable} to propagate the authorization token to the executing thread.
     * Sets the token before execution and clears it after completion (success or failure).
     *
     * @param token    the token to propagate (may be null)
     * @param callable the callable to wrap
     * @param <T>      the return type
     * @return a wrapped callable that propagates the token
     */
    public static <T> Callable<T> withTokenCallable(String token, Callable<T> callable) {
        return () -> {
            try {
                if (token != null) {
                    AuthorizationTokenHolder.setToken(token);
                }
                return callable.call();
            } finally {
                AuthorizationTokenHolder.clearToken();
            }
        };
    }

    /**
     * Wraps a {@link Runnable} to propagate the authorization token to the executing thread.
     * Sets the token before execution and clears it after completion (success or failure).
     *
     * @param token    the token to propagate (may be null)
     * @param runnable the runnable to wrap
     * @return a wrapped runnable that propagates the token
     */
    public static Runnable withTokenRunnable(String token, Runnable runnable) {
        return () -> {
            try {
                if (token != null) {
                    AuthorizationTokenHolder.setToken(token);
                }
                runnable.run();
            } finally {
                AuthorizationTokenHolder.clearToken();
            }
        };
    }
}
