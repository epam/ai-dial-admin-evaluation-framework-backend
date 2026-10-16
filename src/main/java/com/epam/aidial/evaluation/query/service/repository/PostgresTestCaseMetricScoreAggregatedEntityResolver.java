package com.epam.aidial.evaluation.query.service.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_METRIC_SCORES_AGGREGATED;

import com.epam.aidial.evaluation.data.db.analytics.repository.RunDeletionFilter;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.service.JooqTableSchemaResolver;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Map;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Resolves the {@code test_case_metric_scores} entity to the generated
 * {@code TEST_CASE_METRIC_SCORES_AGGREGATED} table on the analytics datasource ({@code analyticsDsl}).
 * Field bindings are static per-table metadata, computed once here — no join, mirroring
 * {@code PostgresMetricScoreResultEntityResolver}'s plain-table template. {@link #rewrite} resolves a
 * {@code computation_id eq "latest"} sentinel the same way (single pinned run to its literal id, otherwise
 * through {@link LatestComputationSupport}). The {@code metric_scores} JSONB
 * column is flattened into addressable fields (e.g. {@code metric_scores::Accuracy.score::avg}) by
 * {@code JsonbFieldResolver} at translation time, the same mechanism used for
 * {@code eval_summaries.metric_values}.
 */
@Repository
@LogExecution
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")
public class PostgresTestCaseMetricScoreAggregatedEntityResolver implements StructuredQueryEntityResolver {

    private static final String ENTITY = "test_case_metric_scores";

    private final DSLContext dsl;
    private final Map<String, QueryFieldBinding> bindings;
    private final MetricScoreLatestComputationDefaulter latestComputationDefaulter;
    private final LatestComputationSupport latestComputationSupport;

    public PostgresTestCaseMetricScoreAggregatedEntityResolver(
            @Qualifier("analyticsDsl") DSLContext dsl,
            JooqTableSchemaResolver schemaResolver,
            MetricScoreLatestComputationDefaulter latestComputationDefaulter,
            LatestComputationSupport latestComputationSupport) {
        this.dsl = dsl;
        this.bindings = latestComputationSupport.withLatest(
                schemaResolver.bindings(TEST_CASE_METRIC_SCORES_AGGREGATED),
                latestComputationSupport.latestBinding(
                        TEST_CASE_METRIC_SCORES_AGGREGATED.TEST_SUITE_RUN_ID,
                        TEST_CASE_METRIC_SCORES_AGGREGATED.COMPUTATION_ID));
        this.latestComputationDefaulter = latestComputationDefaulter;
        this.latestComputationSupport = latestComputationSupport;
    }

    @Override
    public String entity() {
        return ENTITY;
    }

    @Override
    public DSLContext dsl() {
        return dsl;
    }

    @Override
    public Table<?> table() {
        return TEST_CASE_METRIC_SCORES_AGGREGATED;
    }

    @Override
    public Optional<Condition> baseCondition() {
        return Optional.of(RunDeletionFilter.notDeleted(TEST_CASE_METRIC_SCORES_AGGREGATED.TEST_SUITE_RUN_ID));
    }

    @Override
    public Map<String, QueryFieldBinding> bindings(StructuredQuery query) {
        return bindings;
    }

    @Override
    public StructuredQuery rewrite(StructuredQuery query) {
        return latestComputationSupport.rewriteLatest(latestComputationDefaulter.resolveLatestComputation(query));
    }
}
