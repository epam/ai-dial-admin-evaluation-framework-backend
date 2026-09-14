package com.epam.aidial.evaluation.configuration.datasource.migration;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Copies historical {@code run_metric_snapshots} rows from the analytics database into the meta
 * database, once, as part of moving the table's canonical home (see the
 * {@code move-run-metric-snapshots-to-meta-db} OpenSpec change).
 *
 * <p>Registered explicitly via {@code .javaMigrations(...)} in {@code MetaFlywayConfiguration}
 * (never classpath-scanned), so the analytics {@link DataSource} arrives through the constructor
 * rather than static state — see design decision D2.
 *
 * <p>Contains no DDL: the target table, indexes, and foreign key all live in the companion SQL
 * migration {@code V1.32__CreateRunMetricSnapshotsTable.sql}. {@code generateJooq} and
 * {@code JooqSchemaDriftTest} build their own Flyway instances from the SQL migration
 * directories only, so DDL placed here would be invisible to both (design D1).
 *
 * <p>Rows whose {@code test_suite_run_id} no longer exists in meta (an analytics-side orphan left
 * behind by {@code TestSuiteRunService.deleteRun}) are dropped rather than inserted — the existing
 * run ids referenced by each batch are looked up once per batch (never the full run id space) so
 * memory stays flat regardless of table size (design D6).
 */
@Slf4j
public class V1_33__CopyRunMetricSnapshotsFromAnalytics extends BaseJavaMigration {

    /** Stable checksum: this migration's behavior is fixed and never regenerated from content. */
    private static final int CHECKSUM = 1;

    private static final int BATCH_SIZE = 1000;

    private static final String CHECK_SOURCE_TABLE_EXISTS = """
            SELECT EXISTS (SELECT 1 FROM information_schema.tables
                WHERE table_schema = ? AND table_name = 'run_metric_snapshots')
            """;

    private static final String SELECT_SOURCE_ROWS = """
            SELECT id, computation_id, test_suite_run_id, tsmd_id, tsmd_name,
                   metric_declaration_id, metric_declaration_version_id,
                   config_bindings, input_bindings, output_schema, computed_at_ms
            FROM run_metric_snapshots
            """;

    private static final String SELECT_EXISTING_RUN_IDS = "SELECT id FROM test_suite_runs WHERE id = ANY (?)";

    // The WHERE EXISTS guard below is deliberate belt-and-braces (design D6), not dead code: on the
    // intended path it can never fire because existingRunIds(...) already filtered out rows whose
    // run id is absent, within the same transaction. It fires only if a run is deleted concurrently,
    // between that pre-check and this INSERT, under READ COMMITTED isolation — in that one case it
    // silently skips the row instead of letting the FK constraint raise and aborting the batch.
    private static final String INSERT_TARGET_ROW = """
            INSERT INTO run_metric_snapshots (id, computation_id, test_suite_run_id, tsmd_id, tsmd_name,
                metric_declaration_id, metric_declaration_version_id, config_bindings, input_bindings,
                output_schema, computed_at_ms)
            SELECT ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?
            WHERE EXISTS (SELECT 1 FROM test_suite_runs WHERE id = ?)
            ON CONFLICT (computation_id, tsmd_id) DO NOTHING
            """;

    private final DataSource analyticsDataSource;
    private final String analyticsSchema;

    public V1_33__CopyRunMetricSnapshotsFromAnalytics(DataSource analyticsDataSource, String analyticsSchema) {
        this.analyticsDataSource = analyticsDataSource;
        this.analyticsSchema = analyticsSchema;
    }

    @Override
    public Integer getChecksum() {
        return CHECKSUM;
    }

    @Override
    public void migrate(Context context) throws SQLException {
        try (Connection readConnection = analyticsDataSource.getConnection()) {
            readConnection.setAutoCommit(false);
            readConnection.setReadOnly(true);

            if (!sourceTableExists(readConnection)) {
                log.info(
                        "Skipping run_metric_snapshots copy from analytics: source table does not exist in "
                                + "schema '{}' (fresh install)",
                        analyticsSchema);
                return;
            }

            try (PreparedStatement selectStatement = readConnection.prepareStatement(SELECT_SOURCE_ROWS)) {
                selectStatement.setFetchSize(BATCH_SIZE);
                try (ResultSet sourceRows = selectStatement.executeQuery()) {
                    copyRows(sourceRows, context.getConnection());
                }
            }
        }
    }

