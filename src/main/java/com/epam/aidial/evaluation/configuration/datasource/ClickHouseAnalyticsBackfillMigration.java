package com.epam.aidial.evaluation.configuration.datasource;

import com.epam.aidial.evaluation.configuration.properties.clickhouse.ClickHouseBackfillProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;

/**
 * Repeatable Flyway Java migration ({@code R__Backfill_analytics_from_postgres}) that copies the four
 * analytics tables from the previous analytics Postgres database into ClickHouse, executed server-side
 * by ClickHouse's {@code postgresql()} table function ({@code INSERT INTO … SELECT … FROM
 * postgresql(…)}) — the application never streams the rows itself.
 *
 * <p><b>Why a Java migration and not a SQL script:</b> the {@code postgresql()} call needs the source
 * database's credentials inline in the SQL text. A checked-in {@code .sql} migration would either
 * hardcode them or splice them in via Flyway placeholders; here they come from
 * {@link ClickHouseBackfillProperties} at runtime and exist only in the statement sent to ClickHouse
 * (which masks table-function credentials in {@code system.query_log}). Use a <b>read-only</b>
 * Postgres user for the backfill regardless.
 *
 * <p><b>Why repeatable, keyed on a config-derived {@linkplain #getChecksum() checksum}:</b> a
 * versioned migration would be recorded as an applied no-op on every environment that boots the
 * ClickHouse vendor before backfill is configured (any fresh install), and could then never run.
 * Flyway re-applies a repeatable migration whenever its checksum changes, so flipping
 * {@code clickhouse.analytics.backfill.enabled=true} (or re-pointing the source) is exactly what
 * triggers execution. Re-runs are safe: every target table is a {@code ReplacingMergeTree} ordered by
 * the row's natural key, and reads collapse duplicates via the datasource's
 * {@code clickhouse_setting_final=1} connection property, so a repeated backfill is idempotent.
 *
 * <p><b>Ordering:</b> Flyway runs repeatable migrations after all versioned ones, so the schema
 * (V1.1+) always exists before the backfill.
 *
 * <p><b>Failure handling — fail-fast:</b> a partially-applied backfill is a data-integrity problem, so
 * any SQL failure or a target-count shortfall aborts the migration (and startup). Flyway records the
 * failed attempt and blocks subsequent migrations until the history row is repaired (delete the
 * failed {@code flyway_schema_history} row) or the cause is fixed; because the copy is idempotent, the
 * fixed re-run simply re-inserts everything.
 */
@Slf4j
public class ClickHouseAnalyticsBackfillMigration implements JavaMigration {

    /** Table name + shared column list (identical names on both vendors, guarded by AnalyticsModelParityTest). */
    private record BackfillTable(String name, List<String> columns) {}

    private static final List<BackfillTable> TABLES = List.of(
            new BackfillTable(
                    "test_case_run_results",
                    List.of(
                            "id",
                            "test_suite_run_id",
                            "test_suite_id",
                            "test_case_id",
                            "test_case_name",
                            "run_index",
                            "request_index",
                            "total_requests",
                            "turn_index",
                            "total_turns",
                            "test_case_data",
                            "request_body",
                            "response_body",
                            "response_status_code",
                            "execution_status",
                            "exec_started_at_ms",
                            "exec_completed_at_ms",
                            "exec_duration_ms",
                            "retry_count",
                            "log_details",
                            "trace_id",
                            "extracted_columns",
                            "extraction_warnings",
                            "created_at_ms")),
            new BackfillTable(
                    "test_case_eval_summaries",
                    List.of(
                            "id",
                            "test_suite_id",
                            "test_suite_run_id",
                            "test_case_run_result_id",
                            "test_case_id",
                            "test_case_name",
                            "run_index",
                            "request_index",
                            "total_requests",
                            "turn_index",
                            "total_turns",
                            "computation_id",
                            "test_case_data",
                            "extracted_columns",
                            "execution_status",
                            "exec_duration_ms",
                            "metric_eval_duration_ms",
                            "response_status_code",
                            "metric_values",
                            "metric_infos",
                            "extraction_warnings",
                            "created_at_ms",
                            "computed_at_ms")),
            new BackfillTable(
                    "run_metric_snapshots",
                    List.of(
                            "id",
                            "computation_id",
                            "test_suite_run_id",
                            "tsmd_id",
                            "tsmd_name",
                            "metric_declaration_id",
                            "metric_declaration_version_id",
                            "config_bindings",
                            "input_bindings",
                            "output_schema",
                            "computed_at_ms")),
            new BackfillTable(
                    "metric_score_result",
                    List.of(
                            "id",
                            "test_suite_run_id",
                            "test_suite_id",
                            "computation_id",
                            "metric_score_name",
                            "metric_name",
                            "value",
                            "computed_at_ms")));

