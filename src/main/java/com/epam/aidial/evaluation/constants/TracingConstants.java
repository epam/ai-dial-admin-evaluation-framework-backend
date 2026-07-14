package com.epam.aidial.evaluation.constants;

/**
 * Constants for the observability/tracing bounded context: the OTel key names shared between span
 * attributes and OTel Baggage so that a single source of truth backs both.
 *
 * <p>{@code EVAL_RUN_ID}, {@code EVAL_SUITE_ID}, {@code TESTCASE_ID} and {@code RUN_INDEX} are set as
 * span attributes on run-scoped spans ({@code eval.testcase.execute}, {@code metric.tsmd.evaluate})
 * and, additionally, put into OTel Baggage so they are serialized into the outgoing {@code baggage}
 * header and propagate downstream (DIAL Core forwards it verbatim and can capture it in analytics).
 * The remaining keys ({@code TESTCASE_NAME}, {@code RESULT_ID}, {@code TSMD_NAME},
 * {@code TSMD_PROVIDER_ID}, {@code METRIC_DECLARATION_NAME}) are span attributes only.
 */
public final class TracingConstants {

    /** OTel key for the evaluation run id (UUID). Span attribute + baggage. */
    public static final String EVAL_RUN_ID = "eval.run.id";

    /** OTel key for the evaluation suite id (UUID). Span attribute + baggage. */
    public static final String EVAL_SUITE_ID = "eval.suite.id";

    /** OTel key for the test case id (UUID). Span attribute + baggage. */
    public static final String TESTCASE_ID = "testcase.id";

    /** OTel key for the zero-based run index within the run. Span attribute + baggage. */
    public static final String RUN_INDEX = "run.index";

    /** OTel key for the human-readable test case name. Span attribute only. */
    public static final String TESTCASE_NAME = "testcase.name";

    /** OTel key for the test case run result id (UUID). Span attribute only. */
    public static final String RESULT_ID = "result.id";

    /** OTel key for the test-suite metric definition (TSMD) name. Span attribute only. */
    public static final String TSMD_NAME = "tsmd.name";

    /** OTel key for the TSMD declaration provider id. Span attribute only. */
    public static final String TSMD_PROVIDER_ID = "tsmd.provider.id";

    /** OTel key for the metric declaration name from the provider. Span attribute only. */
    public static final String METRIC_DECLARATION_NAME = "metric.declaration.name";

    private TracingConstants() {}
}
