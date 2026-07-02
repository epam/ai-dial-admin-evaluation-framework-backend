package com.epam.aidial.evaluation.experimental.query.service.repository;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.METRIC_SCORE_RESULT;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Postgres implementation of {@link MetricScoreResultQueryRepository}: binds the
 * {@code metric_score_results} entity to the generated {@code METRIC_SCORE_RESULT} table on the
 * analytics datasource ({@code analyticsDsl}) and delegates translation/execution to
 * {@link StructuredQueryExecutor} — mirroring {@code PostgresEvalSummaryQueryRepository}.
 */
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "POSTGRES")
public class PostgresMetricScoreResultQueryRepository implements MetricScoreResultQueryRepository {

    private static final String ENTITY = "metric_score_results";

    @Qualifier("analyticsDsl")
    private final DSLContext dsl;

    private final StructuredQueryExecutor executor;
    private final MetricScoreLatestComputationDefaulter latestComputationDefaulter;

    @Override
    public String supportedEntity() {
        return ENTITY;
    }

    @Override
    public QueryResultPage execute(StructuredQuery query) {
        // Resolve a computation_id eq "latest" sentinel to the run's latest computation before translation.
        return executor.execute(
                ENTITY, dsl, METRIC_SCORE_RESULT, latestComputationDefaulter.resolveLatestComputation(query));
    }
}
