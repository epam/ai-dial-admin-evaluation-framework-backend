package com.epam.aidial.evaluation.query.service.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.OutputColumn;
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
    @DisplayName("an explicit projection keeping id under the id key is extended")
    void extendsExplicitProjectionWithId() {
        final QueryResultPage page = pageWithRow(row(RUN_ID.toString(), "test_run_name", "run-1"));
        when(computationResolver.resolveLatest(List.of(RUN_ID))).thenReturn(Map.of(RUN_ID, COMPUTATION_ID));
        when(executor.execute(any(StructuredQuery.class))).thenReturn(overallScoreResult(RUN_ID, 0.8));

        final QueryResultPage result =
                extender.extend(rowQuery(List.of(col("id", null), col("test_run_name", null))), page);

        assertThat(result.rows().get(0)).containsEntry(TestSuiteRunQueryFields.OVERALL_SCORE_VALUE_FIELD, 0.8);
    }

    @Test
    @DisplayName("a projection without id is untouched")
    void skipsProjectionWithoutId() {
        final Map<String, Object> rowWithoutId = new LinkedHashMap<>();
        rowWithoutId.put("test_run_name", "run-1");
        final QueryResultPage page = new QueryResultPage(List.of(rowWithoutId), 1L);

        final QueryResultPage result = extender.extend(rowQuery(List.of(col("test_run_name", null))), page);

        assertThat(result).isSameAs(page);
        verifyNoInteractions(computationResolver, executor);
    }

    @Test
    @DisplayName("a projection renaming id to another key is untouched")
    void skipsProjectionRenamingId() {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("run", RUN_ID.toString());
        final QueryResultPage page = new QueryResultPage(List.of(row), 1L);

        final QueryResultPage result = extender.extend(rowQuery(List.of(col("id", "run"))), page);

        assertThat(result).isSameAs(page);
        verifyNoInteractions(computationResolver, executor);
    }

    @Test
    @DisplayName("a projection aliasing another field as id is untouched")
    void skipsProjectionAliasingOtherFieldAsId() {
        final QueryResultPage page = pageWithRow(row("run-1", "status", "COMPLETED"));

        final QueryResultPage result =
                extender.extend(rowQuery(List.of(col("test_run_name", "id"), col("status", null))), page);

        assertThat(result).isSameAs(page);
        verifyNoInteractions(computationResolver, executor);
    }

    @Test
    @DisplayName("a projection already carrying the derived key via an alias is untouched")
    void skipsProjectionAlreadyCarryingKey() {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", RUN_ID.toString());
        row.put(TestSuiteRunQueryFields.OVERALL_SCORE_VALUE_FIELD, "run-1");
        final QueryResultPage page = new QueryResultPage(List.of(row), 1L);

        final QueryResultPage result = extender.extend(
                rowQuery(List.of(
                        col("id", null), col("test_run_name", TestSuiteRunQueryFields.OVERALL_SCORE_VALUE_FIELD))),
                page);

        assertThat(result).isSameAs(page);
        verifyNoInteractions(computationResolver, executor);
    }

    @Test
    @DisplayName("on a multi-row page, only scored rows gain the key and row order is unchanged")
    void extendsOnlyScoredRowsOnMultiRowPage() {
        final UUID otherRunId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        final Map<String, Object> unscoredRow = row(RUN_ID.toString(), "existing", "value");
        final Map<String, Object> scoredRow = row(otherRunId.toString(), "existing", "value");
        final QueryResultPage page = new QueryResultPage(List.of(unscoredRow, scoredRow), 2L);
        when(computationResolver.resolveLatest(List.of(RUN_ID, otherRunId)))
                .thenReturn(Map.of(RUN_ID, COMPUTATION_ID, otherRunId, COMPUTATION_ID));
        when(executor.execute(any(StructuredQuery.class))).thenReturn(overallScoreResult(otherRunId, 0.9));

        final QueryResultPage result = extender.extend(rowQuery(), page);

        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0)).isSameAs(unscoredRow);
        assertThat(result.rows().get(1))
                .containsEntry("id", otherRunId.toString())
                .containsEntry(TestSuiteRunQueryFields.OVERALL_SCORE_VALUE_FIELD, 0.9);
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
        return rowQuery(List.of());
    }

    private static StructuredQuery rowQuery(List<OutputColumn> select) {
        return new StructuredQuery(
                TestSuiteRunQueryFields.ENTITY, null, QueryMode.ROW, false, select, null, null, null, null);
    }

    private static OutputColumn col(String field, String as) {
        return new OutputColumn(new FieldExpr(field), as);
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
