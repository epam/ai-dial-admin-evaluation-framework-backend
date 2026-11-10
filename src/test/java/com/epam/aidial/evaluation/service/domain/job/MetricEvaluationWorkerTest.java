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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClientConfiguration;
import com.epam.aidial.evaluation.client.metricprovider.MetricProviderClient;
import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationRequestDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricOutputFieldDto;
import com.epam.aidial.evaluation.configuration.properties.MetricEvaluationProperties;
import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.epam.aidial.evaluation.runner.job.EvaluationTraceSession;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.runner.util.TracingConstants;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@DisplayName("MetricEvaluationWorker")
@ExtendWith(MockitoExtension.class)
class MetricEvaluationWorkerTest {

    @Mock
    private MetricProviderClient metricProviderClient;

    private MetricEvaluationWorker worker;
    private BindingResolver bindingResolver;

    private static final String PROVIDER_ID = "dial";
    private static final String METRIC_NAME = "exact_match";

    @BeforeEach
    void setUp() {
        bindingResolver = new BindingResolver(new ObjectMapper());
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
    @DisplayName("Should throw InterruptedException when interrupted during backoff")
    void shouldThrowInterrupted_whenInterruptedDuringBackoff() {
        // The provider semaphore's acquire() observes the interrupt before any provider call is made,
        // so metricProviderClient is never invoked — no stubbing needed here.
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> worker.evaluate(buildTsmd(), buildResult(), new Semaphore(5), buildContext(1)))
                    .isInstanceOf(InterruptedException.class);
        } finally {
            // Clear the interrupt flag left by the propagated InterruptedException so it does not leak
            // into subsequent tests running on this thread.
            Thread.interrupted();
        }
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
        MetricEvaluationProperties.Retry retryConfig = new MetricEvaluationProperties.Retry();
        retryConfig.setMaxRetries(maxRetries);
        retryConfig.setRetryDelayMs(100L);
        retryConfig.setRetryBackoffMultiplier(1.0);
        retryConfig.setMaxRetryDelayMs(1000L);

