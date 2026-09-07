package com.epam.aidial.evaluation.query.service.metricscore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
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
import com.epam.aidial.evaluation.query.model.OffsetPage;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.service.StructuredQueryService;
import com.epam.aidial.evaluation.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.query.service.translate.StructuredQueryBuilder;
import com.epam.aidial.evaluation.runner.dto.overallscore.CustomFunction;
import com.epam.aidial.evaluation.runner.dto.overallscore.Mean;
import com.epam.aidial.evaluation.runner.dto.overallscore.WeightedMean;
import com.epam.aidial.evaluation.runner.dto.overallscore.WeightedMetric;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@DisplayName("EvalSummaryRowScoreComputer")
class EvalSummaryRowScoreComputerTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMPUTATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ROW_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ROW_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private final BuiltInMetricStatistics builtInStatistics = new BuiltInMetricStatistics();
    private final ObjectMapper objectMapper = new JsonMapperConfiguration().objectMapper();
    private final StructuredQueryService structuredQueryService = mock(StructuredQueryService.class);

    private final EvalSummaryRowScoreComputer computer = new EvalSummaryRowScoreComputer(
            new OverallScoreDefinitionResolver(builtInStatistics, objectMapper), structuredQueryService);

    @Test
    @DisplayName("Should return an empty map without executing when definition is null")
    void nullDefinitionShortCircuits() {
        Map<UUID, Double> result =
                computer.computeBatch(null, List.of("metric::A::score"), RUN_ID, COMPUTATION_ID, List.of(ROW_A));

        assertThat(result).isEmpty();
        verify(structuredQueryService, never()).execute(any(), anyMap());
    }

    @Test
    @DisplayName("Should return an empty map without executing when rowIds is empty")
    void emptyRowIdsShortCircuits() {
        Map<UUID, Double> result =
                computer.computeBatch(new Mean(), List.of("metric::A::score"), RUN_ID, COMPUTATION_ID, List.of());

        assertThat(result).isEmpty();
        verify(structuredQueryService, never()).execute(any(), anyMap());
    }

    @Test
    @DisplayName("Should graft id as a select column and GROUP BY id, ANDing id IN (...) onto the filter")
    void graftsIdSelectAndGroupBy() {
        stubRows(Map.of(ROW_A.toString(), 0.7));

        computer.computeBatch(new Mean(), List.of("metric::A::score"), RUN_ID, COMPUTATION_ID, List.of(ROW_A));

        StructuredQuery executed = capturedQuery();
        assertThat(executed.groupBy()).containsExactly("id");
        assertThat(executed.select()).hasSize(2);
        assertThat(executed.select().getFirst().expr()).isEqualTo(new FieldExpr("id"));
        assertThat(executed.select().getFirst().as()).isEqualTo("id");
        assertThat(executed.filter()).isInstanceOfSatisfying(LogicalNode.class, node -> {
            assertThat(node.op()).isEqualTo(LogicalOp.AND);
            assertThat(node.args())
                    .anySatisfy(arg -> assertThat(arg).isInstanceOfSatisfying(ComparisonNode.class, cmp -> {
                        assertThat(cmp.op()).isEqualTo(ComparisonOp.IN);
                        assertThat(cmp.args().getFirst()).isEqualTo(new FieldExpr("id"));
                    }));
        });
    }

    @Test
    @DisplayName("Should set an explicit page sized to rowIds rather than passing through the resolved query's "
            + "(typically null) page, which would otherwise default to a 100-row limit and silently truncate")
    void setsExplicitPageSizedToRowIds() {
        stubRows(Map.of(ROW_A.toString(), 0.7, ROW_B.toString(), 0.5));

        computer.computeBatch(new Mean(), List.of("metric::A::score"), RUN_ID, COMPUTATION_ID, List.of(ROW_A, ROW_B));

        StructuredQuery executed = capturedQuery();
        assertThat(executed.page()).isEqualTo(new OffsetPage(0, 2, false));
    }

    @Test
    @DisplayName("Should chunk rowIds larger than the translator's MAX_LIMIT into multiple queries, merging "
            + "all chunks' results rather than silently dropping the excess rows")
    void chunksRowIdsExceedingMaxLimit() {
        List<UUID> rowIds = IntStream.range(0, StructuredQueryBuilder.MAX_LIMIT + 50)
                .mapToObj(i -> UUID.randomUUID())
                .toList();

        // Each execute() call returns exactly one scored row: the first id of whatever chunk it received,
        // proving every chunk actually executes rather than only the first MAX_LIMIT rows.
        when(structuredQueryService.execute(any(), anyMap())).thenAnswer(invocation -> {
            StructuredQuery query = invocation.getArgument(0);
            LogicalNode filter = (LogicalNode) query.filter();
            ComparisonNode inNode = filter.args().stream()
                    .filter(ComparisonNode.class::isInstance)
                    .map(ComparisonNode.class::cast)
                    .findFirst()
                    .orElseThrow();
            ArrayExpr idList = (ArrayExpr) inNode.args().get(1);
            String firstId = ((ValueExpr) idList.items().getFirst()).value();
            return new QueryResultPage(List.of(rowOf(firstId, 0.5)), null);
        });

        Map<UUID, Double> result =
                computer.computeBatch(new Mean(), List.of("metric::A::score"), RUN_ID, COMPUTATION_ID, rowIds);

        verify(structuredQueryService, times(2)).execute(any(), anyMap());
        assertThat(result)
                .hasSize(2)
                .containsEntry(rowIds.getFirst(), 0.5)
                .containsEntry(rowIds.get(StructuredQueryBuilder.MAX_LIMIT), 0.5);
    }

    @Test
    @DisplayName("Should map score per row id, including a row whose aggregate is itself SQL NULL")
    void mapsScorePerRowIncludingNull() {
        when(structuredQueryService.execute(any(), anyMap()))
                .thenReturn(new QueryResultPage(
                        List.of(rowOf(ROW_A.toString(), 0.7), rowOfNullValue(ROW_B.toString())), null));

        Map<UUID, Double> result = computer.computeBatch(
                new Mean(), List.of("metric::A::score"), RUN_ID, COMPUTATION_ID, List.of(ROW_A, ROW_B));

        assertThat(result).hasSize(2).containsEntry(ROW_A, 0.7).containsEntry(ROW_B, null);
    }

    @Test
    @DisplayName("Should omit a rowId that the query's result set does not contain")
    void omitsRowIdAbsentFromResultSet() {
        stubRows(Map.of(ROW_A.toString(), 0.7));

        Map<UUID, Double> result = computer.computeBatch(
                new Mean(), List.of("metric::A::score"), RUN_ID, COMPUTATION_ID, List.of(ROW_A, ROW_B));

        assertThat(result).containsOnlyKeys(ROW_A);
    }

    @Test
    @DisplayName("Should compute a per-row weighted mean score")
    void computesWeightedMean() {
        stubRows(Map.of(ROW_A.toString(), 0.6));

        Map<UUID, Double> result = computer.computeBatch(
                new WeightedMean(List.of(new WeightedMetric("Accuracy", "score", BigDecimal.ONE))),
                List.of(),
                RUN_ID,
                COMPUTATION_ID,
                List.of(ROW_A));

        assertThat(result).containsEntry(ROW_A, 0.6);
    }

    @Test
    @DisplayName("Should compute a well-formed CustomFunction, reading its own alias")
    void computesWellFormedCustomFunction() {
        when(structuredQueryService.execute(any(), anyMap()))
                .thenReturn(new QueryResultPage(List.of(Map.of("id", ROW_A.toString(), "myAlias", 0.42)), null));

        Map<UUID, Double> result = computer.computeBatch(
                customFunction("aggregate", "eval_summaries", "\"as\":\"myAlias\"", null),
                List.of(),
                RUN_ID,
                COMPUTATION_ID,
                List.of(ROW_A));

        assertThat(result).containsEntry(ROW_A, 0.42);
    }

    @Test
    @DisplayName("Should reject (empty map, no execute) a CustomFunction not in AGGREGATE mode")
    void rejectsNonAggregateCustomFunction() {
        Map<UUID, Double> result = computer.computeBatch(
                customFunction("row", "eval_summaries", "\"as\":\"value\"", null),
                List.of(),
                RUN_ID,
                COMPUTATION_ID,
                List.of(ROW_A));

        assertThat(result).isEmpty();
        verify(structuredQueryService, never()).execute(any(), anyMap());
    }

    @Test
    @DisplayName("Should reject a CustomFunction targeting a foreign entity")
    void rejectsForeignEntityCustomFunction() {
        Map<UUID, Double> result = computer.computeBatch(
                customFunction("aggregate", "test_suites", "\"as\":\"value\"", null),
                List.of(),
                RUN_ID,
                COMPUTATION_ID,
                List.of(ROW_A));

        assertThat(result).isEmpty();
        verify(structuredQueryService, never()).execute(any(), anyMap());
    }

    @Test
    @DisplayName("Should reject a CustomFunction whose select column has no alias")
    void rejectsCustomFunctionWithoutAlias() {
        Map<UUID, Double> result = computer.computeBatch(
                customFunction("aggregate", "eval_summaries", "\"as\":\"\"", null),
                List.of(),
                RUN_ID,
                COMPUTATION_ID,
                List.of(ROW_A));

        assertThat(result).isEmpty();
        verify(structuredQueryService, never()).execute(any(), anyMap());
    }

    @Test
    @DisplayName("Should reject a CustomFunction that already specifies its own groupBy, rather than overwriting it")
    void rejectsCustomFunctionWithExistingGroupBy() {
        Map<UUID, Double> result = computer.computeBatch(
                customFunction("aggregate", "eval_summaries", "\"as\":\"value\"", List.of("test_case_id")),
                List.of(),
                RUN_ID,
                COMPUTATION_ID,
                List.of(ROW_A));

        assertThat(result).isEmpty();
        verify(structuredQueryService, never()).execute(any(), anyMap());
    }

    @Test
    @DisplayName("Should return an empty map when the resolver cannot parse the CustomFunction")
    void unparseableCustomFunctionYieldsEmptyMap() {
        Map<UUID, Double> result = computer.computeBatch(
                new CustomFunction(Map.of("not", "a valid structured query shape at all", "entity", 123)),
                List.of(),
                RUN_ID,
                COMPUTATION_ID,
                List.of(ROW_A));

        assertThat(result).isEmpty();
        verify(structuredQueryService, never()).execute(any(), anyMap());
    }

    // ----- helpers -----

    private void stubRows(Map<String, Double> idToValue) {
        List<Map<String, Object>> rows = idToValue.entrySet().stream()
                .map(e -> rowOf(e.getKey(), e.getValue()))
                .toList();
        when(structuredQueryService.execute(any(), anyMap())).thenReturn(new QueryResultPage(rows, null));
    }

    private static Map<String, Object> rowOf(String id, double value) {
        return Map.of("id", id, MetricScoreConstants.VALUE_ALIAS, value);
    }

    private static Map<String, Object> rowOfNullValue(String id) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put(MetricScoreConstants.VALUE_ALIAS, null);
        return row;
    }

    private StructuredQuery capturedQuery() {
        ArgumentCaptor<StructuredQuery> captor = ArgumentCaptor.forClass(StructuredQuery.class);
        verify(structuredQueryService).execute(captor.capture(), anyMap());
        return captor.getValue();
    }

    private CustomFunction customFunction(String mode, String entity, String aliasJson, List<String> groupBy) {
        String groupByJson = groupBy == null
                ? ""
                : ",\"group_by\":["
                        + groupBy.stream()
                                .map(s -> "\"" + s + "\"")
                                .reduce((a, b) -> a + "," + b)
                                .orElse("") + "]";
        String json = "{\"entity\":\"" + entity + "\",\"mode\":\"" + mode + "\","
                + "\"select\":[{\"expr\":{\"type\":\"fn\",\"name\":\"avg\","
                + "\"args\":[{\"type\":\"field\",\"name\":\"metric::Accuracy::score\"}]},"
                + aliasJson + "}]"
                + groupByJson
                + "}";
        return new CustomFunction(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}));
    }
}
