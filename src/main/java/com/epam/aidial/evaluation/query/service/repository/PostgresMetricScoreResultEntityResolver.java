package com.epam.aidial.evaluation.query.service.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.METRIC_SCORE_RESULT;

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
 * Resolves the {@code metric_score_results} entity to the generated {@code METRIC_SCORE_RESULT} table
 * on the analytics datasource ({@code analyticsDsl}). Field bindings are static per-table metadata,
 * computed once here. {@link #rewrite} resolves a {@code computation_id eq "latest"} sentinel to the
 * run's most recent computation before translation.
 */
@Repository
@LogExecution
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")
public class PostgresMetricScoreResultEntityResolver implements StructuredQueryEntityResolver {

    private static final String ENTITY = "metric_score_results";

    private final DSLContext dsl;
    private final Map<String, QueryFieldBinding> bindings;
    private final MetricScoreLatestComputationDefaulter latestComputationDefaulter;

    public PostgresMetricScoreResultEntityResolver(
            @Qualifier("analyticsDsl") DSLContext dsl,
            JooqTableSchemaResolver schemaResolver,
            MetricScoreLatestComputationDefaulter latestComputationDefaulter) {
        this.dsl = dsl;
        this.bindings = schemaResolver.bindings(METRIC_SCORE_RESULT);
        this.latestComputationDefaulter = latestComputationDefaulter;
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
        return METRIC_SCORE_RESULT;
    }

    @Override
    public Map<String, QueryFieldBinding> bindings(StructuredQuery query) {
        return bindings;
    }

    @Override
    public StructuredQuery rewrite(StructuredQuery query) {
        return latestComputationDefaulter.resolveLatestComputation(query);
    }
}
