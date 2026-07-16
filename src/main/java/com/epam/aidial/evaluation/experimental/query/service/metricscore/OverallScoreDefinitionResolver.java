package com.epam.aidial.evaluation.experimental.query.service.metricscore;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.CustomFunction;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.Mean;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.OverallScoreDefinition;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.WeightedMean;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.WeightedMetric;
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
 *   <li>{@link Mean} — {@code divide(add(coalesce(avg(f1), 0), coalesce(avg(f2), 0), ...), n)} over
 *       {@code metricFieldNames}, the run's currently discovered numeric metric fields (not anything
 *       persisted on the definition itself). A field missing from the run's data resolves to a SQL
 *       {@code NULL} average that is coalesced to {@code 0} for that term.
 *   <li>{@link WeightedMean} — {@code divide(add(multiply(w1, coalesce(avg(m1), 0)), ...), add(w1, ...))},
 *       built directly from the stored {@link WeightedMetric} list, independent of
 *       {@code metricFieldNames}. A missing metric's average is likewise coalesced to {@code 0} for its
 *       term rather than nulling the whole result.
 *   <li>{@link CustomFunction} — the stored raw expression, converted to a {@link StructuredQuery}
 *       verbatim (the caller supplies the full query, including its own run-scoping filter). NOT subject
 *       to the {@code mean}/{@code weighted_mean} null-to-zero coalescing.
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
     * Resolves {@code definition} into a {@link StructuredQuery}. Returns {@code null} when a
     * {@link CustomFunction}'s stored expression cannot be converted into a valid query (logged, not
     * thrown, so the run still completes).
     */
    public StructuredQuery resolve(OverallScoreDefinition definition, List<String> metricFieldNames) {
        return switch (definition) {
            case Mean _ -> builtInStatistics.aggregateSelecting(meanExpr(metricFieldNames));
            case WeightedMean weightedMean -> builtInStatistics.aggregateSelecting(weightedMeanExpr(weightedMean));
            case CustomFunction customFunction -> parseCustomFunction(customFunction);
        };
    }

    private Expr meanExpr(List<String> metricFieldNames) {
        final List<Expr> avgTerms =
                metricFieldNames.stream().<Expr>map(this::avg).toList();
        return new FnExpr(
                "divide",
                false,
                List.of(new FnExpr("add", false, avgTerms), decimal(BigDecimal.valueOf(metricFieldNames.size()))));
    }

    private Expr weightedMeanExpr(WeightedMean weightedMean) {
        final List<Expr> weightedTerms = weightedMean.weights().stream()
                .map(metric -> (Expr) new FnExpr(
                        "multiply", false, List.of(decimal(metric.weight()), avg(flattenedFieldName(metric)))))
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

    private FnExpr avg(String fieldName) {
        final FnExpr rawAvg = new FnExpr("avg", false, List.of(new FieldExpr(fieldName)));
        return new FnExpr("coalesce", false, List.of(rawAvg, decimal(BigDecimal.ZERO)));
    }

    private static String flattenedFieldName(WeightedMetric metric) {
        return EvalSummaryExportColumnConstants.METRIC_COLUMN_PREFIX
                + metric.metricName()
                + EvalSummaryExportColumnConstants.COLUMN_SEPARATOR
                + metric.outputField();
    }

    private static ValueExpr decimal(BigDecimal weight) {
        return new ValueExpr(ValueType.DECIMAL, weight.toPlainString());
    }
}
