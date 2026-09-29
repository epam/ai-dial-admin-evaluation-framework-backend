package com.epam.aidial.evaluation.query.service.metricscore;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.JsonMapperConfiguration;
import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.query.model.Expr;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.FnExpr;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.runner.dto.overallscore.CustomFunction;
import com.epam.aidial.evaluation.runner.dto.overallscore.Mean;
import com.epam.aidial.evaluation.runner.dto.overallscore.WeightedMean;
import com.epam.aidial.evaluation.runner.dto.overallscore.WeightedMetric;
import com.epam.aidial.evaluation.runner.model.MetricScoreAggregation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@DisplayName("OverallScoreDefinitionResolver")
class OverallScoreDefinitionResolverTest {

    private final BuiltInMetricStatistics builtInStatistics = new BuiltInMetricStatistics();
    private final ObjectMapper objectMapper = new JsonMapperConfiguration().objectMapper();
    private final OverallScoreDefinitionResolver resolver =
            new OverallScoreDefinitionResolver(builtInStatistics, objectMapper);

    @Test
    @DisplayName("Mean composes divide(add(avg(f1), avg(f2)), 2) over test_case_metric_scores, directly, for "
            + "the run's discovered metric keys")
    void resolvesMeanOverTwoFields() {
        StructuredQuery result =
                resolver.resolve(new Mean(), List.of("A.score", "B.score"), MetricScoreAggregation.AVG);

        Expr expected = new FnExpr(
                "divide",
                false,
                List.of(new FnExpr("add", false, List.of(avg("A.score"), avg("B.score"))), decimal("2")));
        assertThat(result)
                .isEqualTo(builtInStatistics.aggregateSelecting(
                        MetricScoreConstants.ENTITY_TEST_CASE_METRIC_SCORES, expected));
    }

    @Test
    @DisplayName("Mean degenerates to a single metric's average for a single-field run")
    void resolvesMeanOverSingleField() {
        StructuredQuery result = resolver.resolve(new Mean(), List.of("A.score"), MetricScoreAggregation.AVG);

        Expr expected =
                new FnExpr("divide", false, List.of(new FnExpr("add", false, List.of(avg("A.score"))), decimal("1")));
        assertThat(result)
                .isEqualTo(builtInStatistics.aggregateSelecting(
                        MetricScoreConstants.ENTITY_TEST_CASE_METRIC_SCORES, expected));
    }

    @Test
    @DisplayName("WeightedMean composes divide(add(multiply(w, avg(m)), ...), add(w, ...)) over "
            + "test_case_metric_scores, combining duplicate terms")
    void resolvesWeightedMeanWithDuplicateTerm() {
        WeightedMean weightedMean = new WeightedMean(List.of(
                new WeightedMetric("A", "score", new BigDecimal("1.0")),
                new WeightedMetric("A", "score", new BigDecimal("1.0")),
                new WeightedMetric("B", "score", new BigDecimal("2.0"))));

        StructuredQuery result = resolver.resolve(weightedMean, List.of(), MetricScoreAggregation.AVG);

        Expr avgA = avg("A.score");
        Expr avgB = avg("B.score");
        Expr expected = new FnExpr(
                "divide",
                false,
                List.of(
                        new FnExpr(
                                "add",
                                false,
                                List.of(
                                        new FnExpr("multiply", false, List.of(decimal("1.0"), avgA)),
                                        new FnExpr("multiply", false, List.of(decimal("1.0"), avgA)),
                                        new FnExpr("multiply", false, List.of(decimal("2.0"), avgB)))),
                        new FnExpr("add", false, List.of(decimal("1.0"), decimal("1.0"), decimal("2.0")))));
        assertThat(result)
                .isEqualTo(builtInStatistics.aggregateSelecting(
                        MetricScoreConstants.ENTITY_TEST_CASE_METRIC_SCORES, expected));
    }

    @Test
    @DisplayName("CustomFunction converts the raw expression Map into the equivalent StructuredQuery")
    void resolvesCustomFunction() {
        String json = "{\"entity\":\"eval_summaries\",\"mode\":\"aggregate\",\"select\":[{\"expr\":{\"type\":\"fn\","
                + "\"name\":\"avg\",\"args\":[{\"type\":\"field\",\"name\":\"metric::A::score\"}]},\"as\":\"value\"}]}";
        Map<String, Object> expression = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});

        StructuredQuery result =
                resolver.resolve(new CustomFunction(expression), List.of(), MetricScoreAggregation.AVG);

        assertThat(result).isEqualTo(objectMapper.readValue(json, StructuredQuery.class));
    }

    @Test
    @DisplayName("CustomFunction with an unparseable expression is logged and resolves to null")
    void rejectsMalformedCustomFunction() {
        CustomFunction customFunction = new CustomFunction(Map.of("entity", "eval_summaries", "select", "not-a-list"));

        StructuredQuery result = resolver.resolve(customFunction, List.of(), MetricScoreAggregation.AVG);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Mean reads the min/max leaf of the aggregated metric scores when that aggregation is chosen")
    void resolvesMeanOverChosenLeaf() {
        for (MetricScoreAggregation aggregation : List.of(MetricScoreAggregation.MIN, MetricScoreAggregation.MAX)) {
            StructuredQuery result = resolver.resolve(new Mean(), List.of("A.score"), aggregation);

            FnExpr rawAgg =
                    new FnExpr("avg", false, List.of(new FieldExpr("metric_scores::A.score::" + aggregation.leaf())));
            Expr term = new FnExpr("coalesce", false, List.of(rawAgg, decimal("0")));
            Expr expected = new FnExpr("divide", false, List.of(new FnExpr("add", false, List.of(term)), decimal("1")));
            assertThat(result)
                    .isEqualTo(builtInStatistics.aggregateSelecting(
                            MetricScoreConstants.ENTITY_TEST_CASE_METRIC_SCORES, expected));
        }
    }

    private static FnExpr avg(String metricKey) {
        FnExpr rawAvg = new FnExpr(
                "avg",
                false,
                List.of(new FieldExpr("metric_scores::" + metricKey + "::" + MetricScoreAggregation.AVG.leaf())));
        return new FnExpr("coalesce", false, List.of(rawAvg, decimal("0")));
    }

    private static ValueExpr decimal(String value) {
        return new ValueExpr(ValueType.DECIMAL, value);
    }
}
