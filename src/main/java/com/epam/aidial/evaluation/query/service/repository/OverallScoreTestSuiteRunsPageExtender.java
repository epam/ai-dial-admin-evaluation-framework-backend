package com.epam.aidial.evaluation.query.service.repository;

import com.epam.aidial.evaluation.constants.MetricScoreConstants;
import com.epam.aidial.evaluation.query.model.ArrayExpr;
import com.epam.aidial.evaluation.query.model.ComparisonNode;
import com.epam.aidial.evaluation.query.model.ComparisonOp;
import com.epam.aidial.evaluation.query.model.Expr;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.LogicalNode;
import com.epam.aidial.evaluation.query.model.LogicalOp;
import com.epam.aidial.evaluation.query.model.OffsetPage;
import com.epam.aidial.evaluation.query.model.OutputColumn;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.analytics.ComputationResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Attaches the run-level {@code overall} metric score of a run's latest computation to each row of a
 * {@code row}-mode {@code test_suite_runs} result page, as the extension-derived
 * {@link TestSuiteRunQueryFields#OVERALL_SCORE_VALUE_FIELD} key (design D4/D5 of
 * {@code enrich-test-suite-runs-overall-score}).
 *
 * <p>Two bounded queries per page, both against the analytics datasource, run <b>after</b> the
 * {@code test_suite_runs} meta read has already returned — neither is wrapped in a transaction:
 *
 * <ol>
 *   <li>{@link ComputationResolver#resolveLatest(java.util.Collection)} resolves the canonical latest
 *       computation for every run on the page in one statement. This step cannot be skipped in favor
 *       of reading the newest {@code overall} row directly: that would answer "latest computation
 *       that has an overall row", not "latest computation", and could surface a score the run's
 *       detail view no longer considers current.
 *   <li>One {@link StructuredQuery} against {@code metric_score_results}, filtered by
 *       {@code test_suite_run_id in […] AND computation_id in […] AND metric_score_name eq 'overall'
 *       AND metric_name eq 'overall'}. The {@code test_suite_run_id} predicate is redundant for
 *       correctness ({@code computation_id} is globally unique) but load-bearing for the query plan —
 *       it is the leading column of both usable indexes, so dropping it makes the query's cost track
 *       total table size instead of page size. Executed through the concrete
 *       {@link JooqStructuredQueryExecutor}, never the {@link StructuredQueryExecutor} interface (that
 *       resolves to the {@code @Primary} {@link JooqStructuredQueryExecutorExtender} — this very
 *       coordinator — which would re-enter the seam and recurse).
 * </ol>
 *
 * <p>Skip conditions, each leaving {@code page} untouched, all decided from the query alone — a
 * {@code row} result's key set is fixed by its projection, so it is the same for every row: the query
 * targets a different entity or runs in {@code aggregate} mode; the projection does not carry the run's
 * {@code id} under the {@code id} key (omitted, renamed, or another expression aliased as {@code id});
 * the projection already carries the derived key (a client-supplied {@code select} alias wins); the
 * {@code metric_score_results} entity has no registered resolver (non-Postgres analytics vendor). Once
 * those pass, every row carries a UUID {@code id} (the run's non-null primary key).
 */
@Component
@LogExecution
@RequiredArgsConstructor
class OverallScoreTestSuiteRunsPageExtender implements QueryResultPageExtender {

    private final ComputationResolver computationResolver;
    private final JooqStructuredQueryExecutor executor;
    private final StructuredQueryEntityRegistry entityRegistry;

    @Override
    public QueryResultPage extend(StructuredQuery query, QueryResultPage page) {
        if (!applies(query)) {
            return page;
        }

        final List<UUID> runIds = candidateRunIds(page);
        final Map<UUID, UUID> latestComputationByRun = computationResolver.resolveLatest(runIds);
        final Map<UUID, Object> valueByRun = fetchOverallValues(latestComputationByRun);

        if (valueByRun.isEmpty()) {
            return page;
        }

        return new QueryResultPage(mergeRows(page.rows(), valueByRun), page.totalCount());
    }

    private boolean applies(StructuredQuery query) {
        return TestSuiteRunQueryFields.ENTITY.equals(query.entity())
                && query.mode() == QueryMode.ROW
                && TestSuiteRunsRowProjection.projectsRunId(query.select())
                && TestSuiteRunsRowProjection.doesNotProjectKey(
                        query.select(), TestSuiteRunQueryFields.OVERALL_SCORE_VALUE_FIELD)
                && entityRegistry.supportedEntities().contains(MetricScoreConstants.ENTITY_METRIC_SCORE_RESULTS);
    }

    private static List<UUID> candidateRunIds(QueryResultPage page) {
        return page.rows().stream()
                .map(TestSuiteRunsRowProjection::runId)
                .distinct()
                .toList();
    }

    private Map<UUID, Object> fetchOverallValues(Map<UUID, UUID> latestComputationByRun) {
        if (latestComputationByRun.isEmpty()) {
            return Map.of();
        }

        final List<UUID> runIds = List.copyOf(latestComputationByRun.keySet());
        final List<UUID> computationIds =
                latestComputationByRun.values().stream().distinct().toList();
        final StructuredQuery overallScoreQuery = buildOverallScoreQuery(runIds, computationIds);

        final Map<UUID, Object> valueByRun = new LinkedHashMap<>();
        for (final Map<String, Object> row : executor.execute(overallScoreQuery).rows()) {
            final Object value = row.get(MetricScoreConstants.VALUE_ALIAS);
            parseUuid(row.get(MetricScoreConstants.FIELD_TEST_SUITE_RUN_ID)).ifPresent(runId -> {
                if (value != null) {
                    valueByRun.put(runId, value);
                }
            });
        }
        return valueByRun;
    }

    private static StructuredQuery buildOverallScoreQuery(List<UUID> runIds, List<UUID> computationIds) {
        final List<OutputColumn> select = List.of(
                new OutputColumn(new FieldExpr(MetricScoreConstants.FIELD_TEST_SUITE_RUN_ID), null),
                new OutputColumn(new FieldExpr(MetricScoreConstants.VALUE_ALIAS), null));
        final OffsetPage page = new OffsetPage(0, runIds.size(), false);
        return new StructuredQuery(
                MetricScoreConstants.ENTITY_METRIC_SCORE_RESULTS,
                overallScoreFilter(runIds, computationIds),
                QueryMode.ROW,
                false,
                select,
                null,
                null,
                null,
                page);
    }

    private static LogicalNode overallScoreFilter(List<UUID> runIds, List<UUID> computationIds) {
        return new LogicalNode(
                LogicalOp.AND,
                List.of(
                        inUuid(MetricScoreConstants.FIELD_TEST_SUITE_RUN_ID, runIds),
                        inUuid(MetricScoreConstants.FIELD_COMPUTATION_ID, computationIds),
                        eqString(MetricScoreConstants.FIELD_METRIC_SCORE_NAME, MetricScoreConstants.SCORE_OVERALL),
                        eqString(MetricScoreConstants.FIELD_METRIC_NAME, MetricScoreConstants.SCORE_OVERALL)));
    }

    private static ComparisonNode inUuid(String field, List<UUID> values) {
        final List<Expr> items = values.stream()
                .<Expr>map(id -> new ValueExpr(ValueType.UUID, id.toString()))
                .toList();
        return new ComparisonNode(ComparisonOp.IN, List.of(new FieldExpr(field), new ArrayExpr(items)));
    }

    private static ComparisonNode eqString(String field, String value) {
        return new ComparisonNode(
                ComparisonOp.EQ, List.of(new FieldExpr(field), new ValueExpr(ValueType.STRING, value)));
    }

    /**
     * Additive, order-preserving merge: copies only the rows that gained a value. Non-overwriting is
     * guaranteed by {@link #applies}, which skips any projection that already carries the derived key.
     */
    private static List<Map<String, Object>> mergeRows(List<Map<String, Object>> rows, Map<UUID, Object> valueByRun) {
        final List<Map<String, Object>> merged = new ArrayList<>(rows.size());
        for (final Map<String, Object> row : rows) {
            final Object value = valueByRun.get(TestSuiteRunsRowProjection.runId(row));
            if (value == null) {
                merged.add(row);
            } else {
                final Map<String, Object> extended = new LinkedHashMap<>(row);
                extended.put(TestSuiteRunQueryFields.OVERALL_SCORE_VALUE_FIELD, value);
                merged.add(extended);
            }
        }
        return merged;
    }

    private static Optional<UUID> parseUuid(Object value) {
        if (!(value instanceof String text)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
