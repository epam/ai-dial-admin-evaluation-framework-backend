package com.epam.aidial.evaluation.query.service.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.METRIC_SCORE_RESULT;

import com.epam.aidial.evaluation.constants.MetricScoreConstants;
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
 * Resolves the {@code metric_score_results} entity to the generated {@code METRIC_SCORE_RESULT} table
 * on the analytics datasource ({@code analyticsDsl}). Field bindings are static per-table metadata,
 * computed once here. {@link #rewrite} resolves a {@code computation_id eq "latest"} sentinel to the
 * run's most recent computation before translation: a single pinned run to its literal id, anything else
 * through the internal {@code is_latest_computation} binding of {@link LatestComputationSupport}.
 */
@Repository
@LogExecution
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")
public class PostgresMetricScoreResultEntityResolver implements StructuredQueryEntityResolver {

    private final DSLContext dsl;
    private final Map<String, QueryFieldBinding> bindings;
    private final MetricScoreLatestComputationDefaulter latestComputationDefaulter;
    private final LatestComputationSupport latestComputationSupport;

    public PostgresMetricScoreResultEntityResolver(
            @Qualifier("analyticsDsl") DSLContext dsl,
            JooqTableSchemaResolver schemaResolver,
            MetricScoreLatestComputationDefaulter latestComputationDefaulter,
            LatestComputationSupport latestComputationSupport) {
        this.dsl = dsl;
        this.bindings = latestComputationSupport.withLatest(
                schemaResolver.bindings(METRIC_SCORE_RESULT),
                latestComputationSupport.latestBinding(
                        METRIC_SCORE_RESULT.TEST_SUITE_RUN_ID, METRIC_SCORE_RESULT.COMPUTATION_ID));
        this.latestComputationDefaulter = latestComputationDefaulter;
        this.latestComputationSupport = latestComputationSupport;
    }

    @Override
    public String entity() {
        return MetricScoreConstants.ENTITY_METRIC_SCORE_RESULTS;
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
    public Optional<Condition> baseCondition() {
        return Optional.of(RunDeletionFilter.notDeleted(METRIC_SCORE_RESULT.TEST_SUITE_RUN_ID));
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
