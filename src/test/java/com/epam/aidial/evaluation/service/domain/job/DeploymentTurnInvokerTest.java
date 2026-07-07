package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeploymentTurnInvoker")
class DeploymentTurnInvokerTest {

    @Mock
    private DialCoreDeploymentInvoker deploymentInvoker;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DeploymentTurnInvoker invoker;

    private final MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();

    private DeploymentTurnInvoker invoker() {
        return new DeploymentTurnInvoker(deploymentInvoker, new JobJsonService(objectMapper));
    }

    private static EvaluationContext context(int maxRetries, long maxResponseSizeBytes) {
        return EvaluationContext.builder()
                .cancellationSignal(new AtomicBoolean(false))
                .maxRetries(maxRetries)
                .retryDelayMs(0)
                .retryBackoffMultiplier(1.0)
                .maxRetryDelayMs(0)
                .maxResponseSizeBytes(maxResponseSizeBytes)
                .build();
    }

    private static DeploymentInvocationResult result(int status, Object body, boolean streaming) {
        return new DeploymentInvocationResult(status, streaming, body, null, new HttpHeaders());
    }

    private StepOutcome invoke(EvaluationContext context) {
        invoker = invoker();
        return invoker.invoke(context, HttpMethod.POST, "/chat", new HttpHeaders(), queryParams, "body");
    }

    @Test
    @DisplayName("2xx returns SUCCESS with no retries")
    void successNoRetries() {
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(result(200, Map.of("ok", true), false));

        final StepOutcome outcome = invoke(context(0, 10_000L));

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(outcome.statusCode()).isEqualTo(200);
        assertThat(outcome.retryCount()).isZero();
        verify(deploymentInvoker, times(1)).invokeWithStreaming(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("429 then 2xx retries once and succeeds")
    void retriesOn429ThenSucceeds() {
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(result(429, Map.of("err", "rate"), false))
                .thenReturn(result(200, Map.of("ok", true), false));

        final StepOutcome outcome = invoke(context(1, 10_000L));

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(outcome.retryCount()).isEqualTo(1);
        verify(deploymentInvoker, times(2)).invokeWithStreaming(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("persistent 500 exhausts retries and returns FAILED")
    void persistent500ExhaustsToFailed() {
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(result(500, Map.of("err", "boom"), false));

        final StepOutcome outcome = invoke(context(2, 10_000L));

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(outcome.retryCount()).isEqualTo(2);
        verify(deploymentInvoker, times(3)).invokeWithStreaming(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("timeout exception maps to TIMEOUT and is retried")
    void timeoutExceptionRetried() {
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("wrap", new SocketTimeoutException("t")));

        final StepOutcome outcome = invoke(context(1, 10_000L));

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.TIMEOUT);
        assertThat(outcome.retryCount()).isEqualTo(1);
        verify(deploymentInvoker, times(2)).invokeWithStreaming(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("streaming response is rejected as ERROR")
    void streamingRejected() {
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(result(200, null, true));

        final StepOutcome outcome = invoke(context(0, 10_000L));

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.ERROR);
        verify(deploymentInvoker, times(1)).invokeWithStreaming(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("oversize response body is rejected as ERROR")
    void oversizeBodyRejected() {
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(result(200, Map.of("data", "x".repeat(100)), false));

        final StepOutcome outcome = invoke(context(0, 10L));

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.ERROR);
    }

    @Test
    @DisplayName("401 maps to ERROR and is not retried")
    void unauthorizedNotRetried() {
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(result(401, Map.of("err", "auth"), false));

        final StepOutcome outcome = invoke(context(2, 10_000L));

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(outcome.retryCount()).isZero();
        verify(deploymentInvoker, times(1)).invokeWithStreaming(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a set cancellation signal breaks the retry loop after the first attempt")
    void cancellationBreaksRetryLoop() {
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(result(500, Map.of("err", "boom"), false));
        final EvaluationContext context = context(2, 10_000L);
        context.getCancellationSignal().set(true);

        final StepOutcome outcome =
                invoker().invoke(context, HttpMethod.POST, "/chat", new HttpHeaders(), queryParams, "body");

        assertThat(outcome.retryCount()).isZero();
        verify(deploymentInvoker, times(1)).invokeWithStreaming(any(), any(), any(), any(), any());
    }
}
