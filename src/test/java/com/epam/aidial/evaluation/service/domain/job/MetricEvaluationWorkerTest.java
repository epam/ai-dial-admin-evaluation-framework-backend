package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.metricprovider.MetricProviderClient;
import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationRequestDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricOutputFieldDto;
import com.epam.aidial.evaluation.configuration.properties.MetricEvaluationProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanBuilder;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

@DisplayName("MetricEvaluationWorker")
@ExtendWith(MockitoExtension.class)
class MetricEvaluationWorkerTest {

    @Mock
    private MetricProviderClient metricProviderClient;

    private MetricEvaluationWorker worker;

    private static final String PROVIDER_ID = "dial";
    private static final String METRIC_NAME = "exact_match";

    @BeforeEach
    void setUp() {
        BindingResolver bindingResolver = new BindingResolver(new ObjectMapper());
        worker = new MetricEvaluationWorker(metricProviderClient, bindingResolver, OpenTelemetry.noop());
    }

    @Test
    @DisplayName("Should evaluate successfully on first attempt")
    void shouldEvaluateSuccessfully() throws InterruptedException {
        EvaluationResponseDto expectedResponse = buildSuccessResponse();
        when(metricProviderClient.evaluate(eq(PROVIDER_ID), any(EvaluationRequestDto.class)))
                .thenReturn(expectedResponse);

        EvaluationResponseDto result = worker.evaluate(buildTsmd(), buildResult(), new Semaphore(5), buildContext(0));

        assertThat(result.getMetricName()).isEqualTo(METRIC_NAME);
        verify(metricProviderClient, times(1)).evaluate(eq(PROVIDER_ID), any());
    }

