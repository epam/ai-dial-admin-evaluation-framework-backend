package com.epam.aidial.evaluation.utils;

import com.epam.aidial.evaluation.constants.TracingConstants;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import java.util.UUID;
import lombok.experimental.UtilityClass;

/**
 * Builds OTel Baggage carrying the eval run/suite ids, test case id, run index and phase (plus the result
 * id and metric name on the metric-evaluation path) for an execution scope.
 *
 * <p>Mirrors the {@link TraceContextUtils} static-helper pattern. Baggage lives in the OTel API and is
 * independent of the SDK, so entries are added to the context regardless of whether OTel is enabled; the
 * outgoing {@code baggage} header is emitted only when the tracing interceptor's propagator is active
 * (a no-op propagator is used when the SDK is disabled, so no header is written in the default config).
 *
 * <p>The returned {@link Baggage} is not made current here — callers combine it with their span into a
 * single {@code Context} (e.g. {@code Context.current().with(span).with(baggage)}) and open one
 * try-with-resources scope, so the entries are cleared when that scope closes and do not leak onto a
 * reused pooled/virtual thread.
 */
@UtilityClass
public class EvalBaggage {

    /**
     * Builds the test-case execution context ({@code eval.run.id}, {@code eval.suite.id},
     * {@code testcase.id}, {@code run.index} and {@code eval.phase=execution}) on top of the current OTel
     * Baggage. Used by {@code EvaluationWorker}; the result id is not yet available at this point (the
     * downstream call produces it), so it is not part of this baggage. Null ids/index are skipped (no
     * entry added), so the call never throws for missing values. All entries are non-sensitive
     * identifiers, safe to broadcast verbatim to downstream services.
     *
     * @param runId the evaluation run id, or {@code null} to skip
     * @param suiteId the evaluation suite id, or {@code null} to skip
     * @param testCaseId the test case id, or {@code null} to skip
     * @param runIndex the zero-based run index, or {@code null} to skip
     * @return the built baggage; combine it with the span into a single {@code Context} and make that
     *     current
     */
    public static Baggage withExecutionContext(UUID runId, UUID suiteId, UUID testCaseId, Integer runIndex) {
        return runContextBuilder(runId, suiteId, testCaseId, runIndex)
                .put(TracingConstants.EVAL_PHASE, TracingConstants.PHASE_EXECUTION)
                .build();
    }

    /**
     * Builds the metric-evaluation context on top of the current OTel Baggage. In addition to the shared
     * run/suite/testcase/run-index members it sets {@code eval.phase=metric-evaluation}, plus
     * {@code result.id} (so downstream telemetry can key back to the exact {@code TestCaseRunResult} row)
     * and {@code metric.declaration.name} (so judge-model spend can be attributed to a specific metric).
     * Used by {@code MetricEvaluationWorker}. Null values are skipped, so the call never throws for
     * missing values. All entries are non-sensitive identifiers, safe to broadcast verbatim to downstream
     * services.
     *
     * @param runId the evaluation run id, or {@code null} to skip
     * @param suiteId the evaluation suite id, or {@code null} to skip
     * @param testCaseId the test case id, or {@code null} to skip
     * @param runIndex the zero-based run index, or {@code null} to skip
     * @param resultId the test case run result id, or {@code null} to skip
     * @param metricDeclarationName the metric declaration name, or {@code null} to skip
     * @return the built baggage; combine it with the span into a single {@code Context} and make that
     *     current
     */
    public static Baggage withMetricContext(
            UUID runId, UUID suiteId, UUID testCaseId, Integer runIndex, UUID resultId, String metricDeclarationName) {
        BaggageBuilder builder = runContextBuilder(runId, suiteId, testCaseId, runIndex)
                .put(TracingConstants.EVAL_PHASE, TracingConstants.PHASE_METRIC_EVALUATION);
        if (resultId != null) {
            builder.put(TracingConstants.RESULT_ID, resultId.toString());
        }
        if (metricDeclarationName != null) {
            builder.put(TracingConstants.METRIC_DECLARATION_NAME, metricDeclarationName);
        }
        return builder.build();
    }

    private static BaggageBuilder runContextBuilder(UUID runId, UUID suiteId, UUID testCaseId, Integer runIndex) {
        BaggageBuilder builder = Baggage.current().toBuilder();
        if (runId != null) {
            builder.put(TracingConstants.EVAL_RUN_ID, runId.toString());
        }
        if (suiteId != null) {
            builder.put(TracingConstants.EVAL_SUITE_ID, suiteId.toString());
        }
        if (testCaseId != null) {
            builder.put(TracingConstants.TESTCASE_ID, testCaseId.toString());
        }
        if (runIndex != null) {
            builder.put(TracingConstants.RUN_INDEX, runIndex.toString());
        }
        return builder;
    }
}
