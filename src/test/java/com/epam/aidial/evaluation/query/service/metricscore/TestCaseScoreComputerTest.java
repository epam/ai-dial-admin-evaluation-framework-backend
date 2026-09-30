package com.epam.aidial.evaluation.query.service.metricscore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.JsonMapperConfiguration;
import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.query.model.ArrayExpr;
import com.epam.aidial.evaluation.query.model.ComparisonNode;
import com.epam.aidial.evaluation.query.model.ComparisonOp;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.LogicalNode;
import com.epam.aidial.evaluation.query.model.LogicalOp;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.service.StructuredQueryService;
import com.epam.aidial.evaluation.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.query.service.translate.StructuredQueryBuilder;
import com.epam.aidial.evaluation.runner.dto.overallscore.CustomFunction;
import com.epam.aidial.evaluation.runner.dto.overallscore.Mean;
import com.epam.aidial.evaluation.runner.dto.overallscore.WeightedMean;
import com.epam.aidial.evaluation.runner.dto.overallscore.WeightedMetric;
import com.epam.aidial.evaluation.runner.model.MetricScoreAggregation;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TestCaseScoreComputer")
class TestCaseScoreComputerTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMPUTATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TC_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final MetricField METRIC_ACCURACY = new MetricField("metric::Accuracy::score", "Accuracy.score");

    private final ObjectMapper objectMapper = new JsonMapperConfiguration().objectMapper();
    private final BuiltInMetricStatistics builtInStatistics = new BuiltInMetricStatistics();
    private final StructuredQueryService structuredQueryService = mock(StructuredQueryService.class);

    private final TestCaseScoreComputer computer = new TestCaseScoreComputer(
            new OverallScoreDefinitionResolver(builtInStatistics, objectMapper), structuredQueryService);

    @Test
    @DisplayName("Should return an empty map without executing when definition is null")
    void nullDefinitionShortCircuits() {
        Map<UUID, Double> result = computer.computeByTestCase(
                null, List.of(METRIC_ACCURACY), MetricScoreAggregation.AVG, RUN_ID, COMPUTATION_ID, List.of(TC_A));

        assertThat(result).isEmpty();
        verify(structuredQueryService, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Should return an empty map without executing when testCaseIds is empty")
    void emptyTestCaseIdsShortCircuits() {
        Map<UUID, Double> result = computer.computeByTestCase(
                new Mean(), List.of(METRIC_ACCURACY), MetricScoreAggregation.AVG, RUN_ID, COMPUTATION_ID, List.of());

        assertThat(result).isEmpty();
        verify(structuredQueryService, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Mean: grafts test_case_id/GROUP BY onto the query built directly against "
            + "test_case_metric_scores, and reads each test case's value from the grouped result")
    void meanGraftsTestCaseIdOntoDirectlyBuiltQuery() {
        stubResult(Map.of("test_case_id", TC_A.toString(), MetricScoreConstants.VALUE_ALIAS, 0.7));

        Map<UUID, Double> result = computer.computeByTestCase(
                new Mean(),
                List.of(METRIC_ACCURACY),
                MetricScoreAggregation.AVG,
                RUN_ID,
                COMPUTATION_ID,
                List.of(TC_A));

        assertThat(result).containsEntry(TC_A, 0.7);
        StructuredQuery executed = capturedQuery();
        assertThat(executed.entity()).isEqualTo("test_case_metric_scores");
        assertThat(executed.groupBy()).containsExactly("test_case_id");
        assertThat(executed.select()).hasSize(2);
        assertThat(executed.select().get(0).expr()).isEqualTo(new FieldExpr("test_case_id"));
        assertThat(executed.select().get(1).as()).isEqualTo(MetricScoreConstants.VALUE_ALIAS);
        assertThat(executed.filter()).isInstanceOfSatisfying(LogicalNode.class, node -> {
            assertThat(node.op()).isEqualTo(LogicalOp.AND);
            assertThat(node.args())
                    .anySatisfy(arg -> assertThat(arg).isInstanceOfSatisfying(ComparisonNode.class, cmp -> {
                        assertThat(cmp.op()).isEqualTo(ComparisonOp.IN);
                        assertThat(cmp.args().getFirst()).isEqualTo(new FieldExpr("test_case_id"));
                    }));
        });
    }

    @Test
    @DisplayName("Mean: a test case whose aggregate is SQL NULL maps to a null score")
    void meanWithNullAggregateYieldsNullScore() {
        stubResult(Map.of("test_case_id", TC_A.toString()));

        Map<UUID, Double> result = computer.computeByTestCase(
                new Mean(),
                List.of(METRIC_ACCURACY),
                MetricScoreAggregation.AVG,
                RUN_ID,
                COMPUTATION_ID,
                List.of(TC_A));

        assertThat(result).containsEntry(TC_A, null);
    }

    @Test
    @DisplayName("Mean: chunks testCaseIds larger than the translator's MAX_LIMIT into multiple queries")
    void chunksTestCaseIdsExceedingMaxLimit() {
        List<UUID> testCaseIds = IntStream.range(0, StructuredQueryBuilder.MAX_LIMIT + 50)
                .mapToObj(i -> UUID.randomUUID())
                .toList();

        when(structuredQueryService.execute(any(), any())).thenAnswer(invocation -> {
            StructuredQuery query = invocation.getArgument(0);
            LogicalNode filter = (LogicalNode) query.filter();
            ComparisonNode inNode = filter.args().stream()
                    .filter(ComparisonNode.class::isInstance)
                    .map(ComparisonNode.class::cast)
                    .filter(cmp -> cmp.op() == ComparisonOp.IN)
                    .findFirst()
                    .orElseThrow();
            ArrayExpr idList = (ArrayExpr) inNode.args().get(1);
            String firstId = ((ValueExpr) idList.items().getFirst()).value();
            return new QueryResultPage(
                    List.of(Map.of("test_case_id", firstId, MetricScoreConstants.VALUE_ALIAS, 0.5)), null);
        });

        Map<UUID, Double> result = computer.computeByTestCase(
                new Mean(), List.of(METRIC_ACCURACY), MetricScoreAggregation.AVG, RUN_ID, COMPUTATION_ID, testCaseIds);

        verify(structuredQueryService, times(2)).execute(any(), any());
        assertThat(result)
                .hasSize(2)
                .containsEntry(testCaseIds.getFirst(), 0.5)
                .containsEntry(testCaseIds.get(StructuredQueryBuilder.MAX_LIMIT), 0.5);
    }

    @Test
    @DisplayName("WeightedMean: grafts the same way, reading the combined value from the grouped result")
    void weightedMeanGraftsOntoDirectlyBuiltQuery() {
        stubResult(Map.of("test_case_id", TC_A.toString(), MetricScoreConstants.VALUE_ALIAS, 0.6));

        Map<UUID, Double> result = computer.computeByTestCase(
                new WeightedMean(List.of(
                        new WeightedMetric("Accuracy", "score", BigDecimal.valueOf(2)),
                        new WeightedMetric("Relevancy", "score", BigDecimal.ONE))),
                List.of(),
                MetricScoreAggregation.AVG,
                RUN_ID,
                COMPUTATION_ID,
                List.of(TC_A));

        assertThat(result).containsEntry(TC_A, 0.6);
        assertThat(capturedQuery().entity()).isEqualTo("test_case_metric_scores");
    }

    @Test
    @DisplayName("Should return an empty map without executing when definition is a CustomFunction (rejected by "
            + "suite validation; this is a defensive fallback only)")
    void customFunctionYieldsEmptyMapDefensively() {
        Map<UUID, Double> result = computer.computeByTestCase(
                new CustomFunction(Map.of("entity", "eval_summaries", "mode", "aggregate")),
                List.of(METRIC_ACCURACY),
                MetricScoreAggregation.AVG,
                RUN_ID,
                COMPUTATION_ID,
                List.of(TC_A));

        assertThat(result).isEmpty();
        verify(structuredQueryService, never()).execute(any(), any());
    }

    // ----- helpers -----

    private void stubResult(Map<String, Object> row) {
        when(structuredQueryService.execute(any(), any())).thenReturn(new QueryResultPage(List.of(row), null));
    }

    private StructuredQuery capturedQuery() {
        ArgumentCaptor<StructuredQuery> captor = ArgumentCaptor.forClass(StructuredQuery.class);
        verify(structuredQueryService).execute(captor.capture(), any());
        return captor.getValue();
    }
}
