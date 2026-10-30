package com.epam.aidial.evaluation.runner.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.util.TracingConstants;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("EvaluationTraceSession")
class EvaluationTraceSessionTest {
    private InMemorySpanExporter exporter;
    private OpenTelemetrySdk telemetry;
    private Span orchestration;
    private final UUID runId = UUID.randomUUID();
    private final UUID suiteId = UUID.randomUUID();
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

    private Span child(EvaluationTraceSession session, String name, UUID id, int index) {
        return session.startSpan(telemetry.getTracer("test").spanBuilder(name), id, "case", index);
    }

    private EvaluationTraceSession session() {
        return new EvaluationTraceSession(telemetry, runId, suiteId, orchestration.getSpanContext());
    }

    private List<SpanData> roots() {
        return exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals(TracingConstants.SPAN_EVAL_TESTCASE))
                .toList();
    }

    @Test
    @DisplayName("repetitions have independent root traces linked to orchestration and metrics reuse execution's root")
    void independentRootsWithCrossPhaseChildren() {
        try (EvaluationTraceSession session = session();
                Scope ignored = orchestration.makeCurrent()) {
            final Span execution = child(session, "execution", caseId, 0);
            final Span metric = child(session, "metric", caseId, 0);
            final Span repetition = child(session, "execution", caseId, 1);
            final Span otherCase = child(session, "execution", UUID.randomUUID(), 0);
            assertThat(execution.getSpanContext().getTraceId())
                    .isEqualTo(metric.getSpanContext().getTraceId())
                    .isNotEqualTo(orchestration.getSpanContext().getTraceId())
                    .isNotEqualTo(repetition.getSpanContext().getTraceId())
                    .isNotEqualTo(otherCase.getSpanContext().getTraceId());
            execution.end();
            metric.end();
            repetition.end();
            otherCase.end();
            assertThat(roots()).isEmpty();
            assertThat(Span.current().getSpanContext()).isEqualTo(orchestration.getSpanContext());
        }
        assertThat(roots()).hasSize(3).allSatisfy(root -> {
            assertThat(root.getParentSpanContext().isValid()).isFalse();
            assertThat(root.getLinks())
                    .singleElement()
                    .satisfies(link -> assertThat(link.getSpanContext()).isEqualTo(orchestration.getSpanContext()));
            assertThat(root.getAttributes().get(AttributeKey.stringKey(TracingConstants.EVAL_RUN_ID)))
                    .isEqualTo(runId.toString());
        });
        final SpanData execution = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("execution"))
                .findFirst()
                .orElseThrow();
        final SpanData metric = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("metric"))
                .findFirst()
                .orElseThrow();
        assertThat(metric.getParentSpanId()).isEqualTo(execution.getParentSpanId());
        assertThat(roots()).anySatisfy(root -> {
            assertThat(root.getSpanId()).isEqualTo(metric.getParentSpanId());
            assertThat(root.getStartEpochNanos()).isLessThanOrEqualTo(execution.getStartEpochNanos());
            assertThat(root.getEndEpochNanos()).isGreaterThanOrEqualTo(metric.getEndEpochNanos());
        });
    }

    @Test
    @DisplayName("close is idempotent, releases retained roots, and late tasks cannot create spans")
    void lateAccessAfterClose() {
        final EvaluationTraceSession session = session();
        child(session, "execution", caseId, 0).end();
        session.close();
        session.close();
        assertThat(child(session, "late-metric", caseId, 0).getSpanContext().isValid())
                .isFalse();
        assertThat(child(session, "late-execution", UUID.randomUUID(), 1)
                        .getSpanContext()
                        .isValid())
                .isFalse();
        assertThat(roots()).hasSize(1);
        assertThat(ReflectionTestUtils.getField(session, "roots")).isEqualTo(Map.of());
        assertThat(exporter.getFinishedSpanItems()).noneMatch(s -> s.getName().startsWith("late"));
    }

    @Test
    @DisplayName("sessions isolate concurrent runs of the same case and repetition")
    void sessionsAreIsolated() {
        try (EvaluationTraceSession first = session();
                EvaluationTraceSession second = new EvaluationTraceSession(
                        telemetry, UUID.randomUUID(), suiteId, orchestration.getSpanContext())) {
            final Span a = child(first, "metric", caseId, 0);
            final Span b = child(second, "metric", caseId, 0);
            assertThat(a.getSpanContext().getTraceId())
                    .isNotEqualTo(b.getSpanContext().getTraceId());
            a.end();
            b.end();
        }
        assertThat(roots()).hasSize(2);
    }

    @Test
    @DisplayName("disabled tracing retains no roots and does not fabricate valid trace contexts")
    void disabledTracingIsNoOp() {
        try (EvaluationTraceSession session =
                new EvaluationTraceSession(OpenTelemetry.noop(), runId, suiteId, SpanContext.getInvalid())) {
            final Span span = session.startSpan(
                    OpenTelemetry.noop().getTracer("test").spanBuilder("execution"), caseId, "case", 0);
            assertThat(span.getSpanContext().isValid()).isFalse();
            assertThat(ReflectionTestUtils.getField(session, "roots")).isEqualTo(Map.of());
        }
        assertThat(roots()).isEmpty();
    }

    @Test
    @DisplayName("root creation racing with close is ended exactly once and cannot resurrect retained state")
    void creationAndCloseRace() throws Exception {
        final CountDownLatch creating = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final SpanProcessor blocker = new SpanProcessor() {
            @Override
            public boolean isStartRequired() {
                return true;
            }

            @Override
            public boolean isEndRequired() {
                return false;
            }

            @Override
            public void onEnd(ReadableSpan span) {}

            @Override
            public void onStart(Context parent, ReadWriteSpan span) {
                if (span.getName().equals(TracingConstants.SPAN_EVAL_TESTCASE)) {
                    creating.countDown();
                    try {
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
            }
        };
        try (OpenTelemetrySdk racing = OpenTelemetrySdk.builder()
                        .setTracerProvider(SdkTracerProvider.builder()
                                .addSpanProcessor(blocker)
                                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                                .build())
                        .build();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final EvaluationTraceSession session =
                    new EvaluationTraceSession(racing, runId, suiteId, orchestration.getSpanContext());
            final var started = executor.submit(
                    () -> session.startSpan(racing.getTracer("test").spanBuilder("execution"), caseId, "case", 0));
            assertThat(creating.await(5, TimeUnit.SECONDS)).isTrue();
            final var closed = executor.submit(session::close);
            release.countDown();
            started.get(5, TimeUnit.SECONDS).end();
            closed.get(5, TimeUnit.SECONDS);
            session.close();
            assertThat(roots()).hasSize(1);
            assertThat(ReflectionTestUtils.getField(session, "roots")).isEqualTo(Map.of());
            assertThat(child(session, "late", caseId, 0).getSpanContext().isValid())
                    .isFalse();
        } finally {
            release.countDown();
        }
    }
}
