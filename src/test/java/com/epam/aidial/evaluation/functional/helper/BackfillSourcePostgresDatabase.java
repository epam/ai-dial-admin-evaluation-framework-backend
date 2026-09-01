package com.epam.aidial.evaluation.functional.helper;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.postgresql.Driver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Backfill-source fixture for {@code ClickHouseBackfillFunctionalTests}: a scratch Postgres database
 * (created inside the meta Postgres container, fully isolated from the meta schema and its
 * snapshot/restore machinery) carrying the <b>real</b> analytics POSTGRES schema — applied via the
 * production Flyway migrations, so the fixture can never drift from what a pre-cutover environment
 * actually holds. Owns every piece of source-side SQL, per the no-raw-SQL-in-test-methods rule.
 *
 * <p>The ClickHouse server (another container) reaches this database container-to-container over the
 * default Docker bridge network, hence {@link #hostReachableFromClickHouse()} returns the container's
 * bridge IP and {@link #port()} the in-container Postgres port — not the host-mapped ones, which are
 * only reachable from the test JVM.
 */
public final class BackfillSourcePostgresDatabase {

    /** One consistent row per analytics table, sharing ids the test asserts on. */
    public record Fixture(
            UUID suiteId,
            UUID runId,
            UUID computationId,
            UUID testCaseId,
            UUID resultId,
            UUID summaryId,
            String trickyText,
            String trickyJson,
            String trickyJsonArray,
            String metricScoreName,
            double metricScoreValue,
            long createdAtMs) {}

    private static final int POSTGRES_PORT = 5432;

    private final PostgreSQLContainer container;
    private final String databaseName;
    private final JdbcTemplate jdbc;

    private BackfillSourcePostgresDatabase(PostgreSQLContainer container, String databaseName, JdbcTemplate jdbc) {
        this.container = container;
        this.databaseName = databaseName;
        this.jdbc = jdbc;
    }

    /** Drops and recreates the scratch database, then applies the production analytics POSTGRES migrations. */
    public static BackfillSourcePostgresDatabase recreate(PostgreSQLContainer container, String databaseName) {
        JdbcTemplate admin = jdbcTemplate(container, container.getJdbcUrl());
        admin.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
        admin.execute("CREATE DATABASE " + databaseName);

        String url = container.getJdbcUrl().replace("/" + container.getDatabaseName(), "/" + databaseName);
        Flyway.configure()
                .dataSource(url, container.getUsername(), container.getPassword())
                .locations("classpath:db/migration/analytics/POSTGRES")
                .load()
                .migrate();
        return new BackfillSourcePostgresDatabase(container, databaseName, jdbcTemplate(container, url));
    }

    public void insertFixtureRows(Fixture fixture) {
        jdbc.update(
                """
                INSERT INTO test_case_run_results (id, test_suite_run_id, test_suite_id, test_case_id,
                    test_case_name, run_index, request_index, total_requests, turn_index, total_turns,
                    test_case_data, request_body, response_body, response_status_code, execution_status,
                    exec_started_at_ms, exec_completed_at_ms, exec_duration_ms, retry_count, log_details,
                    trace_id, extracted_columns, extraction_warnings, created_at_ms)
                VALUES (?, ?, ?, ?, ?, 0, 0, 1, 0, 1, ?::jsonb, ?::jsonb, ?::jsonb, 200, 'SUCCESS',
                    ?, ?, 100, 0, NULL, NULL, ?::jsonb, ?::jsonb, ?)
                """,
                fixture.resultId().toString(),
                fixture.runId().toString(),
                fixture.suiteId().toString(),
                fixture.testCaseId().toString(),
                fixture.trickyText(),
                fixture.trickyJson(),
                fixture.trickyJson(),
                fixture.trickyJson(),
                fixture.createdAtMs(),
                fixture.createdAtMs(),
                fixture.trickyJson(),
                fixture.trickyJsonArray(),
                fixture.createdAtMs());
        jdbc.update(
                """
                INSERT INTO test_case_eval_summaries (id, test_suite_id, test_suite_run_id,
                    test_case_run_result_id, test_case_id, test_case_name, run_index, request_index,
                    total_requests, turn_index, total_turns, computation_id, test_case_data,
                    extracted_columns, execution_status, exec_duration_ms, metric_eval_duration_ms,
                    response_status_code, metric_values, metric_infos, extraction_warnings,
                    created_at_ms, computed_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, 0, 0, 1, 0, 1, ?, ?::jsonb, ?::jsonb, 'SUCCESS', 100, 0, 200,
                    ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
                """,
                fixture.summaryId().toString(),
                fixture.suiteId().toString(),
                fixture.runId().toString(),
                fixture.resultId().toString(),
                fixture.testCaseId().toString(),
                fixture.trickyText(),
                fixture.computationId().toString(),
                fixture.trickyJson(),
                fixture.trickyJson(),
                fixture.trickyJson(),
                fixture.trickyJson(),
                fixture.trickyJsonArray(),
                fixture.createdAtMs(),
                fixture.createdAtMs());
        jdbc.update(
                """
                INSERT INTO run_metric_snapshots (id, computation_id, test_suite_run_id, tsmd_id, tsmd_name,
                    metric_declaration_id, metric_declaration_version_id, config_bindings, input_bindings,
                    output_schema, computed_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                """,
                UUID.randomUUID().toString(),
                fixture.computationId().toString(),
                fixture.runId().toString(),
                UUID.randomUUID().toString(),
                fixture.trickyText(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                fixture.trickyJsonArray(),
                fixture.trickyJsonArray(),
                fixture.trickyJson(),
                fixture.createdAtMs());
        jdbc.update(
                """
                INSERT INTO metric_score_result (id, test_suite_run_id, test_suite_id, computation_id,
                    metric_score_name, metric_name, value, computed_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                fixture.runId().toString(),
                fixture.suiteId().toString(),
                fixture.computationId().toString(),
                fixture.metricScoreName(),
                "Relevancy.score",
                fixture.metricScoreValue(),
                fixture.createdAtMs());
    }

    /** The container's Docker bridge IP — the address the ClickHouse container can dial. */
    public String hostReachableFromClickHouse() {
        return container.getContainerInfo().getNetworkSettings().getNetworks().values().stream()
                .map(network -> network.getIpAddress())
                .filter(ip -> ip != null && !ip.isBlank())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Postgres container has no bridge network IP"));
    }

    public int port() {
        return POSTGRES_PORT;
    }

    public String databaseName() {
        return databaseName;
    }

    public String username() {
        return container.getUsername();
    }

    public String password() {
        return container.getPassword();
    }

    private static JdbcTemplate jdbcTemplate(PostgreSQLContainer container, String url) {
        return new JdbcTemplate(
                new SimpleDriverDataSource(new Driver(), url, container.getUsername(), container.getPassword()));
    }
}
