package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.service.domain.QuietJsonService;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.LinkedMultiValueMap;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guards the rate-limiter accounting contract: one token per <b>outgoing HTTP call</b>, not one per dispatched
 * test-case run. Before this was brought into conformance, a token was consumed once per dispatch, so a test
 * case emitting N calls — multi-turn turns, multi-request chain requests, or retries — put N times the
 * configured RPS on the deployment. Sequentiality within a turn loop or a chain changes the burst shape, not
 * the mean, because chains overlap up to {@code concurrencyLevel}.
 *
 * <p>These tests count acquisitions at {@link DeploymentTurnInvoker}, the single shared gate that both the
 * multi-turn turn loop and the multi-request chain path issue their calls through.
 */
@DisplayName("Rate limit token accounting")
class RateLimitTokenAccountingTest {

    private final AtomicInteger acquisitions = new AtomicInteger();
    private RunRateLimiter limiter;
    private DialCoreDeploymentInvoker deploymentInvoker;
    private DeploymentTurnInvoker turnInvoker;

    @BeforeEach
    void setUp() throws InterruptedException {
        limiter = mock(RunRateLimiter.class);
        // Counts the gate call sites actually use. tryAcquire() is the production entry point — it owns the
        // interrupt policy — so stubbing acquire() alone would leave every call site unmetered.
        doAnswer(invocation -> {
                    acquisitions.incrementAndGet();
                    return true;
                })
                .when(limiter)
                .tryAcquire();
        deploymentInvoker = mock(DialCoreDeploymentInvoker.class);
        turnInvoker = new DeploymentTurnInvoker(
                deploymentInvoker, new QuietJsonService(JsonMapper.builder().build()));
    }

    @Test
    @DisplayName("a multi-turn case of N turns consumes N tokens, closing the pre-existing N-times overshoot")
    void multiTurnConsumesOneTokenPerTurn() throws Exception {
        stubStatus(200);
        EvaluationContext context = context(0);

        int turns = 3;
        for (int turn = 0; turn < turns; turn++) {
            turnInvoker.invoke(context, HttpMethod.POST, "/chat/completions", new HttpHeaders(), params(), null);
        }

        assertThat(acquisitions).hasValue(turns);
        verify(deploymentInvoker, times(turns)).invokeWithStreaming(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a chain of N requests consumes N tokens, since each chain step issues exactly one call")
    void chainConsumesOneTokenPerRequest() throws Exception {
        stubStatus(200);
        EvaluationContext context = context(0);

        int chainLength = 4;
        for (int request = 0; request < chainLength; request++) {
            turnInvoker.invoke(context, HttpMethod.POST, "/r" + request, new HttpHeaders(), params(), null);
        }

        assertThat(acquisitions).hasValue(chainLength);
        verify(deploymentInvoker, times(chainLength)).invokeWithStreaming(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("retries acquire tokens too — a retry is a real request and must not bypass the limiter")
    void retriesConsumeTokens() throws Exception {
        // Every attempt returns 500, which is retryable, so maxRetries=2 means three attempts in total.
        stubStatus(500);

        turnInvoker.invoke(context(2), HttpMethod.POST, "/p", new HttpHeaders(), params(), null);

        assertThat(acquisitions).hasValue(3);
        verify(deploymentInvoker, times(3)).invokeWithStreaming(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a single successful call consumes exactly one token")
    void singleCallConsumesOneToken() throws Exception {
        stubStatus(200);

        turnInvoker.invoke(context(2), HttpMethod.POST, "/p", new HttpHeaders(), params(), null);

        assertThat(acquisitions).hasValue(1);
    }

    @Test
    @DisplayName("an interrupted token wait issues no call and reports the turn as not issued")
    void interruptedWaitIssuesNoCall() throws Exception {
        // A REAL limiter, drained and then interrupted, so the shared interrupt policy in tryAcquire() is
        // what runs — a stub returning false would assert the call site's behaviour against a fiction.
        RunRateLimiter realLimiter = RunRateLimiter.of(1.0);
        realLimiter.acquire();
        Thread.currentThread().interrupt();

        TurnOutcome outcome;
        try {
            outcome = turnInvoker.invoke(
                    contextWith(realLimiter), HttpMethod.POST, "/p", new HttpHeaders(), params(), null);
        } finally {
            // Clear the flag so it cannot leak into later tests on this thread.
            Thread.interrupted();
        }

        verify(deploymentInvoker, never()).invokeWithStreaming(any(), any(), any(), any(), any());
        assertThat(outcome.issued())
                .as("a turn whose token wait was interrupted never sent anything, so it earns no result row")
                .isFalse();
        assertThat(outcome.responseBody())
                .as("the CANCELLED envelope keeps the interruption diagnosable if a row is ever written")
                .contains(DeploymentInvocationSupport.CANCELLED_ERROR_CODE);
    }

    @Test
    @DisplayName("a call that WAS issued reports issued=true, so the flag distinguishes it from a cancellation")
    void issuedCallReportsIssued() {
        stubStatus(200);

        TurnOutcome outcome = turnInvoker.invoke(context(0), HttpMethod.POST, "/p", new HttpHeaders(), params(), null);

        assertThat(outcome.issued()).isTrue();
    }

    private void stubStatus(int statusCode) {
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(invocation ->
                        new DeploymentInvocationResult(statusCode, false, Map.of("ok", true), null, new HttpHeaders()));
    }

    private EvaluationContext context(int maxRetries) {
        return contextWith(limiter, maxRetries);
    }

    private EvaluationContext contextWith(RunRateLimiter gate) {
        return contextWith(gate, 0);
    }

    private EvaluationContext contextWith(RunRateLimiter gate, int maxRetries) {
        return EvaluationContext.builder()
                .rateLimiter(gate)
                .maxRetries(maxRetries)
                .retryDelayMs(1)
                .retryBackoffMultiplier(1.0)
                .maxRetryDelayMs(1)
                .maxResponseSizeBytes(1_000_000)
                .cancellationSignal(new AtomicBoolean(false))
                .build();
    }

    private static LinkedMultiValueMap<String, String> params() {
        return new LinkedMultiValueMap<>();
    }
}