    // information_schema.tables lists every schema the connecting role can see, not only the
    // schema on the connection's search_path — so meta and analytics sharing one database
    // (a supported topology; see DatasourceValidationConfiguration) would otherwise make this
    // check match meta's own run_metric_snapshots table. The schema filter is required, not
    // optional.
    private boolean sourceTableExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CHECK_SOURCE_TABLE_EXISTS)) {
            statement.setString(1, analyticsSchema);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void copyRows(ResultSet sourceRows, Connection writeConnection) throws SQLException {
        long attempted = 0;
        long copied = 0;
        long orphaned = 0;
        long alreadyPresent = 0;

        List<SnapshotRow> batch = new ArrayList<>(BATCH_SIZE);
        while (sourceRows.next()) {
            batch.add(readRow(sourceRows));
            attempted++;

            if (batch.size() == BATCH_SIZE) {
                BatchOutcome outcome = processBatch(batch, writeConnection);
                copied += outcome.copied();
                orphaned += outcome.orphaned();
                alreadyPresent += outcome.alreadyPresent();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            BatchOutcome outcome = processBatch(batch, writeConnection);
            copied += outcome.copied();
            orphaned += outcome.orphaned();
            alreadyPresent += outcome.alreadyPresent();
        }

        log.info(
                "Copied {} run_metric_snapshots rows from analytics to meta ({} attempted, {} dropped as orphans "
                        + "of a deleted test suite run, {} already present from a previous run of this migration)",
                copied,
                attempted,
                orphaned,
                alreadyPresent);
    }

    // Orphan and already-present counts are derived from an explicit existence check scoped to
    // this batch's run ids (never the full run id space), so a retry after a partial previous
    // copy does not get misreported as orphans: ON CONFLICT DO NOTHING alone cannot distinguish
    // "run gone" from "row already copied", which is why the copy count is not simply
    // attempted-minus-inserted.
    private BatchOutcome processBatch(List<SnapshotRow> batch, Connection writeConnection) throws SQLException {
        Set<String> candidateRunIds = new HashSet<>();
        for (SnapshotRow row : batch) {
            candidateRunIds.add(row.testSuiteRunId());
        }
        Set<String> existingRunIds = existingRunIds(writeConnection, candidateRunIds);

        List<SnapshotRow> eligibleRows = new ArrayList<>(batch.size());
        long orphaned = 0;
        for (SnapshotRow row : batch) {
            if (existingRunIds.contains(row.testSuiteRunId())) {
                eligibleRows.add(row);
            } else {
                orphaned++;
            }
        }

        long copied = insertRows(eligibleRows, writeConnection);
        long alreadyPresent = eligibleRows.size() - copied;
        return new BatchOutcome(copied, orphaned, alreadyPresent);
    }

    private Set<String> existingRunIds(Connection connection, Set<String> candidateRunIds) throws SQLException {
        if (candidateRunIds.isEmpty()) {
            return Set.of();
        }
        Array runIdArray = connection.createArrayOf("varchar", candidateRunIds.toArray());
        try (PreparedStatement statement = connection.prepareStatement(SELECT_EXISTING_RUN_IDS)) {
            statement.setArray(1, runIdArray);
            try (ResultSet result = statement.executeQuery()) {
                Set<String> existingIds = new HashSet<>();
                while (result.next()) {
                    existingIds.add(result.getString(1));
                }
                return existingIds;
            }
        }
    }

    private long insertRows(List<SnapshotRow> rows, Connection writeConnection) throws SQLException {
        if (rows.isEmpty()) {
            return 0;
        }
        try (PreparedStatement insertStatement = writeConnection.prepareStatement(INSERT_TARGET_ROW)) {
            for (SnapshotRow row : rows) {
                bindRow(insertStatement, row);
                insertStatement.addBatch();
            }
            return sumUpdateCounts(insertStatement.executeBatch());
        }
    }

    private static SnapshotRow readRow(ResultSet sourceRow) throws SQLException {
        return new SnapshotRow(
                sourceRow.getString("id"),
                sourceRow.getString("computation_id"),
                sourceRow.getString("test_suite_run_id"),
                sourceRow.getString("tsmd_id"),
                sourceRow.getString("tsmd_name"),
                sourceRow.getString("metric_declaration_id"),
                sourceRow.getString("metric_declaration_version_id"),
                sourceRow.getString("config_bindings"),
                sourceRow.getString("input_bindings"),
                sourceRow.getString("output_schema"),
                sourceRow.getLong("computed_at_ms"));
    }

    private static void bindRow(PreparedStatement statement, SnapshotRow row) throws SQLException {
        statement.setString(1, row.id());
        statement.setString(2, row.computationId());
        statement.setString(3, row.testSuiteRunId());
        statement.setString(4, row.tsmdId());
        statement.setString(5, row.tsmdName());
        statement.setString(6, row.metricDeclarationId());
        statement.setString(7, row.metricDeclarationVersionId());
        statement.setString(8, row.configBindings());
        statement.setString(9, row.inputBindings());
        statement.setString(10, row.outputSchema());
        statement.setLong(11, row.computedAtMs());
        statement.setString(12, row.testSuiteRunId());
    }

    // Each statement in the batch is a single-row INSERT ... WHERE ... ON CONFLICT DO NOTHING, so
    // it affects at most one row. Some drivers/configurations report SUCCESS_NO_INFO (-2) instead
    // of an exact count when a row is affected — treat that as 1 rather than discarding it, or a
    // successful copy would misreport as "already present"/orphaned in the summary log, which is
    // the only record of this one-shot, irreversible migration.
    private static long sumUpdateCounts(int[] updateCounts) {
        long total = 0;
        for (int count : updateCounts) {
            if (count == Statement.SUCCESS_NO_INFO) {
                total += 1;
            } else if (count > 0) {
                total += count;
            }
        }
        return total;
    }

    private record SnapshotRow(
            String id,
            String computationId,
            String testSuiteRunId,
            String tsmdId,
            String tsmdName,
            String metricDeclarationId,
            String metricDeclarationVersionId,
            String configBindings,
            String inputBindings,
            String outputSchema,
            long computedAtMs) {}

    private record BatchOutcome(long copied, long orphaned, long alreadyPresent) {}
}
