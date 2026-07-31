package com.epam.aidial.evaluation.runner.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TokenPropagationHelper")
class TokenPropagationHelperTest {

    @AfterEach
    void tearDown() {
        AuthorizationTokenHolder.clearToken();
    }

    // --- withToken (Supplier) tests ---

    @Test
    @DisplayName("withToken sets token before supplier execution")
    void withTokenSetsTokenBeforeExecution() {
        String token = "test-token";
        AtomicReference<String> capturedToken = new AtomicReference<>();

        Supplier<String> wrapped = TokenPropagationHelper.withToken(token, () -> {
            capturedToken.set(AuthorizationTokenHolder.getToken());
            return "result";
        });

        String result = wrapped.get();

        assertThat(result).isEqualTo("result");
        assertThat(capturedToken.get()).isEqualTo(token);
    }

    @Test
    @DisplayName("withToken clears token after successful execution")
    void withTokenClearsTokenAfterSuccess() {
        String token = "test-token";

        Supplier<String> wrapped = TokenPropagationHelper.withToken(token, () -> {
            // Token is set during execution
            assertThat(AuthorizationTokenHolder.getToken()).isEqualTo(token);
            return "result";
        });

        wrapped.get();

        // Token should be cleared after the wrapped supplier completes
        assertThat(AuthorizationTokenHolder.getToken()).isNull();
    }

    @Test
    @DisplayName("withToken clears token after exception")
    void withTokenClearsTokenAfterException() {
        String token = "test-token";

        Supplier<String> wrapped = TokenPropagationHelper.withToken(token, () -> {
            assertThat(AuthorizationTokenHolder.getToken()).isEqualTo(token);
            throw new RuntimeException("test exception");
        });

        assertThatThrownBy(wrapped::get).isInstanceOf(RuntimeException.class).hasMessage("test exception");

        // Token should still be cleared even after exception
        assertThat(AuthorizationTokenHolder.getToken()).isNull();
    }

    @Test
    @DisplayName("withToken handles null token gracefully")
    void withTokenHandlesNullToken() {
        AtomicReference<String> capturedToken = new AtomicReference<>("not-null");

        Supplier<String> wrapped = TokenPropagationHelper.withToken(null, () -> {
            capturedToken.set(AuthorizationTokenHolder.getToken());
            return "result";
        });

        String result = wrapped.get();

        assertThat(result).isEqualTo("result");
        assertThat(capturedToken.get()).isNull();
    }

    @Test
    @DisplayName("withToken does not interfere with existing token in calling thread")
    void withTokenDoesNotInterfereWithCallingThread() {
        // Set a token in the "calling" thread
        String callerToken = "caller-token";
        AuthorizationTokenHolder.setToken(callerToken);

        String propagatedToken = "propagated-token";
        AtomicReference<String> tokenDuringExecution = new AtomicReference<>();

        // This simulates what happens when the wrapped supplier runs in the same thread
        // (e.g., if CompletableFuture decides to run inline)
        Supplier<String> wrapped = TokenPropagationHelper.withToken(propagatedToken, () -> {
            tokenDuringExecution.set(AuthorizationTokenHolder.getToken());
            return "result";
        });

        wrapped.get();

        // After execution, the token should be cleared (not restored to caller's token)
        // This is expected behavior - the helper always clears after execution
        assertThat(tokenDuringExecution.get()).isEqualTo(propagatedToken);
        assertThat(AuthorizationTokenHolder.getToken()).isNull();
    }

    // --- withTokenCallable tests ---

    @Test
    @DisplayName("withTokenCallable sets and clears token")
    void withTokenCallableSetsAndClearsToken() throws Exception {
        String token = "callable-token";
        AtomicReference<String> capturedToken = new AtomicReference<>();

        Callable<String> wrapped = TokenPropagationHelper.withTokenCallable(token, () -> {
            capturedToken.set(AuthorizationTokenHolder.getToken());
            return "callable-result";
        });

        String result = wrapped.call();

        assertThat(result).isEqualTo("callable-result");
        assertThat(capturedToken.get()).isEqualTo(token);
        assertThat(AuthorizationTokenHolder.getToken()).isNull();
    }

    @Test
    @DisplayName("withTokenCallable clears token after checked exception")
    void withTokenCallableClearsTokenAfterCheckedException() {
        String token = "test-token";

        Callable<String> wrapped = TokenPropagationHelper.withTokenCallable(token, () -> {
            throw new Exception("checked exception");
        });

        assertThatThrownBy(wrapped::call).isInstanceOf(Exception.class).hasMessage("checked exception");

        assertThat(AuthorizationTokenHolder.getToken()).isNull();
    }

    // --- withTokenRunnable tests ---

    @Test
    @DisplayName("withTokenRunnable sets and clears token")
    void withTokenRunnableSetsAndClearsToken() {
        String token = "runnable-token";
        AtomicReference<String> capturedToken = new AtomicReference<>();

        Runnable wrapped = TokenPropagationHelper.withTokenRunnable(token, () -> {
            capturedToken.set(AuthorizationTokenHolder.getToken());
        });

        wrapped.run();

        assertThat(capturedToken.get()).isEqualTo(token);
        assertThat(AuthorizationTokenHolder.getToken()).isNull();
    }

    @Test
    @DisplayName("withTokenRunnable clears token after exception")
    void withTokenRunnableClearsTokenAfterException() {
        String token = "test-token";

        Runnable wrapped = TokenPropagationHelper.withTokenRunnable(token, () -> {
            throw new RuntimeException("runnable exception");
        });

        assertThatThrownBy(wrapped::run).isInstanceOf(RuntimeException.class).hasMessage("runnable exception");

        assertThat(AuthorizationTokenHolder.getToken()).isNull();
    }
}
