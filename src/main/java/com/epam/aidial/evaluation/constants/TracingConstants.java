package com.epam.aidial.evaluation.constants;

/**
 * Constants for the observability/tracing bounded context: the OTel key names shared between span
 * attributes and OTel Baggage so that a single source of truth backs both.
 *
 * <p>{@code EVAL_RUN_ID} and {@code EVAL_SUITE_ID} are set as span attributes on run-scoped spans
 * ({@code eval.testcase.execute}, {@code metric.tsmd.evaluate}) and, additionally, put into OTel
 * Baggage so they are serialized into the outgoing {@code baggage} header and propagate downstream
 * (DIAL Core forwards it verbatim and can capture it in analytics).
 */
public final class TracingConstants {

    /** OTel key for the evaluation run id (UUID). */
    public static final String EVAL_RUN_ID = "eval.run.id";

    /** OTel key for the evaluation suite id (UUID). */
    public static final String EVAL_SUITE_ID = "eval.suite.id";

    private TracingConstants() {}
}