    private final ClickHouseBackfillProperties properties;

    public ClickHouseAnalyticsBackfillMigration(ClickHouseBackfillProperties properties) {
        this.properties = properties;
    }

    /** {@code null} version = repeatable migration. */
    @Override
    public MigrationVersion getVersion() {
        return null;
    }

    @Override
    public String getDescription() {
        return "Backfill analytics from postgres";
    }

    /**
     * Derived from the backfill configuration so that changing it (most importantly flipping
     * {@code enabled}) makes Flyway re-apply this repeatable migration. The password is deliberately
     * excluded: rotating the source credential must not replay the backfill.
     */
    @Override
    public Integer getChecksum() {
        ClickHouseBackfillProperties.Postgres source = properties.getPostgres();
        String canonical = String.join(
                "|",
                String.valueOf(properties.isEnabled()),
                source == null ? "" : String.valueOf(source.getHost()),
                source == null ? "" : String.valueOf(source.getPort()),
                source == null ? "" : String.valueOf(source.getDatabase()),
                source == null ? "" : String.valueOf(source.getSchema()),
                source == null ? "" : String.valueOf(source.getUsername()));
        return canonical.hashCode();
    }

    /** ClickHouse has no transactions; each INSERT…SELECT auto-commits per partition part. */
    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        if (!properties.isEnabled()) {
            log.info("Analytics backfill from Postgres is disabled (clickhouse.analytics.backfill.enabled=false)"
                    + " — recording a no-op");
            return;
        }
        ClickHouseBackfillProperties.Postgres source = requireConfiguredSource();
        try (Statement statement = context.getConnection().createStatement()) {
            for (BackfillTable table : TABLES) {
                backfillTable(statement, table, source);
            }
        }
        log.info(
                "Analytics backfill from Postgres {}:{}/{} completed",
                source.getHost(),
                source.getPort(),
                source.getDatabase());
    }

    private void backfillTable(Statement statement, BackfillTable table, ClickHouseBackfillProperties.Postgres source)
            throws SQLException {
        log.info(
                "Backfilling {} from Postgres {}:{}/{}",
                table.name(),
                source.getHost(),
                source.getPort(),
                source.getDatabase());
        String columns = String.join(", ", table.columns());
        statement.execute("INSERT INTO " + table.name() + " (" + columns + ") SELECT " + columns + " FROM "
                + postgresqlTableFunction(table.name(), source));

        long sourceCount =
                fetchLong(statement, "SELECT count(*) FROM " + postgresqlTableFunction(table.name(), source));
        long targetCount = fetchLong(statement, "SELECT count() FROM " + table.name());
        if (targetCount < sourceCount) {
            throw new IllegalStateException("Backfill verification failed for " + table.name() + ": source has "
                    + sourceCount + " rows but target has only " + targetCount);
        }
        // targetCount may legitimately exceed sourceCount when ClickHouse already holds rows written
        // after the cutover (e.g. a backfill re-run) — that is not an error.
        log.info("Backfilled {}: source rows={}, target rows={}", table.name(), sourceCount, targetCount);
    }

    private static String postgresqlTableFunction(String tableName, ClickHouseBackfillProperties.Postgres source) {
        return "postgresql(" + literal(source.getHost() + ":" + source.getPort()) + ", "
                + literal(source.getDatabase()) + ", "
                + literal(tableName) + ", "
                + literal(source.getUsername()) + ", "
                + literal(source.getPassword() == null ? "" : source.getPassword()) + ", "
                + literal(source.getSchema()) + ")";
    }

    /** ClickHouse string literal: backslash-escape syntax, so escape {@code \} and {@code '}. */
    private static String literal(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static long fetchLong(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Count query returned no rows: " + sql);
            }
            return resultSet.getLong(1);
        }
    }

    private ClickHouseBackfillProperties.Postgres requireConfiguredSource() {
        ClickHouseBackfillProperties.Postgres source = properties.getPostgres();
        List<String> missing = new ArrayList<>();
        if (source == null || isBlank(source.getHost())) {
            missing.add("clickhouse.analytics.backfill.postgres.host");
        }
        if (source == null || source.getPort() == null) {
            missing.add("clickhouse.analytics.backfill.postgres.port");
        }
        if (source == null || isBlank(source.getDatabase())) {
            missing.add("clickhouse.analytics.backfill.postgres.database");
        }
        if (source == null || isBlank(source.getSchema())) {
            missing.add("clickhouse.analytics.backfill.postgres.schema");
        }
        if (source == null || isBlank(source.getUsername())) {
            missing.add("clickhouse.analytics.backfill.postgres.username");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "clickhouse.analytics.backfill.enabled=true but required properties are missing: " + missing);
        }
        return source;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
