package com.epam.aidial.evaluation.functional.config.persistence;

import com.epam.aidial.evaluation.functional.PostgresFunctionalTests;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public class PostgresTestPersistenceService extends PostgresMetaSnapshotSupport {

    private final NamedParameterJdbcTemplate analyticsJdbcTemplate;

    @Autowired
    @Lazy
    private PostgresTestPersistenceService self;

    public PostgresTestPersistenceService(
            @Qualifier("metaRawJdbcTemplate") JdbcTemplate metaJdbcTemplate,
            @Qualifier("analyticsJdbcTemplate") NamedParameterJdbcTemplate analyticsJdbcTemplate) {
        super(metaJdbcTemplate, PostgresFunctionalTests.getContainer());
        this.analyticsJdbcTemplate = analyticsJdbcTemplate;
    }

    @Override
    protected void dropAndCreateSchemaTransactionally() {
        self.dropAndCreatePublicSchema();
    }

    @Override
    protected void cleanupAnalyticsTables() {
        analyticsJdbcTemplate.update("DELETE FROM test_case_eval_summaries", new MapSqlParameterSource());
        analyticsJdbcTemplate.update("DELETE FROM run_metric_snapshots", new MapSqlParameterSource());
        analyticsJdbcTemplate.update("DELETE FROM test_case_run_results", new MapSqlParameterSource());
    }
}
