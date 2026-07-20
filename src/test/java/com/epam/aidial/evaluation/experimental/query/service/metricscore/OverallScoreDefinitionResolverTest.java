package com.epam.aidial.evaluation.experimental.query.service.metricscore;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.JsonMapperConfiguration;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.CustomFunction;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.Mean;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.WeightedMean;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.WeightedMetric;
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
    @DisplayName("Mean composes divide(add(avg(f1), avg(f2)), 2) over the run's discovered metric fields")
    void resolvesMeanOverTwoFields() {
        StructuredQuery result = resolver.resolve(new Mean(), List.of("metric::A::score", "metric::B::score"));

        Expr expected = new FnExpr(
                "divide",
                false,
                List.of(
                        new FnExpr("add", false, List.of(avg("metric::A::score"), avg("metric::B::score"))),
                        decimal("2")));
        assertThat(result).isEqualTo(builtInStatistics.aggregateSelecting(expected));
    }

    @Test
    @DisplayName("Mean degenerates to a single metric's average for a single-field run")
    void resolvesMeanOverSingleField() {
        StructuredQuery result = resolver.resolve(new Mean(), List.of("metric::A::score"));

        Expr expected = new FnExpr(
                "divide", false, List.of(new FnExpr("add", false, List.of(avg("metric::A::score"))), decimal("1")));
        assertThat(result).isEqualTo(builtInStatistics.aggregateSelecting(expected));
    }

    @Test
    @DisplayName("WeightedMean composes divide(add(multiply(w, avg(m)), ...), add(w, ...)), combining duplicate terms")
    void resolvesWeightedMeanWithDuplicateTerm() {
        WeightedMean weightedMean = new WeightedMean(List.of(
                new WeightedMetric("A", "score", new BigDecimal("1.0")),
                new WeightedMetric("A", "score", new BigDecimal("1.0")),
                new WeightedMetric("B", "score", new BigDecimal("2.0"))));

        StructuredQuery result = resolver.resolve(weightedMean, List.of());

        Expr avgA = avg("metric::A::score");
        Expr avgB = avg("metric::B::score");
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
        assertThat(result).isEqualTo(builtInStatistics.aggregateSelecting(expected));
    }

    @Test
    @DisplayName("CustomFunction converts the raw expression Map into the equivalent StructuredQuery")
    void resolvesCustomFunction() {
        String json = "{\"entity\":\"eval_summaries\",\"mode\":\"aggregate\",\"select\":[{\"expr\":{\"type\":\"fn\","
                + "\"name\":\"avg\",\"args\":[{\"type\":\"field\",\"name\":\"metric::A::score\"}]},\"as\":\"value\"}]}";
        Map<String, Object> expression = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});

        StructuredQuery result = resolver.resolve(new CustomFunction(expression), List.of());

        assertThat(result).isEqualTo(objectMapper.readValue(json, StructuredQuery.class));
    }

    @Test
    @DisplayName("CustomFunction with an unparseable expression is logged and resolves to null")
    void rejectsMalformedCustomFunction() {
        CustomFunction customFunction = new CustomFunction(Map.of("entity", "eval_summaries", "select", "not-a-list"));

        StructuredQuery result = resolver.resolve(customFunction, List.of());

        assertThat(result).isNull();
    }

    private static FnExpr avg(String fieldName) {
        FnExpr rawAvg = new FnExpr("avg", false, List.of(new FieldExpr(fieldName)));
        return new FnExpr("coalesce", false, List.of(rawAvg, decimal("0")));
    }

    private static ValueExpr decimal(String value) {
        return new ValueExpr(ValueType.DECIMAL, value);
    }
}
