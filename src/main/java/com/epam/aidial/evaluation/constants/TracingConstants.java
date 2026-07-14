package com.epam.aidial.evaluation.constants;

/**
 * Constants for the observability/tracing bounded context: the OTel instrumentation-scope name, span
 * names, and the key names shared between span attributes and OTel Baggage, so that a single source of
 * truth backs all tracing strings emitted by the service.
 *
 * <p>{@code EVAL_RUN_ID}, {@code EVAL_SUITE_ID}, {@code TESTCASE_ID} and {@code RUN_INDEX} are set as
 * span attributes on run-scoped spans ({@link #SPAN_EVAL_TESTCASE_EXECUTE},
 * {@link #SPAN_METRIC_TSMD_EVALUATE}) and, additionally, put into OTel Baggage so they are serialized
 * into the outgoing {@code baggage} header and propagate downstream (DIAL Core forwards it verbatim and
 * can capture it in analytics). The remaining keys ({@code TESTCASE_NAME}, {@code RESULT_ID},
 * {@code TSMD_NAME}, {@code TSMD_PROVIDER_ID}, {@code METRIC_DECLARATION_NAME}, {@code MCP_TOOL_NAME})
 * are span attributes only.
 */
public final class TracingConstants {

    /** OTel instrumentation-scope (tracer) name used for all spans created by the service. */
    public static final String INSTRUMENTATION_SCOPE_NAME = "com.epam.aidial.evaluation";

    // ---- Span names ----

    /** Span name for a single test case execution in {@code EvaluationWorker}. */
    public static final String SPAN_EVAL_TESTCASE_EXECUTE = "eval.testcase.execute";

    /** Span name for a single TSMD evaluation in {@code MetricEvaluationWorker}. */
    public static final String SPAN_METRIC_TSMD_EVALUATE = "metric.tsmd.evaluate";

    /** Span name for a try-it-out deployment invocation. */
    public static final String SPAN_TRY_IT_OUT_INVOKE = "try-it-out.invoke";

    /** Span name for a try-it-out MCP tool invocation. */
    public static final String SPAN_TRY_IT_OUT_MCP_INVOKE = "try-it-out.mcp.invoke";

    // ---- Span attribute / baggage keys ----

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

    /** OTel key for the MCP tool name on try-it-out MCP spans. Span attribute only. */
    public static final String MCP_TOOL_NAME = "mcp.tool.name";

    private TracingConstants() {}
}
