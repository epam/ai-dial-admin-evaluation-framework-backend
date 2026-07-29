package com.epam.aidial.evaluation.experimental.query.service.metricscore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.JsonMapperConfiguration;
import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.experimental.query.model.ArrayExpr;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonNode;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonOp;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FilterNode;
import com.epam.aidial.evaluation.experimental.query.model.LogicalNode;
import com.epam.aidial.evaluation.experimental.query.model.LogicalOp;
import com.epam.aidial.evaluation.experimental.query.model.OutputColumn;
import com.epam.aidial.evaluation.experimental.query.model.QueryMode;
import com.epam.aidial.evaluation.experimental.query.model.SortItem;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import com.epam.aidial.evaluation.experimental.query.service.StructuredQueryService;
import com.epam.aidial.evaluation.experimental.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.service.domain.dto.analytics.MetricScoreValueDto;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.CustomFunction;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.Mean;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.OverallScoreDefinition;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.WeightedMean;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.WeightedMetric;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@DisplayName("FilteredMetricScoreAggregator")
class FilteredMetricScoreAggregatorTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMPUTATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID EXCLUDED_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID EXCLUDED_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final MetricField RELEVANCY = new MetricField("metric::Relevancy::score", "Relevancy.score");
    private static final MetricField ACCURACY = new MetricField("metric::Accuracy::score", "Accuracy.score");

    /** Number of built-in per-metric statistics (AVG/P10/P90/MIN/MAX). */
    private static final int STATISTIC_COUNT = 5;

    private final BuiltInMetricStatistics builtInStatistics = new BuiltInMetricStatistics();
    private final ObjectMapper objectMapper = new JsonMapperConfiguration().objectMapper();
    private final StructuredQueryService structuredQueryService = mock(StructuredQueryService.class);

    private final FilteredMetricScoreAggregator aggregator = new FilteredMetricScoreAggregator(
            builtInStatistics,
            new OverallScoreDefinitionResolver(builtInStatistics, objectMapper),
            structuredQueryService);

    // ----- withIdPredicate / exclusionPredicate -----

    @Test
    @DisplayName("Should AND the exclusion predicate onto an existing filter, leaving every other component alone")
    void shouldGraftOntoExistingFilter() {
        StructuredQuery original = builtInStatistics.perMetric().getFirst().query();
        FilterNode predicate = aggregator.exclusionPredicate(List.of(EXCLUDED_A));

        StructuredQuery grafted = aggregator.withIdPredicate(original, predicate);

        assertThat(grafted.filter()).isEqualTo(new LogicalNode(LogicalOp.AND, List.of(original.filter(), predicate)));
        assertThat(grafted.entity()).isEqualTo(original.entity());
        assertThat(grafted.mode()).isEqualTo(original.mode());
        assertThat(grafted.distinct()).isEqualTo(original.distinct());
        assertThat(grafted.select()).isEqualTo(original.select());
        assertThat(grafted.groupBy()).isEqualTo(original.groupBy());
        assertThat(grafted.having()).isEqualTo(original.having());
        assertThat(grafted.sort()).isEqualTo(original.sort());
        assertThat(grafted.page()).isEqualTo(original.page());
    }

    @Test
    @DisplayName("Should use the exclusion predicate alone when the query has no filter")
    void shouldGraftOntoNullFilter() {
        StructuredQuery noFilter = queryWithoutFilter();
        FilterNode predicate = aggregator.exclusionPredicate(List.of(EXCLUDED_A));

        assertThat(aggregator.withIdPredicate(noFilter, predicate).filter()).isEqualTo(predicate);
    }

    @Test
    @DisplayName("Should build the exclusion predicate as a not-wrapped in over literal UUID values")
    void shouldBuildNotWrappedInPredicate() {
        FilterNode predicate = aggregator.exclusionPredicate(List.of(EXCLUDED_A, EXCLUDED_B));

        // There is no not_in operator in the DSL — exclusion is not(in(...)), and the array items must be
        // literal ValueExprs or FilterTranslator.inValues rejects them.
        assertThat(predicate)
                .isEqualTo(new LogicalNode(
                        LogicalOp.NOT,
                        List.of(new ComparisonNode(
                                ComparisonOp.IN,
                                List.of(
                                        new FieldExpr("id"),
                                        new ArrayExpr(List.of(
                                                new ValueExpr(ValueType.UUID, EXCLUDED_A.toString()),
                                                new ValueExpr(ValueType.UUID, EXCLUDED_B.toString()))))))));
    }

    @Test
    @DisplayName("Should graft nothing when there is nothing to exclude, leaving the query identical")
    void shouldGraftNothingWhenNoExclusions() {
        StructuredQuery original = builtInStatistics.perMetric().getFirst().query();

        assertThat(aggregator.exclusionPredicate(List.of())).isNull();
        assertThat(aggregator.exclusionPredicate(null)).isNull();
        // An empty `in` array would be rejected by the translator, and grafting nothing is also the case
        // where agreement with the persisted full-population value is a tautology.
        assertThat(aggregator.withIdPredicate(original, null)).isSameAs(original);
    }

    // ----- statistics path -----

    @Test
    @DisplayName("Should issue one query per statistic and metric field, binding that field")
    void shouldIssueOneQueryPerStatisticAndField() {
        stubScalar(0.5);

        List<MetricScoreValueDto> values = aggregator.aggregate(request(List.of(RELEVANCY, ACCURACY), null));

        // 5 statistics x 2 fields; overall is skipped (null definition, more than one field).
        verify(structuredQueryService, times(STATISTIC_COUNT * 2)).execute(any(), anyMap());
        assertThat(values).hasSize(STATISTIC_COUNT * 2);
        assertThat(values)
                .extracting(MetricScoreValueDto::getMetricName)
                .containsOnly("Relevancy.score", "Accuracy.score");
        assertThat(values)
                .extracting(MetricScoreValueDto::getMetricScoreName)
                .containsOnly("AVG", "P10", "P90", "MIN", "MAX");

        // Every execution binds the run, the computation and the metric field being aggregated.
        List<Map<String, Expr>> params = capturedParams();
        assertThat(params)
                .allSatisfy(p -> assertThat(p)
                        .containsEntry(
                                MetricScoreConstants.PARAM_RUN_ID, new ValueExpr(ValueType.UUID, RUN_ID.toString()))
                        .containsEntry(
                                MetricScoreConstants.PARAM_COMPUTATION_ID,
                                new ValueExpr(ValueType.UUID, COMPUTATION_ID.toString())));
        assertThat(params)
                .extracting(p -> p.get(MetricScoreConstants.PARAM_METRIC_FIELD))
                .containsOnly(new FieldExpr(RELEVANCY.flattenedName()), new FieldExpr(ACCURACY.flattenedName()));
    }

    @Test
    @DisplayName("Should carry the exclusion predicate on every issued query")
    void shouldCarryExclusionPredicateOnEveryQuery() {
        stubScalar(0.5);

        aggregator.aggregate(request(List.of(RELEVANCY), null, List.of(EXCLUDED_A)));

        FilterNode expected = aggregator.exclusionPredicate(List.of(EXCLUDED_A));
        assertThat(capturedQueries())
                .isNotEmpty()
                .allSatisfy(q -> assertThat(((LogicalNode) q.filter()).args()).contains(expected));
    }

    @Test
    @DisplayName("Should omit an entry whose aggregate is null rather than returning a null value")
    void shouldOmitNullAggregate() {
        when(structuredQueryService.execute(any(), anyMap())).thenReturn(new QueryResultPage(List.of(Map.of()), null));

        assertThat(aggregator.aggregate(request(List.of(RELEVANCY), null))).isEmpty();
    }

    @Test
    @DisplayName("Should omit an entry whose aggregate is not numeric, and one with no rows at all")
    void shouldOmitNonNumericAndEmptyResults() {
        when(structuredQueryService.execute(any(), anyMap()))
                .thenReturn(
                        new QueryResultPage(List.of(Map.of(MetricScoreConstants.VALUE_ALIAS, "not a number")), null))
                .thenReturn(new QueryResultPage(List.of(), null));

        assertThat(aggregator.aggregate(request(List.of(RELEVANCY), null))).isEmpty();
    }

    @Test
    @DisplayName("Should skip a statistic that fails validation without aborting the rest")
    void shouldSkipFailingStatistic() {
        when(structuredQueryService.execute(any(), anyMap()))
                .thenThrow(new ValidationException("boom"))
                .thenReturn(new QueryResultPage(List.of(Map.of(MetricScoreConstants.VALUE_ALIAS, 0.5)), null));

        // Two fields, so the default overall is skipped: 5 statistics x 2 fields = 10 executions, of which
        // only the first throws.
        assertThat(aggregator.aggregate(request(List.of(RELEVANCY, ACCURACY), null)))
                .hasSize(STATISTIC_COUNT * 2 - 1);
    }

    @Test
    @DisplayName("Should issue no query at all when the run discovered no metric fields")
    void shouldIssueNoQueryWithoutMetricFields() {
        assertThat(aggregator.aggregate(request(List.of(), null))).isEmpty();

        verify(structuredQueryService, never()).execute(any(), anyMap());
    }

    // ----- overall variants -----

    @Test
    @DisplayName("Should compute the default overall for a single metric field, binding that field")
    void shouldComputeDefaultOverallForSingleField() {
        stubScalar(0.75);

        List<MetricScoreValueDto> values = aggregator.aggregate(request(List.of(RELEVANCY), null));

        assertThat(values).hasSize(STATISTIC_COUNT + 1);
        assertThat(values)
                .filteredOn(v -> MetricScoreConstants.SCORE_OVERALL.equals(v.getMetricScoreName()))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.getMetricName()).isEqualTo(MetricScoreConstants.SCORE_OVERALL);
                    assertThat(v.getValue()).isEqualTo(0.75);
                });
        assertThat(capturedQueries().getLast().select())
                .isEqualTo(builtInStatistics.defaultOverall().select());
    }

    @Test
    @DisplayName("Should skip the default overall when the run has more than one metric field")
    void shouldSkipDefaultOverallForMultipleFields() {
        stubScalar(0.5);

        List<MetricScoreValueDto> values = aggregator.aggregate(request(List.of(RELEVANCY, ACCURACY), null));

        assertThat(values)
                .extracting(MetricScoreValueDto::getMetricScoreName)
                .doesNotContain(MetricScoreConstants.SCORE_OVERALL);
    }

    @Test
    @DisplayName("Should resolve a mean against the full discovered field list, not a subset")
    void shouldResolveMeanAgainstFullFieldList() {
        stubScalar(0.5);

        aggregator.aggregate(request(List.of(RELEVANCY, ACCURACY), new Mean()));

        // A mean divides by the field count, so the divisor proves the resolver saw both fields. Passing a
        // subset here would silently produce a different number rather than an error.
        StructuredQuery overallQuery = capturedQueries().getLast();
        StructuredQuery expected = new OverallScoreDefinitionResolver(builtInStatistics, objectMapper)
                .resolve(new Mean(), List.of(RELEVANCY.flattenedName(), ACCURACY.flattenedName()));
        assertThat(overallQuery.select()).isEqualTo(expected.select());
    }

    @Test
    @DisplayName("Should compute overall from a weighted mean definition")
    void shouldComputeWeightedMeanOverall() {
        stubScalar(0.5);

        List<MetricScoreValueDto> values = aggregator.aggregate(request(
                List.of(RELEVANCY),
                new WeightedMean(List.of(new WeightedMetric("Relevancy", "score", BigDecimal.valueOf(2))))));

        assertThat(values)
                .extracting(MetricScoreValueDto::getMetricScoreName)
                .contains(MetricScoreConstants.SCORE_OVERALL);
    }

    @Test
    @DisplayName("Should compute a custom function overall and read it by its own alias")
    void shouldComputeCustomFunctionOverallByItsOwnAlias() {
        // Every query returns a row keyed by the custom alias only, so the built-in statistics (which read
        // `value`) are omitted and the surviving entry can only have come from reading the custom alias.
        when(structuredQueryService.execute(any(), anyMap()))
                .thenReturn(new QueryResultPage(List.of(Map.of("myAlias", 0.9)), null));

        List<MetricScoreValueDto> values =
                aggregator.aggregate(request(List.of(RELEVANCY), customFunction("\"as\":\"myAlias\"")));

        assertThat(values).singleElement().satisfies(v -> {
            assertThat(v.getMetricScoreName()).isEqualTo(MetricScoreConstants.SCORE_OVERALL);
            assertThat(v.getValue()).isEqualTo(0.9);
        });
    }

    @Test
    @DisplayName("Should graft the exclusion predicate onto a custom function that carries no filter")
    void shouldGraftOntoCustomFunctionWithoutFilter() {
        stubScalar(0.5);

        aggregator.aggregate(request(List.of(RELEVANCY), customFunction("\"as\":\"value\""), List.of(EXCLUDED_A)));

        // The custom function is the one overall variant that can present a null incoming filter; the
        // built-in paths always attach the run-scoping filter themselves.
        assertThat(capturedQueries().getLast().filter()).isEqualTo(aggregator.exclusionPredicate(List.of(EXCLUDED_A)));
    }

    @Test
    @DisplayName("Should skip a custom function in row mode")
    void shouldSkipCustomFunctionInRowMode() {
        stubScalar(0.5);

        List<MetricScoreValueDto> values = aggregator.aggregate(
                request(List.of(RELEVANCY), customFunctionWithMode("row", "eval_summaries", "\"as\":\"value\"")));

        assertThat(values)
                .extracting(MetricScoreValueDto::getMetricScoreName)
                .doesNotContain(MetricScoreConstants.SCORE_OVERALL);
    }

    @Test
    @DisplayName("Should skip a custom function targeting a foreign entity")
    void shouldSkipCustomFunctionOnForeignEntity() {
        stubScalar(0.5);

        List<MetricScoreValueDto> values = aggregator.aggregate(
                request(List.of(RELEVANCY), customFunctionWithMode("aggregate", "test_suites", "\"as\":\"value\"")));

        assertThat(values)
                .extracting(MetricScoreValueDto::getMetricScoreName)
                .doesNotContain(MetricScoreConstants.SCORE_OVERALL);
    }

    @Test
    @DisplayName("Should skip a custom function whose select column has no alias")
    void shouldSkipCustomFunctionWithoutAlias() {
        stubScalar(0.5);

        List<MetricScoreValueDto> values =
                aggregator.aggregate(request(List.of(RELEVANCY), customFunction("\"as\":\"\"")));

        assertThat(values)
                .extracting(MetricScoreValueDto::getMetricScoreName)
                .doesNotContain(MetricScoreConstants.SCORE_OVERALL);
    }

    // ----- helpers -----

    private void stubScalar(double value) {
        when(structuredQueryService.execute(any(), anyMap()))
                .thenReturn(new QueryResultPage(List.of(Map.of(MetricScoreConstants.VALUE_ALIAS, value)), null));
    }

    private List<StructuredQuery> capturedQueries() {
        ArgumentCaptor<StructuredQuery> captor = ArgumentCaptor.forClass(StructuredQuery.class);
        verify(structuredQueryService, atLeastOnce()).execute(captor.capture(), anyMap());
        return captor.getAllValues();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Expr>> capturedParams() {
        ArgumentCaptor<Map<String, Expr>> captor = ArgumentCaptor.forClass(Map.class);
        verify(structuredQueryService, atLeastOnce()).execute(any(), captor.capture());
        return new ArrayList<>(captor.getAllValues());
    }

    private FilteredMetricScoreRequest request(List<MetricField> fields, OverallScoreDefinition definition) {
        return request(fields, definition, List.of());
    }

    private FilteredMetricScoreRequest request(
            List<MetricField> fields, OverallScoreDefinition definition, List<UUID> excluded) {
        return new FilteredMetricScoreRequest(RUN_ID, COMPUTATION_ID, excluded, fields, definition);
    }

    private CustomFunction customFunction(String aliasJson) {
        return customFunctionWithMode("aggregate", "eval_summaries", aliasJson);
    }

    private CustomFunction customFunctionWithMode(String mode, String entity, String aliasJson) {
        String json = "{\"entity\":\"" + entity + "\",\"mode\":\"" + mode + "\","
                + "\"select\":[{\"expr\":{\"type\":\"fn\",\"name\":\"avg\","
                + "\"args\":[{\"type\":\"field\",\"name\":\"metric::Relevancy::score\"}]},"
                + aliasJson + "}]}";
        return new CustomFunction(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}));
    }

    private static StructuredQuery queryWithoutFilter() {
        return new StructuredQuery(
                MetricScoreConstants.ENTITY_EVAL_SUMMARIES,
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(new OutputColumn(new FieldExpr("x"), MetricScoreConstants.VALUE_ALIAS)),
                null,
                null,
                List.<SortItem>of(),
                null);
    }
}
