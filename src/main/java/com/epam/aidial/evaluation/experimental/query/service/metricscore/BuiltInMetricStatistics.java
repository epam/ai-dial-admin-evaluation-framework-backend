package com.epam.aidial.evaluation.experimental.query.service.metricscore;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonNode;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonOp;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FilterNode;
import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
import com.epam.aidial.evaluation.experimental.query.model.LogicalNode;
import com.epam.aidial.evaluation.experimental.query.model.LogicalOp;
import com.epam.aidial.evaluation.experimental.query.model.OutputColumn;
import com.epam.aidial.evaluation.experimental.query.model.ParamExpr;
import com.epam.aidial.evaluation.experimental.query.model.QueryMode;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The built-in metric-score queries, constructed in code as typed {@link StructuredQuery} objects (no
 * DB table, no seed migration). Phase 3 runs:
 *
 * <ul>
 *   <li><b>Per-metric statistics</b> ({@link #perMetric()}: AVG/P10/P90/MIN/MAX) — each executed once
 *       per numeric metric field, with {@code :metricField} bound by the executor.
 *   <li>The <b>default {@code overall}</b> ({@link #defaultOverall()}) — {@code avg(:metricField)},
 *       used when a suite has no custom {@code overall_score}. The default is only computed for a
 *       single-metric run, so {@code overall} is that one metric's average; the executor binds
 *       {@code :metricField} to the single field. (A custom per-suite {@code overall_score} is a
 *       self-contained expression referencing the real configured metric columns, run only with the
 *       run-scoping params — it does not go through this default.)
 * </ul>
 *
 * <p>Every query selects a single {@link MetricScoreConstants#VALUE_ALIAS value} aggregate over the
 * {@code eval_summaries} entity, scoped to the run/computation via the {@code :runId}/{@code :computationId}
 * params. A statistic's {@code name} is the persisted {@code metric_score_name}.
 */
@Component
@LogExecution
public class BuiltInMetricStatistics {

    private static final String FN_AVG = "avg";
    private static final String FN_MIN = "min";
    private static final String FN_MAX = "max";
    private static final String FN_PERCENTILE_CONT = "percentile_cont";

    private final List<MetricStatistic> perMetric = List.of(
            new MetricStatistic("AVG", aggregate(fn(FN_AVG, metricField()))),
            new MetricStatistic("P10", aggregate(fn(FN_PERCENTILE_CONT, decimal("0.1"), metricField()))),
            new MetricStatistic("P90", aggregate(fn(FN_PERCENTILE_CONT, decimal("0.9"), metricField()))),
            new MetricStatistic("MIN", aggregate(fn(FN_MIN, metricField()))),
            new MetricStatistic("MAX", aggregate(fn(FN_MAX, metricField()))));

    private final StructuredQuery defaultOverall = aggregate(fn(FN_AVG, metricField()));

    /** The per-metric statistics (AVG/P10/P90/MIN/MAX), in stable order; each binds {@code :metricField}. */
    public List<MetricStatistic> perMetric() {
        return perMetric;
    }

    /**
     * The default run-level {@code overall} query — {@code avg(:metricField)}. Computed only for a
     * single-metric run, so the executor binds {@code :metricField} to that one field.
     */
    public StructuredQuery defaultOverall() {
        return defaultOverall;
    }

    /** A built-in statistic: its persisted {@code metric_score_name} and the query computing it. */
    public record MetricStatistic(String name, StructuredQuery query) {}

    /**
     * Public entry point for other Phase-3 collaborators (e.g. {@link OverallScoreDefinitionResolver})
     * that need to build a run/computation-scoped aggregate query around their own select expression,
     * without duplicating the run-scoping filter construction.
     */
    public StructuredQuery aggregateSelecting(Expr selectExpr) {
        return aggregate(selectExpr);
    }

    /** Aggregate query selecting a single {@code value} over {@code eval_summaries}, run/computation scoped. */
    private static StructuredQuery aggregate(Expr selectExpr) {
        return new StructuredQuery(
                MetricScoreConstants.ENTITY_EVAL_SUMMARIES,
                runScopedFilter(),
                QueryMode.AGGREGATE,
                false,
                List.of(new OutputColumn(selectExpr, MetricScoreConstants.VALUE_ALIAS)),
                null,
                null,
                null,
                null);
    }

    /** {@code test_suite_run_id eq :runId AND computation_id eq :computationId}. */
    private static FilterNode runScopedFilter() {
        return new LogicalNode(
                LogicalOp.AND,
                List.of(
                        eq(MetricScoreConstants.FIELD_TEST_SUITE_RUN_ID, MetricScoreConstants.PARAM_RUN_ID),
                        eq(MetricScoreConstants.FIELD_COMPUTATION_ID, MetricScoreConstants.PARAM_COMPUTATION_ID)));
    }

    private static FilterNode eq(String fieldName, String paramName) {
        return new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr(fieldName), param(paramName)));
    }

    private static FnExpr fn(String name, Expr... args) {
        return new FnExpr(name, false, List.of(args));
    }

    private static ParamExpr param(String name) {
        return new ParamExpr(name);
    }

    private static ParamExpr metricField() {
        return param(MetricScoreConstants.PARAM_METRIC_FIELD);
    }

    private static ValueExpr decimal(String value) {
        return new ValueExpr(ValueType.DECIMAL, value);
    }
}
