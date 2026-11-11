package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.constants.SecurityConstants;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.support.CoreRouteTestServer;
import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.runner.util.CredentialKind;
import com.epam.aidial.evaluation.runner.util.TracingConstants;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import com.epam.aidial.evaluation.service.domain.job.ActiveRunRegistry;
import com.epam.aidial.evaluation.web.security.apikey.CoreApiKeyIntrospector;
import com.epam.aidial.evaluation.web.security.apikey.IntrospectionResult;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.LinkData;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("Core-routed evaluation tracing")
public abstract class CoreRoutedTracingFunctionalTests extends EvaluationTracingFunctionalTests {
    @MockitoBean
    private CoreApiKeyIntrospector introspector;

    @Autowired
    private OpenTelemetry telemetry;

    @Autowired
    private ActiveRunRegistry registry;

    protected abstract CoreRouteTestServer core();

    @BeforeEach
    void configureCore() {
        core().configure(URI.create(baseUrl()), telemetry);
        when(introspector.introspect(anyString()))
                .thenReturn(new IntrospectionResult("caller", List.of("admin"), false));
    }

    @Test
    @DisplayName(
            "public start through Core and internal execute keeps orchestration linkage, PRK, and case/metric hierarchy")
    void routedRunRetainsTraceAndCredential() {
        final var suite = createChatSuite("Routed tracing");
        createSingleTurnCase(suite.getDatasetId(), "case", Map.of("prompt", "hello"));
        addTracingMetric(suite.getId());
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    assertThat(AuthorizationTokenHolder.getCredential().kind()).isEqualTo(CredentialKind.API_KEY);
                    assertThat(AuthorizationTokenHolder.getCredential().value()).isEqualTo(CoreRouteTestServer.PRK);
                    return chatReply("answer");
                });
        final SpanContext incoming = SpanContext.createFromRemoteParent(
                "33333333333333333333333333333333",
                "4444444444444444",
                TraceFlags.getSampled(),
                TraceState.getDefault());
        final var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("caller-token");
        headers.set(SecurityConstants.CORRELATION_ID_HEADER_NAME, "routedCorrelation123");
        final TestSuiteRunResponseDto started;
        try (Scope ignored = Span.wrap(incoming).makeCurrent()) {
            final var response = restTemplate.postForEntity(
                    apiUrl("/test-suites/" + suite.getId() + "/runs"),
                    new HttpEntity<>(
                            TestSuiteRunRequestDto.builder()
                                    .runConfig(RunConfigDto.builder()
                                            .numberOfRuns(1)
                                            .build())
                                    .build(),
                            headers),
                    TestSuiteRunResponseDto.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            started = response.getBody();
        }
        final var run = awaitRunTerminal(started.getId(), 20);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(spansFor(run.getId(), TracingConstants.SPAN_EVAL_RUN))
                        .hasSize(1));
        final var orchestration =
                spansFor(run.getId(), TracingConstants.SPAN_EVAL_RUN).getFirst();
        assertThat(orchestration.getTraceId()).isEqualTo(incoming.getTraceId());
        assertThat(core().correlationId()).isEqualTo("routedCorrelation123");
        assertThat(core().authorization()).isEqualTo("Bearer caller-token");
        assertThat(core().failure()).isNull();
        final var execution = spansFor(run.getId(), TracingConstants.SPAN_EVAL_TESTCASE_EXECUTE)
                .getFirst();
        assertThat(execution.getParentSpanContext().isValid()).isFalse();
        assertThat(execution.getLinks())
                .extracting(LinkData::getSpanContext)
                .containsExactly(orchestration.getSpanContext());
        assertThat(execution.getTraceId()).isNotEqualTo(orchestration.getTraceId());
        final var metricsRoot = spansFor(run.getId(), TracingConstants.SPAN_EVAL_TESTCASE_METRICS)
                .getFirst();
        assertThat(metricsRoot.getParentSpanContext().isValid()).isFalse();
        assertThat(metricsRoot.getLinks())
                .extracting(LinkData::getSpanContext)
                .containsExactlyInAnyOrder(orchestration.getSpanContext(), execution.getSpanContext());
        assertThat(spansFor(run.getId(), TracingConstants.SPAN_METRIC_TSMD_EVALUATE))
                .singleElement()
                .satisfies(s -> assertThat(s.getParentSpanId()).isEqualTo(metricsRoot.getSpanId()));
        assertThat(rowsFor(run))
                .singleElement()
                .satisfies(r -> assertThat(r.getTraceId()).isEqualTo(execution.getTraceId()));
    }

    @Test
    @DisplayName("Core trigger rejection leaves the run FAILED without dispatching orchestration or test-case traces")
    void rejectedTriggerCreatesNoExecutionRoots() {
        final var suite = createChatSuite("Rejected trace");
        createSingleTurnCase(suite.getDatasetId(), "case", Map.of("prompt", "hello"));
        core().reject(403);
        final var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("caller-token");
        final var response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                new HttpEntity<>(
                        TestSuiteRunRequestDto.builder()
                                .runConfig(
                                        RunConfigDto.builder().numberOfRuns(1).build())
                                .build(),
                        headers),
                TestSuiteRunResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        final var run = awaitRunTerminal(response.getBody().getId(), 20);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED.name());
        assertThat(spansFor(run.getId(), TracingConstants.SPAN_EVAL_TESTCASE_EXECUTE))
                .isEmpty();
        assertThat(spansFor(run.getId(), TracingConstants.SPAN_EVAL_RUN)).isEmpty();
        assertThat(registry.activeCount()).isZero();
    }

    @Test
    @DisplayName("duplicate internal execute returns 409 without creating a second tracing session")
    void duplicateInternalDispatchIsRejected() {
        final var suite = createChatSuite("Duplicate trace");
        final var run = metaTestDataHelper.createTestSuiteRun(suite.getId(), RunStatus.PENDING);
        final var handle = registry.register(run.getId());
        try {
            final var headers = new HttpHeaders();
            headers.set("Api-Key", CoreRouteTestServer.PRK);
            final var response = restTemplate.postForEntity(
                    baseUrl() + "/api/internal/runs/" + run.getId() + "/execute",
                    new HttpEntity<>(null, headers),
                    String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(spansFor(run.getId(), TracingConstants.SPAN_EVAL_RUN)).isEmpty();
            assertThat(spansFor(run.getId(), TracingConstants.SPAN_EVAL_TESTCASE_EXECUTE))
                    .isEmpty();
        } finally {
            handle.close();
            registry.remove(run.getId());
        }
    }

    @Test
    @DisplayName(
            "Core disconnect cancels the run, ends its execution trace and starts no metrics trace, without persisting unfinished execution")
    void disconnectCancelsAndEndsExecutionTrace() throws Exception {
        final var suite = createChatSuite("Disconnect trace");
        createSingleTurnCase(suite.getDatasetId(), "case", Map.of("prompt", "hello"));
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Deployment interrupted", e);
                    }
                    return chatReply("answer");
                });
        final var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("caller-token");
        final var response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                new HttpEntity<>(
                        TestSuiteRunRequestDto.builder()
                                .runConfig(
                                        RunConfigDto.builder().numberOfRuns(1).build())
                                .build(),
                        headers),
                TestSuiteRunResponseDto.class);
        try {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            await().atMost(5, TimeUnit.SECONDS).until(core()::hasActiveStream);
            core().disconnect();
            final var run = awaitRunTerminal(response.getBody().getId(), 15);
            assertThat(run.getStatus()).isEqualTo(RunStatus.CANCELLED.name());
            await().atMost(5, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(spansFor(run.getId(), TracingConstants.SPAN_EVAL_RUN))
                            .hasSize(1));
            assertThat(rowsFor(run)).isEmpty();
            assertThat(spansFor(run.getId(), TracingConstants.SPAN_EVAL_TESTCASE_METRICS))
                    .isEmpty();
            release.countDown();
            await().atMost(5, TimeUnit.SECONDS)
                    .untilAsserted(() -> assertThat(
                                    spansFor(run.getId(), TracingConstants.SPAN_EVAL_TESTCASE_EXECUTE))
                            .hasSize(1));
        } finally {
            release.countDown();
        }
    }
}
