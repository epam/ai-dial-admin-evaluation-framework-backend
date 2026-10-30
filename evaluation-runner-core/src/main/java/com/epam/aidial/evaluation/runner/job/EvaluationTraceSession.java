package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.util.TracingConstants;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Run-owned tracing handles, never persisted. A repetition's root spans execution and metrics;
 * request/turn rows share that root. Synchronization serializes creation with close so late workers
 * cannot resurrect roots. No credentials or result bodies are retained.
 */
public final class EvaluationTraceSession implements AutoCloseable {
    private final Tracer tracer;
    private final UUID runId;
    private final UUID suiteId;
    private final SpanContext orchestration;
    private final Map<CaseKey, Span> roots = new HashMap<>();
    private boolean closed;

    public EvaluationTraceSession(OpenTelemetry telemetry, UUID runId, UUID suiteId, SpanContext orchestration) {
        this.tracer = telemetry.getTracer(TracingConstants.INSTRUMENTATION_SCOPE_NAME);
        this.runId = runId;
        this.suiteId = suiteId;
        this.orchestration = orchestration;
    }

    /** Starts a child with the caller's attributes already set (available to the sampler). */
    public synchronized Span startSpan(SpanBuilder builder, UUID testCaseId, String testCaseName, int runIndex) {
        if (closed) {
            return Span.getInvalid();
        }
        final CaseKey key = new CaseKey(testCaseId, runIndex);
        Span root = roots.get(key);
        if (root == null) {
            final SpanBuilder rootBuilder = tracer.spanBuilder(TracingConstants.SPAN_EVAL_TESTCASE)
                    .setNoParent()
                    .setAttribute(TracingConstants.EVAL_RUN_ID, runId.toString())
                    .setAttribute(TracingConstants.EVAL_SUITE_ID, suiteId.toString())
                    .setAttribute(TracingConstants.TESTCASE_ID, testCaseId.toString())
                    .setAttribute(TracingConstants.RUN_INDEX, Integer.toString(runIndex));
            if (testCaseName != null) {
                rootBuilder.setAttribute(TracingConstants.TESTCASE_NAME, testCaseName);
            }
            if (orchestration.isValid()) {
                rootBuilder.addLink(orchestration);
            }
            root = rootBuilder.startSpan();
            if (!root.getSpanContext().isValid()) {
                root.end();
                return Span.getInvalid();
            }
            roots.put(key, root);
        }
        return builder.setParent(Context.root().with(root)).startSpan();
    }

    /** Clear under the creation lock; SDK end/export runs outside the lock. */
    @Override
    public void close() {
        final List<Span> completed;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            completed = new ArrayList<>(roots.values());
            roots.clear();
        }
        completed.forEach(Span::end);
    }

    private record CaseKey(UUID testCaseId, int runIndex) {}
}
