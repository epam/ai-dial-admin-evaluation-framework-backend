package com.epam.aidial.evaluation.query.service.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_METRIC_SCORES_AGGREGATED;
import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_METRIC_SCORES_AGGREGATED_ACTIVE;

import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.service.JooqTableSchemaResolver;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Resolves the {@code test_case_metric_scores} entity to the generated
 * {@code TEST_CASE_METRIC_SCORES_AGGREGATED} table on the analytics datasource ({@code analyticsDsl}).
 * Field bindings are static per-table metadata, computed once here — no join, no rewrite hook, mirroring
 * {@code PostgresMetricScoreResultEntityResolver}'s plain-table template. The {@code metric_scores} JSONB
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

    public PostgresTestCaseMetricScoreAggregatedEntityResolver(
            @Qualifier("analyticsDsl") DSLContext dsl, JooqTableSchemaResolver schemaResolver) {
        this.dsl = dsl;
        this.bindings = schemaResolver.bindings(TEST_CASE_METRIC_SCORES_AGGREGATED);
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
        return TEST_CASE_METRIC_SCORES_AGGREGATED_ACTIVE;
    }

    @Override
    public Map<String, QueryFieldBinding> bindings(StructuredQuery query) {
        return bindings;
    }
}
