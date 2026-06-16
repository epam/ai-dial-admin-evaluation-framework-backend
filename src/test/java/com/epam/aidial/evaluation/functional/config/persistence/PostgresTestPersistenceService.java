package com.epam.aidial.evaluation.functional.config.persistence;

import com.epam.aidial.evaluation.functional.PostgresFunctionalTests;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

public class PostgresTestPersistenceService implements TestPersistenceService {

    private static final String DUMP_FILE = "/tmp/test_dump.tar";

    private final JdbcTemplate metaJdbcTemplate;
    private final NamedParameterJdbcTemplate analyticsJdbcTemplate;

    @Autowired
    @Lazy
    private PostgresTestPersistenceService self;

    public PostgresTestPersistenceService(
            @Qualifier("metaRawJdbcTemplate") JdbcTemplate metaJdbcTemplate,
            @Qualifier("analyticsJdbcTemplate") NamedParameterJdbcTemplate analyticsJdbcTemplate) {
        this.metaJdbcTemplate = metaJdbcTemplate;
        this.analyticsJdbcTemplate = analyticsJdbcTemplate;
    }

    @Override
    public void dumpDb() {
        PostgreSQLContainer postgres = PostgresFunctionalTests.getContainer();
        runContainerCommand(
                String.format(
                        "pg_dump -Ft -U %s -f %s %s", postgres.getUsername(), DUMP_FILE, postgres.getDatabaseName()),
                String.format("take a snapshot '%s'", DUMP_FILE));
    }

    @Override
    public void restoreDb() {
        self.dropAndCreatePublicSchema();
        cleanupAnalyticsTables();
        PostgreSQLContainer postgres = PostgresFunctionalTests.getContainer();
        runContainerCommand(
                String.format(
                        "pg_restore -Ft -U %s -d %s %s", postgres.getUsername(), postgres.getDatabaseName(), DUMP_FILE),
                String.format("restore from a snapshot '%s'", DUMP_FILE));
    }

    @Transactional(value = "metaTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void dropAndCreatePublicSchema() {
        metaJdbcTemplate.execute("DROP SCHEMA public cascade;");
        metaJdbcTemplate.execute("CREATE SCHEMA public;");
    }

    private void cleanupAnalyticsTables() {
        analyticsJdbcTemplate.update("DELETE FROM test_case_eval_summaries", new MapSqlParameterSource());
        analyticsJdbcTemplate.update("DELETE FROM run_metric_snapshots", new MapSqlParameterSource());
        analyticsJdbcTemplate.update("DELETE FROM test_case_run_results", new MapSqlParameterSource());
    }

    @Override
    public void cleanupResources() {
        runContainerCommand(String.format("rm %s", DUMP_FILE), String.format("remove snapshot '%s'", DUMP_FILE));
    }

    private void runContainerCommand(String command, String description) {
        PostgreSQLContainer postgres = PostgresFunctionalTests.getContainer();
        try {
            var result = postgres.execInContainer("sh", "-c", command);

            if (result.getExitCode() != 0) {
                throw new RuntimeException("couldn't " + description + ": " + result.getStderr());
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("couldn't " + description, e);
        }
    }
}
