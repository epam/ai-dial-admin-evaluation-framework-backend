package com.epam.aidial.evaluation.query.service.repository;

import static com.epam.aidial.evaluation.data.db.jooq.clickhouse.Tables.METRIC_SCORE_RESULT;

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
 * ClickHouse twin of {@link PostgresMetricScoreResultEntityResolver}. The Postgres resolver's body is
 * trivial per-table metadata plus the shared {@link MetricScoreLatestComputationDefaulter} rewrite,
 * with no Postgres-specific SQL of its own, so this class extends it rather than duplicating it —
 * {@code entity()} and {@code rewrite()} are inherited unchanged, and only the {@code analyticsDsl}
 * qualifier, the vendor gate and the generated model differ.
 *
 * <p>{@link #table()} and {@link #bindings} are re-derived from the ClickHouse-generated
 * {@code METRIC_SCORE_RESULT} so the ClickHouse query path is described by the ClickHouse model end
 * to end. The two models are held column-for-column identical by {@code AnalyticsModelParityTest},
 * so the binding map is equivalent to the Postgres twin's.
 */
@Repository
@LogExecution
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "CLICKHOUSE")
public class ClickHouseMetricScoreResultEntityResolver extends PostgresMetricScoreResultEntityResolver {

    private final Map<String, QueryFieldBinding> bindings;

    public ClickHouseMetricScoreResultEntityResolver(
            @Qualifier("analyticsDsl") DSLContext dsl,
            JooqTableSchemaResolver schemaResolver,
            MetricScoreLatestComputationDefaulter latestComputationDefaulter) {
        super(dsl, schemaResolver, latestComputationDefaulter);
        this.bindings = schemaResolver.bindings(METRIC_SCORE_RESULT);
    }

    @Override
    public Table<?> table() {
        return METRIC_SCORE_RESULT;
    }

    @Override
    public Map<String, QueryFieldBinding> bindings(StructuredQuery query) {
        return bindings;
    }
}
