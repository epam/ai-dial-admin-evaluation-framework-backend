package com.epam.aidial.evaluation.functional.config.persistence;

import com.epam.aidial.evaluation.functional.ClickHouseFunctionalTests;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * ClickHouse-analytics twin of {@link PostgresTestPersistenceService}. Meta snapshot/restore is inherited
 * unchanged (the meta DB is Postgres in both vendors); analytics cleanup is {@code TRUNCATE TABLE}, which
 * on ClickHouse is a synchronous metadata operation, unlike {@code DELETE} (an asynchronous mutation).
 */
public class ClickHouseTestPersistenceService extends PostgresMetaSnapshotSupport {

    private static final List<String> ANALYTICS_TABLES =
            List.of("test_case_eval_summaries", "run_metric_snapshots", "test_case_run_results", "metric_score_result");

    private final NamedParameterJdbcTemplate analyticsJdbcTemplate;

    @Autowired
    @Lazy
    private ClickHouseTestPersistenceService self;

    public ClickHouseTestPersistenceService(
            @Qualifier("metaRawJdbcTemplate") JdbcTemplate metaJdbcTemplate,
            @Qualifier("analyticsJdbcTemplate") NamedParameterJdbcTemplate analyticsJdbcTemplate) {
        super(metaJdbcTemplate, ClickHouseFunctionalTests.getMetaContainer());
        this.analyticsJdbcTemplate = analyticsJdbcTemplate;
    }

    @Override
    protected void dropAndCreateSchemaTransactionally() {
        self.dropAndCreatePublicSchema();
    }

    @Override
    protected void cleanupAnalyticsTables() {
        for (String table : ANALYTICS_TABLES) {
            analyticsJdbcTemplate.update("TRUNCATE TABLE " + table, new MapSqlParameterSource());
        }
    }
}
