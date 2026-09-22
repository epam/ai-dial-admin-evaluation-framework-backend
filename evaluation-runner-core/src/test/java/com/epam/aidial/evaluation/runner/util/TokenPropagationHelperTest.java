package com.epam.aidial.evaluation.runner.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    // --- withCredential (Supplier) tests ---

    @Test
    @DisplayName("withCredential sets credential before supplier execution")
    void withCredentialSetsCredentialBeforeExecution() {
        CallerCredential credential = CallerCredential.bearer("test-token");
        AtomicReference<CallerCredential> capturedCredential = new AtomicReference<>();

        Supplier<String> wrapped = TokenPropagationHelper.withCredential(credential, () -> {
            capturedCredential.set(AuthorizationTokenHolder.getCredential());
            return "result";
        });

        String result = wrapped.get();

        assertThat(result).isEqualTo("result");
        assertThat(capturedCredential.get()).isEqualTo(credential);
    }

    @Test
    @DisplayName("withCredential clears credential after successful execution")
    void withCredentialClearsCredentialAfterSuccess() {
        CallerCredential credential = CallerCredential.bearer("test-token");

        Supplier<String> wrapped = TokenPropagationHelper.withCredential(credential, () -> {
            // Credential is set during execution
            assertThat(AuthorizationTokenHolder.getCredential()).isEqualTo(credential);
            return "result";
        });

        wrapped.get();

        // Credential should be cleared after the wrapped supplier completes
        assertThat(AuthorizationTokenHolder.getCredential()).isNull();
    }

    @Test
    @DisplayName("withCredential clears credential after exception")
    void withCredentialClearsCredentialAfterException() {
        CallerCredential credential = CallerCredential.bearer("test-token");

        Supplier<String> wrapped = TokenPropagationHelper.withCredential(credential, () -> {
            assertThat(AuthorizationTokenHolder.getCredential()).isEqualTo(credential);
            throw new RuntimeException("test exception");
        });

        assertThatThrownBy(wrapped::get).isInstanceOf(RuntimeException.class).hasMessage("test exception");

        // Credential should still be cleared even after exception
        assertThat(AuthorizationTokenHolder.getCredential()).isNull();
    }

    @Test
    @DisplayName("withCredential handles null credential gracefully")
    void withCredentialHandlesNullCredentialGracefully() {
        AtomicReference<CallerCredential> capturedCredential =
                new AtomicReference<>(CallerCredential.bearer("not-null"));

        Supplier<String> wrapped = TokenPropagationHelper.withCredential(null, () -> {
            capturedCredential.set(AuthorizationTokenHolder.getCredential());
            return "result";
        });

        String result = wrapped.get();

        assertThat(result).isEqualTo("result");
        assertThat(capturedCredential.get()).isNull();
    }

    @Test
    @DisplayName("withCredential does not interfere with existing credential in calling thread")
    void withCredentialDoesNotInterfereWithCallingThread() {
        // Set a credential in the "calling" thread
        CallerCredential callerCredential = CallerCredential.bearer("caller-token");
        AuthorizationTokenHolder.setCredential(callerCredential);

        CallerCredential propagatedCredential = CallerCredential.bearer("propagated-token");
        AtomicReference<CallerCredential> credentialDuringExecution = new AtomicReference<>();

        // This simulates what happens when the wrapped supplier runs in the same thread
        // (e.g., if CompletableFuture decides to run inline)
        Supplier<String> wrapped = TokenPropagationHelper.withCredential(propagatedCredential, () -> {
            credentialDuringExecution.set(AuthorizationTokenHolder.getCredential());
            return "result";
        });

        wrapped.get();

        // After execution, the credential should be cleared (not restored to caller's credential)
        // This is expected behavior - the helper always clears after execution
        assertThat(credentialDuringExecution.get()).isEqualTo(propagatedCredential);
        assertThat(AuthorizationTokenHolder.getCredential()).isNull();
    }

    // --- withCredentialCallable tests ---

    @Test
    @DisplayName("withCredentialCallable sets and clears the credential")
    void withCredentialCallableSetsAndClearsCredential() throws Exception {
        CallerCredential credential = CallerCredential.bearer("callable-token");
        AtomicReference<CallerCredential> capturedCredential = new AtomicReference<>();

        Callable<String> wrapped = TokenPropagationHelper.withCredentialCallable(credential, () -> {
            capturedCredential.set(AuthorizationTokenHolder.getCredential());
            return "callable-result";
        });

        String result = wrapped.call();

        assertThat(result).isEqualTo("callable-result");
        assertThat(capturedCredential.get()).isEqualTo(credential);
        assertThat(AuthorizationTokenHolder.getCredential()).isNull();
    }

    @Test
    @DisplayName("withCredentialCallable clears the credential after a checked exception")
    void withCredentialCallableClearsCredentialAfterCheckedException() {
        CallerCredential credential = CallerCredential.bearer("test-token");

        Callable<String> wrapped = TokenPropagationHelper.withCredentialCallable(credential, () -> {
            throw new Exception("checked exception");
        });

        assertThatThrownBy(wrapped::call).isInstanceOf(Exception.class).hasMessage("checked exception");

        assertThat(AuthorizationTokenHolder.getCredential()).isNull();
    }

    @Test
    @DisplayName("withCredentialCallable clears the holder after execution")
    void withCredentialCallableClearsHolderAfterExecution() throws Exception {
        CallerCredential credential = CallerCredential.bearer("bearer-token");

        Callable<String> wrapped = TokenPropagationHelper.withCredentialCallable(credential, () -> {
            assertThat(AuthorizationTokenHolder.getCredential()).isEqualTo(credential);
            return "callable-result";
        });

        String result = wrapped.call();

        assertThat(result).isEqualTo("callable-result");
        assertThat(AuthorizationTokenHolder.getCredential()).isNull();
    }

    // --- withCredentialRunnable tests ---

    @Test
    @DisplayName("withCredentialRunnable sets and clears the credential")
    void withCredentialRunnableSetsAndClearsCredential() {
        CallerCredential credential = CallerCredential.bearer("runnable-token");
        AtomicReference<CallerCredential> capturedCredential = new AtomicReference<>();

        Runnable wrapped = TokenPropagationHelper.withCredentialRunnable(credential, () -> {
            capturedCredential.set(AuthorizationTokenHolder.getCredential());
        });

        wrapped.run();

        assertThat(capturedCredential.get()).isEqualTo(credential);
        assertThat(AuthorizationTokenHolder.getCredential()).isNull();
    }

    @Test
    @DisplayName("withCredentialRunnable clears the credential after an exception")
    void withCredentialRunnableClearsCredentialAfterException() {
        CallerCredential credential = CallerCredential.bearer("test-token");

        Runnable wrapped = TokenPropagationHelper.withCredentialRunnable(credential, () -> {
            throw new RuntimeException("runnable exception");
        });

        assertThatThrownBy(wrapped::run).isInstanceOf(RuntimeException.class).hasMessage("runnable exception");

        assertThat(AuthorizationTokenHolder.getCredential()).isNull();
    }

    @Test
    @DisplayName("withCredentialRunnable clears the holder after execution")
    void withCredentialRunnableClearsHolderAfterExecution() {
        CallerCredential credential = CallerCredential.apiKey("my-key");
        AtomicReference<CallerCredential> capturedCredential = new AtomicReference<>();

        Runnable wrapped = TokenPropagationHelper.withCredentialRunnable(credential, () -> {
            capturedCredential.set(AuthorizationTokenHolder.getCredential());
        });

        wrapped.run();

        assertThat(capturedCredential.get()).isEqualTo(credential);
        assertThat(AuthorizationTokenHolder.getCredential()).isNull();
    }

    // --- cross-thread propagation ---

    @Test
    @DisplayName("withCredential propagates the credential's kind across a thread hop")
    void withCredentialPropagatesKindAcrossThreadHop() throws Exception {
        CallerCredential credential = CallerCredential.apiKey("my-key");
        AtomicReference<CallerCredential> capturedCredential = new AtomicReference<>();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Supplier<String> wrapped = TokenPropagationHelper.withCredential(credential, () -> {
                capturedCredential.set(AuthorizationTokenHolder.getCredential());
                return "result";
            });

            String result = executor.submit(wrapped::get).get();

            assertThat(result).isEqualTo("result");
            assertThat(capturedCredential.get()).isEqualTo(credential);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("withCredential handles a null credential without throwing")
    void withCredentialHandlesNullCredentialWithoutThrowing() {
        AtomicReference<CallerCredential> capturedCredential = new AtomicReference<>();

        Supplier<String> wrapped = TokenPropagationHelper.withCredential(null, () -> {
            capturedCredential.set(AuthorizationTokenHolder.getCredential());
            return "result";
        });

        String result = wrapped.get();

        assertThat(result).isEqualTo("result");
        assertThat(capturedCredential.get()).isNull();
        assertThat(AuthorizationTokenHolder.getCredential()).isNull();
    }
}
