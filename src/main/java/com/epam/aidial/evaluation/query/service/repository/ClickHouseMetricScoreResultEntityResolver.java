package com.epam.aidial.evaluation.query.service.repository;

import com.epam.aidial.evaluation.query.service.JooqTableSchemaResolver;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * ClickHouse twin of {@link PostgresMetricScoreResultEntityResolver}. The Postgres resolver's body is
 * trivial per-table metadata plus the shared {@link MetricScoreLatestComputationDefaulter} rewrite,
 * with no Postgres-specific SQL of its own, so this class extends it rather than duplicating it —
 * every method (including {@code rewrite}) is inherited unchanged, only the {@code analyticsDsl}
 * qualifier and vendor gate differ.
 */
@Repository
@LogExecution
@ConditionalOnProperty(name = "datasource.analytics.vendor", havingValue = "CLICKHOUSE")
public class ClickHouseMetricScoreResultEntityResolver extends PostgresMetricScoreResultEntityResolver {

    public ClickHouseMetricScoreResultEntityResolver(
            @Qualifier("analyticsDsl") DSLContext dsl,
            JooqTableSchemaResolver schemaResolver,
            MetricScoreLatestComputationDefaulter latestComputationDefaulter) {
        super(dsl, schemaResolver, latestComputationDefaulter);
    }
}