        return MetricEvaluationContext.builder()
                .testSuiteRunId(UUID.randomUUID())
                .testSuiteId(UUID.randomUUID())
                .retryConfig(retryConfig)
                .build();
    }

    @Test
    @DisplayName("Phase 2 metric rows reuse execution's root, preserve provider descendants and fresh metric baggage")
    void metricRowsUseCaseRoot() throws InterruptedException {
        final InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (OpenTelemetrySdk telemetry = OpenTelemetrySdk.builder()
                .setPropagators(ContextPropagators.create(TextMapPropagator.composite(
                        W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance())))
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build())
                .build()) {
            final MetricEvaluationContext original = buildContext(0);
            final TestCaseRunResult result = buildResult();
            final Span runSpan = telemetry.getTracer("test").spanBuilder("run").startSpan();
            try (EvaluationTraceSession session = new EvaluationTraceSession(
                    telemetry, original.getTestSuiteRunId(), original.getTestSuiteId(), runSpan.getSpanContext())) {
                final Span execution = session.startSpan(
                        telemetry.getTracer("test").spanBuilder("execution"),
                        result.getTestCaseId(),
                        result.getTestCaseName(),
                        0);
                result.setTraceId(execution.getSpanContext().getTraceId());
                execution.end();
                final MetricEvaluationContext context =
                        original.toBuilder().traceSession(session).build();
                final MetricEvaluationWorker traced =
                        new MetricEvaluationWorker(metricProviderClient, bindingResolver, telemetry);
                final RestClient.Builder clientBuilder = RestClient.builder()
                        .requestInterceptor(DialCoreClientConfiguration.tracingInterceptor(telemetry));
                final MockRestServiceServer server =
                        MockRestServiceServer.bindTo(clientBuilder).build();
                final RestClient client = clientBuilder.build();
                server.expect(ExpectedCount.times(4), request -> {
                            final var current = Span.current().getSpanContext();
                            assertThat(request.getHeaders().getFirst("traceparent"))
                                    .contains(current.getTraceId())
                                    .contains(current.getSpanId());
                            assertThat(request.getHeaders().getFirst("baggage"))
                                    .contains("eval.phase=metric-evaluation")
                                    .contains("result.id=" + result.getId());
                        })
                        .andRespond(withSuccess());
                when(metricProviderClient.evaluate(anyString(), any())).thenAnswer(inv -> {
                    assertThat(Span.current().getSpanContext().getTraceId()).isEqualTo(result.getTraceId());
                    assertThat(Baggage.current().getEntryValue(TracingConstants.EVAL_PHASE))
                            .isEqualTo(TracingConstants.PHASE_METRIC_EVALUATION);
                    assertThat(Baggage.current().getEntryValue(TracingConstants.RESULT_ID))
                            .isEqualTo(result.getId().toString());
                    final Span provider =
                            telemetry.getTracer("test").spanBuilder("provider").startSpan();
                    provider.end();
                    client.post().uri("http://provider/evaluate").retrieve().toBodilessEntity();
                    return buildSuccessResponse();
                });
                try (Scope ignored = runSpan.makeCurrent()) {
                    for (int request = 0; request < 2; request++) {
                        for (int turn = 0; turn < 2; turn++) {
                            result.setId(UUID.randomUUID());
                            result.setRequestIndex(request);
                            result.setTurnIndex(turn);
                            traced.evaluate(buildTsmd(), result, new Semaphore(1), context);
                        }
                    }
                    assertThat(Span.current().getSpanContext()).isEqualTo(runSpan.getSpanContext());
                    assertThat(Baggage.current().getEntryValue(TracingConstants.RESULT_ID))
                            .isNull();
                }
                server.verify();
            }
            final var root = exporter.getFinishedSpanItems().stream()
                    .filter(s -> s.getName().equals(TracingConstants.SPAN_EVAL_TESTCASE))
                    .findFirst()
                    .orElseThrow();
            final var metrics = exporter.getFinishedSpanItems().stream()
                    .filter(s -> s.getName().equals(TracingConstants.SPAN_METRIC_TSMD_EVALUATE))
                    .toList();
            assertThat(metrics).hasSize(4).allSatisfy(metric -> {
                assertThat(metric.getParentSpanId()).isEqualTo(root.getSpanId());
                assertThat(metric.getTraceId()).isEqualTo(result.getTraceId());
            });
            assertThat(exporter.getFinishedSpanItems().stream()
                            .filter(s -> s.getName().equals("provider"))
                            .toList())
                    .hasSize(4)
                    .allSatisfy(provider ->
                            assertThat(metrics).anyMatch(m -> m.getSpanId().equals(provider.getParentSpanId())));
            runSpan.end();
        }
    }

    @Test
    @DisplayName("imported results get fresh metric-only roots without rewriting historical execution trace IDs")
    void importedResultTraceRemainsHistorical() throws InterruptedException {
        final InMemorySpanExporter exporter = InMemorySpanExporter.create();
        try (OpenTelemetrySdk telemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build())
                .build()) {
            final MetricEvaluationContext original = buildContext(0);
            final TestCaseRunResult result = buildResult();
            result.setTraceId("11111111111111111111111111111111");
            try (EvaluationTraceSession session = new EvaluationTraceSession(
                    telemetry,
                    original.getTestSuiteRunId(),
                    original.getTestSuiteId(),
                    Span.current().getSpanContext())) {
                when(metricProviderClient.evaluate(anyString(), any())).thenReturn(buildSuccessResponse());
                new MetricEvaluationWorker(metricProviderClient, bindingResolver, telemetry)
                        .evaluate(
                                buildTsmd(),
                                result,
                                new Semaphore(1),
                                original.toBuilder().traceSession(session).build());
                assertThat(result.getTraceId()).isEqualTo("11111111111111111111111111111111");
            }
            assertThat(exporter.getFinishedSpanItems())
                    .allSatisfy(span -> assertThat(span.getTraceId()).isNotEqualTo(result.getTraceId()));
            assertThat(exporter.getFinishedSpanItems())
                    .anyMatch(span -> span.getName().equals(TracingConstants.SPAN_EVAL_TESTCASE));
        }
    }

    @Test
    @DisplayName("metric completion racing with run cleanup releases roots and late metrics cannot resurrect tracing")
    void metricCompletionAfterCleanup() throws Exception {
        final InMemorySpanExporter exporter = InMemorySpanExporter.create();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try (OpenTelemetrySdk telemetry = OpenTelemetrySdk.builder()
                        .setTracerProvider(SdkTracerProvider.builder()
                                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                                .build())
                        .build();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final MetricEvaluationContext original = buildContext(0);
            final EvaluationTraceSession session = new EvaluationTraceSession(
                    telemetry,
                    original.getTestSuiteRunId(),
                    original.getTestSuiteId(),
                    Span.current().getSpanContext());
            final MetricEvaluationContext context =
                    original.toBuilder().traceSession(session).build();
            final MetricEvaluationWorker traced =
                    new MetricEvaluationWorker(metricProviderClient, bindingResolver, telemetry);
            when(metricProviderClient.evaluate(anyString(), any())).thenAnswer(inv -> {
                entered.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return buildSuccessResponse();
            });
            final var running =
                    executor.submit(() -> traced.evaluate(buildTsmd(), buildResult(), new Semaphore(1), context));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            session.close();
            release.countDown();
            assertThat(running.get(5, TimeUnit.SECONDS).getMetricName()).isEqualTo(METRIC_NAME);
            final int finished = exporter.getFinishedSpanItems().size();
            traced.evaluate(buildTsmd(), buildResult(), new Semaphore(1), context);
            assertThat(exporter.getFinishedSpanItems()).hasSize(finished);
            assertThat(exporter.getFinishedSpanItems().stream()
                            .filter(s -> s.getName().equals(TracingConstants.SPAN_EVAL_TESTCASE))
                            .toList())
                    .hasSize(1);
        } finally {
            release.countDown();
        }
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
                "Should set all span attributes including testcase.id, testcase.name, eval.suite.id, metric.declaration.name, eval.phase")
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

            // then — verify all 9 setAttribute calls on the span builder
            var inOrderVerifier = inOrder(spanBuilder);
            inOrderVerifier.verify(spanBuilder).setAttribute("tsmd.name", "Accuracy");
            inOrderVerifier.verify(spanBuilder).setAttribute("tsmd.provider.id", "dial");
            inOrderVerifier.verify(spanBuilder).setAttribute("eval.run.id", testSuiteRunId.toString());
            inOrderVerifier.verify(spanBuilder).setAttribute("result.id", resultId.toString());
            inOrderVerifier.verify(spanBuilder).setAttribute("testcase.id", testCaseId.toString());
            inOrderVerifier.verify(spanBuilder).setAttribute("testcase.name", "my-test-case");
            inOrderVerifier.verify(spanBuilder).setAttribute("eval.suite.id", testSuiteId.toString());
            inOrderVerifier.verify(spanBuilder).setAttribute("metric.declaration.name", "exact_match");
            inOrderVerifier.verify(spanBuilder).setAttribute("eval.phase", "metric-evaluation");
        }
    }
}
