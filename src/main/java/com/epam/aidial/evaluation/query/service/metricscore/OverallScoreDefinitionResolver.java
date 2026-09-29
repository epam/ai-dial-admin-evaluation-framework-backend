package com.epam.aidial.evaluation.query.service.metricscore;

import com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants;
import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.query.model.Expr;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.FnExpr;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.overallscore.CustomFunction;
import com.epam.aidial.evaluation.runner.dto.overallscore.Mean;
import com.epam.aidial.evaluation.runner.dto.overallscore.OverallScoreDefinition;
import com.epam.aidial.evaluation.runner.dto.overallscore.WeightedMean;
import com.epam.aidial.evaluation.runner.dto.overallscore.WeightedMetric;
import com.epam.aidial.evaluation.runner.model.MetricScoreAggregation;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves a suite's typed {@link OverallScoreDefinition} into a run/computation-scoped
 * {@link StructuredQuery}, composing from the DSL's {@code add}/{@code multiply}/{@code divide}/
 * {@code avg} functions — the composition is purely an implementation detail here, never exposed to a
 * caller as a dedicated DSL function.
 *
 * <ul>
 *   <li>{@link Mean}/{@link WeightedMean} — built directly against the {@code test_case_metric_scores}
 *       entity (see {@code test-case-metric-score-aggregation}), since we author these queries ourselves
 *       and always know exactly which table/fields to target: no need to build against
 *       {@code eval_summaries} and retarget afterward. {@link Mean} composes
 *       {@code divide(add(coalesce(avg(metric_scores::k1::avg), 0), ...), n)} over the run's currently
 *       discovered metric keys (not anything persisted on the definition itself); {@link WeightedMean}
 *       composes {@code divide(add(multiply(w1, coalesce(avg(metric_scores::k1::avg), 0)), ...), add(w1,
 *       ...))} directly from the stored {@link WeightedMetric} list. A metric key missing from a test
 *       case's (or, ungrouped, the run's) aggregated data resolves to a SQL {@code NULL} average that is
 *       coalesced to {@code 0} for that term — the same formula this class has always built, just now
 *       evaluated per test case (via {@code test_case_metric_scores}) instead of per raw row.
 *   <li>{@link CustomFunction} — the stored raw expression, converted to a {@link StructuredQuery}
 *       verbatim (the caller supplies the full query, including its own run-scoping filter), and executed
 *       against {@code eval_summaries} unchanged — an opaque, client-authored expression we don't control
 *       and don't retarget, an accepted out-of-scope weight-skew limitation (same as
 *       {@code BuiltInMetricStatistics}/{@code FilteredMetricScoreAggregator}). NOT subject to the
 *       {@code mean}/{@code weighted_mean} null-to-zero coalescing.
 * </ul>
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class OverallScoreDefinitionResolver {

    private final BuiltInMetricStatistics builtInStatistics;
    private final ObjectMapper objectMapper;

    /**
     * Resolves {@code definition} into a {@link StructuredQuery}. {@code metricKeys} (only consumed by
     * {@link Mean}) are metric keys in {@code <tsmdName>.<outputField>} form (i.e. {@link
     * MetricField#metricName()}'s own format), not flattened {@code metric::} field names. Returns
     * {@code null} when a {@link CustomFunction}'s stored expression cannot be converted into a valid
     * query (logged, not thrown, so the run still completes).
     */
    public StructuredQuery resolve(
            OverallScoreDefinition definition, List<String> metricKeys, MetricScoreAggregation aggregation) {
        return switch (definition) {
            case Mean _ ->
                builtInStatistics.aggregateSelecting(
                        MetricScoreConstants.ENTITY_TEST_CASE_METRIC_SCORES, meanExpr(metricKeys, aggregation));
            case WeightedMean weightedMean ->
                builtInStatistics.aggregateSelecting(
                        MetricScoreConstants.ENTITY_TEST_CASE_METRIC_SCORES,
                        weightedMeanExpr(weightedMean, aggregation));
            case CustomFunction customFunction -> parseCustomFunction(customFunction);
        };
    }

    private Expr meanExpr(List<String> metricKeys, MetricScoreAggregation aggregation) {
        final List<Expr> avgTerms =
                metricKeys.stream().<Expr>map(key -> avg(key, aggregation)).toList();
        return new FnExpr(
                "divide",
                false,
                List.of(new FnExpr("add", false, avgTerms), decimal(BigDecimal.valueOf(metricKeys.size()))));
    }

    private Expr weightedMeanExpr(WeightedMean weightedMean, MetricScoreAggregation aggregation) {
        final List<Expr> weightedTerms = weightedMean.weights().stream()
                .map(metric -> (Expr) new FnExpr(
                        "multiply", false, List.of(decimal(metric.weight()), avg(metricKey(metric), aggregation))))
                .toList();
        final List<Expr> weightTerms = weightedMean.weights().stream()
                .map(metric -> (Expr) decimal(metric.weight()))
                .toList();
        return new FnExpr(
                "divide",
                false,
                List.of(new FnExpr("add", false, weightedTerms), new FnExpr("add", false, weightTerms)));
    }

    private StructuredQuery parseCustomFunction(CustomFunction customFunction) {
        try {
            return objectMapper.convertValue(customFunction.expression(), StructuredQuery.class);
        } catch (JacksonException e) {
            log.warn("Skipping metric score 'overall': unparseable custom_function expression: {}", e.getMessage(), e);
            return null;
        }
    }

    /** {@code avg(metric_scores::<metricKey>::<leaf>)} for the chosen aggregation, coalesced to {@code 0} when the key is absent. */
    private FnExpr avg(String metricKey, MetricScoreAggregation aggregation) {
        final String fieldName = MetricScoreConstants.FIELD_METRIC_SCORES
                + EvalSummaryExportColumnConstants.COLUMN_SEPARATOR
                + metricKey
                + EvalSummaryExportColumnConstants.COLUMN_SEPARATOR
                + aggregation.leaf();
        final FnExpr rawAvg = new FnExpr("avg", false, List.of(new FieldExpr(fieldName)));
        return new FnExpr("coalesce", false, List.of(rawAvg, decimal(BigDecimal.ZERO)));
    }

    private static String metricKey(WeightedMetric metric) {
        return metric.metricName() + "." + metric.outputField();
    }

    private static ValueExpr decimal(BigDecimal weight) {
        return new ValueExpr(ValueType.DECIMAL, weight.toPlainString());
    }
}
