package com.epam.aidial.evaluation.configuration.datasource.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Applies {@code V1.38__BackfillStartedAtForNeverStartedRuns.sql} on top of a database seeded at V1.37. The
 * update cannot be exercised through a normal application boot: Flyway runs during bean construction,
 * before any fixture exists, so the table would always be empty.
 */
@DisplayName("V1.38__BackfillStartedAtForNeverStartedRuns")
class BackfillStartedAtForNeverStartedRunsMigrationTest {

    private static final String MIGRATIONS = "classpath:db/migration/meta/POSTGRES";

    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void startContainer() {
        postgres = new PostgreSQLContainer("postgres:17.4");
        postgres.start();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("backfills startedAt = completedAt for FAILED/CANCELLED runs without a start, changes nothing else")
    void shouldBackfillOnlyNeverStartedTerminalRuns() throws SQLException {
        DataSource dataSource = dataSource();
        migrate(dataSource, "1.37");

        String failedNoStart = UUID.randomUUID().toString();
        String cancelledNoStart = UUID.randomUUID().toString();
        String failedWithStart = UUID.randomUUID().toString();
        String pending = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            // The suite FK is irrelevant to this migration; skip it instead of seeding a whole suite.
            statement.execute("SET session_replication_role = replica");
            insertRun(statement, failedNoStart, "FAILED", null, "5000");
            insertRun(statement, cancelledNoStart, "CANCELLED", null, "6000");
            insertRun(statement, failedWithStart, "FAILED", "1000", "7000");
            insertRun(statement, pending, "PENDING", null, null);
        }

        migrate(dataSource, "1.38");

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            assertThat(startedAt(statement, failedNoStart)).isEqualTo(5000L);
            assertThat(startedAt(statement, cancelledNoStart)).isEqualTo(6000L);
            assertThat(startedAt(statement, failedWithStart)).isEqualTo(1000L);
            assertThat(startedAt(statement, pending)).isNull();
            assertThat(runCount(statement)).isEqualTo(4);
        }
    }

    private static void insertRun(
            Statement statement, String id, String status, String startedAtMs, String completedAtMs)
            throws SQLException {
        statement.execute("INSERT INTO test_suite_runs (id, test_suite_id, test_run_name, status, run_config,"
                + " number_of_test_cases, started_at_ms, completed_at_ms, created_at_ms, updated_at_ms) VALUES ('"
                + id + "', '" + UUID.randomUUID() + "', 'run-" + id + "', '" + status + "', '{}', 0, " + startedAtMs
                + ", " + completedAtMs + ", 1, 1)");
    }

    private static Long startedAt(Statement statement, String id) throws SQLException {
        try (ResultSet resultSet =
                statement.executeQuery("SELECT started_at_ms FROM test_suite_runs WHERE id = '" + id + "'")) {
            resultSet.next();
            long value = resultSet.getLong(1);
            return resultSet.wasNull() ? null : value;
        }
    }

    private static int runCount(Statement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM test_suite_runs")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static void migrate(DataSource dataSource, String targetVersion) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATIONS)
                .target(targetVersion)
                .load()
                .migrate();
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }
}
