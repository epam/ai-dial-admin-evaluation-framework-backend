package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.util.TracingConstants;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Run-owned tracing state, never persisted. Every test-case span it starts is the root of its own short
 * trace, linked (not parented) to the run orchestration span: execution spans cover Phase 1 of one
 * {@code (testCaseId, runIndex)}, metrics spans cover one result row's metric evaluations and are also
 * linked to that repetition's execution span. Only immutable {@link SpanContext}s are retained — no open
 * spans, credentials or result bodies — so nothing stays open for the run's duration.
 */
public final class EvaluationTraceSession implements AutoCloseable {

    private static final Attributes RUN_LINK = Attributes.builder()
            .put(TracingConstants.EVAL_LINK_TYPE, TracingConstants.LINK_TYPE_RUN)
            .build();
    private static final Attributes EXECUTION_LINK = Attributes.builder()
            .put(TracingConstants.EVAL_LINK_TYPE, TracingConstants.LINK_TYPE_EXECUTION)
            .build();

    private final SpanContext orchestration;
    private final Map<CaseKey, SpanContext> executions = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public EvaluationTraceSession(SpanContext orchestration) {
        this.orchestration = orchestration;
    }

    /**
     * Starts a repetition's execution root (caller's attributes already set, so samplers see them) and
     * remembers its context for later metrics links.
     */
    public Span startExecutionSpan(SpanBuilder builder, UUID testCaseId, int runIndex) {
        final Span span = linkedRoot(builder).startSpan();
        if (!closed && span.getSpanContext().isValid()) {
            executions.put(new CaseKey(testCaseId, runIndex), span.getSpanContext());
        }
        return span;
    }

    /**
     * Starts a result row's metrics root, linked to the run and, when this session executed the
     * repetition, to its execution span (absent for imported / metrics-only results).
     */
    public Span startMetricsSpan(SpanBuilder builder, UUID testCaseId, int runIndex) {
        final SpanBuilder root = linkedRoot(builder);
        final SpanContext execution = executions.get(new CaseKey(testCaseId, runIndex));
        if (execution != null) {
            root.addLink(execution, EXECUTION_LINK);
        }
        return root.startSpan();
    }

    /**
     * Releases remembered contexts. Spans started afterwards stay valid and linked to the run; a racing
     * put after the clear is harmless because the session is dropped with its run.
     */
    @Override
    public void close() {
        closed = true;
        executions.clear();
    }

    private SpanBuilder linkedRoot(SpanBuilder builder) {
        builder.setNoParent();
        if (orchestration.isValid()) {
            builder.addLink(orchestration, RUN_LINK);
        }
        return builder;
    }

    private record CaseKey(UUID testCaseId, int runIndex) {}
}
