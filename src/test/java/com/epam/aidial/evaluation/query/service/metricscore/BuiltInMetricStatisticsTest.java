package com.epam.aidial.evaluation.query.service.metricscore;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.query.model.ComparisonNode;
import com.epam.aidial.evaluation.query.model.Expr;
import com.epam.aidial.evaluation.query.model.FilterNode;
import com.epam.aidial.evaluation.query.model.FnExpr;
import com.epam.aidial.evaluation.query.model.LogicalNode;
import com.epam.aidial.evaluation.query.model.ParamExpr;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.service.metricscore.BuiltInMetricStatistics.MetricStatistic;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the code-defined metric-score queries: every built-in statistic MUST be an aggregate
 * {@link StructuredQuery} over {@code eval_summaries} selecting a single {@code value}, run/computation
 * scoped, and reference {@code :metricField} (the default {@code overall} is the single metric's
 * average, so it too is {@code avg(:metricField)}).
 */
class BuiltInMetricStatisticsTest {

    private final BuiltInMetricStatistics builtIns = new BuiltInMetricStatistics();

    @Test
    void perMetricExposesTheFiveStatisticsInStableOrder() {
        assertThat(builtIns.perMetric())
                .extracting(MetricStatistic::name)
                .containsExactly("AVG", "P10", "P90", "MIN", "MAX");
    }

    @Test
    void everyPerMetricQueryAggregatesValueAndBindsMetricField() {
        for (final MetricStatistic statistic : builtIns.perMetric()) {
            final StructuredQuery query = statistic.query();
            assertAggregateValueQuery(query);
            assertThat(params(query.select().getFirst().expr()))
                    .as("%s binds :metricField", statistic.name())
                    .contains(MetricScoreConstants.PARAM_METRIC_FIELD);
        }
    }

    @Test
    void defaultOverallAggregatesValueAndBindsMetricField() {
        // The default overall is the single metric's average — avg(:metricField), bound by the executor.
        final StructuredQuery query = builtIns.defaultOverall();
        assertAggregateValueQuery(query);

        final Expr selectExpr = query.select().getFirst().expr();
        assertThat(selectExpr).isInstanceOf(FnExpr.class);
        assertThat(((FnExpr) selectExpr).name()).isEqualTo("avg");
        assertThat(params(selectExpr)).contains(MetricScoreConstants.PARAM_METRIC_FIELD);
    }

    /** Shared shape: aggregate over eval_summaries, single {@code value} alias, run/computation scoped. */
    private static void assertAggregateValueQuery(StructuredQuery query) {
        assertThat(query.entity()).isEqualTo(MetricScoreConstants.ENTITY_EVAL_SUMMARIES);
        assertThat(query.mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(query.select()).hasSize(1);
        assertThat(query.select().getFirst().as()).isEqualTo(MetricScoreConstants.VALUE_ALIAS);
        assertThat(params(query.filter()))
                .contains(MetricScoreConstants.PARAM_RUN_ID, MetricScoreConstants.PARAM_COMPUTATION_ID);
    }

    private static List<String> params(Expr expr) {
        final List<String> names = new ArrayList<>();
        collectParams(expr, names);
        return names;
    }

    private static List<String> params(FilterNode node) {
        final List<String> names = new ArrayList<>();
        switch (node) {
            case LogicalNode logical -> logical.args().forEach(child -> names.addAll(params(child)));
            case ComparisonNode comparison -> comparison.args().forEach(arg -> collectParams(arg, names));
            case null -> {}
        }
        return names;
    }

    private static void collectParams(Expr expr, List<String> names) {
        switch (expr) {
            case ParamExpr param -> names.add(param.name());
            case FnExpr fn -> fn.args().forEach(arg -> collectParams(arg, names));
            case null -> {}
            default -> {}
        }
    }
}