    @Test
    @DisplayName("Should retry on 5xx and succeed")
    void shouldRetryOn5xxAndSucceed() throws InterruptedException {
        EvaluationResponseDto expectedResponse = buildSuccessResponse();
        when(metricProviderClient.evaluate(eq(PROVIDER_ID), any(EvaluationRequestDto.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR))
                .thenReturn(expectedResponse);

        EvaluationResponseDto result = worker.evaluate(buildTsmd(), buildResult(), new Semaphore(5), buildContext(1));

        assertThat(result.getMetricName()).isEqualTo(METRIC_NAME);
        verify(metricProviderClient, times(2)).evaluate(eq(PROVIDER_ID), any());
    }

    @Test
    @DisplayName("Should throw immediately on 4xx without retry")
    void shouldThrowOn4xxWithoutRetry() {
        when(metricProviderClient.evaluate(eq(PROVIDER_ID), any(EvaluationRequestDto.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> worker.evaluate(buildTsmd(), buildResult(), new Semaphore(5), buildContext(2)))
                .isInstanceOf(HttpClientErrorException.class);

        verify(metricProviderClient, times(1)).evaluate(eq(PROVIDER_ID), any());
    }

    @Test
    @DisplayName("Should throw after all retries exhausted")
    void shouldThrowAfterRetriesExhausted() {
        when(metricProviderClient.evaluate(eq(PROVIDER_ID), any(EvaluationRequestDto.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> worker.evaluate(buildTsmd(), buildResult(), new Semaphore(5), buildContext(1)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("after 2 attempts");

        verify(metricProviderClient, times(2)).evaluate(eq(PROVIDER_ID), any());
    }

    @Test
    @DisplayName("Should throw InterruptedException when cancelled during backoff")
    void shouldThrowWhenCancelledDuringBackoff() {
        AtomicBoolean cancellation = new AtomicBoolean(true);
        MetricEvaluationContext context = buildContextWithCancellation(1, cancellation);

        assertThatThrownBy(() -> worker.evaluate(buildTsmd(), buildResult(), new Semaphore(5), context))
                .isInstanceOf(InterruptedException.class)
                .hasMessageContaining("cancelled");
    }

    private AggregatedMetricDefinition buildTsmd() {
        return AggregatedMetricDefinition.builder()
                .id(UUID.randomUUID())
                .name("Accuracy")
                .metricDeclarationName(METRIC_NAME)
                .declarationProviderId(PROVIDER_ID)
                .configBindings("[]")
                .inputBindings("""
                        [{"property": "actual", "source": {"$type": "Response", "columnName": "answer"}}]
                        """)
                .build();
    }

    private TestCaseRunResult buildResult() {
        return TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testCaseId(UUID.randomUUID())
                .testCaseName("test-case")
                .testCaseData("{\"question\": \"What is 2+2?\"}")
                .extractedColumns("{\"answer\": \"4\"}")
                .build();
    }

    private EvaluationResponseDto buildSuccessResponse() {
        return EvaluationResponseDto.builder()
                .metricName(METRIC_NAME)
                .output(Map.of(
                        METRIC_NAME,
                        MetricOutputFieldDto.builder()
                                .type("value")
                                .value(BigDecimal.ONE)
                                .build()))
                .build();
    }

    private MetricEvaluationContext buildContext(int maxRetries) {
        return buildContextWithCancellation(maxRetries, new AtomicBoolean(false));
    }

    private MetricEvaluationContext buildContextWithCancellation(int maxRetries, AtomicBoolean cancellation) {
        MetricEvaluationProperties.Retry retryConfig = new MetricEvaluationProperties.Retry();
        retryConfig.setMaxRetries(maxRetries);
        retryConfig.setRetryDelayMs(100L);
        retryConfig.setRetryBackoffMultiplier(1.0);
        retryConfig.setMaxRetryDelayMs(1000L);

        return MetricEvaluationContext.builder()
                .testSuiteRunId(UUID.randomUUID())
                .testSuiteId(UUID.randomUUID())
                .cancellationSignal(cancellation)
                .retryConfig(retryConfig)
                .build();
    }

    @Nested
    @DisplayName("Span attributes")
    @ExtendWith(MockitoExtension.class)
    class SpanAttributes {

        @Mock(answer = Answers.RETURNS_DEEP_STUBS)
        private OpenTelemetry mockOpenTelemetry;

        @Mock
        private MetricProviderClient mockMetricProviderClient;

        @Test
        @DisplayName(
                "Should set all span attributes including testcase.id, testcase.name, eval.suite.id, metric.declaration.name")
        void shouldSetAllSpanAttributes() throws InterruptedException {
            // given
            UUID testCaseId = UUID.fromString("11111111-1111-1111-1111-111111111111");
            UUID testSuiteRunId = UUID.fromString("22222222-2222-2222-2222-222222222222");
            UUID testSuiteId = UUID.fromString("33333333-3333-3333-3333-333333333333");
            UUID resultId = UUID.fromString("44444444-4444-4444-4444-444444444444");

            BindingResolver bindingResolver = new BindingResolver(new ObjectMapper());
            MetricEvaluationWorker spanWorker =
                    new MetricEvaluationWorker(mockMetricProviderClient, bindingResolver, mockOpenTelemetry);

            AggregatedMetricDefinition tsmd = AggregatedMetricDefinition.builder()
                    .id(UUID.randomUUID())
                    .name("Accuracy")
                    .metricDeclarationName("exact_match")
                    .declarationProviderId("dial")
                    .configBindings("[]")
                    .inputBindings("[]")
                    .build();

            TestCaseRunResult result = TestCaseRunResult.builder()
                    .id(resultId)
                    .testCaseId(testCaseId)
                    .testCaseName("my-test-case")
                    .testCaseData("{}")
                    .extractedColumns("{}")
                    .build();

            MetricEvaluationProperties.Retry retryConfig = new MetricEvaluationProperties.Retry();
            retryConfig.setMaxRetries(0);
            retryConfig.setRetryDelayMs(100L);
            retryConfig.setRetryBackoffMultiplier(1.0);
            retryConfig.setMaxRetryDelayMs(1000L);

            MetricEvaluationContext context = MetricEvaluationContext.builder()
                    .testSuiteRunId(testSuiteRunId)
                    .testSuiteId(testSuiteId)
                    .cancellationSignal(new AtomicBoolean(false))
                    .retryConfig(retryConfig)
                    .build();

            // Mock the span builder chain — RETURNS_DEEP_STUBS gives us chaining
            SpanBuilder spanBuilder = mockOpenTelemetry.getTracer(anyString()).spanBuilder(anyString());
            // Make setAttribute return the same builder so inOrder can track all calls
            when(spanBuilder.setAttribute(anyString(), anyString())).thenReturn(spanBuilder);

            when(mockMetricProviderClient.evaluate(eq("dial"), any(EvaluationRequestDto.class)))
                    .thenReturn(EvaluationResponseDto.builder()
                            .metricName("exact_match")
                            .output(Map.of(
                                    "exact_match",
                                    MetricOutputFieldDto.builder()
                                            .type("value")
                                            .value(BigDecimal.ONE)
                                            .build()))
                            .build());

            // when
            spanWorker.evaluate(tsmd, result, new Semaphore(5), context);

            // then — verify all 8 setAttribute calls on the span builder
            var inOrderVerifier = inOrder(spanBuilder);
            inOrderVerifier.verify(spanBuilder).setAttribute("tsmd.name", "Accuracy");
            inOrderVerifier.verify(spanBuilder).setAttribute("tsmd.provider.id", "dial");
            inOrderVerifier.verify(spanBuilder).setAttribute("eval.run.id", testSuiteRunId.toString());
            inOrderVerifier.verify(spanBuilder).setAttribute("result.id", resultId.toString());
            inOrderVerifier.verify(spanBuilder).setAttribute("testcase.id", testCaseId.toString());
            inOrderVerifier.verify(spanBuilder).setAttribute("testcase.name", "my-test-case");
            inOrderVerifier.verify(spanBuilder).setAttribute("eval.suite.id", testSuiteId.toString());
            inOrderVerifier.verify(spanBuilder).setAttribute("metric.declaration.name", "exact_match");
        }
    }
}
