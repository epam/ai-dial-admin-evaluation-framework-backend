package com.epam.aidial.evaluation.query.service.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields;
import com.epam.aidial.evaluation.service.domain.analytics.ComputationResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OverallScoreTestSuiteRunsPageExtenderTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMPUTATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final ComputationResolver computationResolver = mock(ComputationResolver.class);
    private final JooqStructuredQueryExecutor executor = mock(JooqStructuredQueryExecutor.class);
    private final StructuredQueryEntityRegistry entityRegistry = mock(StructuredQueryEntityRegistry.class);
    private final OverallScoreTestSuiteRunsPageExtender extender =
            new OverallScoreTestSuiteRunsPageExtender(computationResolver, executor, entityRegistry);

    @BeforeEach
    void registerMetricScoreResultsEntity() {
        when(entityRegistry.supportedEntities())
                .thenReturn(Set.of(MetricScoreConstants.ENTITY_METRIC_SCORE_RESULTS, TestSuiteRunQueryFields.ENTITY));
    }

    @Test
    @DisplayName("a scored run's row carries its latest computation's overall value")
    void attachesValueToScoredRun() {
        final QueryResultPage page = pageWithRow(row(RUN_ID.toString(), "existing", "value"));
        when(computationResolver.resolveLatest(List.of(RUN_ID))).thenReturn(Map.of(RUN_ID, COMPUTATION_ID));
        when(executor.execute(any(StructuredQuery.class))).thenReturn(overallScoreResult(RUN_ID, 0.8));

        final QueryResultPage result = extender.extend(rowQuery(), page);

        final Map<String, Object> extendedRow = result.rows().get(0);
        assertThat(extendedRow).containsEntry("id", RUN_ID.toString()).containsEntry("existing", "value");
        assertThat(extendedRow.get(TestSuiteRunQueryFields.OVERALL_SCORE_VALUE_FIELD))
                .isEqualTo(0.8);
        assertThat(result.totalCount()).isEqualTo(page.totalCount());
        // Order-preserving: the derived key is appended after every existing key.
        assertThat(List.copyOf(extendedRow.keySet()))
                .containsExactly("id", "existing", TestSuiteRunQueryFields.OVERALL_SCORE_VALUE_FIELD);
    }

    @Test
    @DisplayName("a run whose latest computation has no overall row omits the key")
    void omitsKeyWhenNoOverallRow() {
        final QueryResultPage page = pageWithRow(row(RUN_ID.toString(), "existing", "value"));
        when(computationResolver.resolveLatest(List.of(RUN_ID))).thenReturn(Map.of(RUN_ID, COMPUTATION_ID));
        when(executor.execute(any(StructuredQuery.class))).thenReturn(new QueryResultPage(List.of(), null));

        final QueryResultPage result = extender.extend(rowQuery(), page);

        assertThat(result).isSameAs(page);
        assertThat(result.rows().get(0)).doesNotContainKey(TestSuiteRunQueryFields.OVERALL_SCORE_VALUE_FIELD);
    }

    @Test
    @DisplayName("an unscored run (no eval summaries) omits the key without querying metric_score_results")
    void omitsKeyWhenRunHasNoEvalSummaries() {
        final QueryResultPage page = pageWithRow(row(RUN_ID.toString(), "existing", "value"));
        when(computationResolver.resolveLatest(List.of(RUN_ID))).thenReturn(Map.of());

        final QueryResultPage result = extender.extend(rowQuery(), page);

        assertThat(result).isSameAs(page);
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("aggregate mode is untouched")
    void skipsAggregateMode() {
        final QueryResultPage page = pageWithRow(row(RUN_ID.toString(), "existing", "value"));
        final StructuredQuery aggregateQuery = new StructuredQuery(
                TestSuiteRunQueryFields.ENTITY, null, QueryMode.AGGREGATE, false, List.of(), null, null, null, null);

        final QueryResultPage result = extender.extend(aggregateQuery, page);

        assertThat(result).isSameAs(page);
        verifyNoInteractions(computationResolver, executor);
    }

    @Test
    @DisplayName("a projection without id is untouched")
    void skipsProjectionWithoutId() {
        final Map<String, Object> rowWithoutId = new LinkedHashMap<>();
        rowWithoutId.put("test_run_name", "run-1");
        final QueryResultPage page = new QueryResultPage(List.of(rowWithoutId), 1L);

        final QueryResultPage result = extender.extend(rowQuery(), page);

        assertThat(result).isSameAs(page);
        verifyNoInteractions(computationResolver, executor);
    }

    @Test
    @DisplayName("a row whose id is not a UUID is untouched")
    void skipsNonUuidId() {
        final QueryResultPage page = pageWithRow(row("not-a-uuid", "existing", "value"));

        final QueryResultPage result = extender.extend(rowQuery(), page);

        assertThat(result).isSameAs(page);
        verifyNoInteractions(computationResolver, executor);
    }

    @Test
    @DisplayName("a row that already carries the key is untouched")
    void skipsRowAlreadyCarryingKey() {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", RUN_ID.toString());
        row.put(TestSuiteRunQueryFields.OVERALL_SCORE_VALUE_FIELD, 0.42);
        final QueryResultPage page = new QueryResultPage(List.of(row), 1L);

        final QueryResultPage result = extender.extend(rowQuery(), page);

        assertThat(result).isSameAs(page);
        verifyNoInteractions(computationResolver, executor);
    }

    @Test
    @DisplayName("on a multi-row page, a row already carrying the key keeps its value and row order is unchanged")
    void doesNotOverwriteExistingKeyOnMultiRowPage() {
        final UUID otherRunId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        final UUID otherComputationId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        final Map<String, Object> preKeyedRow = new LinkedHashMap<>();
        preKeyedRow.put("id", RUN_ID.toString());
        preKeyedRow.put(TestSuiteRunQueryFields.OVERALL_SCORE_VALUE_FIELD, 0.42);
        final Map<String, Object> toExtendRow = row(otherRunId.toString(), "existing", "value");
        final QueryResultPage page = new QueryResultPage(List.of(preKeyedRow, toExtendRow), 2L);

        // Simulates a resolved computation existing for the pre-keyed run too (e.g. if candidateRunIds
        // ever stopped excluding it): mergeRows alone must still refuse to overwrite the row's own key,
        // not rely on the caller having excluded it.
        when(computationResolver.resolveLatest(any()))
                .thenReturn(Map.of(RUN_ID, COMPUTATION_ID, otherRunId, otherComputationId));
        final Map<String, Object> conflictingScore = new LinkedHashMap<>();
        conflictingScore.put(MetricScoreConstants.FIELD_TEST_SUITE_RUN_ID, RUN_ID.toString());
        conflictingScore.put(MetricScoreConstants.VALUE_ALIAS, 0.99);
        final Map<String, Object> newScore = new LinkedHashMap<>();
        newScore.put(MetricScoreConstants.FIELD_TEST_SUITE_RUN_ID, otherRunId.toString());
        newScore.put(MetricScoreConstants.VALUE_ALIAS, 0.9);
        when(executor.execute(any(StructuredQuery.class)))
                .thenReturn(new QueryResultPage(List.of(conflictingScore, newScore), null));

        final QueryResultPage result = extender.extend(rowQuery(), page);

        assertThat(result.rows()).hasSize(2);
        // Row order unchanged: the pre-keyed row stays first, the extended row stays second.
        assertThat(result.rows().get(0).get("id")).isEqualTo(RUN_ID.toString());
        assertThat(result.rows().get(0).get(TestSuiteRunQueryFields.OVERALL_SCORE_VALUE_FIELD))
                .isEqualTo(0.42);
        assertThat(result.rows().get(1).get("id")).isEqualTo(otherRunId.toString());
        assertThat(result.rows().get(1).get(TestSuiteRunQueryFields.OVERALL_SCORE_VALUE_FIELD))
                .isEqualTo(0.9);
    }

    @Test
    @DisplayName("metric_score_results not being a registered entity is untouched")
    void skipsWhenMetricScoreResultsNotRegistered() {
        when(entityRegistry.supportedEntities()).thenReturn(Set.of(TestSuiteRunQueryFields.ENTITY));
        final QueryResultPage page = pageWithRow(row(RUN_ID.toString(), "existing", "value"));

        final QueryResultPage result = extender.extend(rowQuery(), page);

        assertThat(result).isSameAs(page);
        verifyNoInteractions(computationResolver, executor);
    }

    private static StructuredQuery rowQuery() {
        return new StructuredQuery(
                TestSuiteRunQueryFields.ENTITY, null, QueryMode.ROW, false, List.of(), null, null, null, null);
    }

    private static Map<String, Object> row(String id, String extraKey, Object extraValue) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put(extraKey, extraValue);
        return row;
    }

    private static QueryResultPage pageWithRow(Map<String, Object> row) {
        return new QueryResultPage(List.of(row), 1L);
    }

    private static QueryResultPage overallScoreResult(UUID runId, Object value) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put(MetricScoreConstants.FIELD_TEST_SUITE_RUN_ID, runId.toString());
        row.put(MetricScoreConstants.VALUE_ALIAS, value);
        return new QueryResultPage(List.of(row), null);
    }
}
