package com.epam.aidial.evaluation.runner.job;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.model.suite.RequestExecutionSpec;
import com.epam.aidial.evaluation.runner.util.TracingConstants;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Wraps one request turn in an {@link TracingConstants#SPAN_EVAL_TESTCASE_TURN} span, a child of the current
 * (execution) span, so a repetition's trace groups each turn's outbound call under its own
 * request/turn-labelled node instead of listing them as flat siblings.
 */
@LogExecution
@Component
@RequiredArgsConstructor
public class TurnTracer {

    private final OpenTelemetry openTelemetry;

    /**
     * Runs {@code body} with a turn span current. {@code failureOf} maps the body's result to a {@link
     * TurnFailure} (null when the turn succeeded): its short description becomes the ERROR status and its
     * details, when present, a {@link TracingConstants#EVENT_TURN_FAILURE} event. A thrown exception is
     * recorded and rethrown.
     */
    public <T> T inTurnSpan(
            RequestExecutionSpec spec,
            int turnIndex,
            int totalTurns,
            Supplier<T> body,
            Function<T, TurnFailure> failureOf) {
        final Span span = buildSpan(spec, turnIndex, totalTurns).startSpan();
        try (Scope _ = span.makeCurrent()) {
            final T result = body.get();
            final TurnFailure failure = failureOf.apply(result);
            if (failure != null) {
                markFailed(span, failure);
            }
            return result;
        } catch (RuntimeException e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, String.valueOf(e.getMessage()));
            throw e;
        } finally {
            span.end();
        }
    }

    private static void markFailed(Span span, TurnFailure failure) {
        span.setStatus(StatusCode.ERROR, failure.description());
        if (failure.details() != null) {
            span.addEvent(
                    TracingConstants.EVENT_TURN_FAILURE,
                    Attributes.of(AttributeKey.stringKey(TracingConstants.TURN_FAILURE_DETAILS), failure.details()));
        }
    }

    private SpanBuilder buildSpan(RequestExecutionSpec spec, int turnIndex, int totalTurns) {
        final SpanBuilder builder = openTelemetry
                .getTracer(TracingConstants.INSTRUMENTATION_SCOPE_NAME)
                .spanBuilder(TracingConstants.SPAN_EVAL_TESTCASE_TURN)
                .setAttribute(TracingConstants.REQUEST_INDEX, String.valueOf(spec.requestIndex()))
                .setAttribute(TracingConstants.REQUEST_TOTAL, String.valueOf(spec.totalRequests()))
                .setAttribute(TracingConstants.TURN_INDEX, String.valueOf(turnIndex))
                .setAttribute(TracingConstants.TURN_TOTAL, String.valueOf(totalTurns))
                .setAttribute(TracingConstants.EVAL_PHASE, TracingConstants.PHASE_EXECUTION);
        if (spec.name() != null) {
            builder.setAttribute(TracingConstants.REQUEST_NAME, spec.name());
        }
        return builder;
    }

    /** Why a turn aborted: a short, stable {@code description} plus optional verbose {@code details}. */
    public record TurnFailure(String description, String details) {}
}
