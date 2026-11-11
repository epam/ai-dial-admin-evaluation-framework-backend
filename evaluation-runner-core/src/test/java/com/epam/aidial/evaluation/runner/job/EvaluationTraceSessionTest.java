package com.epam.aidial.evaluation.runner.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.util.TracingConstants;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("EvaluationTraceSession")
class EvaluationTraceSessionTest {
    private static final AttributeKey<String> LINK_TYPE = AttributeKey.stringKey(TracingConstants.EVAL_LINK_TYPE);

    private InMemorySpanExporter exporter;
    private OpenTelemetrySdk telemetry;
    private Span orchestration;
    private final UUID caseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        telemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build())
                .build();
        orchestration = telemetry.getTracer("test").spanBuilder("run").startSpan();
    }

    @AfterEach
    void tearDown() {
        orchestration.end();
        telemetry.close();
    }

    private Span execution(EvaluationTraceSession session, String name, UUID id, int index) {
        return session.startExecutionSpan(telemetry.getTracer("test").spanBuilder(name), id, index);
    }

    private Span metrics(EvaluationTraceSession session, String name, UUID id, int index) {
        return session.startMetricsSpan(telemetry.getTracer("test").spanBuilder(name), id, index);
    }

    private EvaluationTraceSession session() {
        return new EvaluationTraceSession(orchestration.getSpanContext());
    }

    private SpanData finished(String name) {
        return exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, SpanContext> linksByType(SpanData span) {
        return span.getLinks().stream()
                .collect(Collectors.toMap(
                        link -> link.getAttributes().get(LINK_TYPE), LinkData::getSpanContext));
    }

    @Test
    @DisplayName("execution and metrics spans are separate root traces, linked to the run and to each other")
    void linkedPeerRoots() {
        try (EvaluationTraceSession session = session();
                Scope ignored = orchestration.makeCurrent()) {
            execution(session, "execution", caseId, 0).end();
            metrics(session, "metrics", caseId, 0).end();
        }
        final SpanData execution = finished("execution");
        final SpanData metrics = finished("metrics");

        assertThat(execution.getParentSpanContext().isValid()).isFalse();
        assertThat(metrics.getParentSpanContext().isValid()).isFalse();
        assertThat(execution.getTraceId())
                .isNotEqualTo(metrics.getTraceId())
                .isNotEqualTo(orchestration.getSpanContext().getTraceId());
        assertThat(linksByType(execution))
                .containsExactlyEntriesOf(Map.of(TracingConstants.LINK_TYPE_RUN, orchestration.getSpanContext()));
        assertThat(linksByType(metrics))
                .containsOnly(
                        Map.entry(TracingConstants.LINK_TYPE_RUN, orchestration.getSpanContext()),
                        Map.entry(TracingConstants.LINK_TYPE_EXECUTION, execution.getSpanContext()));
    }

    @Test
    @DisplayName("metrics link only to the execution of the same case and repetition")
    void metricsLinkMatchesCaseAndRepetition() {
        try (EvaluationTraceSession session = session()) {
            execution(session, "execution-0", caseId, 0).end();
            execution(session, "execution-1", caseId, 1).end();
            metrics(session, "metrics-1", caseId, 1).end();
            metrics(session, "metrics-other", UUID.randomUUID(), 0).end();
        }
        assertThat(linksByType(finished("metrics-1")))
                .containsEntry(
                        TracingConstants.LINK_TYPE_EXECUTION,
                        finished("execution-1").getSpanContext());
        assertThat(linksByType(finished("metrics-other")))
                .containsOnlyKeys(TracingConstants.LINK_TYPE_RUN);
    }

    @Test
    @DisplayName("execution spans end on their own; the session holds no open spans")
    void noLongLivedSpans() {
        try (EvaluationTraceSession session = session()) {
            final Span span = execution(session, "execution", caseId, 0);
            span.end();
            assertThat(exporter.getFinishedSpanItems()).extracting(SpanData::getName).containsExactly("execution");
        }
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    }

    @Test
    @DisplayName("close is idempotent, releases remembered executions, and later spans stay run-linked")
    void closeReleasesState() {
        final EvaluationTraceSession session = session();
        execution(session, "execution", caseId, 0).end();
        session.close();
        session.close();
        assertThat(ReflectionTestUtils.getField(session, "executions")).isEqualTo(Map.of());

        metrics(session, "late-metrics", caseId, 0).end();
        execution(session, "late-execution", caseId, 1).end();
        assertThat(linksByType(finished("late-metrics"))).containsOnlyKeys(TracingConstants.LINK_TYPE_RUN);
        assertThat(ReflectionTestUtils.getField(session, "executions")).isEqualTo(Map.of());
    }

    @Test
    @DisplayName("sessions isolate concurrent runs of the same case and repetition")
    void sessionsAreIsolated() {
        try (EvaluationTraceSession first = session();
                EvaluationTraceSession second = session()) {
            execution(first, "execution", caseId, 0).end();
            metrics(second, "metrics", caseId, 0).end();
        }
        assertThat(linksByType(finished("metrics"))).containsOnlyKeys(TracingConstants.LINK_TYPE_RUN);
    }

    @Test
    @DisplayName("an invalid orchestration context adds no run link")
    void invalidOrchestrationAddsNoLink() {
        try (EvaluationTraceSession session = new EvaluationTraceSession(SpanContext.getInvalid())) {
            execution(session, "execution", caseId, 0).end();
        }
        assertThat(finished("execution").getLinks()).isEmpty();
    }

    @Test
    @DisplayName("disabled tracing remembers nothing and does not fabricate valid trace contexts")
    void disabledTracingIsNoOp() {
        try (EvaluationTraceSession session = new EvaluationTraceSession(SpanContext.getInvalid())) {
            final Span span = session.startExecutionSpan(
                    OpenTelemetry.noop().getTracer("test").spanBuilder("execution"), caseId, 0);
            assertThat(span.getSpanContext().isValid()).isFalse();
            assertThat(ReflectionTestUtils.getField(session, "executions")).isEqualTo(Map.of());
        }
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }
}
