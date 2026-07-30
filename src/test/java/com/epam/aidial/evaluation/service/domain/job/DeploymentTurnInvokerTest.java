package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.configuration.properties.SseEventProcessingProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.QuietJsonService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("DeploymentTurnInvoker")
@ExtendWith(MockitoExtension.class)
class DeploymentTurnInvokerTest {

    @Mock
    private DialCoreDeploymentInvoker deploymentInvoker;

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));
    private final ObjectMapper objectMapper = new ObjectMapper();

    private DeploymentTurnInvoker invoker;

    @BeforeEach
    void setUp() {
        final QuietJsonService jsonService = new QuietJsonService(objectMapper);
        final SseEventParser sseEventParser = new SseEventParser(objectMapper, FIXED_CLOCK);
        final SseEventProcessingProperties sseEventProcessingProperties = new SseEventProcessingProperties();
        sseEventProcessingProperties.setMaxTotalDurationMs(3_600_000L);
        invoker = new DeploymentTurnInvoker(
                deploymentInvoker,
                jsonService,
                sseEventParser,
                sseEventProcessingProperties,
                objectMapper,
                FIXED_CLOCK);
    }

    @Test
    @DisplayName("Should return SUCCESS outcome for non-streaming 200 response")
    void invoke_nonStreamingSuccess_returnsSuccessOutcome() {
        final DeploymentInvocationResult invocationResult = new DeploymentInvocationResult(
                200, false, Map.of("choices", java.util.List.of()), null, new HttpHeaders());
        when(deploymentInvoker.invokeWithStreaming(
                        any(HttpMethod.class), anyString(), any(HttpHeaders.class), any(), any()))
                .thenReturn(invocationResult);

        final TurnOutcome outcome = invoker.invoke(
                buildContext(), HttpMethod.POST, "/chat/completions", new HttpHeaders(), emptyQueryParams(), Map.of());

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(outcome.statusCode()).isEqualTo(200);
        assertThat(outcome.responseBody()).isNotNull();
        assertThat(outcome.retryCount()).isZero();
        assertThat(outcome.logDetails()).isNull();
    }

    @Test
    @DisplayName("Should assemble an OpenAI-shaped response body from a streaming SSE turn")
    void invoke_streamingResponse_assemblesOpenAiBodyViaAccumulator() {
        final String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n"
                + "data: [DONE]\n\n";
        final InputStream eventStream = new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8));
        final DeploymentInvocationResult invocationResult =
                new DeploymentInvocationResult(200, true, null, eventStream, new HttpHeaders());
        when(deploymentInvoker.invokeWithStreaming(
                        any(HttpMethod.class), anyString(), any(HttpHeaders.class), any(), any()))
                .thenReturn(invocationResult);

        final TurnOutcome outcome = invoker.invoke(
                buildContext(), HttpMethod.POST, "/chat/completions", new HttpHeaders(), emptyQueryParams(), Map.of());

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(outcome.statusCode()).isEqualTo(200);
        final JsonNode root = objectMapper.readTree(outcome.responseBody());
        final JsonNode message = root.path("choices").path(0).path("message");
        assertThat(message.path("role").asString()).isEqualTo("assistant");
        assertThat(message.path("content").asString()).isEqualTo("Hello world");
        assertThat(root.path("choices").path(0).path("finish_reason").asString())
                .isEqualTo("stop");
    }

    @Test
    @DisplayName("Should record retryAttempts logDetails JSON matching EvaluationWorker's shape when retrying on 500")
    void invoke_retryOnServerError_recordsLogDetails() {
        final DeploymentInvocationResult failResult =
                new DeploymentInvocationResult(500, false, Map.of("error", "Server Error"), null, new HttpHeaders());
        final DeploymentInvocationResult successResult = new DeploymentInvocationResult(
                200, false, Map.of("choices", java.util.List.of()), null, new HttpHeaders());
        when(deploymentInvoker.invokeWithStreaming(
                        any(HttpMethod.class), anyString(), any(HttpHeaders.class), any(), any()))
                .thenReturn(failResult)
                .thenReturn(successResult);

        final TurnOutcome outcome = invoker.invoke(
                buildContextWithRetries(1),
                HttpMethod.POST,
                "/chat/completions",
                new HttpHeaders(),
                emptyQueryParams(),
                Map.of());

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(outcome.statusCode()).isEqualTo(200);
        assertThat(outcome.retryCount()).isEqualTo(1);
        assertThat(outcome.logDetails()).isNotNull();
        assertThat(outcome.logDetails())
                .contains("retryAttempts", "\"attemptIndex\":1", "\"statusCode\":500", "\"errorType\":\"HTTP_ERROR\"");

        verify(deploymentInvoker, times(2))
                .invokeWithStreaming(any(HttpMethod.class), anyString(), any(HttpHeaders.class), any(), any());
    }

    @Test
    @DisplayName("Should not retry on 400 client error and leave logDetails null")
    void invoke_noRetryOn4xx_leavesLogDetailsNull() {
        final DeploymentInvocationResult clientErrorResult =
                new DeploymentInvocationResult(400, false, Map.of("error", "Bad Request"), null, new HttpHeaders());
        when(deploymentInvoker.invokeWithStreaming(
                        any(HttpMethod.class), anyString(), any(HttpHeaders.class), any(), any()))
                .thenReturn(clientErrorResult);

        final TurnOutcome outcome = invoker.invoke(
                buildContextWithRetries(2),
                HttpMethod.POST,
                "/chat/completions",
                new HttpHeaders(),
                emptyQueryParams(),
                Map.of());

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(outcome.statusCode()).isEqualTo(400);
        assertThat(outcome.retryCount()).isZero();
        assertThat(outcome.logDetails()).isNull();

        verify(deploymentInvoker, times(1))
                .invokeWithStreaming(any(HttpMethod.class), anyString(), any(HttpHeaders.class), any(), any());
    }

    @Test
    @DisplayName("Should return an INVOCATION_ERROR envelope in responseBody when the call throws")
    void invoke_networkError_returnsInvocationErrorEnvelope() {
        when(deploymentInvoker.invokeWithStreaming(
                        any(HttpMethod.class), anyString(), any(HttpHeaders.class), any(), any()))
                .thenThrow(new RuntimeException(new IOException("Connection refused")));

        final TurnOutcome outcome = invoker.invoke(
                buildContext(), HttpMethod.POST, "/chat/completions", new HttpHeaders(), emptyQueryParams(), Map.of());

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(outcome.statusCode()).isNull();
        assertThat(outcome.responseBody()).contains("INVOCATION_ERROR").contains("Connection refused");
    }

    @Test
    @DisplayName("Should return TIMEOUT status when the call throws a timeout exception")
    void invoke_timeoutException_returnsTimeoutStatus() {
        when(deploymentInvoker.invokeWithStreaming(
                        any(HttpMethod.class), anyString(), any(HttpHeaders.class), any(), any()))
                .thenThrow(new RuntimeException(new HttpTimeoutException("Request timed out")));

        final TurnOutcome outcome = invoker.invoke(
                buildContext(), HttpMethod.POST, "/chat/completions", new HttpHeaders(), emptyQueryParams(), Map.of());

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.TIMEOUT);
        assertThat(outcome.statusCode()).isNull();
        assertThat(outcome.responseBody()).contains("INVOCATION_ERROR");
    }

    @Test
    @DisplayName("Should truncate and mark ERROR when non-streaming body exceeds maxResponseSizeBytes")
    void invoke_oversizeBody_truncatesAndMarksError() {
        final String largeBody = "A".repeat(200);
        final DeploymentInvocationResult invocationResult =
                new DeploymentInvocationResult(200, false, largeBody, null, new HttpHeaders());
        when(deploymentInvoker.invokeWithStreaming(
                        any(HttpMethod.class), anyString(), any(HttpHeaders.class), any(), any()))
                .thenReturn(invocationResult);

        final TurnOutcome outcome = invoker.invoke(
                buildContextWithMaxResponseSize(50L),
                HttpMethod.POST,
                "/chat/completions",
                new HttpHeaders(),
                emptyQueryParams(),
                Map.of());

        assertThat(outcome.status()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(outcome.statusCode()).isEqualTo(200);
    }

    private MultiValueMap<String, String> emptyQueryParams() {
        return new LinkedMultiValueMap<>();
    }

    private EvaluationContext buildContext() {
        return buildContextBase().build();
    }

    private EvaluationContext buildContextWithRetries(int maxRetries) {
        return buildContextBase()
                .maxRetries(maxRetries)
                .retryDelayMs(0L)
                .retryBackoffMultiplier(1.0)
                .build();
    }

    private EvaluationContext buildContextWithMaxResponseSize(long maxResponseSizeBytes) {
        return buildContextBase().maxResponseSizeBytes(maxResponseSizeBytes).build();
    }

    private EvaluationContext.EvaluationContextBuilder buildContextBase() {
        return EvaluationContext.builder()
                .runId(UUID.randomUUID())
                .suiteId(UUID.randomUUID())
                .requestTimeoutMs(30_000L)
                .maxRetries(0)
                .retryDelayMs(0L)
                .retryBackoffMultiplier(2.0)
                .maxRetryDelayMs(1_000L)
                .maxResponseSizeBytes(5_242_880L)
                .cancellationSignal(new AtomicBoolean(false))
                .createdAtMs(System.currentTimeMillis());
    }
}
